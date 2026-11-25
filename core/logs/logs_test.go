package logs

import (
	"fmt"
	"log/slog"
	"strings"
	"testing"
)

func TestRotatingAndTail(t *testing.T) {
	dir := t.TempDir()
	r, err := Open(dir, 200)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	for i := range 30 {
		fmt.Fprintf(r, "line %02d\n", i) // 8 bytes each
	}
	lines, err := r.Tail(5)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Join(lines, ",") != "line 25,line 26,line 27,line 28,line 29" {
		t.Fatalf("tail = %v", lines)
	}
	all, _ := r.Tail(1000)
	// Only the current and one previous file survive: total stays under 2 × limit.
	if len(all) == 0 || len(all) > 50 || all[len(all)-1] != "line 29" {
		t.Fatalf("all = %d lines, last %q", len(all), all[len(all)-1])
	}

	// Reopening appends.
	r.Close()
	r2, _ := Open(dir, 200)
	defer r2.Close()
	NewLogger(r2, slog.LevelInfo).Info("hello", "k", "v")
	last, _ := r2.Tail(1)
	if len(last) != 1 || !strings.Contains(last[0], "msg=hello k=v") {
		t.Fatalf("last = %v", last)
	}
}

func TestEmptyTailAndRedact(t *testing.T) {
	r, _ := Open(t.TempDir(), 0)
	defer r.Close()
	if l, _ := r.Tail(10); len(l) != 0 {
		t.Fatalf("empty log tail = %v", l)
	}
	if Redact("abcdef123456") != "abcd…(12)" || Redact("ab") != "…" {
		t.Fatal("redact")
	}
}
