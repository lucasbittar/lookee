package subtitles

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestToUTF8(t *testing.T) {
	latin1 := []byte("N\xe3o, voc\xea est\xe1 certo.") // Windows-1252 "Não, você está certo."
	if got := string(ToUTF8(latin1)); got != "Não, você está certo." {
		t.Fatalf("got %q", got)
	}
	bom := []byte("\xef\xbb\xbfOlá")
	if got := string(ToUTF8(bom)); got != "Olá" {
		t.Fatalf("BOM not stripped: %q", got)
	}
	if got := string(ToUTF8([]byte("já é UTF-8"))); got != "já é UTF-8" {
		t.Fatalf("utf8 altered: %q", got)
	}
}

func TestShift(t *testing.T) {
	srt := "1\n00:00:01,500 --> 00:00:03,000\nOi\n\n2\n01:59:59,900 --> 02:00:00,100\nTchau\n"
	got := string(Shift([]byte(srt), 600))
	want := "1\n00:00:02,100 --> 00:00:03,600\nOi\n\n2\n02:00:00,500 --> 02:00:00,700\nTchau\n"
	if got != want {
		t.Fatalf("got\n%s\nwant\n%s", got, want)
	}
	if got := string(Shift([]byte("00:00:01,000 --> 00:00:02,000"), -1500)); got != "00:00:00,000 --> 00:00:00,500" {
		t.Fatalf("clamp: %q", got)
	}
	vtt := "WEBVTT\n\n00:05.000 --> 00:06.250\nhi\n"
	if got := string(Shift([]byte(vtt), 100)); !strings.Contains(got, "00:00:05.100 --> 00:00:06.350") {
		t.Fatalf("vtt: %q", got)
	}
}

func TestPrepareFile(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "Filme.pt-BR.srt")
	os.WriteFile(src, []byte("1\n00:00:01,000 --> 00:00:02,000\nAten\xe7\xe3o\n"), 0o644)
	out, err := PrepareFile(src, filepath.Join(dir, "cache"), -200)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(out)
	if string(b) != "1\n00:00:00,800 --> 00:00:01,800\nAtenção\n" || !strings.Contains(out, "shift-200") {
		t.Fatalf("%s: %q", out, b)
	}
	if orig, _ := os.ReadFile(src); bytes.Contains(orig, []byte("00:00:00,800")) {
		t.Fatal("original modified")
	}
	ass := filepath.Join(dir, "x.ass")
	os.WriteFile(ass, []byte("[Script Info]"), 0o644)
	if _, err := PrepareFile(ass, dir, 100); !errors.Is(err, ErrUnsupportedFormat) {
		t.Fatalf("ass shift: %v", err)
	}
}

func TestFindSidecars(t *testing.T) {
	dir := t.TempDir()
	for _, f := range []string{"Movie.mkv", "Other.mkv", "Movie.srt", "Movie.pt-BR.srt", "Movie_eng.srt", "Other.es.srt", ".Movie.srt", "Subs/2_Portuguese.srt", "Subs/notes.txt"} {
		p := filepath.Join(dir, f)
		os.MkdirAll(filepath.Dir(p), 0o755)
		os.WriteFile(p, []byte("x"), 0o644)
	}
	got, err := FindSidecars(filepath.Join(dir, "Movie.mkv"))
	if err != nil {
		t.Fatal(err)
	}
	langs := map[string]string{}
	for _, s := range got {
		langs[s.Name] = s.Language
	}
	want := map[string]string{"Movie.srt": "", "Movie.pt-BR.srt": "pt-BR", "Movie_eng.srt": "en", "2_Portuguese.srt": "pt"}
	if len(langs) != len(want) {
		t.Fatalf("got %v", langs)
	}
	for k, v := range want {
		if l, ok := langs[k]; !ok || l != v {
			t.Errorf("%s: got %q (present=%v), want %q", k, l, ok, v)
		}
	}

	// A folder with a single video accepts any subtitle in it.
	solo := t.TempDir()
	os.WriteFile(filepath.Join(solo, "Annie.Hall.1977.1080p.BluRay.x264.YIFY.mp4"), []byte("x"), 0o644)
	os.WriteFile(filepath.Join(solo, "English.srt"), []byte("x"), 0o644)
	got, _ = FindSidecars(filepath.Join(solo, "Annie.Hall.1977.1080p.BluRay.x264.YIFY.mp4"))
	if len(got) != 1 || got[0].Language != "en" {
		t.Fatalf("solo folder: %+v", got)
	}
}

func TestMovieHash(t *testing.T) {
	// 128 KiB + 16 bytes of a known pattern: word i of each chunk has value i.
	size := int64(128<<10 + 16)
	buf := make([]byte, size)
	for i := 0; i+8 <= len(buf); i += 8 {
		binary.LittleEndian.PutUint64(buf[i:], uint64(i/8%8192))
	}
	got, err := MovieHash(bytes.NewReader(buf), size)
	if err != nil {
		t.Fatal(err)
	}
	// Independent computation.
	want := uint64(size)
	for _, off := range []int64{0, size - 65536} {
		for i := int64(0); i < 65536; i += 8 {
			want += binary.LittleEndian.Uint64(buf[off+i:])
		}
	}
	if got != fmt.Sprintf("%016x", want) {
		t.Fatalf("hash %s, want %016x", got, want)
	}
	if _, err := MovieHash(bytes.NewReader(buf[:100]), 100); err == nil {
		t.Fatal("tiny file should fail")
	}
}

func TestOpenSubtitlesSearchAndDownload(t *testing.T) {
	var searchQuery, downloadAuth string
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// The API requires the key; the CDN download link does not get it.
		if !strings.HasPrefix(r.URL.Path, "/file/") && (r.Header.Get("Api-Key") != "k" || r.Header.Get("User-Agent") == "") {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		switch r.URL.Path {
		case "/subtitles":
			searchQuery = r.URL.RawQuery
			w.Write([]byte(`{"data":[
				{"attributes":{"language":"en","download_count":900,"release":"A","files":[{"file_id":1,"file_name":"a.srt"}]}},
				{"attributes":{"language":"pt-BR","download_count":10,"release":"B","files":[{"file_id":2,"file_name":"b.srt"}]}},
				{"attributes":{"language":"en","download_count":5,"moviehash_match":true,"release":"C","files":[{"file_id":3,"file_name":"c.srt"}]}}]}`))
		case "/download":
			downloadAuth = r.Header.Get("Authorization")
			var body map[string]int64
			json.NewDecoder(r.Body).Decode(&body)
			if body["file_id"] == 99 {
				w.WriteHeader(http.StatusNotAcceptable)
				return
			}
			w.Write([]byte(`{"link":"` + srv.URL + `/file/2","file_name":"Filme (2010).srt"}`))
		case "/file/2":
			w.Write([]byte("1\n00:00:01,000 --> 00:00:02,000\nCora\xe7\xe3o\n"))
		}
	}))
	defer srv.Close()

	c := NewClient("k")
	c.BaseURL = srv.URL
	res, err := c.Search(context.Background(), Query{TMDBID: 603, MovieHash: "8e245d9679d31e12", Languages: []string{"pt-BR", "en"}})
	if err != nil {
		t.Fatal(err)
	}
	if searchQuery != "foreign_parts_only=exclude&languages=en%2Cpt-br&moviehash=8e245d9679d31e12&tmdb_id=603" {
		t.Fatalf("query = %s", searchQuery)
	}
	if len(res) != 3 || res[0].FileID != 3 || res[1].FileID != 2 || res[2].FileID != 1 {
		t.Fatalf("order (hash match, then language preference) wrong: %+v", res)
	}

	c.Token = "tok"
	path, err := c.Download(context.Background(), 2, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(path)
	if !strings.Contains(string(b), "Coração") || filepath.Base(path) != "2_Filme_2010_.srt" || downloadAuth != "Bearer tok" {
		t.Fatalf("path %s auth %q content %q", path, downloadAuth, b)
	}
	if _, err := c.Download(context.Background(), 99, t.TempDir()); !errors.Is(err, ErrQuota) {
		t.Fatalf("quota: %v", err)
	}
	if _, err := NewClient("").Search(context.Background(), Query{}); !errors.Is(err, ErrNoAPIKey) {
		t.Fatalf("no key: %v", err)
	}
}
