# AGENTS.md

Instructions for coding agents working in this repository. People are welcome to read it too.

idelink lets Claude Code on a development host present files, diffs and review tours in a JetBrains IDE
(remote development) and read the IDE's state. Architecture: [docs/architecture.md](docs/architecture.md).

## Installing idelink for your user

If you were asked to install or set up idelink rather than change it, follow
[docs/install.md](docs/install.md); its "For agents" section says what you do and what you guide your user
through.

## Layout

```
plugin/                 IntelliJ Platform plugin (Kotlin, Gradle), split-mode modules
  shared/               RPC interfaces, event types, Plain (UI text sanitiser)
  frontend/             thin-client module: reports editor state, shows diffs and the tour panel
  backend/              socket server, peer gate, pairing, PathScope, tools, audit log
    src/test/           unit tests (JUnit via kotlin-test)
  config/               detekt config and baselines
bridge/                 idelink-bridge: static Go MCP stdio server; tools.json is its embedded tool list
scripts/                check.sh, comment_lint.py, tools_doc.py, install-bridge.sh
docs/                   user and design docs; decisions.md is the decision log
```

## Build, test, lint

Run everything: `scripts/check.sh` (exits non-zero if anything failed; `SKIP_GOLANGCI=1` where golangci-lint
isn't installed; extra arguments go to Gradle, e.g. `--offline`).

Individually:

| What | Command |
|---|---|
| Plugin build + tests | `cd plugin && ./gradlew buildPlugin :backend:test` (the root `:test` has no tests) |
| Plugin Verifier | `cd plugin && ./gradlew verifyPluginStructure verifyPlugin` (report: `plugin/build/reports/pluginVerifier/`); add installed IDEs with `-PverifyIdeDirs=<dir>,<dir>` |
| Kotlin lint | `cd plugin && ./gradlew detekt` |
| Bridge build | `cd bridge && CGO_ENABLED=0 go build -trimpath -o idelink-bridge .` |
| Bridge tests | `cd bridge && go vet ./... && go test ./...` |
| Go lint | `cd bridge && golangci-lint run ./...` (config: `bridge/.golangci.yml`) |
| Comment heuristics | `python3 scripts/comment_lint.py plugin bridge` |
| Tool list in step | `python3 scripts/tools_doc.py check` (after `:backend:test`, which writes `plugin/backend/build/tool-list.json`) |

The build uses the IDE at `ideDir` (Gradle property, `-PideDir=/path`) if set, otherwise downloads PyCharm
`platformVersion`; a set `ideDir` that isn't a directory fails the build. `scripts/check.sh` passes
`$IDELINK_IDE_DIR` as `ideDir` when that variable is set (empty forces the download).

Scratch files go in the repository's `tmp/` (gitignored), never `/tmp`.

## Testing safeguards

For every new or changed safeguard (a check that refuses something), add a test that fails on an assertion
when the safeguard is removed. Then prove it: weaken the safeguard, run the tests, confirm the failure is that
assertion and not a compile error, restore it, and confirm `git diff --exit-code`.

Tests use real processes and files, not mocks of the security checks. Don't add package-level variables or
seams a later edit could rebind to bypass a check; pass dependencies in from the entry point.

## Adding or changing a tool

1. One file under `plugin/backend/src/main/kotlin/.../`, following the `ToolDef`/`Schema` pattern in
   `PresentTools.kt`, and one registration line.
2. Paths through `PathScope`, every string bound for the UI through `Plain`.
3. Run `:backend:test`, then copy `plugin/backend/build/tool-list.json` to `bridge/tools.json`. The bridge
   embeds it and its tests check it; a mismatch with the plugin is version skew at runtime.
4. Add a `## \`tool_name\`` section to `docs/tools.md` (what it does, returns, refuses), then
   `python3 scripts/tools_doc.py render`.

## Code style

Kotlin's conventions (kotlinlang.org/docs/coding-conventions.html) and Go's (go.dev/doc/comment) win wherever
they differ from anything below.

- Comment only what is surprising, non-standard, or unclear from the code itself. Most comments are one line;
  nearly all are at most two. A longer comment means the code wants refactoring or the content belongs in
  `docs/`.
- No history or narration in comments: nothing like "previously", "now", "moved", "replaces", "as discussed",
  task or ticket numbers, or references to a plan or conversation. Git history and `docs/decisions.md` hold that.
- No restating the code, no essay headers, no comments naming sibling files for no reason.
- KDoc: a one-line summary that mentions parameters as `[param]` links. Add `@param`/`@return`/`@throws` only
  when a line is genuinely needed. Public declarations get KDoc (detekt enforces it); trivial overrides don't.
- Go: every exported name and each package gets a doc comment that is a sentence starting with the name and
  ending with a period (revive `exported`/`package-comments`, godot).
- No design rationale in code. Record decisions in `docs/decisions.md` (below).
- When you touch a comment, bring it up to this standard.
- `scripts/comment_lint.py` flags long and narrating comments; it's a heuristic, so rewording to dodge it
  defeats the point. Mark a justified exception with `comment-lint: allow <reason>`.

## Security invariants: do not weaken

idelink's controls are listed in [docs/security.md](docs/security.md). Without an accepted decision in
`docs/decisions.md` and the maintainer's review, don't change:

- the peer gate (`PeerGate.kt`): installed bridge by device+inode, root-owned, parent a root-owned
  `/usr/bin/claude` or an exact path or `dir/` entry in the root-owned `/etc/idelink/trusted-claude`
  (D-052, D-053);
- pairing before any tool other than `list_tools`, with Deny as the result of anything but an Allow click, and
  remembered denials;
- `PathScope`: roots only from the IDE, realpath, regular files under the real root, re-check before use;
- `Plain` on every string from Claude, a file, a terminal or another process before it reaches the UI, in the
  backend and again in the frontend;
- show-only diffs, the rendered-preview refusal, selection withholding and the scrub;
- the bridge's listener check, trust-config ownership checks, input caps, and serving only its embedded list;
- the exclusions: no file writes, command execution, terminal input, builds or run configurations;
- the static, CGO-disabled bridge build.

Any change to plugin, bridge or gate code needs the maintainer's review before merge.

## Decision log

Each design decision goes in `docs/decisions.md`, newest first:

```
## D-NNN Title (YYYY-MM-DD, accepted | superseded by D-MMM)
Context: … (2-4 lines)
Decision: … (a sentence or two)
Consequences:
- …
Rejected: … (optional)
```

Take the next number. Never delete or rewrite an old entry's decision: mark it superseded and point to the new
one. Name people only as "the maintainer".

## Commits

Small, coherent commits with a one-line summary of what changed. Don't commit build outputs (`build/`,
`.intellijPlatform/`) or anything in `tmp/`.
