package tsnet_wrapper

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"strings"
	"sync"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

func init() {
	// Bypass Android's netlink routing permission error (API 30+)
	// by feeding a dummy interface list to Tailscale's netmon.
	// Returning a dummy wlan0 interface prevents Tailscale from thinking the device is offline
	// and pausing the control client, which prevents it from ever fetching the Auth URL.
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		dummy := netmon.Interface{
			Interface: &net.Interface{
				Index: 2,
				Name:  "wlan0",
				Flags: net.FlagUp | net.FlagBroadcast | net.FlagMulticast,
			},
			AltAddrs: []net.Addr{
				&net.IPNet{
					IP:   net.ParseIP("192.168.1.100"),
					Mask: net.CIDRMask(24, 32),
				},
			},
		}
		return []netmon.Interface{dummy}, nil
	})
}

var s *tsnet.Server

// AuthCallback interface for Android to receive the Auth URL and logs
type AuthCallback interface {
	OnAuthURL(url string)
	OnLog(msg string)
}

// StartTailscale initializes the tsnet node and starts the proxy
func StartTailscale(hostname string, storageDir string, callback AuthCallback) error {
	// Tell Tailscale's logpolicy where to safely store logs on Android
	// to prevent a fatal panic when it fails to find /tmp or UserCacheDir.
	os.Setenv("TS_LOGS_DIR", storageDir)

	s = &tsnet.Server{
		Hostname: hostname,
		Dir:      storageDir,
		Logf: func(format string, args ...any) {
			msg := format
			if len(args) > 0 {
				msg = fmt.Sprintf(format, args...)
			}
			
			if callback != nil {
				callback.OnLog(msg)
			}
			
			if strings.Contains(msg, "AuthURL is ") {
				parts := strings.Split(msg, "AuthURL is ")
				if len(parts) == 2 {
					url := strings.TrimSpace(parts[1])
					if callback != nil {
						callback.OnAuthURL(url)
					}
				}
			}
		},
	}

	// Start the tsnet server
	if err := s.Start(); err != nil {
		return err
	}

	// Wait for the tailnet to connect (or prompt for auth)
	_, err := s.Up(context.Background())
	if err != nil {
		return err
	}

	// Start the local HTTP proxy using tsnet's Dial function
	go startProxy()

	return nil
}

var proxyTarget string
var proxyTargetMutex sync.Mutex
var proxyListener net.Listener

// SetProxyTarget sets the destination Tailscale IP:port for the local TCP proxy
func SetProxyTarget(target string) {
	proxyTargetMutex.Lock()
	defer proxyTargetMutex.Unlock()
	proxyTarget = target
}

func startProxy() {
	l, err := net.Listen("tcp", "127.0.0.1:1080")
	if err != nil {
		log.Printf("Proxy listen error: %v", err)
		return
	}
	proxyListener = l
	log.Println("Starting tsnet raw TCP proxy on 127.0.0.1:1080")

	for {
		conn, err := l.Accept()
		if err != nil {
			if strings.Contains(err.Error(), "use of closed network connection") {
				return
			}
			continue
		}
		go handleTCP(conn)
	}
}

func handleTCP(clientConn net.Conn) {
	proxyTargetMutex.Lock()
	target := proxyTarget
	proxyTargetMutex.Unlock()

	if target == "" {
		log.Println("TSNET PROXY ERROR: No proxy target set")
		clientConn.Close()
		return
	}

	log.Printf("TSNET PROXY: Dialing %s", target)
	destConn, err := s.Dial(context.Background(), "tcp", target)
	if err != nil {
		log.Printf("TSNET PROXY ERROR: s.Dial to %s failed: %v", target, err)
		clientConn.Close()
		return
	}
	log.Printf("TSNET PROXY: Successfully dialed %s", target)

	go transfer(destConn, clientConn)
	go transfer(clientConn, destConn)
}

func transfer(destination io.WriteCloser, source io.ReadCloser) {
	defer destination.Close()
	defer source.Close()
	io.Copy(destination, source)
}

// StopTailscale shuts down the proxy and tsnet node
func StopTailscale() {
	if proxyListener != nil {
		proxyListener.Close()
	}
	if s != nil {
		s.Close()
	}
}

// GetPeers returns a JSON array of active peers on the Tailnet
func GetPeers() string {
	if s == nil {
		return "[]"
	}
	lc, err := s.LocalClient()
	if err != nil {
		return "[]"
	}
	status, err := lc.Status(context.Background())
	if err != nil {
		return "[]"
	}

	type Peer struct {
		Hostname string `json:"hostname"`
		Online   bool   `json:"online"`
		IP       string `json:"ip"`
	}

	var peers []Peer
	for _, peer := range status.Peer {
		ip := ""
		if len(peer.TailscaleIPs) > 0 {
			ip = peer.TailscaleIPs[0].String()
		}
		peers = append(peers, Peer{
			Hostname: peer.HostName,
			Online:   peer.Online,
			IP:       ip,
		})
	}

	b, _ := json.Marshal(peers)
	return string(b)
}
