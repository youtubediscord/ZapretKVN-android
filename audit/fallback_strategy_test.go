package fallback

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
)

// This file is copied into the exact pinned checkout only for `go test` and is
// removed immediately afterwards. It deliberately tests the unexported
// strategy implementation without patching the libbox binary we ship.
type zapretAuditTransport struct {
	tag      string
	calls    int
	exchange func(context.Context, *dns.Msg) (*dns.Msg, error)
}

func (t *zapretAuditTransport) Start(adapter.StartStage) error { return nil }
func (t *zapretAuditTransport) Close() error                   { return nil }
func (t *zapretAuditTransport) Type() string                   { return "audit" }
func (t *zapretAuditTransport) Tag() string                    { return t.tag }
func (t *zapretAuditTransport) Dependencies() []string         { return nil }
func (t *zapretAuditTransport) Reset()                         {}
func (t *zapretAuditTransport) Exchange(ctx context.Context, message *dns.Msg) (*dns.Msg, error) {
	t.calls++
	return t.exchange(ctx, message)
}

func TestZapretSequentialSuccessStopsAtFirstTransportAndKeepsContext(t *testing.T) {
	shared := context.WithValue(context.Background(), struct{}{}, "shared")
	query := new(dns.Msg).SetQuestion("example.com.", dns.TypeA)
	want := new(dns.Msg).SetReply(query)
	first := &zapretAuditTransport{tag: "first"}
	first.exchange = func(ctx context.Context, _ *dns.Msg) (*dns.Msg, error) {
		if ctx != shared {
			t.Fatal("first transport received a different context")
		}
		return want, nil
	}
	second := &zapretAuditTransport{tag: "second"}
	second.exchange = func(context.Context, *dns.Msg) (*dns.Msg, error) {
		t.Fatal("second transport ran after first success")
		return nil, nil
	}

	strategy, err := CreateStrategy("sequential", []adapter.DNSTransport{first, second}, nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err := strategy(shared, query)
	if err != nil || response != want {
		t.Fatalf("unexpected result: response=%p error=%v", response, err)
	}
	if first.calls != 1 || second.calls != 0 {
		t.Fatalf("unexpected call counts: first=%d second=%d", first.calls, second.calls)
	}
}

func TestZapretSequentialTransportErrorFallsBackWithSameContext(t *testing.T) {
	shared := context.WithValue(context.Background(), struct{}{}, "shared")
	query := new(dns.Msg).SetQuestion("example.com.", dns.TypeA)
	want := new(dns.Msg).SetReply(query)
	first := &zapretAuditTransport{tag: "first"}
	first.exchange = func(ctx context.Context, _ *dns.Msg) (*dns.Msg, error) {
		if ctx != shared {
			t.Fatal("first transport received a different context")
		}
		return nil, errors.New("transport failed")
	}
	second := &zapretAuditTransport{tag: "second"}
	second.exchange = func(ctx context.Context, _ *dns.Msg) (*dns.Msg, error) {
		if ctx != shared {
			t.Fatal("fallback transport received a different context")
		}
		return want, nil
	}

	strategy, err := CreateStrategy("", []adapter.DNSTransport{first, second}, nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err := strategy(shared, query)
	if err != nil || response != want {
		t.Fatalf("unexpected result: response=%p error=%v", response, err)
	}
	if first.calls != 1 || second.calls != 1 {
		t.Fatalf("unexpected call counts: first=%d second=%d", first.calls, second.calls)
	}
}

func TestZapretSequentialHangConsumesSharedDeadline(t *testing.T) {
	shared, cancel := context.WithTimeout(context.Background(), 40*time.Millisecond)
	defer cancel()
	query := new(dns.Msg).SetQuestion("example.com.", dns.TypeA)
	first := &zapretAuditTransport{tag: "first"}
	first.exchange = func(ctx context.Context, _ *dns.Msg) (*dns.Msg, error) {
		if ctx != shared {
			t.Fatal("first transport received a different context")
		}
		<-ctx.Done()
		return nil, ctx.Err()
	}
	second := &zapretAuditTransport{tag: "second"}
	second.exchange = func(ctx context.Context, _ *dns.Msg) (*dns.Msg, error) {
		if ctx != shared {
			t.Fatal("fallback transport received a new context")
		}
		if !errors.Is(ctx.Err(), context.DeadlineExceeded) {
			t.Fatalf("fallback did not inherit the expired deadline: %v", ctx.Err())
		}
		return nil, ctx.Err()
	}

	strategy, err := CreateStrategy("sequential", []adapter.DNSTransport{first, second}, nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err := strategy(shared, query)
	if response != nil || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("unexpected result: response=%p error=%v", response, err)
	}
	if first.calls != 1 || second.calls != 1 {
		t.Fatalf("unexpected call counts: first=%d second=%d", first.calls, second.calls)
	}
}

func TestZapretSequentialRcodeIsAResultNotFallback(t *testing.T) {
	for _, rcode := range []int{dns.RcodeNameError, dns.RcodeServerFailure, dns.RcodeRefused} {
		t.Run(dns.RcodeToString[rcode], func(t *testing.T) {
			shared := context.WithValue(context.Background(), struct{}{}, rcode)
			query := new(dns.Msg).SetQuestion("example.com.", dns.TypeA)
			want := new(dns.Msg).SetReply(query)
			want.Rcode = rcode
			first := &zapretAuditTransport{tag: "first"}
			first.exchange = func(ctx context.Context, _ *dns.Msg) (*dns.Msg, error) {
				if ctx != shared {
					t.Fatal("first transport received a different context")
				}
				return want, nil
			}
			second := &zapretAuditTransport{tag: "second"}
			second.exchange = func(context.Context, *dns.Msg) (*dns.Msg, error) {
				t.Fatal("second transport ran after a valid RCODE response")
				return nil, nil
			}

			strategy, err := CreateStrategy("sequential", []adapter.DNSTransport{first, second}, nil)
			if err != nil {
				t.Fatal(err)
			}
			response, err := strategy(shared, query)
			if err != nil || response == nil || response.Rcode != rcode {
				t.Fatalf("unexpected result: response=%v error=%v", response, err)
			}
			if first.calls != 1 || second.calls != 0 {
				t.Fatalf("unexpected call counts: first=%d second=%d", first.calls, second.calls)
			}
		})
	}
}
