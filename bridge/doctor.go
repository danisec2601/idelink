package main

import (
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// setup is what the doctor checks besides the IDE socket: the two trust configs and its own parent.
type setup struct {
	ide       trust
	ideErr    error
	claude    claudes
	claudeErr error
	parent    string
}

// doctor checks the setup without pairing (it sends only list_tools) and prints one line per check;
// a warning (⚠) doesn't fail it.
func doctor(out io.Writer, t target, s setup, tools []toolSpec) int {
	failed := false
	check := func(ok bool, format string, a ...any) {
		mark := "✓"
		if !ok {
			mark, failed = "✗", true
		}
		_, _ = fmt.Fprintln(out, mark, asciiOnly(fmt.Sprintf(format, a...)))
	}
	warn := func(format string, a ...any) {
		_, _ = fmt.Fprintln(out, "⚠", asciiOnly(fmt.Sprintf(format, a...)))
	}
	result := func() int {
		if failed {
			return 1
		}
		return 0
	}

	check(true, "idelink-bridge %s, %d tools embedded", version, len(tools))
	if s.ideErr != nil {
		check(false, "trust config: %v", s.ideErr)
	} else {
		check(len(s.ide.dirs) > 0, "%s", s.ide)
	}
	claudeChecks(check, warn, s.claude, s.claudeErr, s.parent)
	if fi, err := os.Stat(t.socket); err != nil {
		check(false, "socket %s: %s", t.socket, unreachable(err, t.socket))
	} else if fi.Mode()&fs.ModeSocket == 0 {
		check(false, "socket %s is not a socket", t.socket)
	} else {
		check(true, "socket %s", t.socket)
	}

	c, err := dial(t)
	if err != nil {
		check(false, "listener: %s", unreachable(err, t.socket))
		check(false, "plugin list_tools: not tried without a trusted listener")
		check(false, "tool lists match: not compared")
		return result()
	}
	defer func() { _ = c.conn.Close() }()
	exe, _ := os.Readlink(fmt.Sprintf("/proc/%d/exe", c.pid))
	check(true, "listener pid %d is %+q, trusted", c.pid, exe)
	plugin, err := c.listTools()
	if err != nil {
		check(false, "plugin list_tools: %v%s", err, gateHint())
		check(false, "tool lists match: not compared")
		return result()
	}
	check(true, "plugin list_tools: %d tools", len(plugin))
	if diff := sameTools(tools, plugin); diff != "" {
		check(false, "tool lists differ (%s): the bridge and plugin are different versions; update them together", diff)
	} else {
		check(true, "tool lists match (names and schemas)")
	}
	return result()
}

// gateHint explains a refusal the plugin's peer gate gives any bridge that Claude Code didn't start.
func gateHint() string {
	parent, _ := os.Readlink(fmt.Sprintf("/proc/%d/exe", os.Getppid()))
	if filepath.Base(parent) == "claude" {
		return ""
	}
	return fmt.Sprintf(" (the plugin admits the bridge only when Claude Code started it; this one's parent is %+q)", parent)
}

func asciiOnly(s string) string {
	return strings.Map(func(r rune) rune {
		if r < 0x20 || r > 0x7e {
			return '?'
		}
		return r
	}, s)
}
