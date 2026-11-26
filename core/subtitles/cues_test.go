package subtitles

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestParseSRT(t *testing.T) {
	in := "\xef\xbb\xbf1\r\n00:00:01,000 --> 00:00:02,500\r\n<i>Hello</i>\r\n{\\an8}there\r\n\r\n2\r\n00:01:00,000 --> 00:01:01,000\r\nSecond &amp; last\r\n"
	got := ParseText(ToUTF8([]byte(in)))
	want := []Cue{{1000, 2500, "Hello\nthere"}, {60000, 61000, "Second & last"}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v, want %+v", got, want)
	}
}

func TestParseVTT(t *testing.T) {
	in := "WEBVTT\n\nNOTE a comment\n\nintro\n00:05.000 --> 00:06.250 align:center\n<c.yellow>Hi</c>\n\n01:00:00.000 --> 01:00:01.000\nLate\n"
	got := ParseText([]byte(in))
	want := []Cue{{5000, 6250, "Hi"}, {3600000, 3601000, "Late"}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v, want %+v", got, want)
	}
}

func TestParseASS(t *testing.T) {
	in := "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
		"Dialogue: 0,0:00:03.50,0:00:05.00,Default,,0,0,0,,{\\i1}One, two{\\i0}\\NThree\n" +
		"Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,First\n" +
		"Comment: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,ignored\n"
	got := ParseASS([]byte(in))
	want := []Cue{{1000, 2000, "First"}, {3500, 5000, "One, two\nThree"}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v, want %+v", got, want)
	}
}

func TestLoadCuesLatin1(t *testing.T) {
	p := filepath.Join(t.TempDir(), "a.srt")
	if err := os.WriteFile(p, []byte("1\n00:00:01,000 --> 00:00:02,000\nN\xe3o\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	got, err := LoadCues(p)
	if err != nil || len(got) != 1 || got[0].Text != "Não" {
		t.Fatalf("got %+v, %v", got, err)
	}
	if _, err := LoadCues(filepath.Join(t.TempDir(), "a.sub")); err == nil {
		t.Fatal("want an error for .sub")
	}
}
