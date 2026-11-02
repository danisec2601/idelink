// Idelink-bridge is the MCP stdio server Claude Code launches; it forwards tool calls to the idelink IDE plugin
// over its Unix socket. Build it static so LD_PRELOAD can't inject: CGO_ENABLED=0 go build -trimpath .
package main

import (
	"bufio"
	"bytes"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/user"
	"reflect"
	"regexp"
	"slices"
	"strings"
	"syscall"
	"time"
	"unicode"
)

const version = "0.5.2"

// Claude Code cuts server instructions at 4,096 characters.
const instructions = "idelink shows things in the user's JetBrains IDE and reads its state. Use open_file after you edit " +
	"a file the user should look at; review_tour to walk them through a change or a review (point stops at source " +
	"files, not .diff files); show_diff to present a proposed change without writing it; get_ide_state or " +
	"get_selection before asking which file or code they mean. The first call in a session asks the user to " +
	"approve in the IDE; if it's denied, don't retry in a loop."

//go:embed tools.json
var embeddedTools []byte

// Limits for both the embedded tool list and the plugin's list it is compared against.
const (
	maxTools       = 64
	maxDescription = 4096
	maxSchema      = 16 << 10
	maxListBytes   = 128 << 10 // all descriptions + schemas together: this is what lands in Claude's context
	maxReply       = 8 << 20
	maxRequest     = 2 << 20 // the plugin's MAX_LINE; over it the plugin answers id null and hangs up
	maxSchemaDepth = 8

	dialTimeout = 5 * time.Second
	listTimeout = 10 * time.Second
	callTimeout = 5 * time.Minute // a first call waits on the user answering the pairing prompt
)

var (
	toolName = regexp.MustCompile(`^[a-z][a-z0-9_]{0,63}$`)
	propName = regexp.MustCompile(`^[a-zA-Z][a-zA-Z0-9_]{0,63}$`)
)

type rpcMsg struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method,omitempty"`
	Params  json.RawMessage `json:"params,omitempty"`
}

type ide struct {
	conn net.Conn
	rd   *bufio.Reader
	next int
	pid  int32
}

// target is where the bridge connects and how it vets the listener; main builds the real one, tests their own.
type target struct {
	socket        string
	checkListener func(pid int32) error
}

// dial connects and checks the listener: owned by our uid (kernel-enforced), then running the RemoteDev
// launcher (a heuristic; see jetBrainsIDE).
func dial(t target) (*ide, error) {
	c, err := net.DialTimeout("unix", t.socket, dialTimeout)
	if err != nil {
		return nil, err
	}
	raw, err := c.(*net.UnixConn).SyscallConn()
	if err != nil {
		_ = c.Close()
		return nil, err
	}
	var cred *syscall.Ucred
	var credErr error
	if err := raw.Control(func(fd uintptr) {
		cred, credErr = syscall.GetsockoptUcred(int(fd), syscall.SOL_SOCKET, syscall.SO_PEERCRED)
	}); err != nil || credErr != nil {
		_ = c.Close()
		return nil, errors.Join(err, credErr)
	}
	if int(cred.Uid) != os.Getuid() {
		_ = c.Close()
		return nil, fmt.Errorf("socket owned by uid %d, not us", cred.Uid)
	}
	if err := t.checkListener(cred.Pid); err != nil {
		_ = c.Close()
		return nil, err
	}
	return &ide{conn: c, rd: bufio.NewReaderSize(c, 1<<16), pid: cred.Pid}, nil
}

// readLine reads one newline-terminated reply, refusing anything over maxReply.
func readLine(r *bufio.Reader) ([]byte, error) {
	var line []byte
	for {
		chunk, err := r.ReadSlice('\n')
		line = append(line, chunk...)
		if len(line) > maxReply {
			return nil, fmt.Errorf("reply larger than %d bytes", maxReply)
		}
		if err == bufio.ErrBufferFull {
			continue
		}
		return line, err
	}
}

type toolSpec struct {
	Name        string          `json:"name"`
	Description string          `json:"description"`
	InputSchema json.RawMessage `json:"inputSchema"`
}

// listTools fetches the plugin's tool list, refused whole unless every entry is well-formed.
func (d *ide) listTools() ([]toolSpec, error) {
	raw, toolErr, err := d.call("list_tools", nil, listTimeout)
	if err == nil && toolErr != "" {
		err = errors.New(toolErr)
	}
	if err != nil {
		return nil, err
	}
	specs, err := parseToolList(raw)
	if err != nil {
		return nil, fmt.Errorf("the IDE plugin's %w", err)
	}
	return specs, nil
}

// parseToolList checks shape, size and characters; it cannot judge what a description says.
func parseToolList(raw []byte) ([]toolSpec, error) {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	var specs []toolSpec
	if err := dec.Decode(&specs); err != nil {
		return nil, fmt.Errorf("tool list is malformed: %w", err)
	}
	if len(specs) == 0 || len(specs) > maxTools {
		return nil, fmt.Errorf("tool list is malformed: %d tools", len(specs))
	}
	seen := map[string]bool{}
	total := 0
	for _, t := range specs {
		total += len(t.Description) + len(t.InputSchema)
		if total > maxListBytes {
			return nil, fmt.Errorf("tool list is malformed: over %d bytes in total", maxListBytes)
		}
		if !toolName.MatchString(t.Name) || seen[t.Name] {
			return nil, fmt.Errorf("tool list is malformed: tool name %q", t.Name)
		}
		seen[t.Name] = true
		if len(t.Description) > maxDescription || !plainText(t.Description) {
			return nil, fmt.Errorf("tool list is malformed: description of %s", t.Name)
		}
		var schema map[string]any
		if len(t.InputSchema) > maxSchema || json.Unmarshal(t.InputSchema, &schema) != nil ||
			schema["type"] != "object" || !schemaOK(schema, 0) {
			return nil, fmt.Errorf("tool list is malformed: inputSchema of %s", t.Name)
		}
	}
	return specs, nil
}

// sameTools compares names and input schemas, ignoring order and descriptions; "" means they match.
func sameTools(bridge, plugin []toolSpec) string {
	schemas := func(specs []toolSpec) map[string]any {
		m := map[string]any{}
		for _, t := range specs {
			var s any
			if json.Unmarshal(t.InputSchema, &s) != nil {
				s = "unparsable: " + string(t.InputSchema)
			}
			m[t.Name] = s
		}
		return m
	}
	b, p := schemas(bridge), schemas(plugin)
	var diffs []string
	for name, s := range b {
		ps, ok := p[name]
		if !ok {
			diffs = append(diffs, name+" only in the bridge")
		} else if !reflect.DeepEqual(s, ps) {
			diffs = append(diffs, name+" has a different schema")
		}
	}
	for name := range p {
		if _, ok := b[name]; !ok {
			diffs = append(diffs, name+" only in the plugin")
		}
	}
	slices.Sort(diffs)
	return strings.Join(diffs, ", ")
}

// plainText refuses control characters (bar tab and newline), format characters (bidi overrides,
// zero-widths, Unicode tag characters) and private-use characters: things that hide text from a reader.
func plainText(s string) bool {
	for _, r := range s {
		if (unicode.Is(unicode.Cc, r) && r != '\n' && r != '\t') || unicode.Is(unicode.Cf, r) || unicode.Is(unicode.Co, r) {
			return false
		}
	}
	return true
}

// schemaOK allows only the JSON Schema subset the plugin emits (Tools.kt Schema.json), so nothing else
// rides along in a schema into Claude's context.
func schemaOK(s map[string]any, depth int) bool {
	if depth > maxSchemaDepth {
		return false
	}
	for k, v := range s {
		ok := false
		switch k {
		case "type", "description":
			str, isStr := v.(string)
			ok = isStr && plainText(str)
		case "maxLength", "minimum", "maximum", "maxItems":
			_, ok = v.(float64)
		case "additionalProperties":
			_, ok = v.(bool)
		case "items":
			sub, isObj := v.(map[string]any)
			ok = isObj && schemaOK(sub, depth+1)
		case "required":
			list, isList := v.([]any)
			ok = isList
			for _, e := range list {
				name, isStr := e.(string)
				ok = ok && isStr && propName.MatchString(name)
			}
		case "properties":
			props, isObj := v.(map[string]any)
			ok = isObj
			for name, p := range props {
				sub, isObj := p.(map[string]any)
				ok = ok && propName.MatchString(name) && isObj && schemaOK(sub, depth+1)
			}
		}
		if !ok {
			return false
		}
	}
	return true
}

// call returns the tool's own failure as toolErr with the connection still good; err means the
// connection is not (closed, timed out, or out of step) and the caller must drop it.
func (d *ide) call(tool string, args json.RawMessage, timeout time.Duration) (result json.RawMessage, toolErr string, err error) {
	d.next++
	if len(args) == 0 || string(args) == "null" {
		args = json.RawMessage("{}")
	}
	req, _ := json.Marshal(map[string]any{"id": d.next, "tool": tool, "args": args})
	if len(req) >= maxRequest {
		// Refused before sending, so the connection is still in step.
		return nil, fmt.Sprintf("request is %d bytes; the IDE takes under %d", len(req), maxRequest), nil
	}
	_ = d.conn.SetDeadline(time.Now().Add(timeout))
	if _, err := d.conn.Write(append(req, '\n')); err != nil {
		return nil, "", errors.Join(errNotSent, err)
	}
	line, err := readLine(d.rd)
	if err != nil {
		// The plugin closes without replying when the gate refuses us or the user declines pairing.
		return nil, "", fmt.Errorf("IDE closed the connection or timed out (not admitted, pairing declined, or IDE gone): %w", err)
	}
	var resp struct {
		ID     int             `json:"id"`
		OK     bool            `json:"ok"`
		Result json.RawMessage `json:"result"`
		Error  string          `json:"error"`
	}
	if err := json.Unmarshal(line, &resp); err != nil {
		return nil, "", fmt.Errorf("bad reply from IDE: %w", err)
	}
	if resp.ID != d.next {
		return nil, "", fmt.Errorf("bad reply from IDE: id %d, want %d", resp.ID, d.next)
	}
	if !resp.OK {
		if resp.Error == "" {
			resp.Error = "IDE reported an error without a message"
		}
		return nil, resp.Error, nil
	}
	return resp.Result, "", nil
}

// errNotSent marks a request the IDE never received: the connection was already closed when it was written.
var errNotSent = errors.New("request not sent")

// env is what main takes from the system; tests supply their own.
type env struct {
	tools        []byte
	socket       string
	trustConfig  string
	claudeConfig string
	rootUID      uint32
}

func main() {
	os.Exit(run(os.Args[1:], os.Stdin, os.Stdout, os.Stderr, env{
		tools:        embeddedTools,
		socket:       fmt.Sprintf("/run/user/%d/idelink/ide.sock", os.Getuid()),
		trustConfig:  "/etc/idelink/trusted-ide-dirs",
		claudeConfig: "/etc/idelink/trusted-claude",
	}))
}

func run(args []string, stdin io.Reader, stdout, stderr io.Writer, e env) int {
	tools, err := parseToolList(e.tools)
	if err != nil {
		_, _ = fmt.Fprintf(stderr, "idelink-bridge: the embedded %v\n", err)
		return 1
	}
	doctorMode := len(args) == 1 && args[0] == "--doctor"
	if len(args) != 0 && !doctorMode {
		_, _ = fmt.Fprintln(stderr, "usage: idelink-bridge [--doctor]")
		return 2
	}
	var home string // from passwd, not $HOME, so whoever launches the bridge can't move the trusted default
	if u, err := user.Current(); err == nil {
		home = u.HomeDir
	}
	tr, trustErr := loadTrust(e.trustConfig, e.rootUID, home)
	t := target{socket: e.socket, checkListener: jetBrainsIDE(tr)}
	if doctorMode {
		cl, clErr := loadClaudes(e.claudeConfig, e.rootUID)
		parent, _ := os.Readlink(fmt.Sprintf("/proc/%d/exe", os.Getppid()))
		return doctor(stdout, t, setup{ide: tr, ideErr: trustErr, claude: cl, claudeErr: clErr, parent: parent}, tools)
	}
	if trustErr != nil {
		_, _ = fmt.Fprintf(stderr, "idelink-bridge: refusing to start: %v\n", trustErr)
		return 1
	}
	serve(stdin, stdout, t, tools)
	return 0
}

// skewError means the plugin serves a different tool list from the one the bridge advertises.
type skewError struct{ diff string }

func (e skewError) Error() string {
	return fmt.Sprintf("the bridge (%s) and the IDE plugin are different versions (%s); update them together, then retry",
		version, e.diff)
}

// unreachable says why the IDE can't be used, in terms the user can act on.
func unreachable(err error, socket string) string {
	switch {
	case errors.Is(err, syscall.ENOENT):
		return "the IDE isn't running, or the idelink plugin isn't loaded (an IDE update can disable it if the " +
			"build is outside its supported range). Open the project in your JetBrains IDE, then retry."
	case errors.Is(err, syscall.ECONNREFUSED):
		return fmt.Sprintf("nothing is listening on %s (a stale socket: the IDE probably exited). "+
			"Open the project in your JetBrains IDE, then retry.", socket)
	}
	return err.Error()
}

func serve(stdin io.Reader, stdout io.Writer, t target, tools []toolSpec) {
	in := bufio.NewReaderSize(stdin, 1<<20)
	out := json.NewEncoder(stdout)
	var conn *ide

	reply := func(id json.RawMessage, result any) {
		_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": id, "result": result})
	}
	fail := func(id json.RawMessage, code int, msg string) {
		_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": id, "error": map[string]any{"code": code, "message": msg}})
	}
	drop := func() {
		if conn != nil {
			_ = conn.conn.Close()
			conn = nil
		}
	}
	// Every new connection is checked, so an IDE restarted with another plugin version is caught too.
	connect := func() error {
		if conn != nil {
			return nil
		}
		c, err := dial(t)
		if err != nil {
			return err
		}
		conn = c
		plugin, err := c.listTools()
		if err == nil {
			if diff := sameTools(tools, plugin); diff != "" {
				err = skewError{diff}
			}
		}
		if err != nil {
			drop()
		}
		return err
	}

	for {
		line, err := in.ReadBytes('\n')
		if len(line) == 0 && err != nil {
			return
		}
		var m rpcMsg
		if json.Unmarshal(line, &m) != nil {
			fail(json.RawMessage("null"), -32700, "parse error")
			continue
		}
		if len(m.ID) == 0 { // notification
			continue
		}
		switch m.Method {
		case "initialize":
			var p struct {
				ProtocolVersion string `json:"protocolVersion"`
			}
			if json.Unmarshal(m.Params, &p) != nil || p.ProtocolVersion == "" {
				fail(m.ID, -32602, "initialize needs params.protocolVersion")
				continue
			}
			reply(m.ID, map[string]any{
				"protocolVersion": p.ProtocolVersion,
				"capabilities":    map[string]any{"tools": map[string]any{}},
				"serverInfo":      map[string]any{"name": "idelink", "version": version},
				"instructions":    instructions,
			})
		case "ping":
			reply(m.ID, map[string]any{})
		case "tools/list":
			reply(m.ID, map[string]any{"tools": tools})
		case "tools/call":
			var p struct {
				Name      string          `json:"name"`
				Arguments json.RawMessage `json:"arguments"`
			}
			if json.Unmarshal(m.Params, &p) != nil || p.Name == "" {
				fail(m.ID, -32602, "tools/call needs params.name")
				continue
			}
			toolFail := func(msg string) {
				reply(m.ID, map[string]any{"isError": true, "content": []any{map[string]any{"type": "text", "text": "idelink: " + msg}}})
			}
			if err := connect(); err != nil {
				toolFail(unreachable(err, t.socket))
				continue
			}
			res, toolErr, err := conn.call(p.Name, p.Arguments, callTimeout)
			if errors.Is(err, errNotSent) {
				// The IDE hung up after an earlier reply (a refusal, or the IDE was replaced): redial once.
				drop()
				if err = connect(); err != nil {
					toolFail(unreachable(err, t.socket))
					continue
				}
				res, toolErr, err = conn.call(p.Name, p.Arguments, callTimeout)
			}
			if err != nil {
				drop()
				toolFail(err.Error())
				continue
			}
			if toolErr != "" {
				toolFail(toolErr)
				continue
			}
			reply(m.ID, map[string]any{"content": []any{map[string]any{"type": "text", "text": string(res)}}})
		default:
			fail(m.ID, -32601, "method not found: "+m.Method)
		}
	}
}
