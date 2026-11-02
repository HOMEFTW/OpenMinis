//go:build !android && !darwin

package tsnet

// Desktop netmon reads the routing table itself; its mobile-only override
// does not exist in these builds. This also permits host-side network tests.
func updateDefaultRouteInterface(string) {}
