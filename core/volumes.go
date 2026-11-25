package lookeecore

import (
	"os"
	"path/filepath"
	"sort"
	"syscall"
)

// storageBase is where Android mounts storage. Shared ("emulated") storage, including a
// drive adopted as internal storage, appears as /storage/emulated/0; a removable drive
// as /storage/<UUID>.
const storageBase = "/storage"

// Volume is a storage root offered by the folder picker.
type Volume struct {
	Path       string `json:"path"`
	Label      string `json:"label"`
	Removable  bool   `json:"removable"`
	FreeBytes  int64  `json:"freeBytes"`
	TotalBytes int64  `json:"totalBytes"`
}

func listVolumes(base string) []Volume {
	out := []Volume{}
	if v, ok := volumeAt(filepath.Join(base, "emulated", "0"), "Internal storage", false); ok {
		out = append(out, v)
	}
	entries, err := os.ReadDir(base)
	if err != nil {
		return out
	}
	var removable []Volume
	for _, e := range entries {
		name := e.Name()
		if name == "emulated" || name == "self" || name[0] == '.' {
			continue
		}
		if v, ok := volumeAt(filepath.Join(base, name), "USB drive "+name, true); ok {
			removable = append(removable, v)
		}
	}
	sort.Slice(removable, func(i, j int) bool { return removable[i].Path < removable[j].Path })
	return append(out, removable...)
}

// volumeAt reports a readable directory with its capacity.
func volumeAt(path, label string, removable bool) (Volume, bool) {
	if _, err := os.ReadDir(path); err != nil {
		return Volume{}, false
	}
	v := Volume{Path: path, Label: label, Removable: removable}
	var st syscall.Statfs_t
	if err := syscall.Statfs(path, &st); err == nil {
		bs := int64(st.Bsize) //nolint:unconvert // Bsize is int32 on Linux/Android and uint32 on Darwin
		v.FreeBytes = int64(st.Bavail) * bs
		v.TotalBytes = int64(st.Blocks) * bs
	}
	return v, true
}
