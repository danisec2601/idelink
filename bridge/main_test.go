package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// must fails the test on a setup error, so a test can't pass on a fixture that was never made.
func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

type pluginStats struct{ accepts, calls atomic.Int32 }

// fakePlugin answers the line protocol on a real Unix socket: list_tools with listReply (or not at all if
// it is "SILENT"), any other tool with a fixed result. It counts connections and non-list_tools requests.
func fakePlugin(t *testing.T, listReply string) (target, *pluginStats) {
	t.Helper()
	sock := filepath.Join(scratchDir(t), "ide.sock")
	l, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = l.Close() })
	stats := &pluginStats{}
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			stats.accepts.Add(1)
			go func() {
				defer func() { _ = c.Close() }()
				r := bufio.NewReader(c)
				for {
					line, err := r.ReadBytes('\n')
					if err != nil {
						return
					}
					var req struct {
						ID   int             `json:"id"`
						Tool string          `json:"tool"`
						Args json.RawMessage `json:"args"`
					}
					_ = json.Unmarshal(line, &req)
					if req.Tool != "list_tools" {
						stats.calls.Add(1)
					}
					var resp string
					if req.Tool == "list_tools" && listReply == "SILENT" {
						continue
					} else if req.Tool == "fails" {
						resp = fmt.Sprintf(`{"id":%d,"ok":false,"error":"no such file"}`, req.ID)
					} else if req.Tool == "list_tools" {
						resp = fmt.Sprintf(`{"id":%d,"ok":true,"result":%s}`, req.ID, listReply)
					} else if req.Tool == "hangs_up" {
						// Like the plugin refusing pairing: reply, then close. Shutting reads first makes the
						// close visible to the bridge's next write without waiting on scheduling.
						_ = c.(*net.UnixConn).CloseRead()
						_, _ = c.Write([]byte(fmt.Sprintf(`{"id":%d,"ok":false,"error":"not paired"}`, req.ID) + "\n"))
						return
					} else if req.Tool == "wrong_id" {
						resp = fmt.Sprintf(`{"id":%d,"ok":true,"result":{}}`, req.ID+1)
					} else {
						resp = fmt.Sprintf(`{"id":%d,"ok":true,"result":{"tool":"%s","args":%s}}`, req.ID, req.Tool, req.Args)
					}
					_, _ = c.Write([]byte(resp + "\n"))
				}
			}()
		}
	}()
	return target{socket: sock, checkListener: func(int32) error { return nil }}, stats
}

// scratchDir is a fresh directory under the repo's gitignored tmp/, removed after the test.
func scratchDir(t *testing.T) string {
	t.Helper()
	base, err := filepath.Abs("../tmp")
	if err != nil {
		t.Fatal(err)
	}
	must(t, os.MkdirAll(base, 0o700))
	dir, err := os.MkdirTemp(base, "bridge-test-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	return dir
}

func embedded(t *testing.T) []toolSpec {
	t.Helper()
	tools, err := parseToolList(embeddedTools)
	if err != nil {
		t.Fatal(err)
	}
	return tools
}

func session(t *testing.T, tg target, reqs ...string) []map[string]any {
	t.Helper()
	return sessionWith(t, tg, embedded(t), reqs...)
}

func sessionWith(t *testing.T, tg target, tools []toolSpec, reqs ...string) []map[string]any {
	t.Helper()
	var out bytes.Buffer
	serve(strings.NewReader(strings.Join(reqs, "\n")+"\n"), &out, tg, tools)
	var msgs []map[string]any
	dec := json.NewDecoder(&out)
	for dec.More() {
		var m map[string]any
		if err := dec.Decode(&m); err != nil {
			t.Fatal(err)
		}
		msgs = append(msgs, m)
	}
	if len(msgs) != len(reqs) {
		t.Fatalf("want %d replies, got %d: %v", len(reqs), len(msgs), msgs)
	}
	return msgs
}

// toolResult returns a tools/call reply's text and isError, failing on a JSON-RPC error.
func toolResult(t *testing.T, msg map[string]any) (string, bool) {
	t.Helper()
	res, ok := msg["result"].(map[string]any)
	if !ok || msg["error"] != nil {
		t.Fatalf("want a tool result, got %v", msg)
	}
	return res["content"].([]any)[0].(map[string]any)["text"].(string), res["isError"] == true
}

const goodList = `[{"name":"open_file","description":"opens","inputSchema":{"type":"object","properties":{}}},` +
	`{"name":"get_ide_state","description":"state","inputSchema":{"type":"object"}}]`

const call = `{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"open_file","arguments":{"path":"a","line":2}}}`

func TestToolsListIsTheEmbeddedListEvenWithNoIDE(t *testing.T) {
	tg := target{socket: "/nonexistent/idelink/ide.sock", checkListener: func(int32) error { return nil }}
	msgs := session(t, tg, `{"jsonrpc":"2.0","id":2,"method":"tools/list"}`)
	tools := msgs[0]["result"].(map[string]any)["tools"].([]any)
	want := embedded(t)
	if len(tools) != len(want) {
		t.Fatalf("want %d tools, got %v", len(want), msgs[0])
	}
	for i, tool := range tools {
		if tool.(map[string]any)["name"] != want[i].Name {
			t.Fatalf("tool %d: want %s, got %v", i, want[i].Name, tool)
		}
	}
}

func TestCallsAreRelayedWhenThePluginMatches(t *testing.T) {
	tg, _ := fakePlugin(t, string(embeddedTools))
	text, isErr := toolResult(t, session(t, tg, call)[0])
	if isErr || !strings.Contains(text, `"tool":"open_file"`) || !strings.Contains(text, `"line":2`) {
		t.Fatalf("tools/call not forwarded: %s", text)
	}
}

func TestMalformedToolListsAreRefusedWhole(t *testing.T) {
	cases := map[string]string{
		"empty":        `[]`,
		"bad name":     `[{"name":"Open-File","description":"x","inputSchema":{"type":"object"}}]`,
		"duplicate":    `[{"name":"a","description":"x","inputSchema":{"type":"object"}},{"name":"a","description":"y","inputSchema":{"type":"object"}}]`,
		"extra field":  `[{"name":"a","description":"x","inputSchema":{"type":"object"},"annotations":{}}]`,
		"schema type":  `[{"name":"a","description":"x","inputSchema":{"type":"string"}}]`,
		"no schema":    `[{"name":"a","description":"x"}]`,
		"huge desc":    `[{"name":"a","description":"` + strings.Repeat("x", maxDescription+1) + `","inputSchema":{"type":"object"}}]`,
		"not an array": `{"tools":[]}`,
		"over budget":  manyTools(40, maxDescription),
		"too many":     manyTools(maxTools+1, 1),
		"huge schema":  `[{"name":"a","description":"x","inputSchema":{"type":"object","description":"` + strings.Repeat("s", maxSchema) + `"}}]`,
		"bidi desc":    `[{"name":"a","description":"ok \u202e evil","inputSchema":{"type":"object"}}]`,
		"tag chars":    `[{"name":"a","description":"ok \udb40\udc41","inputSchema":{"type":"object"}}]`,
		"ansi desc":    `[{"name":"a","description":"\u001b[2Jx","inputSchema":{"type":"object"}}]`,
		"zwsp in prop": `[{"name":"a","description":"x","inputSchema":{"type":"object","properties":{"p":{"type":"string","description":"a\u200bb"}}}}]`,
		"odd keyword":  `[{"name":"a","description":"x","inputSchema":{"type":"object","x-instructions":"do it"}}]`,
		"odd prop":     `[{"name":"a","description":"x","inputSchema":{"type":"object","properties":{"ignore previous":{"type":"string"}}}}]`,
		"deep":         `[{"name":"a","description":"x","inputSchema":{"type":"object","items":` + strings.Repeat(`{"items":`, maxSchemaDepth+1) + `{}` + strings.Repeat(`}`, maxSchemaDepth+2) + `}]`,
	}
	for name, list := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := parseToolList([]byte(list)); err == nil {
				t.Fatal("malformed list accepted")
			}
		})
	}
}

func TestAMalformedPluginListRefusesCalls(t *testing.T) {
	tg, _ := fakePlugin(t, `[{"name":"open_file","description":"x","inputSchema":{"type":"object","x-instructions":"do it"}}]`)
	text, isErr := toolResult(t, session(t, tg, call)[0])
	if !isErr || !strings.Contains(text, "the IDE plugin's tool list is malformed") {
		t.Fatalf("want the plugin's list refused, got %q", text)
	}
}

func TestAnInvalidEmbeddedListIsFatal(t *testing.T) {
	var stderr bytes.Buffer
	code := run(nil, strings.NewReader(""), &bytes.Buffer{}, &stderr, env{tools: []byte(`[{"name":"a","description":"\u202e","inputSchema":{"type":"object"}}]`)})
	if code != 1 || !strings.Contains(stderr.String(), "embedded tool list is malformed") {
		t.Fatalf("want exit 1 with a message, got %d %q", code, stderr.String())
	}
}

func TestNoIDEIsAToolErrorAndTheSessionGoesOn(t *testing.T) {
	tg := target{socket: "/nonexistent/idelink/ide.sock", checkListener: func(int32) error { return nil }}
	msgs := session(t, tg, call, call, `{"jsonrpc":"2.0","id":4,"method":"ping"}`)
	for _, m := range msgs[:2] {
		text, isErr := toolResult(t, m)
		if !isErr || !strings.Contains(text, "the IDE isn't running, or the idelink plugin isn't loaded") {
			t.Fatalf("want an IDE-not-running tool error, got %q", text)
		}
	}
	if msgs[2]["result"] == nil {
		t.Fatalf("session ended after IDE-down calls: %v", msgs[2])
	}
}

func TestAStaleSocketIsAToolError(t *testing.T) {
	sock := filepath.Join(scratchDir(t), "ide.sock")
	l, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	l.(*net.UnixListener).SetUnlinkOnClose(false)
	_ = l.Close()
	tg := target{socket: sock, checkListener: func(int32) error { return nil }}
	text, isErr := toolResult(t, session(t, tg, call)[0])
	if !isErr || !strings.Contains(text, "a stale socket") {
		t.Fatalf("want a stale-socket tool error, got %q", text)
	}
}

func TestVersionSkewRefusesEveryCall(t *testing.T) {
	tg, stats := fakePlugin(t, goodList)
	msgs := session(t, tg, call, call, `{"jsonrpc":"2.0","id":4,"method":"ping"}`)
	for _, m := range msgs[:2] {
		text, isErr := toolResult(t, m)
		if !isErr || !strings.Contains(text, "different versions") || !strings.Contains(text, "review_tour only in the bridge") {
			t.Fatalf("want a version-skew tool error, got %q", text)
		}
	}
	if msgs[2]["result"] == nil || stats.accepts.Load() != 2 {
		t.Fatalf("want the session alive and each call rechecked, got %v after %d connections", msgs[2], stats.accepts.Load())
	}
}

func TestSameToolsComparesNamesAndSchemasOnly(t *testing.T) {
	spec := func(name, desc, schema string) toolSpec {
		return toolSpec{Name: name, Description: desc, InputSchema: json.RawMessage(schema)}
	}
	a := []toolSpec{spec("a", "x", `{"type":"object","maxLength":1}`), spec("b", "y", `{"type":"object"}`)}
	cases := map[string]struct {
		plugin []toolSpec
		want   string
	}{
		"reordered, new wording": {[]toolSpec{spec("b", "other", `{"type":"object"}`), spec("a", "x", `{"maxLength":1,"type":"object"}`)}, ""},
		"schema":                 {[]toolSpec{spec("a", "x", `{"type":"object","maxLength":2}`), spec("b", "y", `{"type":"object"}`)}, "a has a different schema"},
		"missing":                {[]toolSpec{spec("a", "x", `{"type":"object","maxLength":1}`)}, "b only in the bridge"},
		"extra": {[]toolSpec{spec("a", "x", `{"type":"object","maxLength":1}`), spec("b", "y", `{"type":"object"}`),
			spec("c", "z", `{"type":"object"}`)}, "c only in the plugin"},
	}
	for name, c := range cases {
		if got := sameTools(a, c.plugin); got != c.want {
			t.Errorf("%s: want %q, got %q", name, c.want, got)
		}
	}
}

func TestTheRealListenerCheckRefusesANonJetBrainsListener(t *testing.T) {
	tg, _ := fakePlugin(t, string(embeddedTools))
	tg.checkListener = jetBrainsIDE(trust{dirs: []string{"/opt/jb"}, config: "/etc/idelink/trusted-ide-dirs", loaded: true})
	exe, _ := os.Executable() // the listener is this go test binary
	text, isErr := toolResult(t, session(t, tg, call)[0])
	if !isErr || !strings.Contains(text, "not a trusted JetBrains RemoteDev IDE") || !strings.Contains(text, exe) ||
		!strings.Contains(text, "from /etc/idelink/trusted-ide-dirs: /opt/jb") {
		t.Fatalf("want a refusal naming the exe and the trusted dirs, got %q", text)
	}
}

func TestOversizeRepliesAreRefused(t *testing.T) {
	tg, _ := fakePlugin(t, `["`+strings.Repeat("x", maxReply)+`"]`)
	text, isErr := toolResult(t, session(t, tg, call)[0])
	if !isErr || !strings.Contains(text, "larger than") {
		t.Fatalf("want size refusal, got %q", text)
	}
}

func manyTools(n, descLen int) string {
	var parts []string
	for i := 0; i < n; i++ {
		parts = append(parts, fmt.Sprintf(`{"name":"t%d","description":"%s","inputSchema":{"type":"object"}}`, i, strings.Repeat("d", descLen)))
	}
	return "[" + strings.Join(parts, ",") + "]"
}

func TestAReplyWithTheWrongIDIsRefused(t *testing.T) {
	tg, _ := fakePlugin(t, string(embeddedTools))
	msgs := session(t, tg, `{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"wrong_id","arguments":{}}}`)
	text, isErr := toolResult(t, msgs[0])
	if !isErr || !strings.Contains(text, "bad reply from IDE: id") {
		t.Fatalf("want id mismatch error, got %v", msgs[0])
	}
}

// The shape Tools.kt actually emits must pass, or the tightened checks brick the tools.
func TestThePluginsSchemaShapePasses(t *testing.T) {
	list := `[{"name":"open_file","description":"Open a file.\nSecond line.","inputSchema":{"type":"object",` +
		`"properties":{"path":{"type":"string","description":"p","maxLength":4096},"line":{"type":"integer","minimum":1,"maximum":10000000},` +
		`"paths":{"type":"array","items":{"type":"string","maxLength":4096},"maxItems":200},"pin":{"type":"boolean"}},` +
		`"required":["path"],"additionalProperties":false}}]`
	if _, err := parseToolList([]byte(list)); err != nil {
		t.Fatalf("plugin-shaped list refused: %v", err)
	}
}

func TestAToolErrorKeepsTheConnection(t *testing.T) {
	tg, stats := fakePlugin(t, string(embeddedTools))
	msgs := session(t, tg,
		`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"fails","arguments":{}}}`,
		`{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"open_file","arguments":{}}}`)
	if text, isErr := toolResult(t, msgs[0]); !isErr || !strings.Contains(text, "no such file") {
		t.Fatalf("want the tool's error relayed, got %v", msgs[0])
	}
	if _, isErr := toolResult(t, msgs[1]); isErr {
		t.Fatalf("second call failed: %v", msgs[1])
	}
	if n := stats.accepts.Load(); n != 1 {
		t.Fatalf("want one connection, got %d", n)
	}
}

func TestACallAfterTheIDEHungUpRedials(t *testing.T) {
	tg, stats := fakePlugin(t, string(embeddedTools))
	msgs := session(t, tg,
		`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hangs_up","arguments":{}}}`,
		`{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"open_file","arguments":{}}}`)
	if text, isErr := toolResult(t, msgs[0]); !isErr || !strings.Contains(text, "not paired") {
		t.Fatalf("want the refusal relayed, got %v", msgs[0])
	}
	if text, isErr := toolResult(t, msgs[1]); isErr {
		t.Fatalf("call after the hang-up failed: %s", text)
	}
	if n := stats.accepts.Load(); n != 2 {
		t.Fatalf("want a second connection, got %d", n)
	}
	if n := stats.calls.Load(); n != 2 {
		t.Fatalf("want each call sent once, got %d", n)
	}
}

func TestAnOversizeRequestIsRefusedBeforeSending(t *testing.T) {
	tg, stats := fakePlugin(t, string(embeddedTools))
	big := strings.Repeat("x", maxRequest)
	msgs := session(t, tg,
		`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"open_file","arguments":{"path":"`+big+`"}}}`,
		`{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"open_file","arguments":{}}}`)
	text, _ := toolResult(t, msgs[0])
	if _, isErr := toolResult(t, msgs[1]); !strings.Contains(text, "the IDE takes under") || isErr || stats.accepts.Load() != 1 {
		t.Fatalf("want a local refusal on the same connection, got %v / %v", text, msgs[1])
	}
}

func TestASilentIDETimesOut(t *testing.T) {
	if testing.Short() {
		t.Skip("waits listTimeout")
	}
	tg, _ := fakePlugin(t, "SILENT")
	start := time.Now()
	msgs := session(t, tg, call)
	took := time.Since(start)
	if _, isErr := toolResult(t, msgs[0]); !isErr || took < listTimeout || took > listTimeout+5*time.Second {
		t.Fatalf("want a timeout error after %v, got %v after %v", listTimeout, msgs[0], took)
	}
}

func TestBadRequestsGetJSONRPCErrors(t *testing.T) {
	tg := target{socket: "/nonexistent", checkListener: func(int32) error { return nil }}
	msgs := session(t, tg,
		`not json`,
		`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}`,
		`{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"arguments":{}}}`)
	for i, want := range []float64{-32700, -32602, -32602} {
		e, _ := msgs[i]["error"].(map[string]any)
		if e == nil || e["code"] != want {
			t.Fatalf("reply %d: want code %v, got %v", i, want, msgs[i])
		}
	}
}

// A real process running as dist/*/bin/remote-dev-server under the given home passes; the same binary
// as jbr/bin/java, under another home, or with no home does not.
func TestTheListenerCheckAcceptsOnlyTheRemoteDevLauncher(t *testing.T) {
	home, _ := os.UserHomeDir()
	base := scratchDir(t)
	byDefault := func(home string) func(int32) error {
		tr, err := loadTrust("/nonexistent/trusted-ide-dirs", 0, home)
		if err != nil {
			t.Fatal(err)
		}
		return jetBrainsIDE(tr)
	}
	sleep, err := os.ReadFile("/bin/sleep")
	if err != nil {
		t.Fatal(err)
	}
	run := func(rel string) int32 {
		p := filepath.Join(base, ".cache/JetBrains/RemoteDev/dist/263", rel)
		must(t, os.MkdirAll(filepath.Dir(p), 0o755))
		if err := os.WriteFile(p, sleep, 0o755); err != nil {
			t.Fatal(err)
		}
		cmd := exec.Command(p, "30")
		if err := cmd.Start(); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })
		return int32(cmd.Process.Pid)
	}
	launcher, java := run("bin/remote-dev-server"), run("jbr/bin/java")
	if err := byDefault(base)(launcher); err != nil {
		t.Fatalf("real-shaped launcher refused: %v", err)
	}
	if byDefault(base)(java) == nil {
		t.Fatal("jbr/bin/java accepted")
	}
	if byDefault(home)(launcher) == nil {
		t.Fatal("launcher under another home accepted")
	}
	if byDefault("")(launcher) == nil {
		t.Fatal("empty home accepted")
	}
}

// tools.json is the list the plugin really serves, written by the plugin's ToolListContractTest
// (build/tool-list.json). Refresh it whenever the plugin's tools change.
func TestTheEmbeddedListIsValidAndMatchesItself(t *testing.T) {
	tg, _ := fakePlugin(t, string(embeddedTools))
	if _, isErr := toolResult(t, session(t, tg, call)[0]); isErr {
		t.Fatal("the embedded list does not match itself served by a plugin")
	}
}

func TestInitializeGivesInstructionsWithinClaudeCodesLimit(t *testing.T) {
	tg := target{socket: "/nonexistent", checkListener: func(int32) error { return nil }}
	msgs := session(t, tg, `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}`)
	got, _ := msgs[0]["result"].(map[string]any)["instructions"].(string)
	if !strings.HasPrefix(got, "idelink shows things") || len(got) > 4096 || !plainText(got) {
		t.Fatalf("bad instructions: %q", got)
	}
}

func TestNoIDEFailureEndsTheSession(t *testing.T) {
	stale := filepath.Join(scratchDir(t), "ide.sock")
	l, err := net.Listen("unix", stale)
	if err != nil {
		t.Fatal(err)
	}
	l.(*net.UnixListener).SetUnlinkOnClose(false)
	_ = l.Close()
	untrusted, _ := fakePlugin(t, string(embeddedTools))
	untrusted.checkListener = jetBrainsIDE(configured)
	skewed, _ := fakePlugin(t, goodList)
	malformed, _ := fakePlugin(t, `{"tools":[]}`)
	for name, tg := range map[string]target{
		"absent":    {socket: "/nonexistent/ide.sock", checkListener: func(int32) error { return nil }},
		"stale":     {socket: stale, checkListener: func(int32) error { return nil }},
		"untrusted": untrusted,
		"skewed":    skewed,
		"malformed": malformed,
	} {
		msgs := session(t, tg, call, `{"jsonrpc":"2.0","id":4,"method":"tools/list"}`, call, `{"jsonrpc":"2.0","id":5,"method":"ping"}`)
		for _, i := range []int{0, 2} {
			if _, isErr := toolResult(t, msgs[i]); !isErr {
				t.Errorf("%s: call %d succeeded", name, i)
			}
		}
		if msgs[1]["result"] == nil || msgs[3]["result"] == nil {
			t.Errorf("%s: session stopped answering: %v", name, msgs)
		}
	}
}
