package engine

import (
	"context"
	"time"

	"github.com/lucasbittar/lookee/core/remote"
)

// settingsStore exposes engine settings to the phone setup page.
type settingsStore struct{ e *Engine }

func (s settingsStore) Fields(ctx context.Context) ([]remote.Field, error) {
	all, err := s.e.store.AllSettings(ctx)
	if err != nil {
		return nil, err
	}
	v, err := s.e.Settings(ctx)
	if err != nil {
		return nil, err
	}
	f := func(key, label, help, group string, multiline bool) remote.Field {
		value := all[key]
		switch key {
		case KeyTMDBLanguage:
			value = v.TMDBLanguage
		case KeySubtitleLanguages:
			value = v.SubtitleLanguages
		}
		return remote.Field{Key: key, Label: label, Help: help, Group: group, Multiline: multiline,
			Secret: secretKeys[key], IsSet: all[key] != "", Value: value}
	}
	return []remote.Field{
		f(KeyTMDBAPIKey, "TMDB API key", "Free at themoviedb.org → Settings → API. The v3 key or the v4 read token both work.", "Library", false),
		f(KeyTMDBLanguage, "Metadata language", "Titles and synopses language, e.g. en-US or pt-BR.", "Library", false),
		f(KeyOpenSubtitlesAPIKey, "OpenSubtitles API key", "Free at opensubtitles.com → Consumers.", "Subtitles", false),
		f(KeySubtitleLanguages, "Subtitle languages", "In order of preference, comma separated, e.g. pt-BR,en.", "Subtitles", false),
		f(KeyOpenSubtitlesUser, "OpenSubtitles username", "Optional. Logging in raises the daily download limit.", "Subtitles", false),
		f(KeyOpenSubtitlesPass, "OpenSubtitles password", "", "Subtitles", false),
		f(KeyYTSURL, "YTS-compatible API", "Base URL of a YTS-style movie API. Several mirrors? One per line, tried in order.", "Torrent sources", true),
		f(KeyEZTVURL, "EZTV-compatible API", "Base URL of an EZTV-style TV API. Several mirrors? One per line, tried in order.", "Torrent sources", true),
		f(KeyTorznabURLs, "Torznab feeds", "Jackett or Prowlarr Torznab URLs including the apikey, one per line.", "Torrent sources", true),
		f(KeyStremioAddons, "Stremio add-ons", "Add-on manifest URLs, one per line.", "Torrent sources", true),
		f(KeySearchPages, "Search pages", "A torrent site's search address with {query} where the words go, one per line. Used only when you type a search in Discover, never for a title's sources.", "Torrent sources", true),
	}, nil
}

func (s settingsStore) Save(ctx context.Context, values map[string]string) error {
	for k, v := range values {
		if err := s.e.SetSetting(ctx, k, v); err != nil {
			return err
		}
	}
	s.e.log.Info("settings saved from phone", "fields", len(values))
	return nil
}

// RemoteSetup describes a running phone setup page.
type RemoteSetup struct {
	URL       string `json:"url"`
	PIN       string `json:"pin"`
	ExpiresIn int    `json:"expiresInSeconds"`
}

// StartRemoteSetup starts the PIN-protected phone settings page (replacing any running one).
// onClosed is called when it stops for any reason; saved reports whether settings changed.
func (e *Engine) StartRemoteSetup(host string, onClosed func(saved bool)) (RemoteSetup, error) {
	e.StopRemoteSetup()
	saved := false
	srv, err := remote.Start(settingsStore{e}, remote.Options{
		Host:    host,
		Logs:    e.LogTail,
		OnSaved: func() { saved = true },
	})
	if err != nil {
		return RemoteSetup{}, err
	}
	e.remoteMu.Lock()
	e.remote = srv
	e.remoteMu.Unlock()
	go func() {
		<-srv.Done()
		e.remoteMu.Lock()
		if e.remote == srv {
			e.remote = nil
		}
		e.remoteMu.Unlock()
		e.log.Info("phone setup closed", "saved", saved)
		if onClosed != nil {
			onClosed(saved)
		}
	}()
	e.log.Info("phone setup started", "url", srv.URL())
	return RemoteSetup{URL: srv.URL(), PIN: srv.PIN(), ExpiresIn: int(remote.DefaultTimeout / time.Second)}, nil
}

// StopRemoteSetup closes the phone settings page if it is running.
func (e *Engine) StopRemoteSetup() {
	e.remoteMu.Lock()
	srv := e.remote
	e.remote = nil
	e.remoteMu.Unlock()
	if srv != nil {
		srv.Close()
	}
}
