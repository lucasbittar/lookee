package db

// Category of a library root. TUTORIALS roots are browsed live and never indexed.
type Category string

const (
	Movies    Category = "MOVIES"
	TVShows   Category = "TV_SHOWS"
	Concerts  Category = "CONCERTS"
	Tutorials Category = "TUTORIALS"
)

// Valid reports whether c is a known category.
func (c Category) Valid() bool {
	switch c {
	case Movies, TVShows, Concerts, Tutorials:
		return true
	}
	return false
}

// Indexed reports whether files under this category are scanned into the database.
func (c Category) Indexed() bool { return c.Valid() && c != Tutorials }

// MatchStatus records where a title stands with TMDB.
type MatchStatus string

const (
	StatusPending   MatchStatus = "pending"   // never looked up (e.g. no API key yet)
	StatusMatched   MatchStatus = "matched"   // matched automatically
	StatusUnmatched MatchStatus = "unmatched" // looked up, nothing suitable found
	StatusManual    MatchStatus = "manual"    // fixed by the user; never overwritten by a rescan
)

// TMDB media kinds.
const (
	KindMovie = "movie"
	KindTV    = "tv"
)

// Root is a folder the user added to the library.
type Root struct {
	ID        int64    `json:"id"`
	Path      string   `json:"path"`
	Category  Category `json:"category"`
	Available bool     `json:"available"` // false when the drive was not mounted at the last scan
	LastScan  int64    `json:"lastScan,omitempty"`
}

// File is one indexed playable item: a video file, or a whole disc rip
// (Kind "dvd"/"bluray" = a folder path, "iso" = an image file).
type File struct {
	ID         int64  `json:"id"`
	RootID     int64  `json:"rootId"`
	Path       string `json:"path"`
	Kind       string `json:"kind"` // file | dvd | bluray | iso
	Size       int64  `json:"size"`
	MTime      int64  `json:"mtime"`
	TitleID    int64  `json:"titleId"`
	Season     int    `json:"season,omitempty"`
	Episode    int    `json:"episode,omitempty"`
	EpisodeEnd int    `json:"episodeEnd,omitempty"`
	AddedAt    int64  `json:"addedAt"`
}

// Title is a movie, show or concert, matched to TMDB or not.
type Title struct {
	ID          int64       `json:"id"`
	Category    Category    `json:"category"`
	GroupKey    string      `json:"-"` // normalized parsed name used to group files before a match
	Status      MatchStatus `json:"status"`
	TMDBID      int64       `json:"tmdbId,omitempty"`
	Kind        string      `json:"kind,omitempty"` // movie | tv
	Name        string      `json:"title"`
	Original    string      `json:"originalTitle,omitempty"`
	Year        int         `json:"year,omitempty"`
	Tagline     string      `json:"tagline,omitempty"`
	Overview    string      `json:"overview,omitempty"`
	ReleaseDate string      `json:"releaseDate,omitempty"`
	Runtime     int         `json:"runtime,omitempty"` // minutes
	Genres      []string    `json:"genres,omitempty"`
	Rating      float64     `json:"rating,omitempty"`
	PosterURL   string      `json:"posterUrl,omitempty"`
	BackdropURL string      `json:"backdropUrl,omitempty"`
	// PreviewPath is a video file to grab a preview frame from, set by the engine when
	// the title has no poster (unmatched titles, personal videos). Not stored.
	PreviewPath string `json:"previewPath,omitempty"`
	AddedAt     int64  `json:"addedAt"`
	UpdatedAt   int64  `json:"updatedAt"`
}

// CastMember is one of the top billed actors of a title.
type CastMember struct {
	Order      int    `json:"order"`
	Name       string `json:"name"`
	Character  string `json:"character,omitempty"`
	ProfileURL string `json:"profileUrl,omitempty"`
}

// Episode is TMDB metadata for one episode of a show.
type Episode struct {
	Season   int    `json:"season"`
	Episode  int    `json:"episode"`
	Name     string `json:"name"`
	Overview string `json:"overview,omitempty"`
	StillURL string `json:"stillUrl,omitempty"`
	AirDate  string `json:"airDate,omitempty"`
}

// Progress is the saved playback position for a file path or stream key.
type Progress struct {
	Key        string `json:"key"`
	TitleID    int64  `json:"titleId,omitempty"`
	PositionMS int64  `json:"positionMs"`
	DurationMS int64  `json:"durationMs"`
	UpdatedAt  int64  `json:"updatedAt"`
}
