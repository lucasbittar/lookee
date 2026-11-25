//go:build cgo && !nosqlite

package db

// anacrolix/torrent's optional SQLite piece store bundles its own copy of SQLite, which
// clashes with mattn/go-sqlite3 when linking ("duplicate symbol _sqlite3_…"). Build and
// test with the tags in the Makefile's GO_TAGS (`-tags purego_utp,nosqlite`); `make test`,
// `make cli` and `make bind` already do.
var _ = buildWithTagNosqliteSeeMakefileGOTAGS
