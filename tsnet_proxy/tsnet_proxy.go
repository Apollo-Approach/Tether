package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"tailscale.com/tsnet"
)

func main() {
	// Use AppData for the tsnet state directory
	appData, err := os.UserConfigDir()
	if err != nil {
		log.Fatalf("Failed to get config dir: %v", err)
	}
	stateDir := filepath.Join(appData, "Antigravity", "tailscale")
	if err := os.MkdirAll(stateDir, 0700); err != nil {
		log.Fatalf("Failed to create state dir: %v", err)
	}

	hostname, err := os.Hostname()
	if err != nil {
		hostname = "antigravity-pc-fallback"
	}

	s := &tsnet.Server{
		Dir:      stateDir,
		Hostname: hostname,
	}

	// Capture the Auth URL to pass to the Python wrapper
	s.UserLogf = func(format string, args ...any) {
		msg := fmt.Sprintf(format, args...)

		if strings.Contains(msg, "https://login.tailscale.com/") {
			// Extract just the URL using a simple split if possible, or just print the whole message
			// The python side will parse it.
			fmt.Printf("TAILSCALE_AUTH_URL: %s\n", msg)
		} else {
			log.Print(msg)
		}
	}

	if err := s.Start(); err != nil {
		log.Fatalf("Failed to start tsnet: %v", err)
	}
	defer s.Close()

	sigc := make(chan os.Signal, 1)
	signal.Notify(sigc, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sigc
		log.Printf("Received termination signal, shutting down...")
		s.Close()
		os.Exit(0)
	}()

	// Wait for the node to be fully up and authenticated on the Tailnet
	if _, err := s.Up(context.Background()); err != nil {
		log.Fatalf("Failed to connect to tailnet: %v", err)
	}

	ip4, _ := s.TailscaleIPs()
	if !ip4.IsValid() {
		log.Fatalf("Failed to get valid Tailscale IP after Up")
	}

	fmt.Printf("TAILSCALE_IP: %v\n", ip4)
	os.Stdout.Sync()

	// Listen on the Tailnet for incoming WebSocket connections
	ln, err := s.Listen("tcp", ":8765")
	if err != nil {
		log.Fatalf("Failed to listen on tailnet: %v", err)
	}
	defer ln.Close()

	log.Printf("Listening on %v:8765 (Tailnet)", ip4)

	for {
		clientConn, err := ln.Accept()
		if err != nil {
			log.Printf("Accept error: %v", err)
			continue
		}

		go handleConnection(s, clientConn)
	}
}

func handleConnection(s *tsnet.Server, clientConn net.Conn) {
	defer clientConn.Close()

	// Dial the local Python WebSocket server
	dialer := net.Dialer{
		Timeout:   5 * time.Second,
		KeepAlive: 3 * time.Minute,
	}

	// 1. WhoIs check
	lc, err := s.LocalClient()
	if err != nil {
		log.Printf("Failed to get local client: %v", err)
		return
	}
	who, err := lc.WhoIs(context.Background(), clientConn.RemoteAddr().String())
	if err != nil {
		log.Printf("WhoIs failed for %v: %v", clientConn.RemoteAddr(), err)
		return
	}
	log.Printf("Connection accepted from tailscale user: %s", who.UserProfile.LoginName)

	if tcpConn, ok := clientConn.(*net.TCPConn); ok {
		tcpConn.SetKeepAlive(true)
		tcpConn.SetKeepAlivePeriod(3 * time.Minute)
	}

	localConn, err := dialer.Dial("tcp", "127.0.0.1:8080")
	if err != nil {
		log.Printf("Failed to connect to local server: %v", err)
		return
	}
	defer localConn.Close()

	errc := make(chan error, 2)
	go func() {
		_, err := io.Copy(clientConn, localConn)
		errc <- err
	}()
	go func() {
		_, err := io.Copy(localConn, clientConn)
		errc <- err
	}()

	<-errc
}
