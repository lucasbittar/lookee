package scanner

import (
	"path/filepath"
	"strings"
)

// Item kinds stored in media_files.kind.
const (
	KindFile   = "file"   // a single playable video file
	KindDVD    = "dvd"    // a folder of .VOB/.IFO/.BUP files (VIDEO_TS or loose VOBs)
	KindBluray = "bluray" // a BDMV folder
	KindISO    = "iso"    // a disc image
)

var dvdExtensions = map[string]bool{".vob": true, ".ifo": true, ".bup": true}

// blurayMarkers are Blu-ray playlist/index files. A folder holding them is a Blu-ray rip
// even when its BDMV structure was flattened by copying ("(2) 00000.clpi", "00007.m2ts").
var blurayMarkers = map[string]bool{".mpls": true, ".clpi": true, ".bdmv": true}

// IsBlurayMarker reports whether name is a Blu-ray playlist/index file.
func IsBlurayMarker(name string) bool { return blurayMarkers[strings.ToLower(filepath.Ext(name))] }

// discItem classifies a file that belongs to a disc rip. It returns the path that
// identifies the whole disc (the item that gets indexed) and its kind.
// flatBluray holds folders known to contain Blu-ray marker files.
//
//   - anything inside a BDMV folder -> that BDMV folder (bluray)
//   - anything in a flattened Blu-ray folder -> that folder (bluray)
//   - .vob/.ifo/.bup                -> the folder holding them (dvd)
//   - .iso                          -> the file itself (iso)
func discItem(rootPath, filePath string, flatBluray map[string]bool) (key, kind string, ok bool) {
	rel, err := filepath.Rel(rootPath, filePath)
	if err != nil {
		return "", "", false
	}
	parts := strings.Split(rel, string(filepath.Separator))
	for i, p := range parts[:len(parts)-1] {
		if strings.EqualFold(p, "BDMV") {
			return filepath.Join(append([]string{rootPath}, parts[:i+1]...)...), KindBluray, true
		}
	}
	if dir := filepath.Dir(filePath); flatBluray[dir] {
		return dir, KindBluray, true
	}
	switch ext := strings.ToLower(filepath.Ext(filePath)); {
	case dvdExtensions[ext]:
		return filepath.Dir(filePath), KindDVD, true
	case ext == ".iso":
		return filePath, KindISO, true
	}
	return "", "", false
}

// isDiscFolderName reports names that say nothing about the title ("VIDEO_TS", "BDMV").
func isDiscFolderName(name string) bool {
	switch strings.ToUpper(name) {
	case "VIDEO_TS", "BDMV", "AUDIO_TS":
		return true
	}
	return false
}
