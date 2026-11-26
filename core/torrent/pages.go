package torrent

import (
	"bytes"
	"context"
	"crypto/sha1"
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"

	"golang.org/x/net/html"
)

// SearchPage searches a torrent site's own search page: Template is its address with
// {query} where the words go ("https://<site>/search/{query}"). Sites like this have no API,
// so the page is read the way a person reads it:
//
//   - Magnet links on the page itself are results.
//   - Otherwise the results are the links that repeat with long titles, all of one shape
//     ("/t/abc123", "/torrent/42/name"), and each one's size, seeders and leechers are read
//     from the text up to the next result.
//
// A result's magnet is fetched from its own page only when it is picked ([ResolvePage]):
// one request per search, not one per result. These sources answer free-text searches
// only, never a TMDB title's, so whatever they hold stays out of the library's lists.
type SearchPage struct {
	Label    string
	Template string
}

// PagePrefix marks a Result.Magnet that is a result page still to be read for its magnet.
const PagePrefix = "page:"

func (p SearchPage) Name() string { return p.Label }

func (p SearchPage) Search(ctx context.Context, q Query) ([]Result, error) {
	words := strings.TrimSpace(q.Title)
	if words == "" {
		return nil, nil
	}
	if !strings.Contains(p.Template, "{query}") {
		return nil, errors.New("the address needs {query} where the search words go")
	}
	// In a path the words are path-escaped; after "?" they are query-escaped.
	esc := url.PathEscape(words)
	if i := strings.Index(p.Template, "?"); i >= 0 && i < strings.Index(p.Template, "{query}") {
		esc = url.QueryEscape(words)
	}
	u := strings.ReplaceAll(p.Template, "{query}", esc)
	base, err := url.Parse(u)
	if err != nil {
		return nil, err
	}
	body, err := getPage(ctx, u)
	if err != nil {
		return nil, err
	}
	return parseSearchPage(body, base, p.Label), nil
}

// ---- reading the page ----

// A link or a run of text, in document order.
type piece struct {
	href, label string // a link: where it goes and its longest label (title or text)
	text        string // text between links
}

func flatten(body []byte) []piece {
	var out []piece
	z := html.NewTokenizer(bytes.NewReader(body))
	var link *piece
	skip := 0 // inside <script>/<style>
	for {
		switch z.Next() {
		case html.ErrorToken:
			return out
		case html.StartTagToken:
			name, hasAttr := z.TagName()
			tag := string(name)
			if tag == "script" || tag == "style" {
				skip++
				continue
			}
			if tag != "a" {
				continue
			}
			p := piece{}
			for hasAttr {
				var k, v []byte
				k, v, hasAttr = z.TagAttr()
				switch string(k) {
				case "href":
					p.href = string(v)
				case "title":
					p.label = string(v)
				}
			}
			out = append(out, p)
			link = &out[len(out)-1]
		case html.EndTagToken:
			name, _ := z.TagName()
			switch string(name) {
			case "script", "style":
				if skip > 0 {
					skip--
				}
			case "a":
				link = nil
			}
		case html.TextToken:
			if skip > 0 {
				continue
			}
			t := strings.TrimSpace(string(z.Text()))
			if t == "" {
				continue
			}
			if link != nil && len(t) > len(link.label) {
				link.label = t
			}
			out = append(out, piece{text: t})
		}
	}
}

var (
	reBTIH    = regexp.MustCompile(`(?i)urn:btih:([a-z0-9]{32,40})`)
	reURLWord = regexp.MustCompile(`https?://\S+`)
	reTagWord = regexp.MustCompile(`(^|\s)#\S+`)
	reSize    = regexp.MustCompile(`(?i)(\d+(?:[.,]\d+)?)\s*(t|g|m|k)i?b\b`)
	reSeeds   = regexp.MustCompile(`(?i)seed(?:er)?s?\W{0,4}(\d+)`)
	reLeeches = regexp.MustCompile(`(?i)leech(?:er)?s?\W{0,4}(\d+)`)
)

func parseSearchPage(body []byte, base *url.URL, label string) []Result {
	pieces := flatten(body)

	// 1. Magnets on the page.
	var out []Result
	seen := map[string]bool{}
	for _, p := range pieces {
		if !strings.HasPrefix(p.href, "magnet:") {
			continue
		}
		m := reBTIH.FindStringSubmatch(p.href)
		if m == nil || seen[strings.ToLower(m[1])] {
			continue
		}
		seen[strings.ToLower(m[1])] = true
		title := p.label
		if mu, err := url.Parse(p.href); err == nil && mu.Query().Get("dn") != "" {
			title = mu.Query().Get("dn")
		}
		out = append(out, Result{Provider: label, Title: cleanTitle(title), InfoHash: m[1], Magnet: p.href, FileIndex: -1})
	}
	if len(out) > 0 {
		return out
	}

	// 2. Result pages: the most common same-site link shape whose links carry long titles.
	type target struct {
		url, label string
		at         int // index of its first link in pieces
	}
	groups := map[string][]*target{}
	byURL := map[string]*target{}
	for i, p := range pieces {
		if p.href == "" {
			continue
		}
		u, err := base.Parse(p.href)
		if err != nil || u.Host != base.Host || u.Path == base.Path {
			continue
		}
		u.RawQuery, u.Fragment = "", ""
		key := u.String()
		if t, ok := byURL[key]; ok {
			if len(p.label) > len(t.label) {
				t.label = p.label
			}
			continue
		}
		t := &target{url: key, label: p.label, at: i}
		byURL[key] = t
		g := shape(u.Path)
		groups[g] = append(groups[g], t)
	}
	var best []*target
	bestScore := 0
	for _, ts := range groups {
		score := 0
		for _, t := range ts {
			if len(cleanTitle(t.label)) >= 20 {
				score++
			}
		}
		if score > bestScore {
			best, bestScore = ts, score
		}
	}
	if bestScore < 2 {
		return nil
	}
	for i, t := range best {
		end := len(pieces)
		if i+1 < len(best) {
			end = best[i+1].at
		}
		var text strings.Builder
		for _, p := range pieces[t.at+1 : end] {
			if p.text != "" {
				text.WriteString(p.text)
				text.WriteByte(' ')
			}
		}
		info := text.String()
		r := Result{Provider: label, Title: cleanTitle(t.label), Magnet: PagePrefix + t.url, FileIndex: -1}
		// No info hash until the page is read: an id from the address keeps results apart.
		sum := sha1.Sum([]byte(t.url))
		r.InfoHash = "page-" + hex.EncodeToString(sum[:])
		r.Size = sizeOf(info)
		r.Seeders = firstInt(reSeeds, info)
		r.Peers = r.Seeders + firstInt(reLeeches, info)
		out = append(out, r)
	}
	return out
}

// shape is a path's first segment and its depth: "/t/abc" and "/t/xyz" share "t/2".
func shape(path string) string {
	segs := strings.Split(strings.Trim(path, "/"), "/")
	return fmt.Sprintf("%s/%d", segs[0], len(segs))
}

// cleanTitle drops the links and #tags uploaders pad titles with, and keeps it to a line.
func cleanTitle(s string) string {
	s = strings.NewReplacer(`\n`, " ", `\r`, " ", `\t`, " ").Replace(s) // escapes some sites print as text
	s = reTagWord.ReplaceAllString(reURLWord.ReplaceAllString(s, " "), " ")
	s = strings.Join(strings.Fields(s), " ")
	if r := []rune(s); len(r) > 140 {
		s = strings.TrimSpace(string(r[:140])) + "…"
	}
	return s
}

func sizeOf(s string) int64 {
	m := reSize.FindStringSubmatch(s)
	if m == nil {
		return 0
	}
	f, err := strconv.ParseFloat(strings.ReplaceAll(m[1], ",", "."), 64)
	if err != nil {
		return 0
	}
	mult := map[string]float64{"k": 1 << 10, "m": 1 << 20, "g": 1 << 30, "t": 1 << 40}[strings.ToLower(m[2])]
	return int64(f * mult)
}

func firstInt(re *regexp.Regexp, s string) int {
	if m := re.FindStringSubmatch(s); m != nil {
		n, _ := strconv.Atoi(m[1])
		return n
	}
	return 0
}

// ---- picking a result ----

// ResolvePage reads a result page for what to stream: its magnet link, else a .torrent
// file (downloaded into dir). Called with the address after [PagePrefix].
func ResolvePage(ctx context.Context, pageURL, dir string) (string, error) {
	base, err := url.Parse(pageURL)
	if err != nil {
		return "", err
	}
	body, err := getPage(ctx, pageURL)
	if err != nil {
		return "", err
	}
	var torrentLink string
	for _, p := range flatten(body) {
		if strings.HasPrefix(p.href, "magnet:") && reBTIH.MatchString(p.href) {
			return p.href, nil
		}
		if torrentLink == "" && p.href != "" {
			if u, err := base.Parse(p.href); err == nil && strings.HasSuffix(strings.ToLower(u.Path), ".torrent") {
				torrentLink = u.String()
			}
		}
	}
	// Some pages only print the magnet as text.
	if m := regexp.MustCompile(`magnet:\?xt=urn:btih:[A-Za-z0-9]{32,40}[^"'<\s]*`).Find(body); m != nil {
		return html.UnescapeString(string(m)), nil
	}
	if torrentLink == "" {
		return "", errors.New("no magnet link or .torrent file on the result's page")
	}
	data, err := getBody(ctx, torrentLink)
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	f := filepath.Join(dir, "picked.torrent")
	return f, os.WriteFile(f, data, 0o644)
}

// getPage fetches a web page the way a browser would: sites behind simple bot checks
// refuse the API client's user agent.
func getPage(ctx context.Context, u string) ([]byte, error) {
	return getBodyAs(ctx, u, "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")
}
