package subtitles

import (
	"bufio"
	"bytes"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// Cue is one subtitle on screen: Text from StartMS to EndMS. Short JSON keys keep a
// feature film's 1,500 cues small on their way over the bridge.
type Cue struct {
	StartMS int64  `json:"s"`
	EndMS   int64  `json:"e"`
	Text    string `json:"t"`
}

// The player draws external subtitles itself from these cues (ROADMAP Decision 17), so
// loading one mid-playback and moving its sync are instant, under either engine.

// LoadCues reads a subtitle file (.srt, .vtt, .ass, .ssa) of any common encoding.
func LoadCues(path string) ([]Cue, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	switch strings.ToLower(filepath.Ext(path)) {
	case ".srt", ".vtt":
		return ParseText(ToUTF8(b)), nil
	case ".ass", ".ssa":
		return ParseASS(ToUTF8(b)), nil
	}
	return nil, ErrUnsupportedFormat
}

// ParseText parses SRT and WebVTT: blocks of a timing line and the text under it, in any
// order, with numbers, VTT headers, NOTE and STYLE blocks ignored. Tags are dropped.
func ParseText(b []byte) []Cue {
	b = bytes.ReplaceAll(b, []byte("\r\n"), []byte("\n"))
	var cues []Cue
	var cur *Cue
	var lines []string
	flush := func() {
		if cur != nil {
			if t := cleanText(strings.Join(lines, "\n")); t != "" {
				cur.Text = t
				cues = append(cues, *cur)
			}
		}
		cur, lines = nil, nil
	}
	sc := bufio.NewScanner(bytes.NewReader(b))
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	for sc.Scan() {
		line := strings.TrimRight(sc.Text(), " \t")
		if m := reCueTime.FindStringSubmatch(line); m != nil {
			flush()
			cur = &Cue{StartMS: stampMS(m[1]), EndMS: stampMS(m[3])}
			continue
		}
		if line == "" {
			flush()
			continue
		}
		if cur != nil {
			lines = append(lines, line)
		}
	}
	flush()
	return sorted(cues)
}

// ParseASS reads the Dialogue lines of an .ass/.ssa script, using its Format line to find
// the Start, End and Text fields. Override blocks ({\an8}, {\i1}) are dropped, \N breaks.
func ParseASS(b []byte) []Cue {
	start, end, text := 1, 2, 9 // the standard [Events] Format
	var cues []Cue
	inEvents := false
	sc := bufio.NewScanner(bytes.NewReader(b))
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if strings.HasPrefix(line, "[") {
			inEvents = strings.EqualFold(line, "[Events]")
			continue
		}
		if !inEvents {
			continue
		}
		key, rest, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		switch strings.TrimSpace(key) {
		case "Format":
			for i, f := range strings.Split(rest, ",") {
				switch strings.ToLower(strings.TrimSpace(f)) {
				case "start":
					start = i
				case "end":
					end = i
				case "text":
					text = i
				}
			}
		case "Dialogue":
			fields := strings.SplitN(rest, ",", text+1)
			if len(fields) <= text || len(fields) <= start || len(fields) <= end {
				continue
			}
			t := strings.NewReplacer(`\N`, "\n", `\n`, "\n", `\h`, " ").Replace(reASSTag.ReplaceAllString(fields[text], ""))
			if t = cleanText(t); t != "" {
				cues = append(cues, Cue{StartMS: assMS(fields[start]), EndMS: assMS(fields[end]), Text: t})
			}
		}
	}
	return sorted(cues)
}

var (
	reASSTag  = regexp.MustCompile(`\{[^}]*\}`)
	reHTMLTag = regexp.MustCompile(`</?[a-zA-Z][^>]*>`)
)

// cleanText drops markup (<i>, <font …>, ASS overrides left in SRTs) and blank lines.
func cleanText(s string) string {
	s = reHTMLTag.ReplaceAllString(reASSTag.ReplaceAllString(s, ""), "")
	s = strings.NewReplacer("&amp;", "&", "&lt;", "<", "&gt;", ">", "&nbsp;", " ").Replace(s)
	var out []string
	for l := range strings.SplitSeq(s, "\n") {
		if l = strings.TrimSpace(l); l != "" {
			out = append(out, l)
		}
	}
	return strings.Join(out, "\n")
}

// stampMS reads "01:02:03,456", "02:03.456" (VTT without hours).
func stampMS(s string) int64 {
	parts := strings.FieldsFunc(s, func(r rune) bool { return r == ':' || r == ',' || r == '.' })
	var h, m, sec, ms int64
	switch len(parts) {
	case 4:
		h, m, sec, ms = num(parts[0]), num(parts[1]), num(parts[2]), num(parts[3])
	case 3:
		m, sec, ms = num(parts[0]), num(parts[1]), num(parts[2])
	}
	return ((h*60+m)*60+sec)*1000 + ms
}

// assMS reads ASS time, "0:01:02.34" (centiseconds).
func assMS(s string) int64 {
	parts := strings.FieldsFunc(strings.TrimSpace(s), func(r rune) bool { return r == ':' || r == '.' })
	if len(parts) != 4 {
		return 0
	}
	cs := parts[3]
	for len(cs) < 2 {
		cs += "0"
	}
	return ((num(parts[0])*60+num(parts[1]))*60+num(parts[2]))*1000 + num(cs[:2])*10
}

func sorted(c []Cue) []Cue {
	sort.SliceStable(c, func(i, j int) bool { return c[i].StartMS < c[j].StartMS })
	if c == nil {
		return []Cue{}
	}
	return c
}
