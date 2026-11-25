// Package anet replaces github.com/wlynxg/anet (see core/go.mod `replace`).
//
// Upstream anet works around Android 11+ restrictions on listing network interfaces by
// reaching into net.zoneCache with //go:linkname, which current Go linkers reject when
// building for Android ("invalid reference to net.zoneCache"). In lookee it is only used by
// pion's WebRTC stack (WebTorrent peers), which a TV box does not need, so plain net
// package calls are enough. The MIBOX3 runs Android 9, where these work as-is.
package anet

import "net"

// Interfaces lists the system's network interfaces.
func Interfaces() ([]net.Interface, error) { return net.Interfaces() }

// InterfaceAddrs lists the system's unicast interface addresses.
func InterfaceAddrs() ([]net.Addr, error) { return net.InterfaceAddrs() }

// InterfaceAddrsByInterface lists the addresses of one interface.
func InterfaceAddrsByInterface(ifi *net.Interface) ([]net.Addr, error) { return ifi.Addrs() }

// InterfaceByIndex returns the interface with the given index.
func InterfaceByIndex(index int) (*net.Interface, error) { return net.InterfaceByIndex(index) }

// InterfaceByName returns the interface with the given name.
func InterfaceByName(name string) (*net.Interface, error) { return net.InterfaceByName(name) }

// SetAndroidVersion is a no-op kept for API compatibility.
func SetAndroidVersion(version uint) {}
