package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

const defaultClaude = "/usr/bin/claude"

// claudes is the set of binaries the plugin accepts as a bridge's parent, matched against the parent's resolved
// executable: exactly, or for a path ending in / any file directly inside that directory.
type claudes struct {
	paths  []string
	config string
	loaded bool
}

func (c claudes) String() string {
	if c.loaded {
		return fmt.Sprintf("trusted Claude binaries from %s: %s", c.config, strings.Join(c.paths, ", "))
	}
	return fmt.Sprintf("trusted Claude binary by default (%s is absent): %s", c.config, strings.Join(c.paths, ", "))
}

// loadClaudes reads the trusted-claude config with the same rules as trusted-ide-dirs.
func loadClaudes(config string, rootUID uint32) (claudes, error) {
	c := claudes{config: config}
	entries, present, err := readRootList(config, rootUID, "paths")
	if err != nil {
		return c, err
	}
	if !present {
		c.paths = []string{defaultClaude}
		return c, nil
	}
	for _, e := range entries {
		if e.dir && e.path != "/" {
			e.path += "/"
		}
		c.paths = append(c.paths, e.path)
	}
	c.loaded = true
	return c, nil
}

func resolved(p string) string {
	r, err := filepath.EvalSymlinks(p)
	if err != nil {
		return p
	}
	return r
}

func (c claudes) trusts(exe string) bool {
	for _, p := range c.paths {
		if p == exe || isDirEntry(p) && filepath.Dir(exe) == dirOf(p) {
			return true
		}
	}
	return false
}

func isDirEntry(p string) bool { return strings.HasSuffix(p, "/") }

// dirOf is directory entry p without its marking slash.
func dirOf(p string) string {
	if p == "/" {
		return p
	}
	return strings.TrimSuffix(p, "/")
}

// userWritable says why p could be replaced by a non-root user (it or a directory above it isn't root-only),
// or "" if it can't.
func userWritable(p string) string {
	for q := p; ; q = filepath.Dir(q) {
		fi, err := os.Lstat(q)
		if err != nil {
			return err.Error()
		}
		if err := rootOnly(fi, 0); err != nil {
			return fmt.Sprintf("%s %v", q, err)
		}
		if q == "/" {
			return ""
		}
	}
}

// claudeChecks reports each trusted claude path and whether the doctor's own parent would be trusted.
func claudeChecks(check func(ok bool, format string, a ...any), warn func(format string, a ...any), c claudes,
	err error, parent string) {
	if err != nil {
		check(false, "claude config: %v (the plugin refuses every bridge until it's fixed or removed)", err)
		return
	}
	check(true, "%s", c)
	for _, p := range c.paths {
		dir := isDirEntry(p)
		target := p
		if dir {
			target = dirOf(p)
		}
		fi, err := os.Lstat(target)
		switch {
		case err != nil:
			check(false, "claude %s: %v", p, err)
		case resolved(target) != target:
			check(false, "claude %s is not a resolved path; the plugin matches the resolved binary, so list %s instead",
				p, resolved(target)+strings.Repeat("/", len(p)-len(target)))
		case dir && !fi.IsDir():
			check(false, "claude %s is not a directory", p)
		case !dir && !fi.Mode().IsRegular():
			check(false, "claude %s is not a regular file", p)
		default:
			why := userWritable(target)
			owner := fi.Sys().(*syscall.Stat_t).Uid
			switch {
			case why == "":
				check(true, "claude %s: exists, root-owned, not user-writable", p)
			case !c.loaded:
				check(false, "claude %s is user-writable (%s); the default must be root-owned, or list it in %s",
					p, why, c.config)
			default:
				warn("claude %s: exists, owner uid %d, user-writable (%s): anything running as you can replace "+
					"it, so pairing is the only protection", p, owner, why)
			}
		}
	}
	name := filepath.Base(parent)
	if c.trusts(parent) {
		check(true, "parent is %s (%s), a trusted Claude binary (doctor is always list-only)", name, parent)
	} else {
		check(true, "parent is %s (%s), not a trusted Claude binary (doctor is always list-only)", name, parent)
	}
}
