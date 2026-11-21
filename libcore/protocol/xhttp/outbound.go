package xhttp

// Heysocks (黑石 VPN) OFFICIAL "xhttp" private protocol — reverse-engineered from
// hsks-5.2.8 and VERIFIED end-to-end (decrypted real capture + live gateway
// replay returning HTTP/1.1 204, exit IP 172.104.53.249 SG).
//
// The real wire is BARE TCP carrying the xstream camouflage + AES-128-CTR stream.
// There is NO real TLS ClientHello and NO real HTTP POST — the tls/servername/
// xhttp-path fields in the app config are decoy metadata only.
//
// 方案A: the server validates a per-connection `sess` prelude + `token` that the
// official client fetches from the API (Vc.prepare) and rotates within ~tens of
// seconds. Supply them via config `sess` (68-byte hex) + `auth` (hex token).

import (
	"context"
	"net"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// TypeXhttp is the official protocol name.
const TypeXhttp = "xhttp"

type XhttpOutboundOptions struct {
	option.DialerOptions
	option.ServerOptions
	// Password is the credential "key1[:key2]". Optional — the AES key derives
	// solely from key1's ASCII string, fixed across nodes.
	Password string             `json:"password,omitempty"`
	Network  option.NetworkList `json:"network,omitempty"`
	// Sess is the 68-byte session prelude (hex), server-issued auth material.
	Sess string `json:"sess,omitempty"`
	// Auth is the runtime auth token (hex) validated by the server. REQUIRED for
	// 方案A standalone operation — without it the server silently drops the conn.
	Auth string `json:"auth,omitempty"`
}

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[XhttpOutboundOptions](registry, TypeXhttp, NewOutbound)
}

var _ adapter.Outbound = (*Outbound)(nil)

type Outbound struct {
	outbound.Adapter
	logger     logger.ContextLogger
	dialer     N.Dialer
	serverAddr M.Socksaddr
	cipher     *StreamCipher
	sess       []byte
	token      []byte
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options XhttpOutboundOptions) (adapter.Outbound, error) {
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	cipher, err := NewStreamCipher(options.Password)
	if err != nil {
		return nil, E.Cause(err, "xhttp: init cipher")
	}
	ob := &Outbound{
		Adapter:    outbound.NewAdapterWithDialerOptions(TypeXhttp, tag, options.Network.Build(), options.DialerOptions),
		logger:     logger,
		dialer:     outboundDialer,
		serverAddr: options.Build(),
		cipher:     cipher,
		sess:       decodeHex(options.Sess),
		token:      decodeHex(options.Auth),
	}
	return ob, nil
}

func (o *Outbound) Close() error { return nil }

func (o *Outbound) InterfaceUpdated() {}

func (o *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = o.Tag()
	metadata.Destination = destination
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		o.logger.InfoContext(ctx, "outbound connection to ", destination)
		base, err := o.dialer.DialContext(ctx, N.NetworkTCP, o.serverAddr)
		if err != nil {
			return nil, err
		}
		return o.cipher.ClientConn(base, destination, o.sess, o.token), nil
	default:
		return nil, E.New("unsupported network: ", network)
	}
}

func (o *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, E.New("xhttp: UDP not supported")
}
