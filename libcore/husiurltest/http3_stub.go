//go:build !with_quic

package husiurltest

import (
	"crypto/tls"
	"net"
	"net/http"

	C "github.com/sagernet/sing-box/constant"
)

func newHTTP3Transport(_ net.Conn, _ *tls.Config) (http.RoundTripper, error) {
	return nil, C.ErrQUICNotIncluded
}
