package main

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

const maxTrustConfig = 64 << 10

// trust is the set of directories an IDE listener's executable may live under.
type trust struct {
	dirs   []string
	config string
	loaded bool
}

func (tr trust) String() string {
	switch {
	case len(tr.dirs) == 0:
		return "no trusted IDE directories"
	case tr.loaded:
		return fmt.Sprintf("trusted IDE directories from %s: %s", tr.config, strings.Join(tr.dirs, ", "))
	}
	return fmt.Sprintf("trusted IDE directories by default (%s is absent): %s", tr.config, strings.Join(tr.dirs, ", "))
}

// loadTrust reads the trusted-dirs config (see readRootList). Without it, the default is home's RemoteDev dist
// directory.
func loadTrust(config string, rootUID uint32, home string) (trust, error) {
	tr := trust{config: config}
	entries, present, err := readRootList(config, rootUID, "directories")
	if err != nil {
		return tr, err
	}
	if !present {
		if filepath.IsAbs(home) {
			tr.dirs = []string{filepath.Join(home, ".cache/JetBrains/RemoteDev/dist")}
		}
		return tr, nil
	}
	for _, e := range entries {
		if e.path == "/" {
			return tr, fmt.Errorf("%s line %d: / would trust every directory", config, e.line)
		}
		tr.dirs = append(tr.dirs, e.path)
	}
	tr.loaded = true
	return tr, nil
}

type listEntry struct {
	line int
	path string
	dir  bool // written with a trailing slash
}

// readRootList reads a list of absolute paths, one per line with # comments, from config, which with its
// directory must be owned by rootUID and writable by no one else. present is false if config doesn't exist.
func readRootList(config string, rootUID uint32, noun string) (entries []listEntry, present bool, err error) {
	f, err := os.OpenFile(config, os.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_NONBLOCK, 0)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, false, nil
	}
	if err != nil {
		return nil, true, fmt.Errorf("can't open %s: %w", config, err)
	}
	defer func() { _ = f.Close() }()
	fi, err := f.Stat()
	if err != nil {
		return nil, true, err
	}
	if !fi.Mode().IsRegular() {
		return nil, true, fmt.Errorf("%s is not a regular file", config)
	}
	if err := rootOnly(fi, rootUID); err != nil {
		return nil, true, fmt.Errorf("%s %w", config, err)
	}
	parent := filepath.Dir(config)
	pi, err := os.Lstat(parent)
	if err != nil {
		return nil, true, err
	}
	if !pi.IsDir() {
		return nil, true, fmt.Errorf("%s is not a directory", parent)
	}
	if err := rootOnly(pi, rootUID); err != nil {
		return nil, true, fmt.Errorf("%s %w", parent, err)
	}
	body, err := io.ReadAll(io.LimitReader(f, maxTrustConfig+1))
	if err != nil {
		return nil, true, err
	}
	if len(body) > maxTrustConfig {
		return nil, true, fmt.Errorf("%s is over %d bytes", config, maxTrustConfig)
	}
	for i, line := range strings.Split(string(body), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if !filepath.IsAbs(line) {
			return nil, true, fmt.Errorf("%s line %d: %q is not an absolute path", config, i+1, line)
		}
		entries = append(entries, listEntry{i + 1, filepath.Clean(line), strings.HasSuffix(line, "/")})
	}
	if len(entries) == 0 {
		return nil, true, fmt.Errorf("%s lists no %s", config, noun)
	}
	return entries, true, nil
}

func rootOnly(fi fs.FileInfo, rootUID uint32) error {
	if uid := fi.Sys().(*syscall.Stat_t).Uid; uid != rootUID {
		return fmt.Errorf("is owned by uid %d, not root", uid)
	}
	if fi.Mode().Perm()&0o022 != 0 {
		return fmt.Errorf("is writable by group or others (%v)", fi.Mode().Perm())
	}
	return nil
}

// untrustedListener names what was found on the socket and what would have been trusted.
type untrustedListener struct {
	pid   int32
	exe   string
	trust trust
}

func (e *untrustedListener) Error() string {
	return fmt.Sprintf("the process on the IDE socket (pid %d, %+q) is not a trusted JetBrains RemoteDev IDE "+
		"(bin/remote-dev-server under a trusted directory; %s). If the IDE was moved or its layout changed, "+
		"list its directory in %s; if not, something other than the IDE is listening.", e.pid, e.exe, e.trust, e.trust.config)
}

// jetBrainsIDE accepts a listener running bin/remote-dev-server under a trusted directory. A heuristic: the
// default directory is user-writable, and the pid is read after connect, so it could have been reused.
func jetBrainsIDE(tr trust) func(int32) error {
	return func(pid int32) error {
		exe, err := os.Readlink(fmt.Sprintf("/proc/%d/exe", pid))
		if err != nil || !trustedExe(exe, tr.dirs) {
			return &untrustedListener{pid: pid, exe: exe, trust: tr}
		}
		return nil
	}
}

func trustedExe(exe string, dirs []string) bool {
	if filepath.Base(exe) != "remote-dev-server" || filepath.Base(filepath.Dir(exe)) != "bin" {
		return false
	}
	for _, d := range dirs {
		if strings.HasPrefix(exe, d+"/") {
			return true
		}
	}
	return false
}
