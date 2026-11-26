# Torrent sources

lookee ships with **no** torrent sources. Indexers move domains often, and a public repo that hard-codes them invites takedowns (ROADMAP Risk 4 and Risk 6). There are two ways to add your own.

## 1. From your phone (any build)

Settings → **Set up from your phone**. Every source field takes one address per line. For YTS and EZTV, extra lines are **mirrors**: lookee tries them in order and uses the first that answers.

## 2. A private defaults file (your own builds)

Create `app/src/main/assets/sources.local.json`. The path is git-ignored, so it never reaches the repo. On first launch, the app fills in any of these settings that are still empty:

```json
{
  "yts_url": ["https://<yts-api-host>", "https://<mirror>"],
  "eztv_url": ["https://<eztv-api-host>", "https://<mirror>"],
  "torznab_urls": ["http://<mac-ip>:9696/1/api?apikey=<key>"],
  "stremio_addons": ["https://<addon-host>/manifest.json"],
  "search_pages": ["https://<site>/search/{query}"],
  "tmdb_language": "en-US",
  "subtitle_languages": "pt-BR,en"
}
```

- **Values:** a string, or a list of strings (joined one per line).
- **Only empty settings are filled.** Anything you set on the phone wins.
- **Applied once per file.** A source you clear later stays cleared, until the file itself changes.
- **No API keys.** TMDB and OpenSubtitles keys are refused here: they'd be baked into the APK. Use the phone page for those.
- **Try it on the Mac:** `make cli && core/lookee-cli settings seed app/src/main/assets/sources.local.json`.

## Checking an address

A YTS address works if `<address>/api/v2/list_movies.json?query_term=sintel` returns JSON with `"status":"ok"`. An EZTV address works if `<address>/api/get-torrents?imdb_id=0903747` returns JSON with `torrents_count`. Landing pages and "official" look-alike sites usually fail this test.

## More sites (Pirate Bay and others)

These have no clean API of their own. Run **Prowlarr** or **Jackett** on a computer on your network: they search many sites and expose them as one Torznab address, which goes in `torznab_urls`. A Stremio add-on's `manifest.json` address works too, in `stremio_addons`.

## Search pages (sites without an API)

Some torrent sites have only a search page. Give lookee its address with `{query}` where the words go, one per line, under **Search pages** on the phone page (or `search_pages` in the defaults file). It reads the results page as a person would: the result links that repeat with long titles, plus the size and seeders next to them. When you pick a result, it opens that result's page for the magnet link (or `.torrent` file).

- **Only for typed searches.** Search pages answer Discover's search box ("Torrents" under the title results). They are never asked for a movie's or show's own source list.
- **Private.** Like the other source lists, the addresses are never shown back on the phone page, and nothing names a site in this repository.
- **Best effort.** If a site redesigns its pages, its results may stop appearing; the other sources keep working.
