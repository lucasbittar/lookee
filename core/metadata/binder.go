package metadata

import (
	"context"
	"regexp"
	"strconv"
	"strings"
	"unicode"
)

// Query describes what we know about a local title before matching.
type Query struct {
	Title     string
	Year      int
	Kinds     []string // kinds to try, in order, e.g. ["movie"], ["tv"], ["movie","tv"] for concerts
	AltTitles []string // other names for the same item, e.g. the filename's title when the folder named it
}

// reFiller removes words TMDB's term search trips on ("Live at Rock in Rio" vs "Live @ rock In Rio").
var reFiller = regexp.MustCompile(`(?i)(?:^|\s)(?:at|the|of|and|a|an|-|–|&|@)(?:\s|$)`)

// reCountrySuffix strips a trailing country tag that TMDB titles rarely include ("The Office US").
var reCountrySuffix = regexp.MustCompile(`(?i)\s+(US|UK|AU|NZ|CA|BR)$`)

// Match finds the best TMDB entry for q. It returns ok=false when nothing plausible was found.
// Strategy per kind: title+year, then title alone, then title without a country suffix.
func (c *Client) Match(ctx context.Context, q Query) (SearchResult, bool, error) {
	type attempt struct {
		title string
		year  int
	}
	attempts := []attempt{{q.Title, q.Year}}
	if q.Year > 0 {
		attempts = append(attempts, attempt{q.Title, 0})
	}
	if stripped := reCountrySuffix.ReplaceAllString(q.Title, ""); stripped != q.Title {
		attempts = append(attempts, attempt{stripped, q.Year}, attempt{stripped, 0})
	}
	if lean := strings.Join(strings.Fields(reFiller.ReplaceAllString(" "+q.Title+" ", "  ")), " "); lean != "" && lean != q.Title {
		attempts = append(attempts, attempt{lean, q.Year})
	}
	for _, alt := range q.AltTitles {
		if alt != "" && !strings.EqualFold(alt, q.Title) {
			attempts = append(attempts, attempt{alt, q.Year}, attempt{alt, 0})
		}
	}
	// Return the first strong hit; otherwise the best acceptable one seen across all
	// attempts and kinds (a concert may be a strong TV hit after a weak movie hit).
	var fallback SearchResult
	var haveFallback bool
	var fallbackScore float64
	for _, kind := range q.Kinds {
		for _, a := range attempts {
			results, err := c.Search(ctx, kind, a.title, a.year)
			if err != nil {
				return SearchResult{}, false, err
			}
			best, strong, score, ok := pick(results, a.title, q.Year)
			if !ok {
				continue
			}
			if strong {
				return best, true, nil
			}
			if !haveFallback || score > fallbackScore {
				fallback, fallbackScore, haveFallback = best, score, true
			}
		}
	}
	return fallback, haveFallback, nil
}

// Best picks the most plausible result for a parsed title and year, or ok=false.
func Best(results []SearchResult, title string, year int) (SearchResult, bool) {
	r, _, _, ok := pick(results, title, year)
	return r, ok
}

// pick scores results and reports the winner and whether it is a strong match.
//
//   - strong (tier A): title similarity >= 0.8 and the year agrees. Returned at once.
//   - tier B: similarity >= 0.5 and the year agrees.
//   - tier C: near-exact title (>= 0.95) whose year disagrees. Concerts and documentaries
//     are often released years after the event in the folder name ("Live at Woodstock (1969)"
//     -> 1999), but a same-named film from another year must not beat a tier B hit
//     ("Back to the Beginning (2025)": "…: Ozzy's Final Bow" 2026 beats "Back to the Beginning" 2015).
//
// The year agrees when either side has none, they are within one year, or the
// query year appears in the result title ("Oasis Knebworth (1996)" -> "Oasis: Knebworth 1996").
func pick(results []SearchResult, title string, year int) (best SearchResult, strong bool, score float64, ok bool) {
	want := words(title)
	bestRank := -1.0
	for i, r := range results {
		sim := max(similarity(want, words(r.Title)), similarity(want, words(r.OriginalTitle)))
		yearOK := year == 0 || (r.Year > 0 && abs(r.Year-year) <= 1) ||
			strings.Contains(r.Title, strconv.Itoa(year))
		var tier float64
		switch {
		case sim >= 0.8 && yearOK:
			tier = 300
		case sim >= 0.5 && yearOK:
			tier = 200
		case sim >= 0.95:
			tier = 100
		default:
			continue
		}
		isStrong := tier == 300
		sc := sim * 10
		if year > 0 && r.Year > 0 {
			switch abs(r.Year - year) {
			case 0:
				sc += 5
			case 1:
				sc += 3
			}
		}
		// TMDB's own relevance order and vote count break ties.
		sc += 1.5/float64(i+1) + min(float64(r.VoteCount)/2000, 1)
		rank := tier + sc
		if rank > bestRank {
			best, strong, score, ok, bestRank = r, isStrong, rank, true, rank
		}
	}
	return best, strong, score, ok
}

func words(s string) []string {
	f := strings.FieldsFunc(strings.ToLower(s), func(r rune) bool { return !unicode.IsLetter(r) && !unicode.IsDigit(r) })
	// Join runs of single letters so "S.H.I.E.L.D." and "SHIELD" compare equal.
	var joined []string
	for i := 0; i < len(f); {
		j := i
		for j < len(f) && len([]rune(f[j])) == 1 && unicode.IsLetter([]rune(f[j])[0]) {
			j++
		}
		if j-i >= 2 {
			joined = append(joined, strings.Join(f[i:j], ""))
			i = j
			continue
		}
		joined = append(joined, f[i])
		i++
	}
	f = joined
	out := f[:0]
	for _, w := range f {
		switch w {
		case "the", "a", "an", "and", "of", "o", "os", "as", "e", "de", "la", "le":
			continue
		}
		out = append(out, w)
	}
	return out
}

// similarity is the Dice coefficient of the two word sets.
func similarity(a, b []string) float64 {
	if len(a) == 0 || len(b) == 0 {
		return 0
	}
	set := map[string]int{}
	for _, w := range a {
		set[w]++
	}
	common := 0
	for _, w := range b {
		if set[w] > 0 {
			set[w]--
			common++
		}
	}
	return 2 * float64(common) / float64(len(a)+len(b))
}

func abs(n int) int {
	if n < 0 {
		return -n
	}
	return n
}
