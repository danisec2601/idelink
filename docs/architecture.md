# Architecture

idelink connects Claude Code, running on the host where your code lives, to the JetBrains IDE you use to work
on that host remotely. Claude can show you files, diffs and review tours in your editor and read what you're
looking at. Rationale for each choice is in [decisions.md](decisions.md); the security view is in
[security.md](security.md).

## Components

```
 Workstation                         IDE host (same user as Claude Code)
 ┌────────────────────┐   JetBrains  ┌──────────────────────────────────────────────┐
 │ IDE thin client    │   remote dev │ IDE backend (remote-dev-server)               │
 │  idelink frontend  │◄────────────►│  idelink backend ── Unix socket ──┐           │
 │  module            │  RPC/topics  │                                   │           │
 └────────────────────┘              │   /run/user/<uid>/idelink/ide.sock│ (0700 dir)│
                                     │                                   ▼           │
                                     │   idelink-bridge (root-owned, immutable)      │
                                     │        ▲ stdio (MCP)                          │
                                     │   Claude Code (/usr/bin/claude)               │
                                     └──────────────────────────────────────────────┘
```

- **Backend module** (Kotlin, in the IDE backend): listens on the Unix socket, runs the peer gate and pairing,
  resolves paths, serves the tools, writes the audit log. The only part that reads files.
- **Frontend module** (Kotlin, in the thin client): reports editor state (project-relative paths, caret line,
  selection bounds) to the backend, and shows what the backend sends (diffs, the tour panel). No file system
  access, no tools, no network of its own.
- **Shared module**: the RPC interfaces and event types both sides use, and `Plain`, the text sanitiser.
- **Bridge** (`bridge/`, Go, static): the MCP stdio server Claude Code launches. It serves an embedded tool list,
  checks the listener on the socket, and forwards calls.

## Trust boundaries

| Boundary | Who is on the other side | Control |
|---|---|---|
| Socket → backend | any process with your uid | 0700 directory, then the peer gate: the installed bridge binary (device + inode, root-owned) whose parent is `/usr/bin/claude` or a path listed in `/etc/idelink/trusted-claude` (heuristic) |
| First tool call → IDE | any Claude Code process that passed the gate | pairing: you click Allow in an IDE notification; Deny and silence fail closed |
| Bridge → socket | whatever listens at the path | listener's uid (kernel) and executable `bin/remote-dev-server` under a trusted directory (heuristic) |
| Backend → client UI | text from Claude, files, terminals | `Plain` in the backend and again in the frontend; preview-rendering file types refused |
| Client → backend | the thin client's reports | every reported path re-checked against the project root; selection text taken from the backend's own document |
| Tool arguments → file system | Claude | realpath under an open project's real root, re-checked before use; out-of-project paths need your approval |

## Data flow of a tool call

1. Claude Code starts `idelink-bridge` (stdio). `tools/list` is answered from the embedded list; the IDE isn't
   contacted.
2. On the first `tools/call` the bridge connects to the socket, checks the listener, fetches the plugin's
   `list_tools` and compares names and schemas with its own. A mismatch fails the call (version skew).
3. The plugin's gate checks the peer. On the first real tool call from that Claude process the IDE shows a
   pairing notification; the answer holds for the IDE session.
4. The plugin validates the arguments against the tool's schema, resolves paths, runs the tool, and replies
   `{"id", "ok", "result" | "error"}`. One line is appended to the audit log.
5. Presentation tools send an event to the client (diff, tour, file offer). The client's arrival gate shows it
   directly or asks you first, and reports which within about 3 s; an out-of-project item you just allowed
   with Allow once carries a backend flag that skips the question; the reply says that, not whether it was
   seen. A file opens only when the client asks the backend for that offer's file, in that client's editor.

## What crosses each wire

- **Claude ↔ bridge** (stdio, MCP JSON-RPC): tool names and arguments in; tool results (JSON text) out.
- **Bridge ↔ backend** (Unix socket, one JSON object per line, ≤ 2 MiB per request, ≤ 8 MiB per reply):
  `{"id", "tool", "args"}` and the reply. `list_tools` returns the tool specs.
- **Backend → client** (JetBrains remote topics): show-diff events (titles, both texts), tour events (titles,
  project-relative paths, line numbers, notes), file offers (offer id, path, line). All strings sanitised. No event carries something the client
  would open by itself.
- **Client → backend** (JetBrains RPC): editor state (paths, caret line, selection offsets and lines, caret
  count), debounced. Tour clicks as "open stop N of tour T", Show as "open offer N", and how
  the arrival gate handled each arrival. Never file contents or selection text.
- Nothing leaves the host or the workstation except through JetBrains' own remote-development connection.

## Threat model in brief

Claude Code already runs as you on the host, so idelink adds no file-read power. What it adds is terminal
contents you choose to share and the ability to put UI in front of you. The gate and pairing protect those two.
Anything running as your uid has easier routes than idelink (editing files you trust, ptrace), and idelink
doesn't claim to stop it. See [security.md](security.md).
