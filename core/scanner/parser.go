package scanner

import (
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"
)

// Parsed is what can be recovered from a release filename.
type Parsed struct {
	Title      string `json:"title"`
	Year       int    `json:"year,omitempty"`
	Season     int    `json:"season,omitempty"`
	Episode    int    `json:"episode,omitempty"`
	EpisodeEnd int    `json:"episodeEnd,omitempty"` // last episode of a multi-episode file, e.g. S01E01E02
}

// IsEpisode reports whether a season/episode marker was found.
func (p Parsed) IsEpisode() bool { return p.Episode > 0 }

// VideoExtensions are the file types the scanner indexes and the browser lists.
var VideoExtensions = map[string]bool{
	".mkv": true, ".mp4": true, ".avi": true, ".mov": true,
	".webm": true, ".m4v": true, ".ts": true, ".m2ts": true, ".mts": true,
}

// IsVideo reports whether name has a supported video extension.
func IsVideo(name string) bool {
	return VideoExtensions[strings.ToLower(filepath.Ext(name))]
}

var (
	// Leading "[Group]" / "(Group)" tags and "www.site.com - " prefixes.
	reLeadingTag  = regexp.MustCompile(`^\s*(?:\[[^\]]*\]|\([^)]*\)|【[^】]*】)\s*`)
	reSitePrefix  = regexp.MustCompile(`(?i)^\s*(?:www\.)?[a-z0-9-]+\.(?:com|org|net|to|mx|me|io|cc|lol)\s*[-–]\s*`)
	reSeparators  = regexp.MustCompile(`[._]+`)
	reSpaces      = regexp.MustCompile(`\s+`)
	reBracketJunk = regexp.MustCompile(`\[[^\]]*\]|\{[^}]*\}`)

	// Episode markers, most specific first. Group 1 = season, 2 = episode, 3 = optional end episode.
	reEpisodePatterns = []*regexp.Regexp{
		regexp.MustCompile(`(?i)\bS(\d{1,2})\s?E(\d{1,4})(?:(?:\s?-?\s?E|-)(\d{1,4}))?\b`),
		regexp.MustCompile(`(?i)\bSeason\s?(\d{1,2})\s?Episode\s?(\d{1,3})\b`),
		regexp.MustCompile(`(?i)\b(\d{1,2})x(\d{2,3})\b`),
	}
	// A bare "Season 2" (season packs, or folder names).
	reSeasonOnly = regexp.MustCompile(`(?i)^(?:season|series|temporada|volume|vol|s)\s?(\d{1,2})$`)

	reYear = regexp.MustCompile(`\b(19\d{2}|20\d{2})\b`)

	// Tokens that mark the end of the title in a release name.
	reJunk = regexp.MustCompile(`(?i)\b(?:` + strings.Join([]string{
		`\d{3,4}p`, `4k`, `uhd`, `2160p`, `hdr\d*`, `dv`, `dolby\s?vision`, `sdr`,
		`blu-?ray`, `bdrip`, `brrip`, `bdremux`, `remux`, `web-?dl`, `web-?rip`, `web`, `hdtv`, `dvd-?rip`, `dvdscr`, `hdrip`, `hdcam`, `cam`, `ts-?rip`,
		`amzn`, `nf`, `dsnp`, `hmax`, `atvp`, `hulu`,
		`x\.?26[45]`, `h\.?26[45]`, `hevc`, `avc`, `xvid`, `divx`, `10-?bit`, `8-?bit`,
		`dts(?:-?hd)?`, `aac\d?(?:\.\d)?`, `ac3`, `e-?ac3`, `ddp?\d?(?:\.\d)?`, `atmos`, `truehd`, `flac`, `mp3`, `opus`,
		`proper`, `repack`, `internal`, `limited`, `extended`, `unrated`, `uncut`, `directors\s?cut`, `theatrical`, `imax`,
		`multi`, `dual(?:\s?audio)?`, `dubbed`, `subbed`, `legendado`, `dublado`,
		`complete`, `sample`,
	}, "|") + `)\b`)

	reTrailingNoise = regexp.MustCompile(`[\s\-–:,(\[{]+$`)
	reLeadingNoise  = regexp.MustCompile(`^[\s\-–:,)\]}]+`)
)

// maxYear is the latest plausible release year. Anything later ("Blade Runner 2049") is part of the title.
func maxYear() int { return time.Now().Year() + 1 }

// Parse extracts title, year, season and episode from a video filename or folder name.
// It never fails; when nothing can be recognised the cleaned name is returned as the title.
func Parse(name string) Parsed {
	base := name
	if ext := strings.ToLower(filepath.Ext(base)); IsVideo(base) || ext == ".srt" || ext == ".iso" {
		base = strings.TrimSuffix(base, filepath.Ext(base))
	}

	// Strip leading group tags and site prefixes, repeatedly ("[YTS] [1080p] Title").
	for {
		next := reSitePrefix.ReplaceAllString(reLeadingTag.ReplaceAllString(base, ""), "")
		if next == base {
			break
		}
		base = next
	}

	s := reSeparators.ReplaceAllString(base, " ")
	s = reSpaces.ReplaceAllString(s, " ")
	s = strings.TrimSpace(s)

	var p Parsed
	titlePart := s

	// Episode markers: the title is whatever precedes them.
	for _, re := range reEpisodePatterns {
		m := re.FindStringSubmatchIndex(s)
		if m == nil {
			continue
		}
		p.Season = atoi(s[m[2]:m[3]])
		p.Episode = atoi(s[m[4]:m[5]])
		if len(m) > 6 && m[6] >= 0 {
			if end := atoi(s[m[6]:m[7]]); end > p.Episode {
				p.EpisodeEnd = end
			}
		}
		titlePart = s[:m[0]]
		break
	}

	// Year: take the last plausible year that is not the very first word, so that
	// "1917 2019" -> title "1917", year 2019, and "2012" alone stays a title.
	yearCut := -1
	for _, m := range reYear.FindAllStringSubmatchIndex(titlePart, -1) {
		y := atoi(titlePart[m[2]:m[3]])
		if m[0] == 0 || y > maxYear() {
			continue
		}
		p.Year = y
		yearCut = m[0]
	}
	if yearCut >= 0 {
		titlePart = titlePart[:yearCut]
	}

	// Cut at the first release-junk token (only needed when no year/episode marked the end).
	if m := reJunk.FindStringIndex(titlePart); m != nil && m[0] > 0 {
		titlePart = titlePart[:m[0]]
	}

	p.Title = cleanTitle(titlePart)
	if p.Title == "" && !p.IsEpisode() {
		// Nothing left (e.g. the name was only junk); fall back to the raw name.
		p.Title = cleanTitle(s)
	}
	return p
}

// ParseSeasonFolder reports the season number if name looks like "Season 2", "S02" or "Temporada 2".
func ParseSeasonFolder(name string) (int, bool) {
	s := strings.TrimSpace(reSeparators.ReplaceAllString(name, " "))
	if m := reSeasonOnly.FindStringSubmatch(s); m != nil {
		return atoi(m[1]), true
	}
	return 0, false
}

func cleanTitle(s string) string {
	s = reBracketJunk.ReplaceAllString(s, " ")
	s = reSpaces.ReplaceAllString(s, " ")
	s = collapseInitials(s)
	for {
		next := reLeadingNoise.ReplaceAllString(reTrailingNoise.ReplaceAllString(s, ""), "")
		if next == s {
			break
		}
		s = next
	}
	s = strings.TrimSpace(s)
	// Drop an unbalanced trailing "(" left from "Title (2010)".
	if strings.Count(s, "(") > strings.Count(s, ")") {
		s = strings.TrimSpace(strings.TrimRight(s, "("))
	}
	if isAllLower(s) {
		s = titleCase(s)
	}
	return s
}

// collapseInitials joins runs of two or more single letters, which is what dotted
// acronyms become after separators are replaced: "P U L S E" -> "PULSE", "L A" -> "LA".
func collapseInitials(s string) string {
	words := strings.Split(s, " ")
	out := make([]string, 0, len(words))
	for i := 0; i < len(words); {
		j := i
		for j < len(words) && isSingleLetter(words[j]) {
			j++
		}
		if j-i >= 2 {
			out = append(out, strings.Join(words[i:j], ""))
			i = j
			continue
		}
		out = append(out, words[i])
		i++
	}
	return strings.Join(out, " ")
}

func isSingleLetter(w string) bool {
	r := []rune(w)
	return len(r) == 1 && unicode.IsLetter(r[0])
}

func isAllLower(s string) bool {
	hasLetter := false
	for _, r := range s {
		if unicode.IsUpper(r) {
			return false
		}
		if unicode.IsLetter(r) {
			hasLetter = true
		}
	}
	return hasLetter
}

func titleCase(s string) string {
	words := strings.Fields(s)
	for i, w := range words {
		r := []rune(w)
		r[0] = unicode.ToUpper(r[0])
		words[i] = string(r)
	}
	return strings.Join(words, " ")
}

func atoi(s string) int {
	n, _ := strconv.Atoi(s)
	return n
}

// NormalizeKey lowercases and strips punctuation so that "Breaking Bad", "breaking.bad"
// and "Breaking-Bad" group to the same show.
func NormalizeKey(s string) string {
	var b strings.Builder
	space := false
	for _, r := range strings.ToLower(s) {
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r):
			b.WriteRune(r)
			space = false
		case r == '&':
			if !space && b.Len() > 0 {
				b.WriteRune(' ')
			}
			b.WriteString("and ")
			space = true
		default:
			if !space && b.Len() > 0 {
				b.WriteRune(' ')
				space = true
			}
		}
	}
	return strings.TrimSpace(b.String())
}
