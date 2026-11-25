package engine

import (
	"context"
	"fmt"
	"sort"
	"strings"

	"github.com/lucasbittar/lookee/core/db"
	"github.com/lucasbittar/lookee/core/metadata"
)

// Trending returns this week's trending movies or shows on TMDB, for Discover.
func (e *Engine) Trending(ctx context.Context, kind string) ([]metadata.SearchResult, error) {
	if kind != db.KindMovie && kind != db.KindTV {
		return nil, fmt.Errorf("kind must be %q or %q", db.KindMovie, db.KindTV)
	}
	client, err := e.tmdb(ctx)
	if err != nil {
		return nil, err
	}
	res, err := client.Trending(ctx, kind)
	if res == nil {
		res = []metadata.SearchResult{}
	}
	return res, err
}

// SearchTMDB searches TMDB by text for Discover. kind "" searches movies and shows and
// orders the merged list by popularity.
func (e *Engine) SearchTMDB(ctx context.Context, query, kind string) ([]metadata.SearchResult, error) {
	query = strings.TrimSpace(query)
	if query == "" {
		return []metadata.SearchResult{}, nil
	}
	kinds := []string{db.KindMovie, db.KindTV}
	switch kind {
	case "":
	case db.KindMovie, db.KindTV:
		kinds = []string{kind}
	default:
		return nil, fmt.Errorf("kind must be %q, %q or empty", db.KindMovie, db.KindTV)
	}
	client, err := e.tmdb(ctx)
	if err != nil {
		return nil, err
	}
	out := []metadata.SearchResult{}
	for _, k := range kinds {
		res, err := client.Search(ctx, k, query, 0)
		if err != nil {
			return nil, err
		}
		out = append(out, res...)
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Popularity > out[j].Popularity })
	return out, nil
}
