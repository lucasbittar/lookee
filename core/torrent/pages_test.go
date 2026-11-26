package torrent

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

const pageHash = "07f95d5986adaf735a42d74bb1a1c07707d76fd9"

// A results page as torrent sites draw them: uploader links repeat too (noise), result
// links carry long titles padded with links and #tags, and the size and seeders follow.
const resultsPage = `<html><head><script>var x = "<a href='/t/fake'>no</a>";</script></head><body>
<a href="/">Home</a> <a href="/s/top">Top</a>
<div class="row"><a href="/u/alice" title="alice torrents">alice</a>
 <a href="/t/AbC123?sk=q&sp=1" title="Big Buck Bunny 2008 Remaster https://example.org/x #animation #hd">Big Buck Bunny</a>
 <div>[added]: 2 hours ago [size]: 273.07 Mb [seeders]: 12 [leechers]: 4</div></div>
<div class="row"><a href="/u/bob" title="bob torrents">bob</a>
 <a href="/t/XyZ789?sk=q&sp=1" title="Elephants Dream Directors Cut 1080p\n\nmore">Elephants Dream</a>
 <div>[size]: 1.5 GB [seeders]: 30 [leechers]: 2</div></div>
<div class="row"><a href="/u/alice" title="alice torrents">alice</a>
 <a href="/t/AbC123?sk=q&sp=1">Big Buck Bunny</a></div>
</body></html>`

func fakeSite(t *testing.T) *httptest.Server {
	mux := http.NewServeMux()
	mux.HandleFunc("/s/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/s/big buck" { // the words arrive path-escaped
			http.Error(w, "bad query "+r.URL.Path, 400)
			return
		}
		w.Write([]byte(resultsPage))
	})
	mux.HandleFunc("/t/AbC123", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`<a href="magnet:?xt=urn:btih:` + pageHash + `&amp;dn=Big+Buck+Bunny">Magnet</a>`))
	})
	mux.HandleFunc("/t/file", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`<a href="/dl/thing.torrent">Download</a>`))
	})
	mux.HandleFunc("/dl/thing.torrent", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("d4:infoe")) })
	mux.HandleFunc("/q", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("q") != "big buck" {
			http.Error(w, "bad query", 400)
			return
		}
		w.Write([]byte(`<a href="magnet:?xt=urn:btih:` + strings.ToUpper(pageHash) + `&dn=Big.Buck.Bunny.mkv">m</a>
			<a href="magnet:?xt=urn:btih:` + pageHash + `">again</a>`))
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

func TestSearchPageResultLinks(t *testing.T) {
	srv := fakeSite(t)
	got, err := SearchPage{Label: "page1", Template: srv.URL + "/s/{query}"}.Search(context.Background(), Query{Title: "big buck"})
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 {
		t.Fatalf("got %d results: %+v", len(got), got)
	}
	a, b := got[0], got[1]
	if a.Title != "Big Buck Bunny 2008 Remaster" || a.Magnet != PagePrefix+srv.URL+"/t/AbC123" || a.Seeders != 12 || a.Peers != 16 || a.Size != sizeOf("273.07 Mb") {
		t.Errorf("first = %+v", a)
	}
	if b.Title != "Elephants Dream Directors Cut 1080p more" || b.Seeders != 30 || b.Size != 1610612736 {
		t.Errorf("second = %+v", b)
	}
	if a.Size < 286_000_000 || a.Size > 287_000_000 {
		t.Errorf("size %d", a.Size)
	}
	if a.InfoHash == "" || a.InfoHash == b.InfoHash || a.InfoHash != strings.ToLower(a.InfoHash) {
		t.Errorf("ids %q %q", a.InfoHash, b.InfoHash)
	}

	m, err := ResolvePage(context.Background(), srv.URL+"/t/AbC123", t.TempDir())
	if err != nil || !strings.Contains(m, pageHash) || strings.Contains(m, "&amp;") {
		t.Fatalf("resolve = %q, %v", m, err)
	}
}

func TestSearchPageMagnetsAndQueryString(t *testing.T) {
	srv := fakeSite(t)
	got, err := SearchPage{Label: "page1", Template: srv.URL + "/q?q={query}"}.Search(context.Background(), Query{Title: "big buck"})
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0].Title != "Big.Buck.Bunny.mkv" || !strings.EqualFold(got[0].InfoHash, pageHash) {
		t.Fatalf("got %+v", got)
	}
}

func TestResolvePageTorrentFile(t *testing.T) {
	srv := fakeSite(t)
	dir := t.TempDir()
	f, err := ResolvePage(context.Background(), srv.URL+"/t/file", dir)
	if err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(f); string(b) != "d4:infoe" {
		t.Fatalf("file %q holds %q", f, b)
	}
}

func TestSearchPageNeedsPlaceholder(t *testing.T) {
	if _, err := (SearchPage{Template: "https://example.org/search"}).Search(context.Background(), Query{Title: "x"}); err == nil {
		t.Fatal("want an error without {query}")
	}
}
