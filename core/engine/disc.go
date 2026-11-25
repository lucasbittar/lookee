package engine

import (
	"context"

	"github.com/lucasbittar/lookee/core/disc"
	"github.com/lucasbittar/lookee/core/loopback"
)

// DiscPlay is a disc rip ready to play: the loopback URL of its main title.
type DiscPlay struct {
	URL     string  `json:"url"`
	Kind    string  `json:"kind"`
	Parts   int     `json:"parts"`
	Size    int64   `json:"size"`
	Seconds float64 `json:"seconds,omitempty"`
}

// PlayDisc finds the main title of the disc rip at path (Decision 13) and serves it on the
// loopback server as one stream. kind is the scanner's kind ("" guesses). One disc plays at
// a time; starting another stops the previous one.
func (e *Engine) PlayDisc(_ context.Context, path, kind string) (DiscPlay, error) {
	title, err := disc.Find(path, kind)
	if err != nil {
		e.log.Warn("disc", "path", path, "err", err)
		return DiscPlay{}, err
	}
	srv, err := loopback.Listen("127.0.0.1:0", title.Name, func(context.Context) (loopback.ReadSeekCloser, error) {
		return title.Open()
	})
	if err != nil {
		return DiscPlay{}, err
	}
	e.discMu.Lock()
	if e.disc != nil {
		e.disc.Close()
	}
	e.disc = srv
	e.discMu.Unlock()
	e.log.Info("disc", "path", path, "title", title.String())
	return DiscPlay{URL: srv.URL(), Kind: title.Kind, Parts: len(title.Parts), Size: title.Size(), Seconds: title.Seconds}, nil
}

// StopDisc closes the disc server, if one is running.
func (e *Engine) StopDisc() {
	e.discMu.Lock()
	defer e.discMu.Unlock()
	if e.disc != nil {
		e.disc.Close()
		e.disc = nil
	}
}
