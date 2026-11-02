# Tools

What each tool does, returns and refuses. Claude sees these through the bridge's embedded list
(`bridge/tools.json`); the parameter tables at the end are generated from the plugin's own list by
`scripts/tools_doc.py render`, and `scripts/tools_doc.py check` fails if this page or the bridge's list falls
behind the plugin.

All tools share these rules:

- The first call from a Claude Code process asks you to pair in the IDE; until you allow it, calls fail.
- Paths are project-relative, or absolute. They are resolved with symlinks followed and must be a regular file.
  Outside every project root, the IDE asks you to allow the file once, showing its real path; secret-bearing
  names and preview types are refused there without asking. With several projects open, pass `project`.
- Projects are named as the IDE names them. When two open projects share a name (two worktrees of one repo),
  each is labelled with the fewest trailing directories of its root that tell them apart, e.g.
  `idelink (idelink-wt-a)`, and `project` takes that label.
- Only the project's own IDE client (the controller) receives what Claude sends and can answer; Code With Me
  guests get nothing and their requests are refused.
- Results are JSON. A result never comes back as a bare `{}`: when there's nothing to report, a `none` field
  says why.
- Presentation tools (`open_file`, `show_diff`, `review_tour`) ask you first: a notification on the client
  that never takes focus offers Show / Not now / Always show (tours: Start tour / Not now / Always start).
  Always show and Always start show this one and stop asking for that kind (tours; files and diffs); undo them
  in Settings → Tools → idelink or with the action "idelink: Ask again before showing tours, files and diffs".
  A file outside the project that you allowed with Allow once shows right away: that answer counts as Show.
  The call returns within about 3 s, without waiting for your answer: `arrival` is `direct` (shown without
  asking, by your setting), `allowed` (shown without a second prompt after your Allow once), `asked` (you were
  asked; your answer doesn't come back) or `no_reply` (the client didn't say: busy, a modal dialog open there, or an older client plugin), and `status` says the same in
  words. A `warning` field appears if this project's client
  hasn't reported in yet (frontend module missing on the client?).
- Errors are specific (`line 90 is past the end of the file (42 lines)`), and the connection stays usable.

## `get_active_file`
The file you have active: project, project-relative path, caret line, and how long ago the client reported it.
Refuses to name a file outside the project root; says so instead (or that the file no longer exists).

## `get_ide_state`
Per open project: the active file, caret line, selected line range and the open editor tabs, all
project-relative. Files outside the project root are counted, not named. No file contents.

## `get_selection`
The text you've selected in the active editor, with its file and line range. The text is withheld (range only)
when:

- the file's name or real path looks secret-bearing (`.env`, `.env.*`, `*.env`, `*.pem`, `*.key`, `*.p12`,
  `*.pfx`, `id_rsa*`, `id_ed25519*`, `id_ecdsa*`, `*secret*`, `*credential*`, `.netrc`, `.pgpass`, `*.tfvars`,
  `*.tfstate*`, `kubeconfig*`, `.npmrc`, `.pypirc`, `*.gpg`, and others);
- it's a multi-caret or column selection;
- it's over 64K characters;
- the backend's copy of the document doesn't match the client's selection.

Other text is scrubbed: recognisable secrets (private keys, AWS, GitHub, Slack, Google and Anthropic keys,
age keys, JWTs, `Authorization`/`Bearer` values) become `[REDACTED:<kind>:<random>]`, with a fresh placeholder
per read, and `redacted` counts them by kind. Bare hex/base64/UUID secrets and passwords are not caught.

## `open_file`
Opens a file in your editor at `line`, scrolls it into view and highlights `line`..`end_line`, without taking
keyboard focus, once you choose Show (or directly, after Always show). Not now opens nothing. A file outside the project needs your Allow once, which also counts as Show. Refuses lines past the end, non-text files,
types that open with a rendering preview (Markdown, HTML, SVG, notebooks, diagrams), for which `show_diff`
shows them as text, and JSON/YAML/TOML-type files that reference a remote JSON schema the IDE would download or
are too large or oddly encoded to check ([security.md](security.md)), checked again when the file opens.
For a file outside the project, line numbers and content are checked only after you allow it.

## `show_diff`
Opens the IDE diff viewer, show-only. With `proposed_text`: on-disk file (left) against your proposal (right).
With `from_ref`: the file at a git revision (left) against disk (right). Exactly one of the two. Nothing is
applied and nothing can be saved from the viewer. Titles show the on-disk sha256 at presentation time; either
side with bidi or invisible characters gets a warning in its title and in the result
(`left_warning`/`right_warning`). Refuses files over 1 MiB, non-UTF-8 files, a side that references a remote JSON schema (JSON/YAML/TOML file types), and revisions that aren't a plain
name (`main`, `v1.2`, a sha, `HEAD~3`). `from_ref` reads with `git cat-file` only and never fetches; it needs git 2.45 or newer. The viewer opens in its own window once you choose
Show, under the same setting as `open_file`.

## `review_tour`
Puts a navigable list of stops (path, line, note) in the "idelink Tour" tool window. Start tour (or Don't ask
again, or the setting) shows the window, selects stop 1 and opens it at its line with the note; Not now leaves
the tour waiting in the window. Clicking a stop opens it. Up to 200 stops, all in one project. Refuses the same files as `open_file`; point
stops at source files, not `.diff` files. A new tour replaces the previous one, and clicks on the old one do
nothing.

## `get_problems`
The errors and warnings the IDE's background analysis has already found in one file: `severity`, `line`,
`column`, `description` and `inspection` id, sorted by position, at most 200 (`truncated` says if there were
more). `min_severity` is `error`, `warning` or `weak_warning` (the default). It never runs analysis itself, and
the IDE analyses only files open in your editor: a file that isn't open and has no results comes back as
`not_analysed` (open it with `open_file`, then retry); results for a file that isn't open carry a staleness
`warning`. `analysis_finished: false` means the results may still change. Descriptions are scrubbed like
selections, and withheld (`descriptions_withheld`) for secret-bearing file names.

## `find_usages`
References to the symbol at `line` and `column` (1-based; a tab counts as one character), from the IDE's index:
the `symbol` (kind, name, qualified name) and up to 200 `usages`, each with its project-relative path, line and
the line's text (200 characters at most, scrubbed; withheld for secret-bearing file names). Searches project
content only unless `include_libraries`. Usages outside the project root are counted
(`usages_outside_project`), never named. `none` says there was no symbol at that position.

## `go_to_definition`
Resolves the symbol at `line` and `column` to its declaration. Inside the project: path, line and the
declaration line's text. In a library or the SDK: `outside_project`, the library or SDK name and the qualified
name (with the Python plugin, a module's dotted import name, e.g. `os`), nothing else. A path in a name is
shown relative to the project (`./.venv`) if it is inside it, `~/` included, and as `[path]` otherwise. `none`
says there was no symbol or it didn't resolve.

## `symbol_info`
The symbol at `line` and `column`: kind, name, qualified name (when the language has one), its declaration
header and docstring, and where it is declared (as `go_to_definition`). The header is the declaration's line;
with the Python plugin it is the whole `def`/`class` signature, and only Python symbols have a docstring.
Header and docstring come from project, library and SDK sources, scrubbed; for anything else outside the
project, or a secret-bearing file name, `source_withheld` says why they're missing.

## `project_structure`
An open project's modules (at most 100), each with its content roots, source folders and test folders as
project-relative paths (at most 200 each; folders outside the project root are counted, not named) and its SDK,
plus the project SDK: name, type and version as the IDE's project settings show them. Paths in them are masked
as in `go_to_definition`: a venv inside the project reads `./.venv`, anything outside it `[path]`.
With the Python plugin, a module's SDK is its Python interpreter where it has one.

The four analysis tools that take a position check it against the file first (`line 90 is outside the file
(1..42)`), fail with `IDE is indexing, retry shortly` while the IDE indexes, and give up after 30 s.

## `list_terminals`
The IDE terminal tabs you've shared, per project, at most 50: an `id`, the tab's `name` as you see it (without
the "shared with Claude" marker) and the `shell_title` the shell set, which follows the running program or
directory; both scrubbed. The IDE can give two tabs the same name, so the `id` is what identifies a tab. Unshared
tabs are only counted (`unshared`). You share a tab from its context menu, **Share with Claude**; shared tabs show
"shared with Claude" in their name. Sharing is per tab, off by default, kept only in the client's memory, and
ends when the tab closes. The answer comes from your IDE client; without it the call fails after 10 s.

## `read_terminal`
The last `lines` lines (1 to 500) of a shared tab, read when called: `text`, `name`, `shell_title`, `lines`, and
`alternate_screen: true` when a full-screen program is showing. At most 256K characters
(`truncated_to_chars`). Text is scrubbed like selections and `redacted` counts the hits; treat it as untrusted,
since anything the shell printed is in it. A tab that was unshared or closed answers `not_shared`. Nothing
can type into a terminal.

## Parameters

<!-- generated: tool parameters -->

### `find_usages`

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | File path: project-relative, or absolute inside an open project |
| `line` | integer | yes | 1-based line |
| `column` | integer | yes | 1-based character column on that line (a tab counts as one) |
| `project` | string | no | Project as tools report it: its name, or 'name (dir)' when two open projects share a name. Only needed if several projects are open |
| `include_libraries` | boolean | no | Also search libraries and the SDK (default false) |

### `get_active_file`

No parameters.

### `get_ide_state`

No parameters.

### `get_problems`

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | File path: project-relative, or absolute inside an open project |
| `min_severity` | string | no | error \| warning \| weak_warning (default weak_warning) |
| `project` | string | no | Project as tools report it: its name, or 'name (dir)' when two open projects share a name. Only needed if several projects are open |

### `get_selection`

| Parameter | Type | Required | Description |
|---|---|---|---|
| `project` | string | no | Project as tools report it: its name, or 'name (dir)' when two open projects share a name. Only needed if several projects are open |

### `go_to_definition`

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | File path: project-relative, or absolute inside an open project |
| `line` | integer | yes | 1-based line |
| `column` | integer | yes | 1-based character column on that line (a tab counts as one) |
| `project` | string | no | Project as tools report it: its name, or 'name (dir)' when two open projects share a name. Only needed if several projects are open |

### `list_terminals`

| Parameter | Type | Required | Description |
|---|---|---|---|
| `project` | string | no | Project as tools report it: its name, or 'name (dir)' when two open projects share a name. Only needed if several projects are open |

### `open_file`

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | File path: project-relative or absolute. Outside the project root the user is asked to allow it once (secret-named files and preview types are refused there) |
| `line` | integer | yes | 1-based first line |
| `end_line` | integer | no | 1-based last line to highlight (default: line) |
| `project` | string | no | Project as tools report it: its name, or 'name (dir)' when two open projects share a name. Only needed if several projects are open |

### `project_structure`

| Parameter | Type | Required | Description |
|---|---|---|---|
| `project` | string | no | Project as tools report it: its name, or 'name (dir)' when two open projects share a name. Only needed if several projects are open |

### `read_terminal`

| Parameter | Type | Required | Description |
|---|---|---|---|
| `id` | string | yes | Terminal id from list_terminals |
| `lines` | integer | yes | How many lines from the end |
| `project` | string | no | Project as tools report it: its name, or 'name (dir)' when two open projects share a name. Only needed if several projects are open |

### `review_tour`

| Parameter | Type | Required | Description |
|---|---|---|---|
| `title` | string | no | Tour title |
| `items` | array of object | yes |  |
| `items[].path` | string | yes | File path: project-relative or absolute. Outside the project root the user is asked to allow it once (secret-named files and preview types are refused there) |
| `items[].line` | integer | yes | 1-based line |
| `items[].note` | string | yes | What to look at here |
| `project` | string | no | Project as tools report it: its name, or 'name (dir)' when two open projects share a name. Only needed if several projects are open |

### `show_diff`

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | File path: project-relative or absolute. Outside the project root the user is asked to allow it once (secret-named files and preview types are refused there) |
| `proposed_text` | string | no | Full proposed file content |
| `from_ref` | string | no | git revision: branch, tag, sha, HEAD~n |
| `title` | string | no | Short title |
| `project` | string | no | Project as tools report it: its name, or 'name (dir)' when two open projects share a name. Only needed if several projects are open |

### `symbol_info`

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | File path: project-relative, or absolute inside an open project |
| `line` | integer | yes | 1-based line |
| `column` | integer | yes | 1-based character column on that line (a tab counts as one) |
| `project` | string | no | Project as tools report it: its name, or 'name (dir)' when two open projects share a name. Only needed if several projects are open |

<!-- /generated -->
