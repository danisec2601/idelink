# Security

idelink lets a Claude Code session on your development host show you things in your JetBrains IDE and read
what you're looking at. This page says what it protects, what it doesn't, and how to check that what you
installed is what was reviewed. The reasoning behind each control is in [decisions.md](decisions.md).

## What idelink adds, and so what it guards

Claude Code already runs as your user on the host and can read anything the IDE can read on disk. idelink adds
two things:

1. **Terminal contents** you explicitly share, which aren't on disk.
2. **UI in front of you**: diffs, highlighted files and review tours. A convincing diff is a social-engineering
   tool, so who may show you one matters.

The controls below protect those two, plus the integrity of the tool list Claude sees.

## What it guards against

- **Processes no Claude Code started.** The plugin admits only the installed bridge binary, matched by device
  and inode, root-owned and immutable, whose parent is the root-owned `/usr/bin/claude`, or a binary root listed in `/etc/idelink/trusted-claude`
  by exact path or directory (D-052, D-053; a listed user-writable binary leaves pairing as the only protection). (Heuristic: see below.)
- **Any Claude process you didn't mean to connect.** The first tool call from each Claude Code process needs
  your click on an IDE notification. Ignoring it denies; a Deny click or the timeout is remembered for the IDE session. A balloon cleared from the Notifications panel without a click denies only that call.
- **Paths that escape the project.** Every path is resolved with all symlinks followed and must be a regular file
  under an open project's real root, re-checked just before use. A path outside every project root asks you,
  showing the real path, default Deny.
- **HTML and hidden characters in UI text.** Every string from Claude, a file, a terminal or another process is
  shown as plain text: no HTML, bidi and invisible characters shown as ⟦U+XXXX⟧. Diffs warn when either side
  contains bidi or invisible characters.
- **Previews that fetch.** Files whose editor renders content (Markdown, HTML, SVG, notebooks, diagrams) aren't
  opened; `show_diff` shows them as text.
- **Remote JSON schemas.** The IDE downloads a schema named in a file (`"$schema": "https://…"`, a
  yaml-language-server modeline, `#:schema`) when it opens the file; remote schema download is on by default.
  `open_file`, `review_tour` and `show_diff` (either side) refuse such a reference, except the bundled
  json-schema.org metaschemas, in file types the IDE applies schemas to: JSON, JSON5, JSON Lines, YAML and TOML
  by the bundled plugins' extensions and names (`.babelrc`, `Cargo.lock`, …), the JS configs whose schema
  enablers are bundled (`.eslintrc.js`, `prettier.config.*`, `*webpack*.js`, …), and any name the backend's file
  type settings map to those languages. Other files are checked only for a yaml-language-server modeline, so a
  script embedding a config with `"$schema"` still opens. Such a file must be at most 1 MiB, and UTF-8 or
  BOM-marked UTF-16/32 (BOM-less with NUL bytes is refused). The check runs when Claude calls and again just
  before Show or a tour stop opens the file, so an edit in between is caught; a write in the instant between
  that re-check and the open is not. For a file outside the project it runs only after you allow it. `show_diff`
  checks the text it sends, by the file's name (the viewer builds a JSON document for it; whether that document
  resolves schemas wasn't established, so the check stays). Heuristic: misses YAML aliases, escapes other than
  `\u0024` and `\/`, file types a client-side setting or another plugin maps to JSON/YAML/TOML, encodings
  other than UTF-8/16/32, and the schemastore.org catalog the IDE consults by file name (a fixed host, not one
  the file chooses).
- **Typing into a tab you can no longer see.** Nothing Claude sends opens until you choose Show or Start tour
  on a notification that never takes focus, unless you chose Always show / Always start for that kind (a client
  setting) or just allowed that request's out-of-project files with Allow once (the backend marks only those
  requests; for a tour that starts the whole tour, which the prompt says).
  It's a usability guard, not a security control: the backend can open files without asking the client.
- **A hostile repository behind `from_ref`.** `git cat-file` runs with `--no-lazy-fetch` (git 2.45+; older git
  rejects the flag, so `from_ref` fails rather than fetching), transports disallowed, fsmonitor off, no pager and
  an environment cut to `PATH`, `HOME` and `XDG_CONFIG_HOME`: a partial clone's promisor remote (whose repo config
  can allow `ext::` commands) is never contacted. Repo config is still read; keys that run commands for other git
  operations (filters, textconv, hooks, credential helpers, `core.sshCommand`) don't apply to `cat-file blob`.
- **Who started this Claude.** The pairing prompt adds a ⚠ line when Claude was started with `--mcp-config` or
  with a base-URL or proxy variable (`ANTHROPIC_*BASE_URL`, `CLAUDE_CODE_*BASE_URL`, `HTTP(S)_PROXY`, `ALL_PROXY`)
  in its or the bridge's environment, showing only host:port. Heuristic: settings-file values Claude applies
  in-process are seen only when they reach the bridge's environment.
- **Writes.** idelink never writes your files. `show_diff` is show-only; Claude makes edits with its own tools,
  behind Claude Code's permission prompts.
- **Secrets in shared text.** Selections from secret-bearing file names (`.env*`, `*.pem`, `*.key`, `id_*`,
  `*secret*`, `*credential*`, `*.tfvars`, `kubeconfig*`, …) return the line range only. Other selection and
  terminal text is scrubbed of recognisable secret shapes (private keys, cloud and SaaS tokens, JWTs,
  `Authorization`/`Bearer` values) into per-read random placeholders.
- **A fake IDE on the socket.** The bridge accepts a listener only with your uid that runs
  `bin/remote-dev-server` under a trusted directory, and Claude only ever sees the bridge's embedded tool list.
- **Oversized or malformed input.** Strict per-tool schemas, size caps on every message, no polymorphic JSON.

Every connection decision and tool call is appended to `~/.local/state/idelink/audit.log` (never tool output).

## What it does not guard against

- **Anything running as your user that wants in badly.** The gate is a heuristic. It can't tell your Claude
  session from Claude's own Bash commands, hooks, MCP servers or other Claude sessions; a process can exec the
  bridge after connecting; with `kernel.yama.ptrace_scope = 0` a same-uid process can attach to an admitted
  bridge. Pairing is the control a same-uid process can't fake, and only if you approve only requests you just
  caused. Set `ptrace_scope` to 1 or higher on the host if you can.
- **A pairing notification is not proof of anything.** Whatever can draw it can draw a reassuring one. Trust
  the reviewed source and the release attestation, never UI text.
- **Secrets the scrub doesn't recognise.** Bare hex, base64 or UUID secrets, passwords in prose or config values,
  `user:pass@` in URLs, PEM bodies selected without their header, providers not on the list, and secrets in
  ordinarily named files. Surrounding text can reveal which credential a placeholder replaced.
- **The default trusted IDE directory is writable by you.** `~/.cache/JetBrains/RemoteDev/dist` is
  user-writable, so the bridge's listener check is a heuristic there. Listing only root-owned directories in
  `/etc/idelink/trusted-ide-dirs` narrows it.
- **The audit log** is writable by your user; it's a record, not evidence.
- **Other users on the workstation or host** are outside scope beyond file permissions (0700 socket directory,
  0600 audit log).

## Invariants contributors must keep

Changes that weaken any of these need a decision in [decisions.md](decisions.md) and the maintainer's review:

- The bridge stays static (CGO disabled) and is installed root-owned and immutable; the gate compares by device
  and inode and refuses a writable bridge or parent.
- Pairing is asked before any tool other than `list_tools`, and Deny is the outcome of anything but an Allow
  click. Closing the balloon (✕) hides it in the Notifications panel, where it stays answerable until the
  2-minute timeout denies it.
- Project roots come only from the IDE; every path goes through `PathScope`, and is re-checked before use.
- Every externally sourced string reaching the UI goes through `Plain`, in the backend and again in the
  frontend.
- No tool writes files, runs commands, sends terminal input or starts builds or run configurations.
- The frontend module has no file access and sends no file contents.
- Claude sees only the bridge's embedded tool list.

## Verifying a release

Each release's plugin zip and bridge binary are built by GitHub Actions from the tagged source and carry a
GitHub build-provenance attestation. To check an artifact you downloaded:

```sh
gh attestation verify idelink-<version>.zip --repo danisec2601/idelink
gh attestation verify idelink-bridge --repo danisec2601/idelink
sha256sum idelink-<version>.zip idelink-bridge    # compare with SHA256SUMS from the same release
```

A passing verification says the file was built by this repository's workflow from the commit it names. Review
that commit's source (or have it reviewed); the attestation ties the binary to it. Nothing the IDE displays,
including the pairing notification, can vouch for the build.

Building it yourself instead: see [install.md](install.md#building-from-source).

## Reporting a vulnerability

Open a GitHub security advisory on the repository (Security → Report a vulnerability) rather than a public
issue.
