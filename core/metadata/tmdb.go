// Package metadata is a small TMDB v3 client and the logic that binds parsed filenames to TMDB entries.
package metadata

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	DefaultBaseURL  = "https://api.themoviedb.org/3"
	DefaultImageURL = "https://image.tmdb.org/t/p"

	PosterSize   = "w500"
	BackdropSize = "w1280"
	ProfileSize  = "w185"
	StillSize    = "w300"

	MaxCast = 15
)

// ErrNoAPIKey is returned when no TMDB key is configured.
var ErrNoAPIKey = errors.New("TMDB API key not set")

// ErrUnauthorized is returned when TMDB rejects the key.
var ErrUnauthorized = errors.New("TMDB rejected the API key")

// Client talks to TMDB. It accepts either a v3 API key or a v4 read access token.
type Client struct {
	BaseURL  string
	ImageURL string
	Key      string
	Language string // e.g. "en-US", "pt-BR"
	HTTP     *http.Client

	mu   sync.Mutex
	last time.Time
	gap  time.Duration
}

// NewClient returns a client for the given key and language.
func NewClient(key, language string) *Client {
	if language == "" {
		language = "en-US"
	}
	return &Client{
		BaseURL:  DefaultBaseURL,
		ImageURL: DefaultImageURL,
		Key:      strings.TrimSpace(key),
		Language: language,
		HTTP:     &http.Client{Timeout: 15 * time.Second},
		gap:      40 * time.Millisecond, // ~25 req/s, under TMDB's limit
	}
}

// isBearer reports whether the key is a v4 read access token (a JWT) rather than a v3 key.
func (c *Client) isBearer() bool { return strings.HasPrefix(c.Key, "eyJ") }

func (c *Client) throttle(ctx context.Context) error {
	c.mu.Lock()
	wait := time.Until(c.last.Add(c.gap))
	if wait < 0 {
		wait = 0
	}
	c.last = time.Now().Add(wait)
	c.mu.Unlock()
	if wait == 0 {
		return nil
	}
	select {
	case <-time.After(wait):
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (c *Client) get(ctx context.Context, path string, params url.Values, out any) error {
	if c.Key == "" {
		return ErrNoAPIKey
	}
	if params == nil {
		params = url.Values{}
	}
	if c.Language != "" && params.Get("language") == "" {
		params.Set("language", c.Language)
	}
	if !c.isBearer() {
		params.Set("api_key", c.Key)
	}
	u := strings.TrimRight(c.BaseURL, "/") + path + "?" + params.Encode()

	for attempt := 0; attempt < 3; attempt++ {
		if err := c.throttle(ctx); err != nil {
			return err
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
		if err != nil {
			return err
		}
		req.Header.Set("Accept", "application/json")
		if c.isBearer() {
			req.Header.Set("Authorization", "Bearer "+c.Key)
		}
		resp, err := c.HTTP.Do(req)
		if err != nil {
			return err
		}
		body, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
		resp.Body.Close()
		if err != nil {
			return err
		}
		switch {
		case resp.StatusCode == http.StatusTooManyRequests:
			retry, _ := strconv.Atoi(resp.Header.Get("Retry-After"))
			if retry <= 0 {
				retry = 1
			}
			select {
			case <-time.After(time.Duration(retry) * time.Second):
				continue
			case <-ctx.Done():
				return ctx.Err()
			}
		case resp.StatusCode == http.StatusUnauthorized:
			return ErrUnauthorized
		case resp.StatusCode != http.StatusOK:
			return fmt.Errorf("tmdb %s: HTTP %d", path, resp.StatusCode)
		}
		return json.Unmarshal(body, out)
	}
	return fmt.Errorf("tmdb %s: rate limited", path)
}

// Image builds a full image URL, or "" when TMDB has no image.
func (c *Client) Image(size, path string) string {
	if path == "" {
		return ""
	}
	return strings.TrimRight(c.ImageURL, "/") + "/" + size + path
}

// ---- search ----

// SearchResult is one hit from /search/movie or /search/tv.
type SearchResult struct {
	ID            int64   `json:"id"`
	Kind          string  `json:"kind"`
	Title         string  `json:"title"`
	OriginalTitle string  `json:"originalTitle,omitempty"`
	Year          int     `json:"year,omitempty"`
	Overview      string  `json:"overview,omitempty"`
	PosterURL     string  `json:"posterUrl,omitempty"`
	Popularity    float64 `json:"popularity"`
	VoteCount     int     `json:"voteCount"`
}

type rawResult struct {
	ID            int64   `json:"id"`
	MediaType     string  `json:"media_type"`
	Title         string  `json:"title"`
	Name          string  `json:"name"`
	OriginalTitle string  `json:"original_title"`
	OriginalName  string  `json:"original_name"`
	ReleaseDate   string  `json:"release_date"`
	FirstAirDate  string  `json:"first_air_date"`
	Overview      string  `json:"overview"`
	PosterPath    string  `json:"poster_path"`
	Popularity    float64 `json:"popularity"`
	VoteCount     int     `json:"vote_count"`
}

func (c *Client) toResult(r rawResult, kind string) SearchResult {
	if r.MediaType != "" {
		kind = r.MediaType
	}
	out := SearchResult{ID: r.ID, Kind: kind, Overview: r.Overview, PosterURL: c.Image(PosterSize, r.PosterPath),
		Popularity: r.Popularity, VoteCount: r.VoteCount}
	if kind == "tv" {
		out.Title, out.OriginalTitle, out.Year = r.Name, r.OriginalName, yearOf(r.FirstAirDate)
	} else {
		out.Title, out.OriginalTitle, out.Year = r.Title, r.OriginalTitle, yearOf(r.ReleaseDate)
	}
	return out
}

// Search queries /search/movie or /search/tv. year may be 0.
func (c *Client) Search(ctx context.Context, kind, query string, year int) ([]SearchResult, error) {
	params := url.Values{"query": {query}, "include_adult": {"false"}}
	if year > 0 {
		if kind == "tv" {
			params.Set("first_air_date_year", strconv.Itoa(year))
		} else {
			params.Set("primary_release_year", strconv.Itoa(year))
		}
	}
	var resp struct {
		Results []rawResult `json:"results"`
	}
	if err := c.get(ctx, "/search/"+kind, params, &resp); err != nil {
		return nil, err
	}
	out := make([]SearchResult, 0, len(resp.Results))
	for _, r := range resp.Results {
		out = append(out, c.toResult(r, kind))
	}
	return out, nil
}

// Trending returns this week's trending movies or shows.
func (c *Client) Trending(ctx context.Context, kind string) ([]SearchResult, error) {
	var resp struct {
		Results []rawResult `json:"results"`
	}
	if err := c.get(ctx, "/trending/"+kind+"/week", nil, &resp); err != nil {
		return nil, err
	}
	out := make([]SearchResult, 0, len(resp.Results))
	for _, r := range resp.Results {
		out = append(out, c.toResult(r, kind))
	}
	return out, nil
}

// ---- details ----

// Details is the full record for a movie or show.
type Details struct {
	ID          int64
	Kind        string
	Title       string
	Original    string
	Year        int
	Tagline     string
	Overview    string
	ReleaseDate string
	Runtime     int
	Genres      []string
	Rating      float64
	PosterURL   string
	BackdropURL string
	Cast        []Cast
	Seasons     []int
}

// Cast is one credited actor.
type Cast struct {
	Order      int
	Name       string
	Character  string
	ProfileURL string
}

type rawDetails struct {
	rawResult
	Tagline        string `json:"tagline"`
	Runtime        int    `json:"runtime"`
	EpisodeRunTime []int  `json:"episode_run_time"`
	Genres         []struct {
		Name string `json:"name"`
	} `json:"genres"`
	VoteAverage  float64 `json:"vote_average"`
	BackdropPath string  `json:"backdrop_path"`
	Credits      struct {
		Cast []struct {
			Name        string `json:"name"`
			Character   string `json:"character"`
			ProfilePath string `json:"profile_path"`
			Order       int    `json:"order"`
		} `json:"cast"`
	} `json:"credits"`
	Seasons []struct {
		SeasonNumber int `json:"season_number"`
	} `json:"seasons"`
}

// Details fetches a movie or show with its credits.
func (c *Client) Details(ctx context.Context, kind string, id int64) (Details, error) {
	var raw rawDetails
	params := url.Values{"append_to_response": {"credits"}}
	if err := c.get(ctx, fmt.Sprintf("/%s/%d", kind, id), params, &raw); err != nil {
		return Details{}, err
	}
	base := c.toResult(raw.rawResult, kind)
	d := Details{
		ID: raw.ID, Kind: kind, Title: base.Title, Original: base.OriginalTitle, Year: base.Year,
		Tagline: raw.Tagline, Overview: raw.Overview, Runtime: raw.Runtime, Rating: raw.VoteAverage,
		PosterURL: base.PosterURL, BackdropURL: c.Image(BackdropSize, raw.BackdropPath),
	}
	if kind == "tv" {
		d.ReleaseDate = raw.FirstAirDate
		if d.Runtime == 0 && len(raw.EpisodeRunTime) > 0 {
			d.Runtime = raw.EpisodeRunTime[0]
		}
	} else {
		d.ReleaseDate = raw.ReleaseDate
	}
	for _, g := range raw.Genres {
		d.Genres = append(d.Genres, g.Name)
	}
	for i, cm := range raw.Credits.Cast {
		if i >= MaxCast {
			break
		}
		d.Cast = append(d.Cast, Cast{Order: i, Name: cm.Name, Character: cm.Character, ProfileURL: c.Image(ProfileSize, cm.ProfilePath)})
	}
	for _, s := range raw.Seasons {
		d.Seasons = append(d.Seasons, s.SeasonNumber)
	}
	return d, nil
}

// EpisodeInfo is one episode of a season.
type EpisodeInfo struct {
	Season   int
	Episode  int
	Name     string
	Overview string
	StillURL string
	AirDate  string
}

// Season fetches the episode list of one season of a show.
func (c *Client) Season(ctx context.Context, showID int64, season int) ([]EpisodeInfo, error) {
	var raw struct {
		Episodes []struct {
			SeasonNumber  int    `json:"season_number"`
			EpisodeNumber int    `json:"episode_number"`
			Name          string `json:"name"`
			Overview      string `json:"overview"`
			StillPath     string `json:"still_path"`
			AirDate       string `json:"air_date"`
		} `json:"episodes"`
	}
	if err := c.get(ctx, fmt.Sprintf("/tv/%d/season/%d", showID, season), nil, &raw); err != nil {
		return nil, err
	}
	out := make([]EpisodeInfo, 0, len(raw.Episodes))
	for _, e := range raw.Episodes {
		out = append(out, EpisodeInfo{Season: e.SeasonNumber, Episode: e.EpisodeNumber, Name: e.Name,
			Overview: e.Overview, StillURL: c.Image(StillSize, e.StillPath), AirDate: e.AirDate})
	}
	return out, nil
}

func yearOf(date string) int {
	if len(date) < 4 {
		return 0
	}
	y, _ := strconv.Atoi(date[:4])
	return y
}
