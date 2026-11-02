package main

import (
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

// trustedClaude makes a trusted-claude config with the given body and modes.
func trustedClaude(t *testing.T, body string, dirMode, fileMode os.FileMode) string {
	t.Helper()
	ide := trustDir(t, "/opt/jb\n", 0o755, 0o644)
	config := filepath.Join(filepath.Dir(ide), "trusted-claude")
	must(t, os.Chmod(filepath.Dir(ide), 0o700))
	if err := os.WriteFile(config, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	must(t, os.Chmod(config, fileMode))
	must(t, os.Chmod(filepath.Dir(ide), dirMode))
	return config
}

func TestTheClaudeConfigIsParsedAndAbsentMeansTheDefault(t *testing.T) {
	c, err := loadClaudes(trustedClaude(t, "# per-user\n /home/u/.local/bin/claude \n/usr/bin/claude\n", 0o755, 0o644), me)
	if err != nil || !c.loaded || !slices.Equal(c.paths, []string{"/home/u/.local/bin/claude", "/usr/bin/claude"}) {
		t.Fatalf("got %+v, %v", c, err)
	}
	c, err = loadClaudes(filepath.Join(scratchDir(t), "trusted-claude"), me)
	if err != nil || c.loaded || !slices.Equal(c.paths, []string{defaultClaude}) {
		t.Fatalf("absent config: got %+v, %v", c, err)
	}
}

func TestAnUntrustedClaudeConfigIsAnError(t *testing.T) {
	cases := map[string]struct {
		body              string
		dirMode, fileMode os.FileMode
		root              uint32
		want              string
	}{
		"not root's":     {"/usr/bin/claude\n", 0o755, 0o644, me + 1, "owned by uid"},
		"group-writable": {"/usr/bin/claude\n", 0o755, 0o664, me, "writable by group or others"},
		"dir writable":   {"/usr/bin/claude\n", 0o757, 0o644, me, "writable by group or others"},
		"relative":       {"bin/claude\n", 0o755, 0o644, me, "not an absolute path"},
		"empty":          {"# none\n", 0o755, 0o644, me, "lists no paths"},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := loadClaudes(trustedClaude(t, c.body, c.dirMode, c.fileMode), c.root); err == nil ||
				!strings.Contains(err.Error(), c.want) {
				t.Fatalf("want an error containing %q, got %v", c.want, err)
			}
		})
	}
}

func TestClaudePathsMatchExactly(t *testing.T) {
	c := claudes{paths: []string{"/home/u/.local/bin/claude"}}
	for exe, want := range map[string]bool{
		"/home/u/.local/bin/claude":      true,
		"/home/u/.local/bin/claude-evil": false,
		"/home/u/.local/bin":             false,
		"/home/u/.local/bin/claude/x":    false,
	} {
		if got := c.trusts(exe); got != want {
			t.Errorf("%s: want %v, got %v", exe, want, got)
		}
	}
}

func TestATrailingSlashMakesADirectoryEntry(t *testing.T) {
	c, err := loadClaudes(trustedClaude(t, "/a/versions/\n/a//b/./c//\n/a/file\n/a/dot/.\n", 0o755, 0o644), me)
	want := []string{"/a/versions/", "/a/b/c/", "/a/file", "/a/dot"}
	if err != nil || !slices.Equal(c.paths, want) {
		t.Fatalf("want %v, got %+v, %v", want, c.paths, err)
	}
}

func TestADirectoryEntryMatchesOnlyFilesDirectlyInside(t *testing.T) {
	c := claudes{paths: []string{"/a/versions/", "/a/exact"}}
	for exe, want := range map[string]bool{
		"/a/versions/2.1.0":  true,
		"/a/exact":           true,
		"/a/versions-evil/x": false,
		"/a/versionsx":       false,
		"/a/versions/sub/x":  false,
		"/a/versions":        false,
		"/a/x":               false,
		"/a/exact/x":         false,
	} {
		if got := c.trusts(exe); got != want {
			t.Errorf("%s: want %v, got %v", exe, want, got)
		}
	}
	if (claudes{paths: []string{"/a/versions"}}).trusts("/a/versions/2.1.0") {
		t.Error("without the trailing slash a directory is an exact path")
	}
}

func TestDoctorFlagsAUserWritableDirectoryEntry(t *testing.T) {
	dir := filepath.Join(scratchDir(t), "versions")
	must(t, os.Mkdir(dir, 0o755))
	tg := target{socket: "/nonexistent/ide.sock", checkListener: func(int32) error { return nil }}
	file := filepath.Join(dir, "2.1.0")
	must(t, os.WriteFile(file, []byte("x"), 0o755))
	listed := claudes{paths: []string{dir + "/", "/usr/bin/", file + "/"}, config: "/etc/idelink/trusted-claude",
		loaded: true}
	_, lines := doctorSetup(t, tg, setup{ide: configured, claude: listed, parent: file})
	for sub, want := range map[string]string{
		"claude " + dir + "/: exists":                      "⚠ ",
		"claude /usr/bin/: exists, root-owned":             "✓ ",
		"claude " + file + "/ is not a directory":          "✗ ",
		"parent is 2.1.0 (" + file + "), a trusted Claude": "✓ ",
	} {
		if l := lineWith(lines, sub); !strings.HasPrefix(l, want) {
			t.Errorf("want a %q line containing %q, got:\n%s", want, sub, strings.Join(lines, "\n"))
		}
	}
	if l := lineWith(lines, dir+"/: exists"); !strings.Contains(l, "pairing is the only protection") {
		t.Errorf("the directory warning lacks the tradeoff: %q", l)
	}
}

func lineWith(lines []string, sub string) string {
	for _, l := range lines {
		if strings.Contains(l, sub) {
			return l
		}
	}
	return ""
}

func TestDoctorReportsEachClaudeAndItsParent(t *testing.T) {
	dir := scratchDir(t)
	mine := filepath.Join(dir, "claude")
	if err := os.WriteFile(mine, []byte("x"), 0o755); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "claude-link")
	must(t, os.Symlink(mine, link))
	missing := filepath.Join(dir, "gone")
	tg := target{socket: "/nonexistent/ide.sock", checkListener: func(int32) error { return nil }}

	listed := claudes{paths: []string{"/usr/bin/env", mine, link, missing}, config: "/etc/idelink/trusted-claude", loaded: true}
	_, lines := doctorSetup(t, tg, setup{ide: configured, claude: listed, parent: "/usr/bin/bash"})
	for sub, want := range map[string]string{
		"claude /usr/bin/env: exists, root-owned, not user-writable": "✓ ",
		"claude " + mine + ": exists":                                "⚠ ",
		"claude " + link + " is not a resolved path":                 "✗ ",
		"claude " + missing + ":":                                    "✗ ",
		"parent is bash (/usr/bin/bash), not a trusted Claude":       "✓ ",
	} {
		if l := lineWith(lines, sub); !strings.HasPrefix(l, want) {
			t.Errorf("want a %q line containing %q, got:\n%s", want, sub, strings.Join(lines, "\n"))
		}
	}
	if l := lineWith(lines, mine+": exists"); !strings.Contains(l, "user-writable") ||
		!strings.Contains(l, "pairing is the only protection") {
		t.Errorf("the user-writable warning lacks the tradeoff: %q", l)
	}
	if l := lineWith(lines, link+" is not"); !strings.Contains(l, "list "+mine+" instead") {
		t.Errorf("the symlink line doesn't name the resolved binary: %q", l)
	}

	_, lines = doctorSetup(t, tg, setup{ide: configured, claude: listed, parent: mine})
	if l := lineWith(lines, "parent is claude"); !strings.HasPrefix(l, "✓ ") || !strings.Contains(l, "a trusted Claude binary (doctor is always list-only)") {
		t.Errorf("want the trusted parent reported, got:\n%s", strings.Join(lines, "\n"))
	}
}

func TestDoctorFailsAUserWritableDefaultClaude(t *testing.T) {
	mine := filepath.Join(scratchDir(t), "claude")
	must(t, os.WriteFile(mine, []byte("x"), 0o755))
	tg := target{socket: "/nonexistent/ide.sock", checkListener: func(int32) error { return nil }}
	def := claudes{paths: []string{mine}, config: "/etc/idelink/trusted-claude"}
	_, lines := doctorSetup(t, tg, setup{ide: configured, claude: def, parent: "/usr/bin/bash"})
	if l := lineWith(lines, "claude "+mine); !strings.HasPrefix(l, "✗ ") || !strings.Contains(l, "the default must be root-owned") {
		t.Errorf("want the default refused, got:\n%s", strings.Join(lines, "\n"))
	}
}

func TestDoctorReportsAnUntrustedClaudeConfig(t *testing.T) {
	tg := target{socket: "/nonexistent/ide.sock", checkListener: func(int32) error { return nil }}
	config := trustedClaude(t, "/usr/bin/claude\n", 0o755, 0o666)
	cl, err := loadClaudes(config, me)
	code, lines := doctorSetup(t, tg, setup{ide: configured, claude: cl, claudeErr: err, parent: "/usr/bin/bash"})
	if l := lineWith(lines, "claude config: "+config+" is writable by group or others"); code != 1 || !strings.HasPrefix(l, "✗ ") {
		t.Errorf("want the claude config refused, got %d:\n%s", code, strings.Join(lines, "\n"))
	}
}

func TestAUserWritableWarningAloneDoesNotFailTheDoctor(t *testing.T) {
	var failed bool
	check := func(ok bool, _ string, _ ...any) { failed = failed || !ok }
	var warned int
	warn := func(string, ...any) { warned++ }
	mine := filepath.Join(scratchDir(t), "claude")
	must(t, os.WriteFile(mine, []byte("x"), 0o755))
	claudeChecks(check, warn, claudes{paths: []string{mine}, loaded: true}, nil, mine)
	if failed || warned != 1 {
		t.Fatalf("want one warning and no failure, got failed=%v warned=%d", failed, warned)
	}
}
