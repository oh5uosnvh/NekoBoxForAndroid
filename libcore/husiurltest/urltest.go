// Package urltest is the custom URL test implementation instead of github.com/sagernet/sing-box/common/urltest
package husiurltest

import (
	"cmp"
	"context"
	"crypto/tls"
	"net"
	"net/http"
	"net/url"
	"time"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/ntp"
)

var ErrOutboundNotFound = E.New("outbound is not found")

type Flags uint8

const (
	UnifiedDelay Flags = 1 << iota
	IgnoreHandshakeTime
)

// RunTag measures the outbound named tag, or the default outbound when tag is
// empty. instanceCtx is the context of the instance manager belongs to: the
// deadline and the history storage both come from it.
func RunTag(
	instanceCtx context.Context,
	manager adapter.OutboundManager,
	tag, link string,
	timeoutMs int32,
	flags Flags,
) (int32, error) {
	if instanceCtx == nil {
		return -1, E.New("instance context not available")
	}
	if manager == nil {
		return -1, E.New("outbound manager not available")
	}
	var detour adapter.Outbound
	if tag == "" {
		detour = manager.Default()
	} else {
		var loaded bool
		detour, loaded = manager.Outbound(tag)
		if !loaded {
			return -1, E.Cause(ErrOutboundNotFound, tag)
		}
	}
	return Run(instanceCtx, detour, link, timeoutMs, flags)
}

// Run measures detour within timeoutMs and stores the result in the instance's
// URL test history, so the UI sees the same number the group selector does.
func Run(
	instanceCtx context.Context,
	detour adapter.Outbound,
	link string,
	timeoutMs int32,
	flags Flags,
) (int32, error) {
	if detour == nil {
		return -1, E.New("outbound not available")
	}
	if timeoutMs <= 0 {
		timeoutMs = 5000
	}
	ctx, cancel := context.WithTimeout(instanceCtx, time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	chLatency := make(chan uint16, 1)
	var testErr error
	go func() {
		var latency uint16
		latency, testErr = Measure(ctx, link, detour, flags)
		if testErr != nil {
			close(chLatency)
			return
		}
		chLatency <- latency

	}()
	select {
	case <-ctx.Done():
		return -1, ctx.Err()
	case latency, loaded := <-chLatency:
		if !loaded {
			return -1, testErr
		}
		return int32(latency), nil
	}
}

// Measure times a HEAD request through detour. A multiplexed outbound is warmed
// up with a preceding request that keeps its session alive, so the measured
// request rides the established session instead of paying for the handshake.
func Measure(ctx context.Context, link string, detour N.Dialer, flags Flags) (uint16, error) {
	return measure(ctx, link, detour, flags)
}

const defaultLink = "https://www.gstatic.com/generate_204"

const (
	schemeHTTP  = "http"
	schemeHTTPS = "https"
	schemeHTTP3 = "http3"
	schemeQUIC  = "quic"
)

func measure(ctx context.Context, link string, detour N.Dialer, flags Flags) (t uint16, err error) {
	link = cmp.Or(link, defaultLink)
	linkURL, err := url.Parse(link)
	if err != nil {
		return
	}
	network := N.NetworkTCP
	isHTTP3 := linkURL.Scheme == schemeHTTP3 || linkURL.Scheme == schemeQUIC
	if isHTTP3 {
		if !C.WithQUIC {
			return 0, C.ErrQUICNotIncluded
		}
		network = N.NetworkUDP
		linkURL.Scheme = schemeHTTPS
	}
	hostname := linkURL.Hostname()
	port := linkURL.Port()
	if port == "" {
		switch linkURL.Scheme {
		case schemeHTTP:
			port = "80"
		case schemeHTTPS:
			port = "443"
		}
	}

	start := time.Now()
	instance, err := detour.DialContext(ctx, network, M.ParseSocksaddrHostPortStr(hostname, port))
	if err != nil {
		return
	}
	defer instance.Close()
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, linkURL.String(), nil)
	if err != nil {
		return
	}
	tlsConfig := &tls.Config{
		Time:    ntp.TimeFuncFromContext(ctx),
		RootCAs: adapter.RootPoolFromContext(ctx),
	}
	var transport http.RoundTripper
	if isHTTP3 {
		transport, err = newHTTP3Transport(instance, tlsConfig)
		if err != nil {
			return
		}
		defer common.Close(transport)
	} else {
		transport = &http.Transport{
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return instance, nil
			},
			TLSClientConfig: tlsConfig,
		}
	}
	client := http.Client{
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
		Timeout: C.TCPTimeout,
	}
	defer client.CloseIdleConnections()
	response, err := client.Do(req)
	if err != nil {
		return
	}
	defer response.Body.Close()
	if flags&UnifiedDelay != 0 {
		start = time.Now()
		response, err := client.Do(req)
		if err != nil {
			return 0, err
		}
		defer response.Body.Close()
	}
	t = uint16(time.Since(start) / time.Millisecond)
	return
}
