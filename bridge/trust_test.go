package main

import (
	"bytes"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"syscall"
	"testing"
)

var me = uint32(os.Getuid())

// trustDir makes a directory holding a trusted-ide-dirs file with the given body and modes.
func trustDir(t *testing.T, body string, dirMode, fileMode os.FileMode) string {
	t.Helper()
	dir := filepath.Join(scratchDir(t), "idelink")
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	config := filepath.Join(dir, "trusted-ide-dirs")
	if err := os.WriteFile(config, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	must(t, os.Chmod(config, fileMode))
	must(t, os.Chmod(dir, dirMode))
	t.Cleanup(func() { _ = os.Chmod(dir, 0o700) })
	return config
}

func TestTheTrustConfigIsParsed(t *testing.T) {
	config := trustDir(t, "# IDEs\n/opt/jb/\n\n  /srv/ide  \n# end\n", 0o755, 0o644)
	tr, err := loadTrust(config, me, "/home/x")
	if err != nil {
		t.Fatal(err)
	}
	if !tr.loaded || !slices.Equal(tr.dirs, []string{"/opt/jb", "/srv/ide"}) {
		t.Fatalf("got %+v", tr)
	}
}

func TestAnAbsentConfigTrustsTheDefault(t *testing.T) {
	missing := filepath.Join(scratchDir(t), "trusted-ide-dirs")
	for home, want := range map[string][]string{
		"/home/x":  {"/home/x/.cache/JetBrains/RemoteDev/dist"},
		"":         nil,
		"relative": nil,
	} {
		tr, err := loadTrust(missing, me, home)
		if err != nil || tr.loaded || !slices.Equal(tr.dirs, want) {
			t.Errorf("home %q: want %v, got %+v, %v", home, want, tr, err)
		}
	}
}

func TestAnUntrustedOrInvalidConfigIsRefused(t *testing.T) {
	cases := map[string]struct {
		body              string
		dirMode, fileMode os.FileMode
		root              uint32
		want              string
	}{
		"not root's":        {"/opt/jb\n", 0o755, 0o644, me + 1, "owned by uid"},
		"group-writable":    {"/opt/jb\n", 0o755, 0o664, me, "writable by group or others"},
		"other-writable":    {"/opt/jb\n", 0o755, 0o646, me, "writable by group or others"},
		"dir writable":      {"/opt/jb\n", 0o775, 0o644, me, "writable by group or others"},
		"relative":          {"opt/jb\n", 0o755, 0o644, me, "not an absolute path"},
		"tilde":             {"~/jb\n", 0o755, 0o644, me, "not an absolute path"},
		"root dir":          {"/\n", 0o755, 0o644, me, "would trust every directory"},
		"empty":             {"# nothing\n", 0o755, 0o644, me, "lists no directories"},
		"oversize":          {strings.Repeat("/x\n", maxTrustConfig), 0o755, 0o644, me, "over"},
		"unreadable parent": {"/opt/jb\n", 0o000, 0o644, me, "permission denied"},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			if name == "unreadable parent" && me == 0 {
				t.Skip("root ignores directory modes")
			}
			config := trustDir(t, c.body, c.dirMode, c.fileMode)
			if _, err := loadTrust(config, c.root, "/home/x"); err == nil || !strings.Contains(err.Error(), c.want) {
				t.Fatalf("want an error containing %q, got %v", c.want, err)
			}
		})
	}
}

func TestAConfigThatIsNotARegularFileIsRefused(t *testing.T) {
	real := trustDir(t, "/opt/jb\n", 0o755, 0o644)
	dir := filepath.Dir(real)
	link, fifo, sub := filepath.Join(dir, "link"), filepath.Join(dir, "fifo"), filepath.Join(dir, "sub")
	must(t, os.Symlink(real, link))
	if err := syscall.Mkfifo(fifo, 0o644); err != nil {
		t.Fatal(err)
	}
	must(t, os.Mkdir(sub, 0o755))
	for p, want := range map[string]string{link: "too many levels of symbolic links", fifo: "not a regular file", sub: "not a regular file"} {
		if _, err := loadTrust(p, me, "/home/x"); err == nil || !strings.Contains(err.Error(), want) {
			t.Errorf("%s: want an error containing %q, got %v", filepath.Base(p), want, err)
		}
	}
}

func TestARefusedConfigStopsTheBridge(t *testing.T) {
	var stderr bytes.Buffer
	config := trustDir(t, "/opt/jb\n", 0o755, 0o666)
	if code := run(nil, strings.NewReader(""), &bytes.Buffer{}, &stderr, env{tools: embeddedTools, socket: "/nonexistent", trustConfig: config, rootUID: me}); code != 1 ||
		!strings.Contains(stderr.String(), "refusing to start") {
		t.Fatalf("want exit 1, got %d %q", code, stderr.String())
	}
	config = trustDir(t, "/opt/jb\n", 0o755, 0o644)
	if code := run(nil, strings.NewReader(""), &bytes.Buffer{}, &stderr, env{tools: embeddedTools, socket: "/nonexistent", trustConfig: config, rootUID: me}); code != 0 {
		t.Fatalf("want a trusted config to start, got %d %q", code, stderr.String())
	}
}

func TestTrustedExeMatchesWholePathComponents(t *testing.T) {
	dirs := []string{"/opt/jb"}
	for exe, want := range map[string]bool{
		"/opt/jb/IU-263.1/bin/remote-dev-server": true,
		"/opt/jb/bin/remote-dev-server":          true,
		"/opt/jbevil/bin/remote-dev-server":      false,
		"/opt/jb/x/bin/remote-dev-server-evil":   false,
		"/opt/jb/x/remote-dev-server":            false,
		"/opt/jb/x/xbin/remote-dev-server":       false,
		"/opt/jb/x/bin/java":                     false,
		"/srv/opt/jb/bin/remote-dev-server":      false,
	} {
		if got := trustedExe(exe, dirs); got != want {
			t.Errorf("%s: want %v, got %v", exe, want, got)
		}
	}
}

// A real launcher in a Coder-style IU-<build>/ layout passes when its parent directory is configured.
func TestAConfiguredDirTrustsARealLauncher(t *testing.T) {
	base := scratchDir(t)
	p := filepath.Join(base, "jb/IU-263.1/bin/remote-dev-server")
	sleep, err := os.ReadFile("/bin/sleep")
	if err != nil {
		t.Fatal(err)
	}
	must(t, os.MkdirAll(filepath.Dir(p), 0o755))
	if err := os.WriteFile(p, sleep, 0o755); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(p, "30")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })
	tr, err := loadTrust(trustDir(t, filepath.Join(base, "jb")+"\n", 0o755, 0o644), me, "/home/x")
	if err != nil {
		t.Fatal(err)
	}
	if err := jetBrainsIDE(tr)(int32(cmd.Process.Pid)); err != nil {
		t.Fatalf("configured launcher refused: %v", err)
	}
	if err := jetBrainsIDE(trust{dirs: []string{base + "/j"}})(int32(cmd.Process.Pid)); err == nil {
		t.Fatal("launcher under a dir-name prefix accepted")
	}
}
