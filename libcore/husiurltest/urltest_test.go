package husiurltest

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"

	N "github.com/sagernet/sing/common/network"
)

func TestMeasureUnifiedDelayUsesTwoRequests(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		requests.Add(1)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	latency, err := Measure(context.Background(), server.URL, new(N.DefaultDialer), UnifiedDelay)
	if err != nil {
		t.Fatal(err)
	}
	if requests.Load() != 2 {
		t.Fatalf("expected two requests, got %d", requests.Load())
	}
	_ = latency
}

func TestMeasureRejectsClosedServer(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	url := server.URL
	server.Close()

	_, err := Measure(context.Background(), url, new(N.DefaultDialer), 0)
	if err == nil {
		t.Fatal("expected an error")
	}
}
