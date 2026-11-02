# Decision log

Newest first. Each entry: Context, Decision, Consequences, and optionally what was rejected. A decision that
is replaced stays here, marked superseded, with a pointer to its replacement. Code comments don't carry
rationale; it lives here (see [AGENTS.md](../AGENTS.md)).

## D-080 The build refuses a pre-release IDE (2026-11-02, accepted)
Context: through 0.4.4 the plugin was built, pinned and live-tested only against 2026.3 EAP, Beta and RC builds,
because those were the IDEs at hand; no check asked whether users run them (D-075).
Decision: the build fails when `ideDir` names an IDE whose `product-info.json` has a `versionSuffix` (EAP, Beta,
RC). `-PallowPrerelease` overrides.
Consequences:
- Targeting a pre-release IDE at hand takes a deliberate flag. Verifying against one (`verifyIdeDirs`) is allowed.
- `platformVersion` (the download) isn't checked: a build number doesn't say whether it was a release, so a change
  to it has to be checked against JetBrains' release list by whoever makes it.

## D-079 The weekly Plugin Verifier run checks releases only (2026-11-02, accepted)
Context: the maintainer supports stable releases and doesn't track EAP builds (D-075); an issue for every EAP
breakage would be noise.
Decision: the scheduled job verifies against the newest PyCharm RELEASE only.
Consequences:
- A break in an upcoming release is found when it ships, not during its EAP.
- One IDE download a week. Supersedes D-048.

## D-078 A pairing balloon that closes without a click isn't a remembered denial (2026-11-02, accepted)
Context: the balloon expires without a click when it is cleared from the Notifications panel (seen once more in
GoLand with no cause found; a client restart alone doesn't expire it). That was remembered like a Deny click, so
the Claude process stayed refused for the IDE session though the user never chose Allow or Deny.
Decision: expiry without a click denies that call only. A Deny click and the 2-minute timeout stay remembered.
Consequences:
- Still fails closed: nothing but an Allow click pairs.
- A Claude whose balloon keeps closing can ask again; a ✕ only hides the balloon, so it still runs to the timeout.

## D-077 The terminal share action finds its menu at startup (2026-11-02, accepted)
Context: 2026.3 has a tool window tab menu (`ToolWindowTabContextMenu`); 2026.2 doesn't, and puts tab actions in
`ToolWindowContextMenu`. An `add-to-group` naming the 2026.3 group left the action out of every menu on 2026.2,
with no error, and the Plugin Verifier doesn't check action group ids.
Decision: an `actionConfigurationCustomizer` adds the action to the first of the two groups the IDE defines.
Consequences:
- One menu entry on each line; a warning in idea.log if neither group exists.
- Action group ids are invisible to the verifier, so menu placement needs a live check on each build line.

## D-076 libc is called through the JDK's FFM API, not JNA (2026-11-02, accepted)
Context: 2026.3 ships JNA as the plugin `com.intellij.modules.jna`; 2026.2 has no such plugin. A module depending
on it silently doesn't load on 2026.2, one that doesn't can't see JNA on 2026.3, and the verifier calls both
Compatible.
Decision: `LibC` binds libc with `java.lang.foreign` downcalls, capturing errno per call and keeping it per thread.
Consequences:
- No IDE dependency for native calls; both lines run JBR 25 and launch with `--enable-native-access=ALL-UNNAMED`.
- Each call copies through a confined arena. Tests run with the same JVM flag.

## D-075 The build range is 2026.2.4 through 2026.3 (2026-11-02, accepted)
Context: every 2026.3 build so far is a pre-release, and the maintainer doesn't want to track EAP. The stable release
is 2026.2.4 (262.11879).
Decision: compile against PyCharm 262.11879.98; `sinceBuild = 262.11879`, `untilBuild = 263.7307.*`.
Consequences:
- Still a narrow range: each new build line is a rebuild plus a verifier run.
- Compiled against 262, calls bind to the 262 shapes, which 263.7307 still provides.
- GoLand 2026.2.3 (262.10968) is below the range and unsupported.
- Supersedes D-045.

## D-074 A Compatible verdict doesn't prove the Python module is isolated (2026-11-02, accepted)
Context: the Plugin Verifier said Compatible for GoLand both before and after the Python split: it lists absent
optional modules as unavailable without flagging Python class references from other modules.
Decision: the evidence is the per-module dependency tree (`dependencies.txt`): Python modules appear only under
`idelink.python`. A live load in a non-Python IDE is the real proof.
Consequences:
- A CI check for this asserts on `dependencies.txt`, not on the verdict.

## D-073 Python analysis is an optional content module (2026-11-02, accepted)
Context: the backend depended on the Python plugin's modules, so the plugin couldn't fully load in other IDEs.
Decision: the backend declares the `languageAnalysis` extension point (`LanguageAnalysis`: symbol extras, module
and project SDK); `idelink.python` implements it and is declared `loading="optional"` explicitly. Module SDK =
the language's first, else the platform's; project SDK = the platform's first, else the language's.
Consequences:
- Without Python, `symbol_info` has no docstring and its header is the declaration line; tool descriptions say so.
- The precedence logic is pure and unit-tested; the Python PSI code is covered only by a live test.

## D-072 Terminal sharing is keyed by tab identity, with a fresh id per share (2026-11-02, accepted)
Context: ids handed to Claude must be opaque and die with the tab.
Decision: tabs map by identity to random 64-bit ids; closed tabs are pruned on every lookup, and unsharing then
sharing again yields a new id.
Consequences:
- An id from before an unshare never works again.

## D-071 Terminal output is bounded on both sides; the backend re-cuts it (2026-11-02, accepted)
Context: the thin client is untrusted and the RPC deserializes the whole answer before idelink sees it.
Decision: at most 500 lines and 256K characters. The frontend reads a bounded window; the backend re-tails to
the requested lines and characters, then scrubs, then applies `Plain`.
Consequences:
- A hostile client can still send a large RPC payload; deserialization isn't bounded by idelink.
- A secret cut at the boundary, or a PEM whose header is above the window, is not scrubbed.

## D-070 Shared terminal tabs are marked by a title suffix (2026-11-02, accepted)
Context: the terminal rewrites a tab's display name on every title change, so a one-off marker would vanish.
Decision: a listener re-appends " · shared with Claude" whenever the display name changes; unsharing removes
the listener and the suffix.
Consequences:
- Tab persistence and Rename use the terminal's own title, so the suffix isn't saved; it may flicker.

## D-069 Terminal answers are accepted only from the clients asked (2026-11-02, accepted)
Context: per-client topic sends are internal API, so requests are broadcast to the project's clients.
Decision: the backend records the project's CONTROLLER client ids when it sends, refuses an answer from a known
caller outside them, and accepts (logging `matched=unknown`) when the caller or the set is unknown.
Consequences:
- If RPC handlers run under an unexpected client id, every read fails loudly; the log line shows which.
- A refused answer consumes the request id: a racing client can deny service, not read data.

## D-068 Any code in the thin client can answer a terminal request (2026-11-02, accepted)
Context: the request id travels to the client in a topic event and the answer returns as an ordinary RPC.
Decision: accept the limit. Request ids are 128-bit random, single use, expire after 10 s and are checked
against the project; answers are bounded, scrubbed and passed through `Plain`.
Consequences:
- Other client-side code can feed Claude fake terminal text (prompt-injection shaped, not privilege); it can't
  read a tab. `read_terminal`'s description tells Claude to treat output as untrusted.

## D-067 Terminal reading lives in its own frontend module (2026-11-02, accepted)
Context: the reworked terminal keeps output on the client, behind the Terminal plugin's frontend API.
Decision: a separate content module, `idelink.terminal`, depends on the Terminal plugin; the rest of the
frontend doesn't.
Consequences:
- With the Terminal plugin disabled only that module fails to load; terminal calls time out after 10 s with a
  hint.

## D-066 Notification text is escaped as HTML on top of `Plain` (2026-11-02, accepted)
Context: notification content is HTML; `Plain` removes `<` but leaves character entities.
Decision: the notification prompter escapes `&`, `<` and `>` in the title and every line.
Consequences:
- An entity such as `&#x202E;` in a working directory shows literally instead of becoming a bidi control.

## D-065 Out-of-project files: refusals before the prompt, approval before any read (2026-11-02, accepted)
Context: D-027 lets the user allow files outside every project root once.
Decision: secret-bearing names (the path as given and every component of the real path) and preview types are
refused without a prompt, in every tool; the prompt comes before the IDE loads the file; a path inside another
open project is refused with "pass that project"; more than 20 outside files in one call are refused.
`get_selection` prompts the same way for an outside selection, returning the path only after approval.
Consequences:
- A directory whose name looks secret-bearing refuses everything under it.
- Claude reading a selection in an outside file raises a prompt each time.

## D-064 A startup self-check of internal APIs refuses calls before pairing (2026-11-02, accepted)
Context: after an IDE update, a changed internal API should fail every call up front, not mid-call.
Decision: at startup the plugin checks by reflection the internal and experimental members it uses; if any is
missing, tool calls get a "needs a rebuild for this IDE build" error before pairing, while `list_tools` stays
available for `--doctor`.
Consequences:
- The checked list must grow with each new internal API use; a test runs it against the build IDE.

## D-063 Every tool failure becomes a tool error, worded by kind (2026-11-02, accepted)
Context: a handler failure must reach Claude as an error that names the IDE build.
Decision: argument errors keep their message; other exceptions report message, class and build; errors (the
`LinkageError` family and the rest) say idelink may need a rebuild for this build. All but argument errors are
logged with the stack.
Consequences:
- An I/O failure isn't misreported as an upgrade problem; a missing method after an update says what to do.

## D-062 A bridge without a trusted Claude parent gets `list_tools` only (2026-11-02, accepted)
Context: `--doctor` run from a shell must list tools without pairing (D-037).
Decision: the bridge binary check stays mandatory; any parent-side failure (not a trusted claude, an untrusted
`trusted-claude` config, an unreadable parent) makes the connection list-only. The server answers `list_tools`
and refuses everything else, before tool lookup, the API check or pairing.
Consequences:
- A shell-started bridge learns only the public tool list and never raises a pairing prompt.
- The refusal reason reaches Claude as a tool error, so the fix is visible.

## D-061 Unpaired and list-only connections get a reason before the close (2026-11-02, accepted)
Context: the bridge could only report a bare hangup as "not admitted, pairing declined, or IDE gone".
Decision: the plugin writes `not paired with this IDE: <reason>` or `only list_tools is served to this
connection: <reason>`, then closes. Gate denials (wrong binary or uid) still get silence.
Consequences:
- Claude and `--doctor` can tell "no client connected" from "declined"; reasons carry only gate-derived facts.

## D-060 Only denials the user saw are remembered (2026-11-02, accepted)
Context: remembering denials stops a refused claude asking again until the user gives in (D-022).
Decision: remember a Deny click, a dismissal and a timeout; don't remember "no client connected" or a prompt
that failed to show.
Consequences:
- A claude that connected before the user's client did can ask once the client connects.

## D-059 Analysis fails fast while indexing and bounds every search (2026-11-02, accepted)
Context: index-backed calls block until the IDE leaves dumb mode.
Decision: a dumb project fails at once with "IDE is indexing, retry shortly", as does a search interrupted by
indexing; each search is bounded at 30 s.
Consequences:
- A call during indexing returns a retry message instead of blocking.

## D-058 `get_problems` reports the IDE's existing results only (2026-11-02, accepted)
Context: the IDE analyses only files open in an editor, and the backend can't reliably see which are open.
Decision: "open" means in the client's last reported open files. No results and not open: `not_analysed`;
results but not open: returned with a staleness warning. The tool never starts analysis.
Consequences:
- Without a report from the client, every empty result reads as `not_analysed`.

## D-057 `symbol_info` returns library and SDK headers and docstrings (2026-11-02, accepted)
Context: `go_to_definition` withholds library contents, but `symbol_info` is most useful on library symbols.
Decision: header and docstring are returned for project, library and SDK declarations (scrubbed, through
`Plain`, without paths), and withheld for anything else outside the project and for secret-bearing names.
Consequences:
- A little library source text reaches Claude.

## D-056 Analysis results name a location only inside the project (2026-11-02, accepted)
Context: an excluded `.venv` is still under the project root, and SDK names often embed interpreter paths.
Decision: a location is named only if the IDE puts it in project content and `PathScope` accepts its real path;
otherwise it is counted (usages) or given as a library name and qualified name (definitions). Excerpts get the
selection treatment (withheld for secret-bearing names, else scrubbed). Absolute paths in SDK and library names
are masked (a heuristic).
Consequences:
- Library code vendored inside the root counts as a library; a symlink out of the root is never named.

## D-055 Analysis tools keep platform calls behind an interface (2026-11-02, accepted)
Context: the IntelliJ test framework isn't available offline for this build, so PSI code can't be
fixture-tested.
Decision: `AnalysisTools` handles arguments, `PathScope`, positions, bounds, redaction and `Plain`;
`PlatformAnalysis` (behind `AnalysisIde`) only queries the IDE and returns raw results.
Consequences:
- Every safeguard is unit-tested against a fake; the PSI calls themselves need a live test.

## D-054 Plugin-internal declarations are `internal`; detekt runs clean on new code (2026-11-02, accepted)
Context: `UndocumentedPublic*` flagged every property of plugin-private classes, and KDoc on each would be noise.
Decision: declarations used only inside their Gradle module are `internal`; only `shared` (used by every module)
and the `LanguageAnalysis` extension point with `SymbolExtras` (used by `idelink.python`) stay public, with KDoc.
Classes registered in module XML are `internal` too, as the Plugin DevKit inspection "Private modifier used with
IntelliJ Platform extension class" recommends.
Consequences:
- Caps use named constants or a named `max =` argument; a rule is suppressed only with a one-line reason.
- Baselines only shrink: 238 entries became 95, none added.

## D-053 A trusted-claude entry ending in `/` trusts binaries directly inside that directory (2026-11-02, accepted)
Context: the native installer's launcher points into `~/.local/share/claude/versions/`, and every auto-update
installs a new versioned file, so an exact-path entry (D-052) broke on each update.
Decision: an entry ending in `/` matches a binary whose resolved path's parent is exactly that directory; exact
entries are unchanged. Install docs recommend Anthropic's signed apt/dnf/apk package first.
Consequences:
- Subdirectories and siblings sharing the prefix (`versions-evil/`) don't match; the binary is still pinned by
  device and inode for the connection.
- A directory entry is user-writable whenever the directory, a directory above it, or the binary is: same prompt
  line, audit line, `--doctor` warning and tradeoff text as D-052.
Rejected: prefix matching (admits subdirectories and look-alike siblings); globbing (more syntax for no gain).

## D-052 Extra Claude binaries are trusted only when root lists them (2026-11-02, accepted)
Context: the gate admitted only a root-owned `/usr/bin/claude`, so Claude Code's per-user native install was
refused, and the only fix was a system install.
Decision: an optional root-owned `/etc/idelink/trusted-claude` lists claude binaries by exact resolved path, read
with the rules of `trusted-ide-dirs` (D-039). Listed binaries may be user-writable; the default may not.
Consequences:
- Tradeoff, stated the same in the refusal and the docs: a Claude binary your account can write can be replaced
  by any process running as you, so the pairing prompt becomes the only protection.
- Each admission through a user-writable binary is audited as `claude-binary-user-writable`, the pairing prompt
  says so, and `--doctor` warns.
- An untrusted config admits no Claude; a self-updating install needs its new path listed after each update.
- The IDE's bundled claude (D-028) becomes listable too, with the same tradeoff.
Rejected: trusting a listed path by prefix or through symlinks (a sibling `claude-evil` or a re-pointed link).

## D-051 The root install step is a short script shipped with each release (2026-11-02, accepted)
Context: the bridge install runs as root, and a hash check on the source file leaves a window to swap it before
the copy.
Decision: `scripts/install-bridge.sh` (also a release asset, attested) copies the bridge into a root-owned
staging file, checks that copy's sha256 against the value the user passes, then moves it into place and sets
`chattr +i`. It stays short enough to read on one screen.
Consequences:
- Where `chattr` is unsupported the script says so and continues; ownership and mode are what the gate checks.
- Releases are created as drafts, so the maintainer reviews the assets before publishing.

## D-050 Docs and the bridge's tool list are checked against the plugin's (2026-11-02, accepted)
Context: `docs/tools.md` and `bridge/tools.json` both restate the plugin's tool list, and both drift silently
when a tool changes.
Decision: `scripts/tools_doc.py check` fails when a plugin tool has no section in `docs/tools.md`, when the
generated parameter tables are stale, or when `bridge/tools.json` differs in names or schemas.
`scripts/check.sh` runs it.
Consequences:
- Adding a tool breaks `check.sh` until its docs section and the bridge's list are updated.
- Descriptions are compared nowhere, as in the bridge's own skew check (D-041).

## D-049 Release artifacts carry GitHub build-provenance attestations (2026-11-02, accepted)
Context: users need to defend the plugin zip and bridge binary they install. Approval UI text can't vouch for
itself: anything that can draw the pairing notification can draw a reassuring one.
Decision: tagged releases are built by CI from the tagged source and attested with `actions/attest` (SLSA build
provenance). Trust rests on reviewed source plus a verified attestation, never on text shown in the IDE.
Consequences:
- `gh attestation verify <file> --repo danisec2601/idelink` checks an artifact (docs/security.md).
- A locally built artifact has no attestation; its trust is the builder's own review.
Rejected: `actions/attest-build-provenance`, which is now a wrapper over `actions/attest` and is not
recommended for new workflows.

## D-048 Weekly Plugin Verifier run against the newest PyCharm release and EAP (2026-11-02, superseded by D-079)
Context: the plugin uses internal APIs (D-046) and pins its build range (D-045), so a new IDE build can break
it without any change here.
Decision: a scheduled CI job verifies the plugin against the newest PyCharm RELEASE and EAP builds and opens an
issue for each failing IDE build that has no open issue yet.
Consequences:
- Breakage shows up as an issue before users hit it, not after.
- The run downloads two full IDEs a week.

## D-047 Public builds take the IDE from the `pycharm()` helper (2026-11-02, accepted)
Context: the build compiled against an installed IDE through a local `ideDir` path, which a public clone or CI
runner doesn't have.
Decision: when `ideDir` is set (and not blank) the build uses that IDE; otherwise it uses
`pycharm(platformVersion)` from JetBrains' repositories. CI passes `-PideDir=`. The Plugin Verifier uses the
same IDE unless `-PverifyChannel=RELEASE|EAP` selects the newest of a channel.
Consequences:
- A fresh clone builds with network access and nothing installed.
- `ideDir` is never committed: local builds pass `-PideDir=`, and `scripts/check.sh` takes it from
  `IDELINK_IDE_DIR`.

## D-046 Plugin Verifier fails on compatibility problems, not on deliberate API use (2026-11-02, accepted)
Context: the plugin uses internal APIs (ClientSessionsManager, ClientId) and experimental ones (split-mode RPC,
remote topics) on purpose. The verifier's default failure levels would fail on every build.
Decision: `verifyPlugin` fails on COMPATIBILITY_PROBLEMS, INVALID_PLUGIN, MISSING_DEPENDENCIES,
OVERRIDE_ONLY_API_USAGES and NON_EXTENDABLE_API_USAGES. INTERNAL_API_USAGES, EXPERIMENTAL_API_USAGES,
DEPRECATED_API_USAGES, SCHEDULED_FOR_REMOVAL_API_USAGES, COMPATIBILITY_WARNINGS, NOT_DYNAMIC and
PLUGIN_STRUCTURE_WARNINGS are reported, not failing. `verifyPluginStructure` runs with `ignoreWarnings = false`.
Consequences:
- The report under `plugin/build/reports/pluginVerifier/` lists the internal and experimental usages; read it
  when the IDE build changes.
- Adding INTERNAL_API_USAGES to the list fails the build today (checked).

## D-045 The build range stays pinned to one IDE build (2026-11-02, superseded by D-075)
Context: JetBrains recommends leaving `until-build` unset for 243+. The plugin depends on internal and
experimental APIs that can change in any build.
Decision: `sinceBuild = 263.7307`, `untilBuild = 263.7307.*`. Each IDE build the plugin supports is a deliberate
rebuild.
Consequences:
- An IDE update disables the plugin until a matching build exists (docs/troubleshooting.md).
- `verifyPluginProjectConfiguration` warns about `until-build`; the warning is expected.

## D-044 JetBrains Marketplace is not a v1 target (2026-11-02, accepted)
Context: Marketplace approval guidelines (v1.3) forbid internal API use and require a clean Plugin Verifier run.
idelink needs internal APIs to open files in a remote client's editor.
Decision: distribute through GitHub releases only.
Consequences:
- Users install from disk on both the backend and the thin client; Marketplace's automatic client sync doesn't
  apply.
- Revisit if the APIs become public.

## D-043 GitHub first, MIT license, one repository (2026-11-02, accepted)
Context: users at other organisations need to show their security reviewers the source of both halves.
Decision: publish the plugin and the bridge together in one public GitHub repository under the MIT license,
with CI-built tagged releases. The bridge moved into `bridge/` with its history.
Consequences:
- One tag versions the plugin and the bridge, which must match (D-041).
Supersedes D-012.

## D-042 v1 supports remote development only; local IDE support is v1.1 (2026-11-02, accepted)
Context: in split mode a LOCAL client session exists on the backend too, so "no remote session, use the local
one" would open files in an editor nobody sees. Local installs are untested.
Decision: v1 supports every setup where the IDE backend runs `remote-dev-server` (JetBrains Gateway or Toolbox
over SSH, Coder), with the trusted-directories config (D-039). A local (monolithic) IDE is v1.1, after testing
on a real local install. ClientNavigator opens files only in CONTROLLER sessions (REMOTE also includes
Code With Me guests).
Consequences:
- With a local IDE the bridge refuses the listener, and presentation tools would have no client to show in.
- Code With Me guests never receive opened files.

## D-041 Version skew is checked per connection, by names and schemas (2026-11-02, accepted)
Context: the bridge serves an embedded tool list (D-040) while calls go to whatever plugin is loaded, and the
IDE can restart with another plugin version mid-session.
Decision: on every new connection the bridge fetches the plugin's list, validates it fully, and compares tool
names and input schemas, ignoring order and descriptions. On a mismatch every call fails with an error naming
both sides' differences.
Consequences:
- Calls recover without restarting Claude once both versions match.
- A description-only change in the plugin isn't flagged; Claude keeps the bridge's wording until the next bridge
  build.

## D-040 The bridge serves an embedded tool list (2026-11-02, accepted)
Context: Claude Code reads the tool list once at session start. Taken from the IDE (D-019), it was empty when the
IDE wasn't running, and a squatter on the socket could put prose into every session's context.
Decision: the bridge embeds `tools.json` at build time and serves it whether or not the IDE is up. Each call
fails with "IDE not running" (or the specific reason) until it is. The plugin's list is still fetched and
validated, but only to detect skew; it never reaches Claude.
Consequences:
- A new or changed tool needs a new bridge build and a new root install step.
- A socket squatter can affect call results only, not the tool list.
Supersedes D-019.

## D-039 Trusted IDE directories come from a root-owned config (2026-11-02, accepted)
Context: the bridge trusted only `~/.cache/JetBrains/RemoteDev/dist`. Admins may install the backend elsewhere,
and a user-writable config would let anyone with the uid widen the trust.
Decision: `/etc/idelink/trusted-ide-dirs` lists absolute directories, one per line. It and its directory must be
root-owned and not group/world-writable; it is opened with O_NOFOLLOW|O_NONBLOCK and fstat'd. `/` and a file
listing no directories are errors. Only an absent file gives the default. Entries are compared literally with
the kernel-resolved `/proc/<pid>/exe`.
Consequences:
- A symlinked directory in the config matches nothing; the refusal message shows the resolved path to list.
- The default directory is user-writable, so the listener check stays a heuristic (D-009).
- `$HOME` doesn't move the default: the home directory comes from the passwd entry.

## D-038 Specific, actionable errors when the IDE can't be used (2026-11-02, accepted)
Context: one generic "can't reach the IDE" message sent users to the wrong fix.
Decision: the bridge tells apart a missing socket (IDE not running or plugin not loaded), a refused connection
(stale socket), an untrusted listener (names its executable and the trusted directories) and version skew.
Listener-controlled text is quoted ASCII-only. The messages say "your JetBrains IDE", not a product name.
Consequences:
- docs/troubleshooting.md maps each message to a fix.

## D-037 `idelink-bridge --doctor` checks the setup without pairing (2026-11-02, accepted)
Context: users and agents need to check an install without triggering a pairing request.
Decision: `--doctor` prints one ✓/✗ line per check (embedded list, trust config, socket, listener, plugin
`list_tools`, skew) and exits 1 if any failed. It sends only `list_tools`. Output is forced to ASCII apart from
the marks. A refused trust config is reported and the remaining checks still run. The plugin may admit a doctor
started from a shell for the read-only tool list only; every other request keeps the full gate and pairing.
Consequences:
- Until the plugin admits a shell-started doctor, its `list_tools` line fails from a shell, naming the parent
  process; a healthy setup then shows exactly that one ✗.

## D-036 The bridge tells Claude when to use idelink (2026-11-02, accepted)
Context: Claude needs to know the tools exist and when they help, without a separate skill to install.
Decision: the bridge returns MCP server `instructions` (under Claude Code's 4,096-character cut): when to use
each tool, that the first call asks the user to approve, and not to retry a denial in a loop.
Consequences:
- Changing the guidance needs a bridge build.

## D-035 Pairing is a non-modal notification that auto-denies (2026-11-02, accepted)
Context: the modal pairing dialog took focus; Enter was Deny, so a keystroke typed into it while working denied
the request. Notifications created as expiring ran their handler only after expiring.
Decision: pairing is a non-modal notification with Allow and Deny actions that never takes focus. It records the
answer from a plain action, then expires, and auto-denies after about two minutes.
Consequences:
- Nothing the user types can answer it; only a click can.
- An unanswered request fails closed.
Supersedes D-021.

## D-034 Agents install what they can and guide the user through the rest (2026-11-02, accepted)
Context: an agent on the IDE host can build, hash and register the bridge, but can't install client plugins,
run root steps or approve pairing.
Decision: docs/install.md is written for both humans and agents: host-side steps an agent runs itself, and the
user steps it walks its user through, in the order they must happen.
Consequences:
- The root install step and the pairing approval stay with the user by design.

## D-033 Code comments follow Kotlin and Go conventions, briefly (2026-11-02, accepted)
Context: generated code tends to over-comment: restating code, narrating history, essays. Bad comments mislead
the next reader, human or agent.
Decision: comment only what is surprising, non-standard or unclear from the code; one line typical, two at
most. No history or narration. KDoc: a one-line summary with [param] links; tags only when a line is genuinely
needed. Go: doc comments on exported names, starting with the name. Rationale goes in this log, longer prose in
docs/. Where the maintainer's preferences and Kotlin conventions differ, Kotlin conventions win.
Consequences:
- Enforced by detekt (D-032), golangci-lint (revive, godot) and `scripts/comment_lint.py` (a heuristic for
  length and narration).

## D-032 detekt comment rules on public API only, with a baseline (2026-11-02, accepted)
Context: Kotlin has no published check for comment length or narration, and documenting private code is a
matter of judgment here.
Decision: detekt runs on detekt's default rules plus UndocumentedPublicClass, UndocumentedPublicFunction,
UndocumentedPublicProperty, OutdatedDocumentation and KDocReferencesNonPublicProperty.
DocumentationOverPrivateFunction and DocumentationOverPrivateProperty stay off. Findings that existed when it
was wired are in `plugin/config/detekt-baseline-*.xml`. detekt's version moves with the Kotlin version, since it
runs its own Kotlin compiler.
Consequences:
- New code is held to the full rule set; the comment pass shrinks the baselines.
- The first build downloads detekt from Maven Central.

## D-031 Review cap before publishing (2026-11-02, accepted)
Context: open-ended review loops delay a release without making it safer.
Decision: one full fresh review, then one pass that checks only the fixes. Non-security findings after that
become issues.
Consequences:
- Security findings still block.

## D-030 The backend opens files in the client's session (2026-11-02, accepted)
Context: a client can't resolve a file reference for a file it hasn't loaded, and the MCP server thread runs in
no client's context, so opens sent to the client failed.
Decision: the backend opens the file itself under each remote client's ClientId (ClientNavigator). Tour clicks
go client to backend as "open stop N of tour T"; the backend opens only stops it resolved for the current tour,
after re-checking the path.
Consequences:
- No message from the client carries a path the backend would act on.
- A click on a replaced tour does nothing.

## D-029 Selections from `<name>.env` files are withheld too (2026-11-02, accepted)
Context: an end-to-end test returned the text of `test.env`; the secret-name list only matched `.env*`.
Decision: add `.+\.env` and `.+\.env\..*` to the secret-bearing names (D-020).
Consequences:
- `prod.env`, `app.env.local` and docker env files return the range only.

## D-028 Agents in the IDE's own AI chat are not admitted yet (2026-11-01, accepted)
Context: the IDE's in-IDE agent runs a bundled `claude` under a user-writable cache directory, which the gate's
parent check (root-owned `/usr/bin/claude`) refuses.
Decision: not yet. Admitting it would weaken the parent check to pairing alone. Revisit after v1.
Consequences:
- Only a Claude Code started from `/usr/bin/claude` can use idelink.

## D-027 Out-of-project paths ask the user (2026-11-01, accepted)
Context: a flat refusal (D-026) blocked legitimate files just outside the project; the IDE's built-in MCP
server checks only a path prefix, so a symlink in the project opened a file outside it.
Decision: a path outside every open project root raises an approval prompt in the IDE showing the real path
(symlinks followed), with Allow once / Deny, default Deny. In-project paths stay prompt-free.
Consequences:
- The user sees the resolved target, not the name Claude used.
Supersedes D-026.

## D-026 Paths outside the project root are refused (2026-11-01, superseded by D-027)
Context: the plugin must not become a way to present arbitrary files.
Decision: any path outside an open project root is refused.
Consequences:
- Files outside the project couldn't be shown at all.

## D-025 Show-only diffs (2026-11-01, accepted)
Context: Claude already edits files on disk through Claude Code, whose permission prompts are the write gate.
A diff viewer with Accept could write past that gate.
Decision: `show_diff` displays a change and never writes. Both sides are text-only contents (nothing on disk
the viewer could save to), read-only, with the on-disk file's sha256 at presentation time and a warning for
bidi or invisible characters. `from_ref` reads the old side with a fixed-argv `git cat-file` with fetching,
transports and inherited `GIT_*` variables disabled.
Consequences:
- The user applies nothing through idelink; Claude makes the edit with its normal tools.
- Semantic edits through the IDE (rename, reformat) are a separate, later decision.

## D-024 Files with rendering previews are refused (2026-11-01, accepted)
Context: the editor for Markdown, HTML, SVG, notebooks and diagram files renders them, so a file Claude wrote
could make the workstation's client fetch a URL.
Decision: `open_file` and tour stops refuse those types, by extension. `show_diff` shows them as text.
Consequences:
- A heuristic: preview plugins for other types, or types detected by content, aren't covered.

## D-023 Selection text is returned, with cheap protections (2026-11-01, accepted)
Context: selections are the most direct way for the user to point at code, and they can contain secrets.
Decision: `get_selection` returns text with: range only for secret-bearing file names (D-020), a pattern scrub
with per-read random placeholders, a 64K-character cap, and range only for multi-caret or column selections.
Consequences:
- Both protections are heuristics; the tool description says what they miss.

## D-022 Pairing answers are remembered per Claude process (2026-11-01, accepted)
Context: a denied process that can re-ask can wear the user down until they click Allow.
Decision: Allow and Deny are both remembered for the IDE session, keyed by the Claude process's pid and start
time. Settled answers don't wait for an open request.
Consequences:
- A denied session must be restarted to ask again.
- A new IDE session asks again.

## D-021 Pairing is a modal dialog with Deny as default (2026-11-01, superseded by D-035)
Context: pairing needed a prompt the user would see.
Decision: a modal dialog, Deny default and focused, with the Claude process's working directory passed through
Plain.
Consequences:
- It took focus, and a stray Enter denied.

## D-020 Secret-bearing file names are withheld by name and by real path (2026-11-01, accepted)
Context: a symlink such as `.env -> config/app.txt` would hide a secret-bearing name from a check of one path.
Decision: selection text is withheld when any path component of the editor's path or of the real path matches
the secret-name list (`.env*`, `*.pem`, `*.key`, `id_rsa*`, `*secret*`, `*credential*`, `.netrc`, `*.tfvars`,
`kubeconfig*` and others; see `Scrub.kt`), case-insensitively.
Consequences:
- Secrets in ordinarily named files (`config.yaml`) are not withheld; the scrub (D-023) is the only cover there.

## D-019 The tool list comes from the plugin, before pairing (2026-11-01, superseded by D-040)
Context: pairing at session start would prompt for every Claude session, including ones that never use the
IDE, and a tool list kept in two places drifts.
Decision: the bridge forwards the plugin's `list_tools` answer to Claude, bounded in shape, size, characters and
schema keywords. The plugin answers `list_tools` after the peer gate but before pairing; pairing is asked on the
first real tool call.
Consequences:
- Sessions that never call a tool never prompt (still true under D-040).
- The list was empty when the IDE was down, and nothing could judge what a description says: a squatter that
  passed the listener check could put up to 128 KiB of prose into every session's context.

## D-018 The thin client reports editor state to the backend (2026-11-01, accepted)
Context: in remote development the editor state lives in the thin client; the backend's editor manager reports
no open files.
Decision: a frontend module in the client reports project-relative open files, the active file, caret line and
selection bounds to the backend over platform RPC (debounced, 250 ms). It has no file system access, no tools,
and no network of its own; it never sends file contents or selection text.
Consequences:
- The plugin zip is installed on the client too.
- The backend re-checks every reported path against the project root and extracts selection text from its own
  document.

## D-017 Split-mode modules on platform RPC (2026-11-01, accepted)
Context: JetBrains' split-mode model separates shared, frontend and backend modules. Its RPC Gradle plugin was
published only as a release candidate, documented for an earlier IDE.
Decision: build the plugin as shared/frontend/backend modules with `@Rpc` interfaces and remote topics, on the
RC RPC plugin.
Consequences:
- The split-mode and RPC APIs are experimental; the weekly verifier run (D-048) watches them.

## D-016 Audit log of every decision and call (2026-11-01, accepted)
Context: the user needs to see what used the IDE, when.
Decision: the plugin appends one line per gate decision, pairing answer and tool call (tool, result, pids) to
`~/.local/state/idelink/audit.log` (0600, directory 0700). Tool output is never logged.
Consequences:
- Same-uid processes can edit the log; it is a record, not evidence.

## D-015 Terminal reading is opt-in per tab and scrubbed (2026-11-01, accepted)
Context: terminal contents are the one thing idelink exposes that isn't already on disk.
Decision: terminal tabs are readable only after the user shares each tab (default off); reads are on demand, the
last N lines, with ANSI stripped and soft wraps joined before scanning; hits become per-read random placeholders.
Per-tab state lives in the IDE. Passing or permanently redacting a value is decided by the user in an IDE panel;
any file keeps hashes and redacted context only.
Consequences:
- Surrounding text can still reveal which credential was redacted; the tool description says so.
Supersedes D-014.

## D-014 A review file holding secrets for pass/scram (2026-11-01, superseded by D-015)
Context: the user needs a way to release a false positive or block a value permanently.
Decision: a file listing detected values, which the user marks pass or scram.
Consequences:
- It would be a plaintext secret store Claude can read, with verdicts Claude could edit.

## D-013 All Claude-supplied text reaches the UI as plain text (2026-11-01, accepted)
Context: the UI renders on the workstation, and Swing renders labels starting with `<html>` as HTML, so a title
like `<html><img src=…>` would make the workstation fetch.
Decision: every string from Claude, a file, a terminal or another process goes through `Plain` in the backend
and again in the frontend: no `<`/`>`, newlines flattened where needed, control/bidi/invisible characters shown
as ⟦U+XXXX⟧, length capped.
Consequences:
- A convention at each call site, not a type: new call sites must remember it, and review checks it.

## D-012 The bridge lives in a separate repository (2026-11-01, superseded by D-043)
Context: the bridge is Go, the plugin Kotlin.
Decision: keep the bridge in its own repository.
Consequences:
- Two repositories to keep in version step.

## D-011 Every path is scoped to an open project's real root (2026-11-01, accepted)
Context: a path check by prefix lets a symlink inside the project reach outside it.
Decision: project roots come only from the IDE's open-project list, never from the bridge. Both root and
candidate are realpath'd; the candidate must be a regular file under the real root, and is re-checked just
before use. The same filter applies to what the IDE reports (active file, open files, selection).
Consequences:
- A symlink pointing out of the project is refused; one staying inside works.
- Files outside the root are counted in IDE state, not named.

## D-010 Strict parsing and bounded resources (2026-11-01, accepted)
Context: the socket accepts input from a process the gate only heuristically identifies.
Decision: one JSON object per line, capped at 2 MiB; no polymorphic deserialisation; strict per-tool schemas
(unknown keys and wrong types are errors); handlers off the UI thread in their own pool. The bridge caps replies,
schema depth and the whole tool list, and refuses control, format and private-use characters in descriptions.
Consequences:
- Oversized requests are refused before sending, so the connection stays usable.

## D-009 The bridge checks the listener too (2026-11-01, accepted)
Context: a stale or squatted socket works both ways: something else could listen where the IDE should.
Decision: the bridge requires the socket's peer to have its uid (kernel-enforced) and to run
`bin/remote-dev-server` under a trusted directory (D-039). Its home directory comes from passwd, not `$HOME`.
Consequences:
- A heuristic: the default directory is user-writable and the pid is read after connect.
- A general-purpose JVM (`jbr/bin/java`) is not accepted as the listener.

## D-008 The bridge is installed root-owned and immutable, built static (2026-11-01, accepted)
Context: the gate identifies the bridge by its executable; a file the user's uid can rewrite identifies nothing.
Decision: install the bridge at `/usr/local/libexec/idelink/idelink-bridge`, root-owned, mode 0755, `chattr +i`.
Build it static with CGO disabled so LD_PRELOAD can't inject into it. The plugin compares the peer's
`/proc/<pid>/exe` with that file by device and inode, and refuses a bridge that isn't root-owned or is
group/world-writable.
Consequences:
- Installing or upgrading the bridge needs root (docs/install.md).
- An upgraded bridge needs its sessions restarted; a running one whose file was replaced shows `(deleted)` and
  fails closed.

## D-007 Recommend restricting ptrace (2026-11-01, accepted)
Context: with `kernel.yama.ptrace_scope = 0`, any same-uid process can attach to a running bridge or claude after
the gate admitted it.
Decision: recommend `ptrace_scope` 1 or higher on the IDE host; it's the host owner's call.
Consequences:
- At 1, debugging an unrelated process needs root.

## D-006 Human pairing is the real discriminator (2026-11-01, accepted)
Context: the peer gate can't tell this Claude session from Claude's own Bash commands, hooks, MCP servers or
other sessions (D-005). The user is the one party a same-uid process can't impersonate.
Decision: the first tool call from each Claude process needs the user's approval in the IDE.
Consequences:
- The approval prompt can't vouch for itself (D-049); it shows the Claude process's pid and working directory,
  and the user approves only a request they just caused.

## D-005 The peer gate is a heuristic (2026-11-01, accepted)
Context: the plugin must refuse processes that no Claude Code started, and the JVM's peer credentials give no pid.
Decision: on accept, the plugin reads the peer's credentials and a pidfd (native, `SO_PEERPIDFD`), and admits it
only if it has the user's uid, its executable is the installed bridge (device and inode), and its parent is the
root-owned `/usr/bin/claude`; the pidfd must still be alive after the checks.
Consequences:
- Stops processes not spawned by any Claude Code.
- Doesn't stop Claude's own Bash tool, hooks or other sessions, ptrace, or a process that execs the bridge after
  connecting. Pairing (D-006) covers those.

## D-004 A Unix socket plus a stdio MCP bridge (2026-11-01, accepted)
Context: Claude Code talks to MCP servers over stdio or HTTP, and a TCP port is reachable by every local user.
Decision: the plugin listens on `/run/user/<uid>/idelink/ide.sock` in a 0700 directory. Claude Code launches the
bridge as a stdio MCP server; the bridge forwards calls over the socket.
Consequences:
- Only the user's uid can connect (directory mode); the gate decides the rest.
- The backend must share Claude's `XDG_RUNTIME_DIR`, so it needs a login session or lingering.

## D-003 Exclusions (2026-11-01, accepted)
Context: each capability idelink adds is reachable by whatever passes the gate and pairing.
Decision: never: terminal input or command execution, run configurations, builds, notebook kernels. Semantic
edits (rename, reformat, optimize imports) are a later, separate decision.
Consequences:
- idelink presents and reads; Claude Code remains the only actor.

## D-002 Threat model (2026-11-01, accepted)
Context: Claude already has the user's uid on the host, so it can read anything the IDE can on disk.
Decision: idelink is judged by what it adds: terminal contents (not on disk) and the ability to put UI in front
of the user (diffs, tours, highlights). The gate and pairing protect "who can read the user's terminals" and
"who can show the user a convincing diff". Same-uid attacks with easier routes (writing files the user trusts,
ptrace) are out of scope for structural guarantees.
Consequences:
- docs/security.md lists what idelink does and doesn't guard against.
- The listener check is load-bearing for the integrity of Claude's context, not just for access.

## D-001 A narrow custom plugin, not an existing IDE integration (2026-11-01, accepted)
Context: Claude Code runs on the IDE host; the user works in a JetBrains thin client on a workstation.
Decision: build a backend plugin with exactly the verbs needed (present files and diffs, read IDE state, IDE
analysis, opted-in terminal reads) and nothing else.
Consequences:
- The plugin runs where the code is and sees only that host.
Rejected:
- The IDE's built-in MCP server on the client: its tools act on the workstation's file system and terminal.
- The built-in MCP server on the backend: a broad surface including unprompted command execution, with a
  per-start token and no clean handoff.
- The Claude Code JetBrains plugin: its model-visible tools can't open files or navigate.
