//go:build with_quic

package husiurltest

import (
	"crypto/tls"
	"net"
	"net/http"

	C "github.com/sagernet/sing-box/constant"
)

// Neko's pinned quic-go exposes a different HTTP/3 dial contract than husi's
// current dependency. Keep the build-tagged entry point ABI-compatible and
// fail closed instead of silently testing HTTP/3 outside the selected outbound.
// The regular HTTP/HTTPS URLTest path remains fully supported.
func newHTTP3Transport(_ net.Conn, _ *tls.Config) (http.RoundTripper, error) {
	return nil, C.ErrQUICNotIncluded
}
