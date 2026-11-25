package torrent

import (
	"context"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Query describes what to look for. IMDbID ("tt0111161") is required by EZTV and
// Stremio add-ons; Title is used by text-search indexers.
type Query struct {
	Kind    string // "movie" or "tv"
	Title   string
	Year    int
	IMDbID  string
	Season  int
	Episode int
}

// Result is one torrent found by a provider.
type Result struct {
	Provider  string `json:"provider"`
	Title     string `json:"title"`
	Quality   string `json:"quality"` // "2160p", "1080p", "720p", "480p" or ""
	Size      int64  `json:"size,omitempty"`
	Seeders   int    `json:"seeders"`
	Peers     int    `json:"peers"`
	InfoHash  string `json:"infoHash"`
	Magnet    string `json:"magnet"`
	FileIndex int    `json:"fileIndex"` // -1 = let the streamer pick the largest video
}

// Provider searches one indexer.
type Provider interface {
	Name() string
	Search(ctx context.Context, q Query) ([]Result, error)
}

// DefaultTrackers are added to magnets built from a bare info hash.
var DefaultTrackers = []string{
	"udp://tracker.opentrackr.org:1337/announce",
	"udp://open.demonii.com:1337/announce",
	"udp://open.stealth.si:80/announce",
	"udp://exodus.desync.com:6969/announce",
	"udp://tracker.torrent.eu.org:451/announce",
}

// MagnetFromHash builds a magnet link with the default trackers.
func MagnetFromHash(hash, name string) string {
	v := url.Values{}
	v.Set("xt", "urn:btih:"+strings.ToLower(hash))
	if name != "" {
		v.Set("dn", name)
	}
	for _, tr := range DefaultTrackers {
		v.Add("tr", tr)
	}
	return "magnet:?" + v.Encode()
}

var (
	reQuality  = regexp.MustCompile(`(?i)\b(2160p|4k|uhd|1080p|720p|480p)\b`)
	reInfoHash = regexp.MustCompile(`(?i)urn:btih:([a-f0-9]{40}|[a-z2-7]{32})`)
)

// QualityOf extracts a normalized resolution label from a release name.
func QualityOf(s string) string {
	m := reQuality.FindString(s)
	switch strings.ToLower(m) {
	case "4k", "uhd", "2160p":
		return "2160p"
	case "":
		return ""
	}
	return strings.ToLower(m)
}

func qualityRank(q string) int {
	switch q {
	case "1080p":
		return 4 // preferred on a 1080p TV box: best balance of quality and bandwidth
	case "2160p":
		return 3
	case "720p":
		return 2
	case "480p":
		return 1
	}
	return 0
}

// Search queries all providers in parallel, merges results by info hash, and sorts them
// by seeders (then quality). Provider failures are returned alongside partial results.
func Search(ctx context.Context, providers []Provider, q Query) ([]Result, error) {
	type out struct {
		res []Result
		err error
	}
	outs := make([]out, len(providers))
	var wg sync.WaitGroup
	for i, p := range providers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r, err := p.Search(ctx, q)
			if err != nil {
				err = fmt.Errorf("%s: %w", p.Name(), err)
			}
			outs[i] = out{r, err}
		}()
	}
	wg.Wait()

	byHash := map[string]Result{}
	var errs []error
	for _, o := range outs {
		if o.err != nil {
			errs = append(errs, o.err)
		}
		for _, r := range o.res {
			r.InfoHash = strings.ToLower(r.InfoHash)
			if r.InfoHash == "" {
				continue
			}
			if r.Quality == "" {
				r.Quality = QualityOf(r.Title)
			}
			if prev, ok := byHash[r.InfoHash]; !ok || r.Seeders > prev.Seeders {
				byHash[r.InfoHash] = r
			}
		}
	}
	results := make([]Result, 0, len(byHash))
	for _, r := range byHash {
		results = append(results, r)
	}
	sort.Slice(results, func(i, j int) bool {
		if results[i].Seeders != results[j].Seeders {
			return results[i].Seeders > results[j].Seeders
		}
		return qualityRank(results[i].Quality) > qualityRank(results[j].Quality)
	})
	return results, errors.Join(errs...)
}

// ---- HTTP helper ----

var httpClient = &http.Client{Timeout: 20 * time.Second}

func getBody(ctx context.Context, u string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "lookee/0.1")
	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 8<<20))
}

func join(base, path string) string { return strings.TrimRight(base, "/") + path }

// ---- YTS (movies) ----

// YTS queries a YTS-compatible API (…/api/v2/list_movies.json).
type YTS struct{ BaseURL string }

func (YTS) Name() string { return "yts" }

func (p YTS) Search(ctx context.Context, q Query) ([]Result, error) {
	if q.Kind != "movie" {
		return nil, nil
	}
	term := q.IMDbID
	if term == "" {
		term = q.Title
	}
	body, err := getBody(ctx, join(p.BaseURL, "/api/v2/list_movies.json?"+url.Values{"query_term": {term}, "limit": {"20"}}.Encode()))
	if err != nil {
		return nil, err
	}
	var resp struct {
		Data struct {
			Movies []struct {
				Title    string `json:"title_long"`
				Year     int    `json:"year"`
				IMDb     string `json:"imdb_code"`
				Torrents []struct {
					Hash    string `json:"hash"`
					Quality string `json:"quality"`
					Type    string `json:"type"`
					Codec   string `json:"video_codec"`
					Seeds   int    `json:"seeds"`
					Peers   int    `json:"peers"`
					Size    int64  `json:"size_bytes"`
				} `json:"torrents"`
			} `json:"movies"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, err
	}
	var out []Result
	for _, m := range resp.Data.Movies {
		if q.IMDbID != "" && !strings.EqualFold(m.IMDb, q.IMDbID) {
			continue
		}
		if q.IMDbID == "" && q.Year > 0 && m.Year > 0 && abs(m.Year-q.Year) > 1 {
			continue
		}
		for _, t := range m.Torrents {
			name := strings.TrimSpace(fmt.Sprintf("%s %s %s %s", m.Title, t.Quality, t.Type, t.Codec))
			out = append(out, Result{Provider: "yts", Title: name, Quality: QualityOf(t.Quality), Size: t.Size,
				Seeders: t.Seeds, Peers: t.Peers, InfoHash: t.Hash, Magnet: MagnetFromHash(t.Hash, m.Title), FileIndex: -1})
		}
	}
	return out, nil
}

// ---- EZTV (TV episodes) ----

// EZTV queries an EZTV-compatible API (…/api/get-torrents?imdb_id=).
type EZTV struct{ BaseURL string }

func (EZTV) Name() string { return "eztv" }

func (p EZTV) Search(ctx context.Context, q Query) ([]Result, error) {
	if q.Kind != "tv" || q.IMDbID == "" {
		return nil, nil
	}
	id := strings.TrimPrefix(strings.ToLower(q.IMDbID), "tt")
	var out []Result
	for page := 1; page <= 5; page++ {
		body, err := getBody(ctx, join(p.BaseURL, "/api/get-torrents?"+url.Values{"imdb_id": {id}, "limit": {"100"}, "page": {strconv.Itoa(page)}}.Encode()))
		if err != nil {
			return out, err
		}
		var resp struct {
			Count    int `json:"torrents_count"`
			Torrents []struct {
				Title   string `json:"title"`
				Hash    string `json:"hash"`
				Magnet  string `json:"magnet_url"`
				Seeds   int    `json:"seeds"`
				Peers   int    `json:"peers"`
				Size    string `json:"size_bytes"`
				Season  string `json:"season"`
				Episode string `json:"episode"`
			} `json:"torrents"`
		}
		if err := json.Unmarshal(body, &resp); err != nil {
			return out, err
		}
		for _, t := range resp.Torrents {
			s, _ := strconv.Atoi(t.Season)
			e, _ := strconv.Atoi(t.Episode)
			if (q.Season > 0 && s != q.Season) || (q.Episode > 0 && e != q.Episode) {
				continue
			}
			size, _ := strconv.ParseInt(t.Size, 10, 64)
			magnet := t.Magnet
			if magnet == "" {
				magnet = MagnetFromHash(t.Hash, t.Title)
			}
			out = append(out, Result{Provider: "eztv", Title: t.Title, Size: size, Seeders: t.Seeds, Peers: t.Peers,
				InfoHash: t.Hash, Magnet: magnet, FileIndex: -1})
		}
		if len(resp.Torrents) < 100 || page*100 >= resp.Count {
			break
		}
	}
	return out, nil
}

// ---- Torznab (Jackett, Prowlarr, …) ----

// Torznab queries a Torznab endpoint. URL is the API URL including its apikey,
// e.g. "http://192.168.1.10:9117/api/v2.0/indexers/all/results/torznab/api?apikey=…".
type Torznab struct {
	Label string
	URL   string
}

func (p Torznab) Name() string {
	if p.Label != "" {
		return p.Label
	}
	return "torznab"
}

func (p Torznab) Search(ctx context.Context, q Query) ([]Result, error) {
	u, err := url.Parse(p.URL)
	if err != nil {
		return nil, err
	}
	v := u.Query()
	switch {
	case q.Kind == "tv":
		v.Set("t", "tvsearch")
		v.Set("q", q.Title)
		if q.Season > 0 {
			v.Set("season", strconv.Itoa(q.Season))
		}
		if q.Episode > 0 {
			v.Set("ep", strconv.Itoa(q.Episode))
		}
	case q.IMDbID != "":
		v.Set("t", "movie")
		v.Set("imdbid", q.IMDbID)
	default:
		v.Set("t", "search")
		term := q.Title
		if q.Year > 0 {
			term += " " + strconv.Itoa(q.Year)
		}
		v.Set("q", term)
	}
	u.RawQuery = v.Encode()
	body, err := getBody(ctx, u.String())
	if err != nil {
		return nil, err
	}
	var feed struct {
		Items []struct {
			Title     string `xml:"title"`
			Link      string `xml:"link"`
			Size      int64  `xml:"size"`
			Enclosure struct {
				URL string `xml:"url,attr"`
			} `xml:"enclosure"`
			Attrs []struct {
				Name  string `xml:"name,attr"`
				Value string `xml:"value,attr"`
			} `xml:"attr"`
		} `xml:"channel>item"`
	}
	if err := xml.Unmarshal(body, &feed); err != nil {
		return nil, err
	}
	var out []Result
	for _, it := range feed.Items {
		r := Result{Provider: p.Name(), Title: it.Title, Size: it.Size, FileIndex: -1}
		for _, a := range it.Attrs {
			switch a.Name {
			case "seeders":
				r.Seeders, _ = strconv.Atoi(a.Value)
			case "peers":
				r.Peers, _ = strconv.Atoi(a.Value)
			case "infohash":
				r.InfoHash = a.Value
			case "magneturl":
				r.Magnet = a.Value
			case "size":
				if r.Size == 0 {
					r.Size, _ = strconv.ParseInt(a.Value, 10, 64)
				}
			}
		}
		for _, l := range []string{r.Magnet, it.Link, it.Enclosure.URL} {
			if r.Magnet == "" && strings.HasPrefix(l, "magnet:") {
				r.Magnet = l
			}
		}
		if r.InfoHash == "" {
			if m := reInfoHash.FindStringSubmatch(r.Magnet); m != nil {
				r.InfoHash = m[1]
			}
		}
		if r.Magnet == "" && r.InfoHash != "" {
			r.Magnet = MagnetFromHash(r.InfoHash, r.Title)
		}
		if r.Magnet != "" {
			out = append(out, r)
		}
	}
	return out, nil
}

// ---- Stremio add-on (stream resource) ----

// Stremio queries a Stremio add-on's stream endpoint, e.g. BaseURL "https://addon.example/…"
// → …/stream/movie/tt0111161.json or …/stream/series/tt0944947:1:1.json.
type Stremio struct {
	Label   string
	BaseURL string
}

func (p Stremio) Name() string {
	if p.Label != "" {
		return p.Label
	}
	return "stremio"
}

var reSeeders = regexp.MustCompile(`👤\s*(\d+)`)

func (p Stremio) Search(ctx context.Context, q Query) ([]Result, error) {
	if q.IMDbID == "" {
		return nil, nil
	}
	path := "/stream/movie/" + q.IMDbID + ".json"
	if q.Kind == "tv" {
		path = fmt.Sprintf("/stream/series/%s:%d:%d.json", q.IMDbID, max(q.Season, 1), max(q.Episode, 1))
	}
	body, err := getBody(ctx, join(strings.TrimSuffix(p.BaseURL, "/manifest.json"), path))
	if err != nil {
		return nil, err
	}
	var resp struct {
		Streams []struct {
			Name     string `json:"name"`
			Title    string `json:"title"`
			InfoHash string `json:"infoHash"`
			FileIdx  *int   `json:"fileIdx"`
		} `json:"streams"`
	}
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, err
	}
	var out []Result
	for _, s := range resp.Streams {
		if s.InfoHash == "" {
			continue
		}
		title := strings.SplitN(s.Title, "\n", 2)[0]
		r := Result{Provider: p.Name(), Title: title, Quality: QualityOf(s.Name + " " + s.Title),
			InfoHash: s.InfoHash, Magnet: MagnetFromHash(s.InfoHash, title), FileIndex: -1}
		if s.FileIdx != nil {
			r.FileIndex = *s.FileIdx
		}
		if m := reSeeders.FindStringSubmatch(s.Title); m != nil {
			r.Seeders, _ = strconv.Atoi(m[1])
		}
		out = append(out, r)
	}
	return out, nil
}

func abs(n int) int {
	if n < 0 {
		return -n
	}
	return n
}
