package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
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
		msg := format
		if len(args) > 0 {
			if strArg, ok := args[0].(string); ok {
				msg = strArg
			} else {
				msg = fmt.Sprintf(format, args...)
			}
		}

		if strings.Contains(msg, "https://login.tailscale.com/") {
			// Extract just the URL using a simple split if possible, or just print the whole message
			// The python side will parse it.
			fmt.Printf("TAILSCALE_AUTH_URL: %s\n", msg)
		}
	}

	if err := s.Start(); err != nil {
		log.Fatalf("Failed to start tsnet: %v", err)
	}
	defer s.Close()

	// Wait for the node to be fully up and authenticated on the Tailnet
	if _, err := s.Up(context.Background()); err != nil {
		log.Fatalf("Failed to connect to tailnet: %v", err)
	}

	var ip4 netip.Addr
	for i := 0; i < 20; i++ {
		v4, _ := s.TailscaleIPs()
		if v4.IsValid() {
			ip4 = v4
			break
		}
		time.Sleep(500 * time.Millisecond)
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

		go handleConnection(clientConn)
	}
}

func handleConnection(clientConn net.Conn) {
	defer clientConn.Close()

	// Dial the local Python WebSocket server
	localConn, err := net.Dial("tcp", "127.0.0.1:8080")
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
