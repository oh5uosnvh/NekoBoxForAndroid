package xhttp

import (
	"net"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

func TestStreamCipherRoundTrip(t *testing.T) {
	left, right := net.Pipe()
	defer left.Close()
	defer right.Close()

	cipher, err := NewStreamCipher("")
	if err != nil {
		t.Fatal(err)
	}
	client := cipher.ClientConn(left, M.ParseSocksaddr("example.com:443"), nil, nil)

	want := []byte("hello xhttp")
	readDone := make(chan []byte, 1)
	go func() {
		buf := make([]byte, 1024)
		wire := make([]byte, 0, 256)
		for {
			_ = right.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
			n, err := right.Read(buf)
			if n > 0 {
				wire = append(wire, buf[:n]...)
			}
			if err != nil {
				break
			}
		}
		readDone <- wire
	}()
	_, err = client.Write(want)
	if err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-readDone:
		if string(want) == string(got) {
			t.Fatal("wire data must not be plaintext")
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for encrypted wire data")
	}
}
