package subtitles

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

const DefaultBaseURL = "https://api.opensubtitles.com/api/v1"

// ErrNoAPIKey is returned when no OpenSubtitles key is configured.
var ErrNoAPIKey = errors.New("OpenSubtitles API key not set")

// ErrQuota is returned when the daily download quota is used up.
var ErrQuota = errors.New("OpenSubtitles download limit reached for today")

// Client is a minimal OpenSubtitles REST client.
type Client struct {
	BaseURL   string
	APIKey    string
	Token     string // optional: from Login, raises the daily download quota
	UserAgent string
	HTTP      *http.Client
}

// NewClient returns a client for apiKey.
func NewClient(apiKey string) *Client {
	return &Client{
		BaseURL:   DefaultBaseURL,
		APIKey:    strings.TrimSpace(apiKey),
		UserAgent: "lookee v0.1",
		HTTP:      &http.Client{Timeout: 20 * time.Second},
	}
}

// Query selects subtitles. Fill whatever is known; MovieHash gives the best-synced results.
type Query struct {
	MovieHash    string
	TMDBID       int64 // movie id, or the show id when Season/Episode are set
	Query        string
	Season       int
	Episode      int
	Languages    []string // e.g. ["pt-BR", "en"]
	IsEpisode    bool
	ExcludeHI    bool // skip hearing-impaired subtitles
	ForeignParts bool
}

// Result is one downloadable subtitle file.
type Result struct {
	FileID          int64   `json:"fileId"`
	FileName        string  `json:"fileName"`
	Language        string  `json:"language"`
	Release         string  `json:"release"`
	Downloads       int     `json:"downloads"`
	Rating          float64 `json:"rating"`
	HearingImpaired bool    `json:"hearingImpaired"`
	HashMatch       bool    `json:"hashMatch"` // synced to this exact file
	Trusted         bool    `json:"trusted"`
}

func (c *Client) do(ctx context.Context, method, path string, params url.Values, body any, out any) error {
	if c.APIKey == "" {
		return ErrNoAPIKey
	}
	u := strings.TrimRight(c.BaseURL, "/") + path
	if len(params) > 0 {
		// The API redirects unless parameters are lowercase and sorted; Encode sorts keys.
		u += "?" + params.Encode()
	}
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, u, rd)
	if err != nil {
		return err
	}
	req.Header.Set("Api-Key", c.APIKey)
	req.Header.Set("User-Agent", c.UserAgent)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if c.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return err
	}
	switch {
	case resp.StatusCode == http.StatusNotAcceptable || resp.StatusCode == http.StatusTooManyRequests:
		return ErrQuota
	case resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden:
		return fmt.Errorf("OpenSubtitles rejected the API key or login (HTTP %d)", resp.StatusCode)
	case resp.StatusCode >= 300:
		return fmt.Errorf("opensubtitles %s: HTTP %d", path, resp.StatusCode)
	}
	return json.Unmarshal(b, out)
}

// Login exchanges a username and password for a token stored on the client.
func (c *Client) Login(ctx context.Context, username, password string) error {
	var resp struct {
		Token   string `json:"token"`
		BaseURL string `json:"base_url"`
	}
	if err := c.do(ctx, http.MethodPost, "/login", nil, map[string]string{"username": username, "password": password}, &resp); err != nil {
		return err
	}
	c.Token = resp.Token
	return nil
}

// Search finds subtitles, best first: exact hash matches, then preferred language order,
// then downloads.
func (c *Client) Search(ctx context.Context, q Query) ([]Result, error) {
	p := url.Values{}
	if len(q.Languages) > 0 {
		langs := make([]string, len(q.Languages))
		for i, l := range q.Languages {
			langs[i] = strings.ToLower(strings.TrimSpace(l))
		}
		sorted := append([]string(nil), langs...)
		sort.Strings(sorted)
		p.Set("languages", strings.Join(sorted, ","))
	}
	if q.MovieHash != "" {
		p.Set("moviehash", q.MovieHash)
	}
	if q.IsEpisode {
		p.Set("type", "episode")
		if q.TMDBID > 0 {
			p.Set("parent_tmdb_id", strconv.FormatInt(q.TMDBID, 10))
		}
		if q.Season > 0 {
			p.Set("season_number", strconv.Itoa(q.Season))
		}
		if q.Episode > 0 {
			p.Set("episode_number", strconv.Itoa(q.Episode))
		}
	} else if q.TMDBID > 0 {
		p.Set("tmdb_id", strconv.FormatInt(q.TMDBID, 10))
	}
	if q.Query != "" && q.TMDBID == 0 {
		p.Set("query", strings.ToLower(q.Query))
	}
	if q.ExcludeHI {
		p.Set("hearing_impaired", "exclude")
	}
	if !q.ForeignParts {
		p.Set("foreign_parts_only", "exclude")
	}

	var resp struct {
		Data []struct {
			Attributes struct {
				Language        string  `json:"language"`
				Downloads       int     `json:"download_count"`
				Ratings         float64 `json:"ratings"`
				HearingImpaired bool    `json:"hearing_impaired"`
				Release         string  `json:"release"`
				FromTrusted     bool    `json:"from_trusted"`
				HashMatch       bool    `json:"moviehash_match"`
				Files           []struct {
					FileID   int64  `json:"file_id"`
					FileName string `json:"file_name"`
				} `json:"files"`
			} `json:"attributes"`
		} `json:"data"`
	}
	if err := c.do(ctx, http.MethodGet, "/subtitles", p, nil, &resp); err != nil {
		return nil, err
	}
	rank := map[string]int{}
	for i, l := range q.Languages {
		rank[strings.ToLower(l)] = i
	}
	var out []Result
	for _, d := range resp.Data {
		a := d.Attributes
		for _, f := range a.Files {
			out = append(out, Result{FileID: f.FileID, FileName: f.FileName, Language: a.Language, Release: a.Release,
				Downloads: a.Downloads, Rating: a.Ratings, HearingImpaired: a.HearingImpaired, HashMatch: a.HashMatch, Trusted: a.FromTrusted})
		}
	}
	langRank := func(l string) int {
		if r, ok := rank[strings.ToLower(l)]; ok {
			return r
		}
		return len(rank)
	}
	sort.SliceStable(out, func(i, j int) bool {
		a, b := out[i], out[j]
		if a.HashMatch != b.HashMatch {
			return a.HashMatch
		}
		if la, lb := langRank(a.Language), langRank(b.Language); la != lb {
			return la < lb
		}
		return a.Downloads > b.Downloads
	})
	return out, nil
}

var reUnsafe = regexp.MustCompile(`[^\w.\-]+`)

// Download fetches a subtitle file, converts it to UTF-8 and saves it in dir.
// It returns the local path.
func (c *Client) Download(ctx context.Context, fileID int64, dir string) (string, error) {
	var resp struct {
		Link     string `json:"link"`
		FileName string `json:"file_name"`
	}
	if err := c.do(ctx, http.MethodPost, "/download", nil, map[string]int64{"file_id": fileID}, &resp); err != nil {
		return "", err
	}
	if resp.Link == "" {
		return "", errors.New("opensubtitles: no download link returned")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, resp.Link, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", c.UserAgent)
	r, err := c.HTTP.Do(req)
	if err != nil {
		return "", err
	}
	defer r.Body.Close()
	if r.StatusCode != http.StatusOK {
		return "", fmt.Errorf("subtitle download: HTTP %d", r.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(r.Body, 8<<20))
	if err != nil {
		return "", err
	}
	name := resp.FileName
	if name == "" {
		name = strconv.FormatInt(fileID, 10) + ".srt"
	}
	name = reUnsafe.ReplaceAllString(name, "_")
	if !Extensions[strings.ToLower(filepath.Ext(name))] {
		name += ".srt"
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	out := filepath.Join(dir, fmt.Sprintf("%d_%s", fileID, name))
	return out, os.WriteFile(out, ToUTF8(b), 0o644)
}
