package torrent

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

const hashA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
const hashB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

func serve(t *testing.T, routes map[string]string) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		for prefix, body := range routes {
			if strings.HasPrefix(r.URL.Path+"?"+r.URL.RawQuery, prefix) {
				w.Write([]byte(body))
				return
			}
		}
		http.NotFound(w, r)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestYTS(t *testing.T) {
	srv := serve(t, map[string]string{"/api/v2/list_movies.json?": `{"data":{"movies":[
		{"title_long":"Sintel (2010)","year":2010,"imdb_code":"tt1727587","torrents":[
			{"hash":"` + strings.ToUpper(hashA) + `","quality":"1080p","type":"web","video_codec":"x264","seeds":40,"peers":5,"size_bytes":1000},
			{"hash":"` + hashB + `","quality":"2160p","type":"bluray","video_codec":"x265","seeds":10,"peers":1,"size_bytes":5000}]},
		{"title_long":"Other (1990)","year":1990,"imdb_code":"tt0000001","torrents":[{"hash":"cccccccccccccccccccccccccccccccccccccccc","quality":"720p","seeds":99}]}]}}`})
	res, err := YTS{BaseURL: srv.URL}.Search(context.Background(), Query{Kind: "movie", IMDbID: "tt1727587"})
	if err != nil {
		t.Fatal(err)
	}
	if len(res) != 2 || res[0].Quality != "1080p" || res[1].Quality != "2160p" || !strings.Contains(res[0].Magnet, "urn%3Abtih%3A"+hashA) {
		t.Fatalf("res = %+v", res)
	}
	if r, _ := (YTS{BaseURL: srv.URL}).Search(context.Background(), Query{Kind: "tv", IMDbID: "x"}); r != nil {
		t.Fatal("YTS should ignore TV")
	}
}

func TestEZTVFiltersEpisode(t *testing.T) {
	srv := serve(t, map[string]string{"/api/get-torrents?": `{"torrents_count":3,"torrents":[
		{"title":"Show S01E01 1080p","hash":"` + hashA + `","magnet_url":"magnet:?xt=urn:btih:` + hashA + `","seeds":10,"peers":2,"size_bytes":"123","season":"1","episode":"1"},
		{"title":"Show S01E02 720p","hash":"` + hashB + `","seeds":50,"season":"1","episode":"2"},
		{"title":"Show S02E01","hash":"cccccccccccccccccccccccccccccccccccccccc","seeds":5,"season":"2","episode":"1"}]}`})
	res, err := EZTV{BaseURL: srv.URL}.Search(context.Background(), Query{Kind: "tv", IMDbID: "tt0944947", Season: 1, Episode: 1})
	if err != nil {
		t.Fatal(err)
	}
	if len(res) != 1 || res[0].InfoHash != hashA || res[0].Size != 123 {
		t.Fatalf("res = %+v", res)
	}
}

func TestTorznab(t *testing.T) {
	srv := serve(t, map[string]string{"/api?": `<?xml version="1.0"?>
<rss xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel>
 <item><title>Sintel.2010.1080p.BluRay</title><link>http://jackett/dl/1</link><size>700</size>
  <torznab:attr name="seeders" value="12"/><torznab:attr name="peers" value="3"/>
  <torznab:attr name="infohash" value="` + hashA + `"/></item>
 <item><title>Sintel.2010.720p</title><enclosure url="magnet:?xt=urn:btih:` + hashB + `&amp;dn=x"/>
  <torznab:attr name="seeders" value="4"/></item>
 <item><title>No magnet or hash</title><link>http://jackett/dl/3</link></item>
</channel></rss>`})
	res, err := Torznab{URL: srv.URL + "/api?apikey=k"}.Search(context.Background(), Query{Kind: "movie", Title: "Sintel", Year: 2010})
	if err != nil {
		t.Fatal(err)
	}
	if len(res) != 2 || res[0].Seeders != 12 || !strings.HasPrefix(res[0].Magnet, "magnet:") || res[1].InfoHash != hashB {
		t.Fatalf("res = %+v", res)
	}
}

func TestStremio(t *testing.T) {
	srv := serve(t, map[string]string{"/stream/series/tt0944947:2:3.json": `{"streams":[
		{"name":"Addon\n1080p","title":"Show.S02E03.1080p.WEB\n👤 77 💾 1.2 GB","infoHash":"` + hashA + `","fileIdx":4},
		{"name":"Addon","title":"http only","url":"https://x"}]}`})
	res, err := Stremio{BaseURL: srv.URL + "/manifest.json"}.Search(context.Background(), Query{Kind: "tv", IMDbID: "tt0944947", Season: 2, Episode: 3})
	if err != nil {
		t.Fatal(err)
	}
	if len(res) != 1 || res[0].Seeders != 77 || res[0].FileIndex != 4 || res[0].Quality != "1080p" || res[0].Title != "Show.S02E03.1080p.WEB" {
		t.Fatalf("res = %+v", res)
	}
}

type fakeProvider struct {
	name string
	res  []Result
	err  error
}

func (f fakeProvider) Name() string { return f.name }
func (f fakeProvider) Search(context.Context, Query) ([]Result, error) {
	return f.res, f.err
}

func TestSearchMergesAndSorts(t *testing.T) {
	res, err := Search(context.Background(), []Provider{
		fakeProvider{name: "a", res: []Result{
			{Title: "X 720p", InfoHash: strings.ToUpper(hashA), Seeders: 5},
			{Title: "X 1080p", InfoHash: hashB, Seeders: 30},
		}},
		fakeProvider{name: "b", res: []Result{{Title: "X 720p", InfoHash: hashA, Seeders: 9}}},
		fakeProvider{name: "broken", err: errors.New("down")},
	}, Query{Kind: "movie"})
	if err == nil || !strings.Contains(err.Error(), "broken: down") {
		t.Fatalf("provider error should be reported, got %v", err)
	}
	if len(res) != 2 || res[0].InfoHash != hashB || res[1].Seeders != 9 || res[0].Quality != "1080p" {
		t.Fatalf("res = %+v", res)
	}
}

func TestQualityOf(t *testing.T) {
	for in, want := range map[string]string{
		"Movie.2019.2160p.UHD": "2160p", "Movie 4K HDR": "2160p", "x.1080p.x": "1080p", "720P": "720p", "dvdrip": "",
	} {
		if got := QualityOf(in); got != want {
			t.Errorf("QualityOf(%q) = %q want %q", in, got, want)
		}
	}
}

type countingProvider struct {
	name string
	res  []Result
	err  error
	hits *int
}

func (f countingProvider) Name() string { return f.name }
func (f countingProvider) Search(context.Context, Query) ([]Result, error) {
	*f.hits++
	return f.res, f.err
}

func TestMirrorsFallBackInOrder(t *testing.T) {
	var a, b, c int
	m := Mirrors{
		countingProvider{name: "eztv", err: errors.New("down"), hits: &a},
		countingProvider{name: "eztv", res: []Result{{InfoHash: "x"}}, hits: &b},
		countingProvider{name: "eztv", res: []Result{{InfoHash: "y"}}, hits: &c},
	}
	res, err := m.Search(context.Background(), Query{})
	if err != nil || len(res) != 1 || res[0].InfoHash != "x" || a != 1 || b != 1 || c != 0 {
		t.Fatalf("res=%v err=%v hits=%d,%d,%d", res, err, a, b, c)
	}
	var d int
	all := Mirrors{countingProvider{name: "eztv", err: errors.New("down"), hits: &d}, countingProvider{name: "eztv", err: errors.New("gone"), hits: &d}}
	if _, err := all.Search(context.Background(), Query{}); err == nil || !strings.Contains(err.Error(), "all 2 mirrors failed") || !strings.Contains(err.Error(), "gone") {
		t.Fatalf("all failing: %v", err)
	}
}
