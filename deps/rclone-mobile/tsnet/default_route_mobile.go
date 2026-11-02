//go:build android || darwin

package tsnet

import "tailscale.com/net/netmon"

func updateDefaultRouteInterface(ifName string) {
	netmon.UpdateLastKnownDefaultRouteInterface(ifName)
}
