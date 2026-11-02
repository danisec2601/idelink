# Troubleshooting

Start with `--doctor`, then look up the message. The plugin's own record of every refusal is in
`~/.local/state/idelink/audit.log` on the IDE host (`tail -20` it; it holds pids and reasons, no file contents).

## `idelink-bridge --doctor`

```sh
/usr/local/libexec/idelink/idelink-bridge --doctor
```

It prints one line per check, ✓, ⚠ (a warning) or ✗, and exits 1 if any ✗. It sends the IDE nothing but a request for its
tool list, so it never asks you to pair. Once a check fails, the checks after it that depend on it print
"not tried" or "not compared"; fix the first ✗.

| Line | ✗ means | Fix |
|---|---|---|
| `idelink-bridge <version>, N tools embedded` | (always ✓; a broken build exits earlier with "the embedded tool list is malformed") | rebuild or re-download the bridge |
| `trust config: …` | `/etc/idelink/trusted-ide-dirs` exists but is refused: not a regular file, not owned by root, writable by group or others, its directory isn't root-owned or is writable, a line isn't an absolute path, a line is `/`, or it lists no directories | fix the file as the message says (`sudo chown root:root`, `sudo chmod 0644`, absolute paths only), or remove it to use the default. While it's refused, the bridge won't start for Claude at all |
| `claude config: …` | `/etc/idelink/trusted-claude` exists but is refused, for the same reasons as `trust config` (it lists no paths instead of no directories) | fix it as root as the message says, or remove it to trust only `/usr/bin/claude`. While it's refused, the plugin admits no Claude at all |
| `claude <path>: …` (✗) | a trusted Claude path doesn't exist, isn't a regular file (or, for an entry ending in `/`, a directory), or isn't the resolved path (a symlink, or under one) | list the path the line suggests, or remove a stale line |
| `claude <path> is user-writable …; the default must be root-owned` | no config, and `/usr/bin/claude` isn't root-only | reinstall Claude Code from Anthropic's apt/dnf/apk package, or list the path in `/etc/idelink/trusted-claude` and accept the tradeoff below |
| ⚠ `claude <path>: … user-writable …` | a listed Claude binary or directory entry, or a directory above it, isn't root-only | a warning, not a failure: a Claude binary your account can write can be replaced by any process running as you, so the pairing prompt becomes the only protection |
| `parent is <name> (…), not a trusted Claude binary (doctor is always list-only)` | (✓, information) what started this `--doctor`; from a shell it's your shell | nothing: the doctor only ever lists tools |
| `no trusted IDE directories` | no config file and no usable home directory for the default | create `/etc/idelink/trusted-ide-dirs` |
| `socket …: the IDE isn't running, or the idelink plugin isn't loaded` | no socket at `/run/user/<uid>/idelink/ide.sock` | open the project in the IDE; if it is open, the plugin didn't load: see [IDE updates](#the-ide-updated-and-idelink-stopped-working) and [backend install](#the-plugins-page-shows-the-new-version-but-the-backend-runs-the-old-one). If `/run/user/<uid>` itself doesn't exist (a container without a login session), the plugin can't create its socket: enable lingering (`loginctl enable-linger <user>`) or have root create the directory owned by you, mode 0700 |
| `socket … is not a socket` | something else occupies the path | close the IDE, remove the file, reopen |
| `listener: nothing is listening on … (a stale socket)` | the IDE exited without cleaning up | open the project in the IDE again |
| `listener: socket owned by uid N, not us` | another user's process holds the socket | investigate: this should not happen in a 0700 directory |
| `listener: the process on the IDE socket (pid N, "…") is not a trusted JetBrains RemoteDev IDE` | the listener isn't `bin/remote-dev-server` under a trusted directory | if the path shown is your IDE, list a directory containing it (the path shown minus `/bin/remote-dev-server`, or a parent of that) in `/etc/idelink/trusted-ide-dirs`; if it isn't your IDE, something else is listening where the IDE should: stop and find out what |
| `plugin list_tools: … (the plugin admits the bridge only when Claude Code started it; this one's parent is "…")` | the plugin's gate refused a bridge started from a shell | expected when you run `--doctor` from a shell against a plugin that doesn't admit a doctor; the other lines are what matter |
| `plugin list_tools: IDE closed the connection or timed out …` | the gate refused the bridge | read the newest `deny` line in the audit log (next section) |
| `plugin list_tools: the IDE plugin's tool list is malformed …` | the plugin sent a list the bridge won't accept | reinstall the plugin from a release; if it persists, report it |
| `tool lists differ (…): the bridge and plugin are different versions` | [version skew](#version-skew) | install matching versions |

## Refusals in the audit log

| `deny` reason | Meaning | Fix |
|---|---|---|
| `bridge binary untrusted: … not owned by root` / `writable by group/other` | `/usr/local/libexec/idelink/idelink-bridge` isn't installed as the plugin requires | redo [install step 2](install.md#2-install-the-bridge-as-root-user) |
| `peer N is not the bridge binary` | something other than the installed bridge connected, or the bridge was replaced while that process ran | restart the Claude Code session; if you didn't start anything, find out what did |
| `list-only` `this bridge's parent runs …, which is not a trusted Claude Code binary` | the bridge wasn't started by a trusted Claude binary; Claude gets the same message as a tool error | if the path shown is your Claude Code: install it from Anthropic's apt/dnf/apk package instead, or as root add a line to `/etc/idelink/trusted-claude`, which must be root-owned and not group/other-writable ([install](install.md#per-user-claude-code-optional)). For the native installer (a path under `~/.local/share/claude/versions/`), list that directory with a trailing `/` (absolute path, e.g. `/home/<you>/.local/share/claude/versions/`), since each auto-update adds a new versioned file; otherwise list the exact path shown, unless it is a script interpreter (`node` for an npm install, `python3`, `bash`, …): trusting an interpreter trusts every script it runs, so install the native or apt/dnf/apk package instead. Tradeoff: a Claude binary your account can write can be replaced by any process running as you, so the pairing prompt becomes the only protection. The IDE's own AI chat agent runs a bundled, user-writable claude; listing it carries the same tradeoff |
| `list-only` `… (… is owned by uid N)` | no config, and `/usr/bin/claude` (or a directory above it) isn't root-only | reinstall Claude Code from Anthropic's apt/dnf/apk package, or list it as above |
| `list-only` `… /etc/idelink/trusted-claude …; until it is fixed (as root) or removed, no Claude Code binary is trusted` | the config exists but can't be trusted | fix it as root or remove it |
| `claude-binary-user-writable` | not a refusal: a listed, user-writable Claude binary (or one in a listed user-writable directory) was admitted (logged on every connection) | nothing, if you chose this; see the tradeoff above |
| `unpaired … declined` / `denied earlier` | pairing was denied or ignored (closing the balloon with ✕ only hides it; it denies at the 2-minute timeout); a Deny click or the timeout holds for that Claude process for the rest of the IDE session, while a balloon cleared from the Notifications panel without a click denies only that call | restart the Claude Code session and allow the next request |

## Version skew

```
idelink: the bridge (0.5.2) and the IDE plugin are different versions (open_file has a different schema);
update them together, then retry
```

The plugin and the bridge come from the same release and must be upgraded together
([install](install.md#upgrading)). The check runs on every new connection, so once both match, calls work again
without restarting Claude.

## The IDE updated and idelink stopped working

The plugin declares the narrow range of IDE builds it supports (0.5.2: 262.11879 to 263.7307.*, that is 2026.2.4
through 2026.3), so an IDE update outside it disables it (the IDE says it is
incompatible, and `--doctor` shows the socket missing). Either install the idelink release built for the new
IDE build, or build one yourself with `platformVersion` set to the new build
([Building from source](install.md#building-from-source)). If your IDE updates itself, consider holding updates
until a matching release exists.

## The Plugins page shows the new version, but the backend runs the old one

Installing from the Plugins page reaches the thin client only, and a queued `idelink.zip` in the backend's cache
reinstalls the old version on each start. Redo [install step 3](install.md#3-install-the-plugin-on-the-backend-user-ide-stopped)
with the IDE stopped, including removing the queued zip, and check the version the backend logged.

## Calls succeed but nothing shows in the IDE

- The result says `no report from this project's IDE client yet`: the client half isn't installed or loaded on
  your workstation ([install step 4](install.md#4-install-the-plugin-on-the-thin-client-user)).
- The result says `no IDE client is connected to this project`: no remote client has the project open. A local
  IDE isn't supported in v1.
- Presentation results say `arrival: asked`: a notification on the client is waiting for Show / Start tour
  (look in the Notifications tool window if the balloon was closed). `arrival: no_reply`: the client plugin
  didn't answer within 3 s; check it's installed and the same version as the backend's.
- `arrival: direct` or `allowed` but nothing visible: idelink can't tell whether the client showed it. Check that the project
  is open in the client you're looking at.

## Claude doesn't have the idelink tools

Tools appear in sessions started after `claude mcp add`. Check `claude mcp list` shows `idelink`, then start a
new session.
