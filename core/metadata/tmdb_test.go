package metadata

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

// fakeTMDB serves canned JSON keyed by request path and records the queries it received.
func fakeTMDB(t *testing.T, routes map[string]string) (*Client, *[]string) {
	t.Helper()
	var seen []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = append(seen, r.URL.Path+"?"+r.URL.RawQuery)
		if r.URL.Query().Get("api_key") == "bad" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		key := r.URL.Path
		if q := r.URL.Query().Get("query"); q != "" {
			key += "?query=" + q
			if y := r.URL.Query().Get("primary_release_year") + r.URL.Query().Get("first_air_date_year"); y != "" {
				key += "&year=" + y
			}
		}
		body, ok := routes[key]
		if !ok {
			body = `{"results":[]}`
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	c := NewClient("testkey", "en-US")
	c.BaseURL = srv.URL
	c.ImageURL = "https://img"
	c.gap = 0
	return c, &seen
}

func TestSearchAndImages(t *testing.T) {
	c, seen := fakeTMDB(t, map[string]string{
		"/search/movie?query=The Matrix&year=1999": `{"results":[
			{"id":603,"title":"The Matrix","original_title":"The Matrix","release_date":"1999-03-30","poster_path":"/p.jpg","popularity":80,"vote_count":25000}]}`,
	})
	res, err := c.Search(context.Background(), "movie", "The Matrix", 1999)
	if err != nil {
		t.Fatal(err)
	}
	if len(res) != 1 || res[0].ID != 603 || res[0].Year != 1999 || res[0].PosterURL != "https://img/w500/p.jpg" {
		t.Fatalf("res = %+v", res)
	}
	if !strings.Contains((*seen)[0], "api_key=testkey") || !strings.Contains((*seen)[0], "language=en-US") {
		t.Fatalf("query missing key/language: %s", (*seen)[0])
	}
}

func TestBearerTokenUsesHeader(t *testing.T) {
	var auth atomic.Value
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth.Store(r.Header.Get("Authorization") + "|" + r.URL.Query().Get("api_key"))
		w.Write([]byte(`{"results":[]}`))
	}))
	defer srv.Close()
	c := NewClient("eyJhbGciOi.token", "")
	c.BaseURL = srv.URL
	c.Search(context.Background(), "movie", "x", 0)
	if got := auth.Load().(string); got != "Bearer eyJhbGciOi.token|" {
		t.Fatalf("auth = %q", got)
	}
}

func TestErrors(t *testing.T) {
	c := NewClient("", "")
	if _, err := c.Search(context.Background(), "movie", "x", 0); !errors.Is(err, ErrNoAPIKey) {
		t.Fatalf("err = %v", err)
	}
	c2, _ := fakeTMDB(t, nil)
	c2.Key = "bad"
	if _, err := c2.Search(context.Background(), "movie", "x", 0); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("err = %v", err)
	}
}

func TestDetailsMovieAndTV(t *testing.T) {
	cast := `"credits":{"cast":[` + strings.Repeat(`{"name":"Actor","character":"Role","profile_path":"/a.jpg"},`, 20) + `{"name":"Last"}]}`
	c, _ := fakeTMDB(t, map[string]string{
		"/movie/603": `{"id":603,"title":"The Matrix","release_date":"1999-03-30","tagline":"Free your mind","runtime":136,
			"genres":[{"name":"Action"},{"name":"Science Fiction"}],"vote_average":8.2,"backdrop_path":"/b.jpg",` + cast + `}`,
		"/tv/1396": `{"id":1396,"name":"Breaking Bad","first_air_date":"2008-01-20","episode_run_time":[47],
			"seasons":[{"season_number":0},{"season_number":1}],"credits":{"cast":[]}}`,
		"/tv/1396/season/1": `{"episodes":[{"season_number":1,"episode_number":1,"name":"Pilot","still_path":"/s.jpg","air_date":"2008-01-20"}]}`,
	})
	ctx := context.Background()

	m, err := c.Details(ctx, "movie", 603)
	if err != nil {
		t.Fatal(err)
	}
	if m.Title != "The Matrix" || m.Year != 1999 || m.Runtime != 136 || len(m.Genres) != 2 ||
		m.BackdropURL != "https://img/w1280/b.jpg" || len(m.Cast) != MaxCast || m.Cast[0].ProfileURL != "https://img/w185/a.jpg" {
		t.Fatalf("movie = %+v", m)
	}

	tv, err := c.Details(ctx, "tv", 1396)
	if err != nil {
		t.Fatal(err)
	}
	if tv.Title != "Breaking Bad" || tv.Year != 2008 || tv.Runtime != 47 || tv.ReleaseDate != "2008-01-20" || len(tv.Seasons) != 2 {
		t.Fatalf("tv = %+v", tv)
	}

	eps, err := c.Season(ctx, 1396, 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(eps) != 1 || eps[0].Name != "Pilot" || eps[0].StillURL != "https://img/w300/s.jpg" {
		t.Fatalf("eps = %+v", eps)
	}
}

func TestMatchFallsBackWithoutYearAndCountry(t *testing.T) {
	c, seen := fakeTMDB(t, map[string]string{
		"/search/tv?query=The Office": `{"results":[{"id":2316,"name":"The Office","first_air_date":"2005-03-24","vote_count":3000}]}`,
	})
	r, ok, err := c.Match(context.Background(), Query{Title: "The Office US", Kinds: []string{"tv"}})
	if err != nil || !ok || r.ID != 2316 || r.Kind != "tv" {
		t.Fatalf("r=%+v ok=%v err=%v seen=%v", r, ok, err, *seen)
	}
}

func TestMatchConcertTriesMovieThenTV(t *testing.T) {
	c, _ := fakeTMDB(t, map[string]string{
		"/search/tv?query=Rock in Rio": `{"results":[{"id":9,"name":"Rock in Rio","first_air_date":"1985-01-11"}]}`,
	})
	r, ok, err := c.Match(context.Background(), Query{Title: "Rock in Rio", Kinds: []string{"movie", "tv"}})
	if err != nil || !ok || r.Kind != "tv" {
		t.Fatalf("r=%+v ok=%v err=%v", r, ok, err)
	}
}

func TestBest(t *testing.T) {
	results := []SearchResult{
		{ID: 1, Title: "Dune", Year: 1984, VoteCount: 3000},
		{ID: 2, Title: "Dune", Year: 2021, VoteCount: 12000},
		{ID: 3, Title: "Dune: Part Two", Year: 2024, VoteCount: 6000},
		{ID: 4, Title: "Jodorowsky's Dune", Year: 2013},
	}
	tests := []struct {
		title string
		year  int
		want  int64
		ok    bool
	}{
		{"Dune", 1984, 1, true},
		{"Dune", 2021, 2, true},
		{"Dune", 2020, 2, true}, // off-by-one release years are common
		{"Dune Part Two", 2024, 3, true},
		{"Completely Different", 0, 0, false},
	}
	for _, tt := range tests {
		got, ok := Best(results, tt.title, tt.year)
		if ok != tt.ok || got.ID != tt.want {
			t.Errorf("Best(%q,%d) = %d,%v want %d,%v", tt.title, tt.year, got.ID, ok, tt.want, tt.ok)
		}
	}
}

// Cases from the real MIBOX3 library (2026-11-24 crawl).
func TestBestRealLibrary(t *testing.T) {
	tests := []struct {
		name    string
		results []SearchResult
		title   string
		year    int
		want    int64 // 0 = must be rejected
	}{
		{"weak hit with no year is rejected",
			[]SearchResult{{ID: 1, Title: "The Complete History of the Philadelphia Eagles"}},
			"History Of The Eagles", 2013, 0},
		{"near-exact concert title despite release year",
			[]SearchResult{{ID: 2, Title: "Jimi Hendrix: Live at Woodstock", Year: 1999}},
			"Jimi Hendrix - Live at Woodstock", 1969, 2},
		{"concert year in the TMDB title",
			[]SearchResult{{ID: 3, Title: "Oasis: Knebworth 1996", Year: 2021}},
			"Oasis Knebworth", 1996, 3},
		{"dotted acronym",
			[]SearchResult{{ID: 4, Title: "Pink Floyd: P.U.L.S.E. Live at Earls Court", Year: 1994}},
			"Pink Floyd PULSE Live At Earls Court", 1994, 4},
		{"right year beats exact title with the wrong year",
			[]SearchResult{{ID: 6, Title: "Back to the Beginning", Year: 2015}, {ID: 7, Title: "Back to the Beginning: Ozzy's Final Bow", Year: 2026}},
			"Back to the Beginning", 2025, 7},
		{"partial title with a far-off year is rejected",
			[]SearchResult{{ID: 5, Title: "Insanity: The Asylum Volume 1", Year: 2011}},
			"Volume 1", 2020, 0},
	}
	for _, tt := range tests {
		got, ok := Best(tt.results, tt.title, tt.year)
		if (tt.want == 0 && ok) || (tt.want != 0 && got.ID != tt.want) {
			t.Errorf("%s: got %+v ok=%v, want id %d", tt.name, got, ok, tt.want)
		}
	}
}

func TestMatchPrefersStrongTVHitOverWeakMovieHit(t *testing.T) {
	c, _ := fakeTMDB(t, map[string]string{
		"/search/movie?query=History Of The Eagles&year=2013": `{"results":[{"id":1,"title":"History of Eagles in America","release_date":"2013-01-01"}]}`,
		"/search/tv?query=History Of The Eagles&year=2013":    `{"results":[{"id":2,"name":"History of the Eagles","first_air_date":"2013-02-15"}]}`,
	})
	r, ok, err := c.Match(context.Background(), Query{Title: "History Of The Eagles", Year: 2013, Kinds: []string{"movie", "tv"}})
	if err != nil || !ok || r.ID != 2 {
		t.Fatalf("r=%+v ok=%v err=%v", r, ok, err)
	}
}

func TestMatchRetriesWithoutFillerAndWithAltTitle(t *testing.T) {
	c, seen := fakeTMDB(t, map[string]string{
		"/search/movie?query=Green Day Live Rock in Rio&year=2022": `{"results":[{"id":1828092,"title":"Green Day - Live @ rock In Rio","release_date":"2022-09-09"}]}`,
	})
	r, ok, err := c.Match(context.Background(), Query{Title: "Green Day - Live at Rock in Rio", Year: 2022, Kinds: []string{"movie"}})
	if err != nil || !ok || r.ID != 1828092 {
		t.Fatalf("lean query: r=%+v ok=%v err=%v seen=%v", r, ok, err, *seen)
	}
	c2, _ := fakeTMDB(t, map[string]string{
		"/search/movie?query=green day rock in rio&year=2022": `{"results":[{"id":1828092,"title":"Green Day - Live @ rock In Rio","release_date":"2022-09-09"}]}`,
	})
	r, ok, _ = c2.Match(context.Background(), Query{Title: "Something Else Entirely", Year: 2022, Kinds: []string{"movie"}, AltTitles: []string{"green day rock in rio"}})
	if !ok || r.ID != 1828092 {
		t.Fatalf("alt title: r=%+v ok=%v", r, ok)
	}
}

func TestBestUsesOriginalTitle(t *testing.T) {
	results := []SearchResult{{ID: 598, Title: "City of God", OriginalTitle: "Cidade de Deus", Year: 2002}}
	if got, ok := Best(results, "Cidade de Deus", 2002); !ok || got.ID != 598 {
		t.Fatalf("got %+v ok=%v", got, ok)
	}
}
