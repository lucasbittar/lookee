// Package db is lookee's SQLite persistence layer (pure Go via modernc.org/sqlite).
package db

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

// ErrNotFound is returned when a requested row does not exist.
var ErrNotFound = errors.New("not found")

// Store wraps the database handle.
type Store struct {
	sql *sql.DB
}

// migrations are applied in order; the index+1 is stored in PRAGMA user_version.
// Never edit an applied migration; append a new one.
var migrations = []string{
	`
CREATE TABLE settings (
	key   TEXT PRIMARY KEY,
	value TEXT NOT NULL
);

CREATE TABLE library_roots (
	id         INTEGER PRIMARY KEY,
	path       TEXT NOT NULL UNIQUE,
	category   TEXT NOT NULL CHECK (category IN ('MOVIES','TV_SHOWS','CONCERTS','TUTORIALS')),
	available  INTEGER NOT NULL DEFAULT 1,
	last_scan  INTEGER NOT NULL DEFAULT 0,
	created_at INTEGER NOT NULL
);

CREATE TABLE titles (
	id           INTEGER PRIMARY KEY,
	category     TEXT NOT NULL,
	group_key    TEXT NOT NULL,
	status       TEXT NOT NULL DEFAULT 'pending',
	tmdb_id      INTEGER,
	kind         TEXT,
	name         TEXT NOT NULL,
	original     TEXT NOT NULL DEFAULT '',
	year         INTEGER NOT NULL DEFAULT 0,
	tagline      TEXT NOT NULL DEFAULT '',
	overview     TEXT NOT NULL DEFAULT '',
	release_date TEXT NOT NULL DEFAULT '',
	runtime      INTEGER NOT NULL DEFAULT 0,
	genres       TEXT NOT NULL DEFAULT '[]',
	rating       REAL NOT NULL DEFAULT 0,
	poster_url   TEXT NOT NULL DEFAULT '',
	backdrop_url TEXT NOT NULL DEFAULT '',
	added_at     INTEGER NOT NULL,
	updated_at   INTEGER NOT NULL
);
CREATE INDEX titles_category ON titles(category, name);
CREATE INDEX titles_group ON titles(category, group_key, year);
CREATE INDEX titles_tmdb ON titles(kind, tmdb_id);

CREATE TABLE media_files (
	id          INTEGER PRIMARY KEY,
	root_id     INTEGER NOT NULL REFERENCES library_roots(id) ON DELETE CASCADE,
	path        TEXT NOT NULL UNIQUE,
	size        INTEGER NOT NULL,
	mtime       INTEGER NOT NULL,
	title_id    INTEGER NOT NULL REFERENCES titles(id),
	season      INTEGER NOT NULL DEFAULT 0,
	episode     INTEGER NOT NULL DEFAULT 0,
	episode_end INTEGER NOT NULL DEFAULT 0,
	added_at    INTEGER NOT NULL
);
CREATE INDEX media_files_title ON media_files(title_id, season, episode);
CREATE INDEX media_files_root ON media_files(root_id);

CREATE TABLE cast_members (
	title_id    INTEGER NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
	ord         INTEGER NOT NULL,
	name        TEXT NOT NULL,
	character   TEXT NOT NULL DEFAULT '',
	profile_url TEXT NOT NULL DEFAULT '',
	PRIMARY KEY (title_id, ord)
);

CREATE TABLE episodes (
	title_id  INTEGER NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
	season    INTEGER NOT NULL,
	episode   INTEGER NOT NULL,
	name      TEXT NOT NULL DEFAULT '',
	overview  TEXT NOT NULL DEFAULT '',
	still_url TEXT NOT NULL DEFAULT '',
	air_date  TEXT NOT NULL DEFAULT '',
	PRIMARY KEY (title_id, season, episode)
);

CREATE TABLE playback_progress (
	key         TEXT PRIMARY KEY,
	title_id    INTEGER,
	position_ms INTEGER NOT NULL,
	duration_ms INTEGER NOT NULL,
	updated_at  INTEGER NOT NULL
);
CREATE INDEX playback_progress_recent ON playback_progress(updated_at DESC);
`,
}

// Open opens (creating if needed) the database at path and applies migrations.
func Open(path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, fmt.Errorf("create db dir: %w", err)
	}
	q := url.Values{}
	q.Add("_pragma", "journal_mode(WAL)")
	q.Add("_pragma", "foreign_keys(1)")
	q.Add("_pragma", "busy_timeout(5000)")
	q.Add("_pragma", "synchronous(NORMAL)")
	sqlDB, err := sql.Open("sqlite", "file:"+path+"?"+q.Encode())
	if err != nil {
		return nil, err
	}
	s := &Store{sql: sqlDB}
	if err := s.migrate(context.Background()); err != nil {
		sqlDB.Close()
		return nil, err
	}
	return s, nil
}

// Close closes the database.
func (s *Store) Close() error { return s.sql.Close() }

func (s *Store) migrate(ctx context.Context) error {
	var version int
	if err := s.sql.QueryRowContext(ctx, `PRAGMA user_version`).Scan(&version); err != nil {
		return fmt.Errorf("read schema version: %w", err)
	}
	for i := version; i < len(migrations); i++ {
		tx, err := s.sql.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, migrations[i]); err != nil {
			tx.Rollback()
			return fmt.Errorf("migration %d: %w", i+1, err)
		}
		if _, err := tx.ExecContext(ctx, fmt.Sprintf(`PRAGMA user_version = %d`, i+1)); err != nil {
			tx.Rollback()
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}
	return nil
}

func now() int64 { return time.Now().Unix() }

// ---- settings ----

// GetSetting returns the value for key, or "" if unset.
func (s *Store) GetSetting(ctx context.Context, key string) (string, error) {
	var v string
	err := s.sql.QueryRowContext(ctx, `SELECT value FROM settings WHERE key = ?`, key).Scan(&v)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return v, err
}

// SetSetting stores value under key; an empty value deletes the key.
func (s *Store) SetSetting(ctx context.Context, key, value string) error {
	if value == "" {
		_, err := s.sql.ExecContext(ctx, `DELETE FROM settings WHERE key = ?`, key)
		return err
	}
	_, err := s.sql.ExecContext(ctx,
		`INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value`, key, value)
	return err
}

// AllSettings returns every stored setting.
func (s *Store) AllSettings(ctx context.Context) (map[string]string, error) {
	rows, err := s.sql.QueryContext(ctx, `SELECT key, value FROM settings`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]string{}
	for rows.Next() {
		var k, v string
		if err := rows.Scan(&k, &v); err != nil {
			return nil, err
		}
		out[k] = v
	}
	return out, rows.Err()
}

// ---- library roots ----

// AddRoot registers a folder; adding an existing path updates its category.
func (s *Store) AddRoot(ctx context.Context, path string, cat Category) (Root, error) {
	if !cat.Valid() {
		return Root{}, fmt.Errorf("invalid category %q", cat)
	}
	_, err := s.sql.ExecContext(ctx, `
		INSERT INTO library_roots(path, category, created_at) VALUES(?, ?, ?)
		ON CONFLICT(path) DO UPDATE SET category = excluded.category`, path, string(cat), now())
	if err != nil {
		return Root{}, err
	}
	return s.rootByPath(ctx, path)
}

func (s *Store) rootByPath(ctx context.Context, path string) (Root, error) {
	var r Root
	var cat string
	err := s.sql.QueryRowContext(ctx,
		`SELECT id, path, category, available, last_scan FROM library_roots WHERE path = ?`, path).
		Scan(&r.ID, &r.Path, &cat, &r.Available, &r.LastScan)
	if errors.Is(err, sql.ErrNoRows) {
		return r, ErrNotFound
	}
	r.Category = Category(cat)
	return r, err
}

// Roots lists all library folders.
func (s *Store) Roots(ctx context.Context) ([]Root, error) {
	rows, err := s.sql.QueryContext(ctx, `SELECT id, path, category, available, last_scan FROM library_roots ORDER BY path`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Root
	for rows.Next() {
		var r Root
		var cat string
		if err := rows.Scan(&r.ID, &r.Path, &cat, &r.Available, &r.LastScan); err != nil {
			return nil, err
		}
		r.Category = Category(cat)
		out = append(out, r)
	}
	return out, rows.Err()
}

// RemoveRoot deletes a root, its files, and any titles left without files.
func (s *Store) RemoveRoot(ctx context.Context, id int64) error {
	return s.Tx(ctx, func(tx *Tx) error {
		if _, err := tx.ExecContext(ctx, `DELETE FROM media_files WHERE root_id = ?`, id); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM library_roots WHERE id = ?`, id); err != nil {
			return err
		}
		_, err := tx.DeleteOrphanTitles(ctx)
		return err
	})
}

// MarkRootScanned records availability and scan time for a root.
func (s *Store) MarkRootScanned(ctx context.Context, id int64, available bool) error {
	_, err := s.sql.ExecContext(ctx, `UPDATE library_roots SET available = ?, last_scan = ? WHERE id = ?`, available, now(), id)
	return err
}

// ---- transactions ----

// Tx is a write transaction with the helpers the scanner needs.
type Tx struct {
	*sql.Tx
}

// Tx runs fn inside a transaction, committing on success.
func (s *Store) Tx(ctx context.Context, fn func(*Tx) error) error {
	t, err := s.sql.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	if err := fn(&Tx{t}); err != nil {
		t.Rollback()
		return err
	}
	return t.Commit()
}

// FileStat is the change-detection snapshot of an indexed file.
type FileStat struct {
	ID    int64
	Size  int64
	MTime int64
}

// FileStats returns path -> stat for every file under a root.
func (s *Store) FileStats(ctx context.Context, rootID int64) (map[string]FileStat, error) {
	rows, err := s.sql.QueryContext(ctx, `SELECT id, path, size, mtime FROM media_files WHERE root_id = ?`, rootID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]FileStat{}
	for rows.Next() {
		var p string
		var f FileStat
		if err := rows.Scan(&f.ID, &p, &f.Size, &f.MTime); err != nil {
			return nil, err
		}
		out[p] = f
	}
	return out, rows.Err()
}

// FindOrCreateTitle returns the title grouped under (category, groupKey, year),
// creating a pending one named displayName if none exists.
func (tx *Tx) FindOrCreateTitle(ctx context.Context, cat Category, groupKey string, year int, displayName string) (int64, error) {
	var id int64
	err := tx.QueryRowContext(ctx,
		`SELECT id FROM titles WHERE category = ? AND group_key = ? AND year = ? ORDER BY id LIMIT 1`,
		string(cat), groupKey, year).Scan(&id)
	if err == nil {
		return id, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return 0, err
	}
	ts := now()
	res, err := tx.ExecContext(ctx,
		`INSERT INTO titles(category, group_key, status, name, year, added_at, updated_at) VALUES(?, ?, ?, ?, ?, ?, ?)`,
		string(cat), groupKey, string(StatusPending), displayName, year, ts, ts)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// InsertFile indexes a new file.
func (tx *Tx) InsertFile(ctx context.Context, f File) (int64, error) {
	res, err := tx.ExecContext(ctx, `
		INSERT INTO media_files(root_id, path, size, mtime, title_id, season, episode, episode_end, added_at)
		VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		f.RootID, f.Path, f.Size, f.MTime, f.TitleID, f.Season, f.Episode, f.EpisodeEnd, now())
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// UpdateFileStat records a changed size/mtime without touching the match.
func (tx *Tx) UpdateFileStat(ctx context.Context, id, size, mtime int64) error {
	_, err := tx.ExecContext(ctx, `UPDATE media_files SET size = ?, mtime = ? WHERE id = ?`, size, mtime, id)
	return err
}

// DeleteFile removes a file from the index.
func (tx *Tx) DeleteFile(ctx context.Context, id int64) error {
	_, err := tx.ExecContext(ctx, `DELETE FROM media_files WHERE id = ?`, id)
	return err
}

// DeleteOrphanTitles removes titles that no longer have any files.
func (tx *Tx) DeleteOrphanTitles(ctx context.Context) (int64, error) {
	res, err := tx.ExecContext(ctx, `DELETE FROM titles WHERE id NOT IN (SELECT DISTINCT title_id FROM media_files)`)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// ---- titles ----

const titleCols = `id, category, group_key, status, COALESCE(tmdb_id, 0), COALESCE(kind, ''), name, original, year,
	tagline, overview, release_date, runtime, genres, rating, poster_url, backdrop_url, added_at, updated_at`

type scanner interface{ Scan(...any) error }

func scanTitle(r scanner) (Title, error) {
	var t Title
	var cat, status, genres string
	err := r.Scan(&t.ID, &cat, &t.GroupKey, &status, &t.TMDBID, &t.Kind, &t.Name, &t.Original, &t.Year,
		&t.Tagline, &t.Overview, &t.ReleaseDate, &t.Runtime, &genres, &t.Rating, &t.PosterURL, &t.BackdropURL,
		&t.AddedAt, &t.UpdatedAt)
	if err != nil {
		return t, err
	}
	t.Category = Category(cat)
	t.Status = MatchStatus(status)
	_ = json.Unmarshal([]byte(genres), &t.Genres)
	return t, nil
}

// Title fetches one title by id.
func (s *Store) Title(ctx context.Context, id int64) (Title, error) {
	t, err := scanTitle(s.sql.QueryRowContext(ctx, `SELECT `+titleCols+` FROM titles WHERE id = ?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return t, ErrNotFound
	}
	return t, err
}

// TitleFilter narrows ListTitles.
type TitleFilter struct {
	Category Category      // empty = all
	Statuses []MatchStatus // empty = all
	Offset   int
	Limit    int    // 0 = no limit
	OrderBy  string // "name" (default) or "added"
}

// ListTitles returns titles matching f.
func (s *Store) ListTitles(ctx context.Context, f TitleFilter) ([]Title, error) {
	var where []string
	var args []any
	if f.Category != "" {
		where = append(where, "category = ?")
		args = append(args, string(f.Category))
	}
	if len(f.Statuses) > 0 {
		ph := make([]string, len(f.Statuses))
		for i, st := range f.Statuses {
			ph[i] = "?"
			args = append(args, string(st))
		}
		where = append(where, "status IN ("+strings.Join(ph, ",")+")")
	}
	q := `SELECT ` + titleCols + ` FROM titles`
	if len(where) > 0 {
		q += " WHERE " + strings.Join(where, " AND ")
	}
	if f.OrderBy == "added" {
		q += " ORDER BY added_at DESC, id DESC"
	} else {
		q += " ORDER BY name COLLATE NOCASE, year"
	}
	if f.Limit > 0 {
		q += " LIMIT ? OFFSET ?"
		args = append(args, f.Limit, f.Offset)
	}
	rows, err := s.sql.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Title
	for rows.Next() {
		t, err := scanTitle(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// TitleMetadata is everything fetched from TMDB for a title.
type TitleMetadata struct {
	TMDBID      int64
	Kind        string
	Name        string
	Original    string
	Year        int
	Tagline     string
	Overview    string
	ReleaseDate string
	Runtime     int
	Genres      []string
	Rating      float64
	PosterURL   string
	BackdropURL string
	Cast        []CastMember
}

// ApplyMetadata stores TMDB data on a title and replaces its cast.
// If another title in the same category already has this TMDB id, the files are
// moved onto that title and this one is removed; the surviving id is returned.
func (s *Store) ApplyMetadata(ctx context.Context, titleID int64, status MatchStatus, m TitleMetadata) (int64, error) {
	finalID := titleID
	err := s.Tx(ctx, func(tx *Tx) error {
		var existing int64
		err := tx.QueryRowContext(ctx,
			`SELECT id FROM titles WHERE kind = ? AND tmdb_id = ? AND id <> ? AND category = (SELECT category FROM titles WHERE id = ?) LIMIT 1`,
			m.Kind, m.TMDBID, titleID, titleID).Scan(&existing)
		switch {
		case err == nil:
			if _, err := tx.ExecContext(ctx, `UPDATE media_files SET title_id = ? WHERE title_id = ?`, existing, titleID); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `DELETE FROM titles WHERE id = ?`, titleID); err != nil {
				return err
			}
			finalID = existing
		case !errors.Is(err, sql.ErrNoRows):
			return err
		}

		genres, _ := json.Marshal(m.Genres)
		if m.Genres == nil {
			genres = []byte("[]")
		}
		if _, err := tx.ExecContext(ctx, `
			UPDATE titles SET status = ?, tmdb_id = ?, kind = ?, name = ?, original = ?, year = ?, tagline = ?, overview = ?,
				release_date = ?, runtime = ?, genres = ?, rating = ?, poster_url = ?, backdrop_url = ?, updated_at = ?
			WHERE id = ?`,
			string(status), m.TMDBID, m.Kind, m.Name, m.Original, m.Year, m.Tagline, m.Overview,
			m.ReleaseDate, m.Runtime, string(genres), m.Rating, m.PosterURL, m.BackdropURL, now(), finalID); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM cast_members WHERE title_id = ?`, finalID); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM episodes WHERE title_id = ?`, finalID); err != nil {
			return err
		}
		for _, c := range m.Cast {
			if _, err := tx.ExecContext(ctx,
				`INSERT INTO cast_members(title_id, ord, name, character, profile_url) VALUES(?, ?, ?, ?, ?)`,
				finalID, c.Order, c.Name, c.Character, c.ProfileURL); err != nil {
				return err
			}
		}
		return nil
	})
	return finalID, err
}

// SetTitleStatus updates only the match status (e.g. pending -> unmatched).
func (s *Store) SetTitleStatus(ctx context.Context, id int64, status MatchStatus) error {
	_, err := s.sql.ExecContext(ctx, `UPDATE titles SET status = ?, updated_at = ? WHERE id = ?`, string(status), now(), id)
	return err
}

// Cast returns the stored cast of a title in billing order.
func (s *Store) Cast(ctx context.Context, titleID int64) ([]CastMember, error) {
	rows, err := s.sql.QueryContext(ctx,
		`SELECT ord, name, character, profile_url FROM cast_members WHERE title_id = ? ORDER BY ord`, titleID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []CastMember
	for rows.Next() {
		var c CastMember
		if err := rows.Scan(&c.Order, &c.Name, &c.Character, &c.ProfileURL); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// Files returns the files of a title ordered by season/episode/path.
func (s *Store) Files(ctx context.Context, titleID int64) ([]File, error) {
	rows, err := s.sql.QueryContext(ctx, `
		SELECT id, root_id, path, size, mtime, title_id, season, episode, episode_end, added_at
		FROM media_files WHERE title_id = ? ORDER BY season, episode, path`, titleID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []File
	for rows.Next() {
		var f File
		if err := rows.Scan(&f.ID, &f.RootID, &f.Path, &f.Size, &f.MTime, &f.TitleID, &f.Season, &f.Episode, &f.EpisodeEnd, &f.AddedAt); err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, rows.Err()
}

// SeasonsMissingEpisodes returns seasons that have files but no episode metadata yet.
func (s *Store) SeasonsMissingEpisodes(ctx context.Context, titleID int64) ([]int, error) {
	rows, err := s.sql.QueryContext(ctx, `
		SELECT DISTINCT f.season FROM media_files f
		WHERE f.title_id = ? AND f.episode > 0
		  AND NOT EXISTS (SELECT 1 FROM episodes e WHERE e.title_id = f.title_id AND e.season = f.season)
		ORDER BY f.season`, titleID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []int
	for rows.Next() {
		var n int
		if err := rows.Scan(&n); err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, rows.Err()
}

// TitlesWithMissingSeasons returns matched TV titles that have files in seasons without episode metadata.
func (s *Store) TitlesWithMissingSeasons(ctx context.Context) ([]int64, error) {
	rows, err := s.sql.QueryContext(ctx, `
		SELECT DISTINCT t.id FROM titles t JOIN media_files f ON f.title_id = t.id
		WHERE t.kind = 'tv' AND t.status IN ('matched','manual') AND f.episode > 0
		  AND NOT EXISTS (SELECT 1 FROM episodes e WHERE e.title_id = t.id AND e.season = f.season)`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// ReplaceSeason stores episode metadata for one season.
func (s *Store) ReplaceSeason(ctx context.Context, titleID int64, season int, eps []Episode) error {
	return s.Tx(ctx, func(tx *Tx) error {
		if _, err := tx.ExecContext(ctx, `DELETE FROM episodes WHERE title_id = ? AND season = ?`, titleID, season); err != nil {
			return err
		}
		for _, e := range eps {
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO episodes(title_id, season, episode, name, overview, still_url, air_date) VALUES(?, ?, ?, ?, ?, ?, ?)`,
				titleID, season, e.Episode, e.Name, e.Overview, e.StillURL, e.AirDate); err != nil {
				return err
			}
		}
		return nil
	})
}

// Episodes returns all stored episodes of a title.
func (s *Store) Episodes(ctx context.Context, titleID int64) ([]Episode, error) {
	rows, err := s.sql.QueryContext(ctx, `
		SELECT season, episode, name, overview, still_url, air_date FROM episodes WHERE title_id = ? ORDER BY season, episode`, titleID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Episode
	for rows.Next() {
		var e Episode
		if err := rows.Scan(&e.Season, &e.Episode, &e.Name, &e.Overview, &e.StillURL, &e.AirDate); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

// ---- playback progress ----

// SaveProgress upserts a playback position.
func (s *Store) SaveProgress(ctx context.Context, p Progress) error {
	_, err := s.sql.ExecContext(ctx, `
		INSERT INTO playback_progress(key, title_id, position_ms, duration_ms, updated_at) VALUES(?, NULLIF(?, 0), ?, ?, ?)
		ON CONFLICT(key) DO UPDATE SET title_id = excluded.title_id, position_ms = excluded.position_ms,
			duration_ms = excluded.duration_ms, updated_at = excluded.updated_at`,
		p.Key, p.TitleID, p.PositionMS, p.DurationMS, now())
	return err
}

// GetProgress returns the saved position for key, or ErrNotFound.
func (s *Store) GetProgress(ctx context.Context, key string) (Progress, error) {
	var p Progress
	err := s.sql.QueryRowContext(ctx,
		`SELECT key, COALESCE(title_id, 0), position_ms, duration_ms, updated_at FROM playback_progress WHERE key = ?`, key).
		Scan(&p.Key, &p.TitleID, &p.PositionMS, &p.DurationMS, &p.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return p, ErrNotFound
	}
	return p, err
}

// RecentProgress returns the most recently watched, unfinished items.
// An item counts as finished past 95% of its duration.
func (s *Store) RecentProgress(ctx context.Context, limit int) ([]Progress, error) {
	rows, err := s.sql.QueryContext(ctx, `
		SELECT key, COALESCE(title_id, 0), position_ms, duration_ms, updated_at FROM playback_progress
		WHERE duration_ms > 0 AND position_ms < duration_ms * 95 / 100 AND position_ms > 0
		ORDER BY updated_at DESC LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Progress
	for rows.Next() {
		var p Progress
		if err := rows.Scan(&p.Key, &p.TitleID, &p.PositionMS, &p.DurationMS, &p.UpdatedAt); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}
