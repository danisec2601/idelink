package main

import (
	"bytes"
	"os"
	"strings"
	"testing"
)

var configured = trust{dirs: []string{"/opt/jb"}, config: "/etc/idelink/trusted-ide-dirs", loaded: true}

// noClaudes adds two lines to the doctor's output: the claude config and the parent.
var noClaudes = claudes{config: "/etc/idelink/trusted-claude", loaded: true}

// doctorLines runs the doctor and checks every line is a mark and ASCII.
func doctorLines(t *testing.T, tg target, tr trust, trustErr error) (int, []string) {
	t.Helper()
	return doctorSetup(t, tg, setup{ide: tr, ideErr: trustErr, claude: noClaudes, parent: "/usr/bin/bash"})
}

func doctorSetup(t *testing.T, tg target, s setup) (int, []string) {
	t.Helper()
	var out bytes.Buffer
	code := doctor(&out, tg, s, embedded(t))
	lines := strings.Split(strings.TrimSuffix(out.String(), "\n"), "\n")
	for _, l := range lines {
		rest, ok := strings.CutPrefix(l, "✓ ")
		if !ok {
			rest, ok = strings.CutPrefix(l, "✗ ")
		}
		if !ok {
			rest, ok = strings.CutPrefix(l, "⚠ ")
		}
		if !ok || asciiOnly(rest) != rest {
			t.Fatalf("bad doctor line %q", l)
		}
	}
	return code, lines
}

func TestDoctorPassesAgainstAMatchingPluginWithoutCallingTools(t *testing.T) {
	tg, stats := fakePlugin(t, string(embeddedTools))
	code, lines := doctorLines(t, tg, configured, nil)
	if code != 0 || len(lines) != 8 || strings.Contains(strings.Join(lines, "\n"), "✗") {
		t.Fatalf("want eight passing checks, got %d:\n%s", code, strings.Join(lines, "\n"))
	}
	if !strings.Contains(lines[0], version) || !strings.Contains(lines[5], "listener pid") {
		t.Fatalf("missing version or listener:\n%s", strings.Join(lines, "\n"))
	}
	if n := stats.calls.Load(); n != 0 {
		t.Fatalf("doctor sent %d tool calls; only list_tools is pre-pairing", n)
	}
}

func TestDoctorReportsVersionSkew(t *testing.T) {
	tg, _ := fakePlugin(t, goodList)
	code, lines := doctorLines(t, tg, configured, nil)
	if code != 1 || !strings.HasPrefix(lines[len(lines)-1], "✗ tool lists differ (") {
		t.Fatalf("want skew reported, got %d:\n%s", code, strings.Join(lines, "\n"))
	}
}

func TestDoctorReportsNoIDE(t *testing.T) {
	tg := target{socket: "/nonexistent/idelink/ide.sock", checkListener: func(int32) error { return nil }}
	code, lines := doctorLines(t, tg, configured, nil)
	if code != 1 || !strings.HasPrefix(lines[4], "✗ socket") || !strings.Contains(lines[4], "isn't running") || len(lines) != 8 {
		t.Fatalf("want a missing socket reported, got %d:\n%s", code, strings.Join(lines, "\n"))
	}
}

func TestDoctorNamesAnUntrustedListener(t *testing.T) {
	tg, _ := fakePlugin(t, string(embeddedTools))
	tg.checkListener = jetBrainsIDE(configured)
	exe, _ := os.Executable()
	code, lines := doctorLines(t, tg, configured, nil)
	if code != 1 || !strings.HasPrefix(lines[5], "✗ listener") || !strings.Contains(lines[5], exe) ||
		!strings.Contains(lines[5], "/opt/jb") {
		t.Fatalf("want the listener's exe and trusted dirs, got %d:\n%s", code, strings.Join(lines, "\n"))
	}
}

func TestDoctorReportsAnUntrustedConfigInsteadOfExiting(t *testing.T) {
	var out, stderr bytes.Buffer
	config := trustDir(t, "/opt/jb\n", 0o755, 0o666)
	code := run([]string{"--doctor"}, strings.NewReader(""), &out, &stderr,
		env{tools: embeddedTools, socket: "/nonexistent/ide.sock", trustConfig: config, rootUID: me})
	if code != 1 || !strings.Contains(out.String(), "✗ trust config: "+config+" is writable by group or others") {
		t.Fatalf("want the config refusal as a check, got %d:\n%s%s", code, out.String(), stderr.String())
	}
}

func TestUnknownArgumentsAreAUsageError(t *testing.T) {
	var stderr bytes.Buffer
	if code := run([]string{"--doctr"}, strings.NewReader(""), &bytes.Buffer{}, &stderr, env{tools: embeddedTools}); code != 2 {
		t.Fatalf("want exit 2, got %d", code)
	}
}

func TestASCIIOnlyReplacesEverythingElse(t *testing.T) {
	if got := asciiOnly("a\u202eb\nc\x1bé"); got != "a?b?c??" {
		t.Fatalf("got %q", got)
	}
}
