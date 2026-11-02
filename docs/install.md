# Installing idelink

For people and for coding agents. If you are an agent installing this for your user, read
[For agents](#for-agents) first, then follow the steps in order: each step says who does it.

## What you need

- **A remote-development setup**: the JetBrains IDE backend (`remote-dev-server`) runs on a Linux host, and you
  work in the thin client on your workstation. Started from JetBrains Gateway or Toolbox over SSH, or from Coder.
  **A local (non-remote) IDE is not supported in v1**; support is planned for v1.1.
- **An IDE build in the release's range** (0.5.2: 2026.2.4 through 2026.3, builds 262.11879 to 263.7307.*;
  built against PyCharm 2026.2.4). Other builds disable the plugin
  ([troubleshooting](troubleshooting.md#the-ide-updated-and-idelink-stopped-working)).
- **The IDE host**: Linux x86_64, kernel 6.5 or newer, root access for one step, and `/run/user/<uid>` present
  for the user who runs the IDE (a systemd login session or lingering provides it; on Coder, see step 2).
- **Claude Code on the IDE host at `/usr/bin/claude`, root-owned** (Anthropic's signed apt/dnf/apk package is
  the recommended way), or a per-user install listed in `/etc/idelink/trusted-claude`
  ([below](#per-user-claude-code-optional)). The plugin admits the bridge
  only when its parent process is a trusted Claude binary.
- **git 2.45 or newer** for `show_diff` with `from_ref` (older git is refused, because it can run commands
  from a repository's config; everything else works without it).

Where things go:

| What | Where | Installed by |
|---|---|---|
| Plugin, backend half | the IDE's plugins directory on the host, e.g. `~/.local/share/JetBrains/PyCharm2026.2/idelink/` | user (IDE stopped) |
| Plugin, client half | the thin client on the workstation | user |
| Bridge | `/usr/local/libexec/idelink/idelink-bridge`, root-owned, immutable | user, as root |
| Trusted IDE directories (optional) | `/etc/idelink/trusted-ide-dirs`, root-owned | user, as root |
| Trusted Claude binaries (optional) | `/etc/idelink/trusted-claude`, root-owned | user, as root |
| MCP registration | Claude Code's user config | user or agent |

The backend itself lives under `~/.cache/JetBrains/RemoteDev/dist/`, in a directory named
`<product>-<build>` (Coder's usual layout) or `<hash>_<product>-<build>` (Gateway's). Both are trusted by
default.

## Steps

Order matters where stated. Steps 2, 3, 4 and 5 can happen in any order, but all before step 6.

### 1. Get the release files (agent or user)

Download from the GitHub release: `idelink-<version>.zip` (the plugin, both halves), `idelink-bridge`
(static, linux-amd64), `install-bridge.sh` and `SHA256SUMS`. Then verify them ([security.md](security.md#verifying-a-release)):

```sh
gh attestation verify idelink-<version>.zip --repo danisec2601/idelink
gh attestation verify idelink-bridge --repo danisec2601/idelink
sha256sum -c SHA256SUMS
```

Or build them yourself: see [Building from source](#building-from-source). The plugin and the bridge must come
from the same release.

### 2. Install the bridge as root (user)

The plugin trusts the bridge only if it is root-owned and not writable by anyone else, at exactly this path.
`install-bridge.sh` (in the release and in `scripts/`) copies the file into a root-owned place, checks the hash
of that copy, and installs it with `chattr +i`. It fits on one screen; read it before running it as root.

```sh
sudo sh install-bridge.sh idelink-bridge <sha256 of idelink-bridge from SHA256SUMS>
```

Where `chattr` isn't supported (some container filesystems) the script says so and carries on: root ownership
and mode 0755 are what the plugin checks. On Coder, a workspace rebuild may reset everything outside your home directory; put this step
in the workspace image or a startup script. A Coder workspace usually has no login session, so `/run/user/<uid>`
(a tmpfs, empty after every restart) may be missing: have the same root-run startup step create it, owned by the
user who runs the IDE and mode 0700:

```sh
[ -d /run/user/<uid> ] || install -d -m 0700 -o <user> -g <user> /run/user/<uid>
```

Optional, if the IDE backend lives outside `~/.cache/JetBrains/RemoteDev/dist` (an admin-installed backend, for
example): list each directory that contains its `bin/remote-dev-server`, one absolute path per line.

```sh
sudo install -d -o root -g root -m 0755 /etc/idelink
printf '%s\n' /opt/jetbrains/backends | sudo tee /etc/idelink/trusted-ide-dirs
sudo chmod 0644 /etc/idelink/trusted-ide-dirs
```

The file replaces the default rather than adding to it, so list the default too if you still use it (as an
absolute path, without `~`). Paths are compared literally with the IDE's resolved executable path; don't list a
symlink.

#### Per-user Claude Code (optional)

Recommended instead: install Claude Code from Anthropic's signed apt, dnf or apk repository
([Claude Code setup](https://code.claude.com/docs/en/setup#install-with-linux-package-managers)). The package is
installed by root, so nothing under your account can replace it. Check where it landed with
`readlink -f "$(command -v claude)"`: at `/usr/bin/claude` it is trusted without any config; elsewhere, list that
path as below.

Without `/etc/idelink/trusted-claude`, the plugin trusts only a root-owned `/usr/bin/claude`. To allow a Claude
Code installed under your home directory, as root list it in `/etc/idelink/trusted-claude`, which must be
root-owned and not group/other-writable. Each line is one of:

- an exact binary path, matched against the running executable after symlinks (the path the refusal message
  shows); `--doctor` flags a listed symlink;
- a directory ending in `/`, which matches any binary directly inside it (not in its subdirectories).

The native installer's `~/.local/bin/claude` is a symlink into `~/.local/share/claude/versions/`, and each
auto-update installs a new versioned file there, so list that directory (as an absolute path, with the trailing
`/`) rather than one version:

```sh
sudo install -d -o root -g root -m 0755 /etc/idelink
printf '%s\n' /usr/bin/claude "$HOME/.local/share/claude/versions/" | sudo tee /etc/idelink/trusted-claude
sudo chmod 0644 /etc/idelink/trusted-claude
```

Tradeoff: a Claude binary your account can write can be replaced by any process running as you, so the pairing
prompt becomes the only protection. A directory entry counts as user-writable whenever the directory (or the
binary in it) is, and a directory under your home always is. Such a binary is allowed anyway; each connection
through it is logged as `claude-binary-user-writable` in the audit log, the pairing notification says "this
Claude binary can be modified by your user account", and `--doctor` warns. As with `trusted-ide-dirs`, the file
replaces the default (list `/usr/bin/claude` too if you use it), and a file that exists but can't be trusted
admits no Claude at all. An exact-path entry stops matching when Claude Code updates itself to a new path; the
refusal then names the new path.

Recommended while you're root: `sysctl kernel.yama.ptrace_scope=1` (persist it in `/etc/sysctl.d/`); see
[security.md](security.md#what-it-does-not-guard-against).

### 3. Install the plugin on the backend (user, IDE stopped)

Installing from the IDE's Plugins page installs only the client half. For the backend:

1. Close the project in the thin client and make sure the backend has exited (no `remote-dev-server` process for
   your user on the host).
2. Remove the previous version, then unzip into the IDE's plugins directory. Unzipping over an old copy would
   leave its jar beside the new one, since the jar's name carries the version:
   `rm -rf ~/.local/share/JetBrains/PyCharm2026.2/idelink`
   `unzip idelink-<version>.zip -d ~/.local/share/JetBrains/PyCharm2026.2/`
3. Delete any queued copy, which would otherwise replace yours on the next start:
   `rm -f ~/.cache/JetBrains/PyCharm2026.2/plugins/idelink.zip`
4. Reopen the project. Check the version the backend loaded (this prints only the match; the log holds session
   tokens, so don't share it whole):
   `grep -o 'idelink ([0-9][0-9.]*)' ~/.cache/JetBrains/PyCharm2026.2/log/idea.log | tail -1`

For another JetBrains IDE, replace `PyCharm2026.2` with its directory name (`ls ~/.local/share/JetBrains/`).

### 4. Install the plugin on the thin client (user)

In the thin client on your workstation: Settings → Plugins → ⚙ → Install Plugin from Disk…, choose the same
`idelink-<version>.zip`, and restart the client when asked. Do this even if the Plugins page already lists idelink
as "on backend": that is the backend's copy, and the client half isn't running until you install it here. The client
half reports what you're looking at and shows diffs and tours; without it, idelink can't see your editor (tools then
warn "no report from this project's IDE client").

On its first start, a backend may try to download the client's plugins, idelink included, from JetBrains
Marketplace. idelink isn't published there, so that download fails harmlessly and the step 3 copy is what loads.

### 5. Register the bridge with Claude Code (agent or user)

On the IDE host, as the user who runs Claude Code:

```sh
claude mcp add --scope user idelink -- /usr/local/libexec/idelink/idelink-bridge
```

`--scope user` makes it available in every project; use `--scope local` to limit it to one.

### 6. Check the setup (agent or user)

With the project open in the IDE:

```sh
/usr/local/libexec/idelink/idelink-bridge --doctor
```

Every line should be ✓ or ⚠ (a listed user-writable Claude binary). What each ✗ means:
[troubleshooting.md](troubleshooting.md#idelink-bridge---doctor).
Run from a plain shell, the `plugin list_tools` line can fail with "the plugin admits the bridge only when
Claude Code started it"; on an otherwise healthy setup that line alone is expected.

### 7. Pair (user)

Start a new Claude Code session (sessions started before step 5 don't have the tools) and ask Claude to use the
IDE, e.g. "show me the active file in the IDE". The IDE shows a notification: "Claude Code pid N … wants to
connect". Click **Allow** only if you just asked for this. Ignoring it denies after about two minutes; a denial
holds until that Claude session restarts. The balloon's ✕ only hides it: the request stays answerable in the
Notifications panel until that two-minute timeout denies it.

## Upgrading

Upgrade the plugin (steps 3 and 4) and the bridge (step 2) together, then restart Claude Code sessions. With
mismatched versions every call fails with a version-skew error that names the difference.

Uninstalling idelink from the thin client's Plugins page also removes the backend's copy (the `idelink`
directory from step 3), so after a client-side uninstall, reinstall both halves.

## Building from source

Needs JDK 25, Go 1.27.3 (the `toolchain` in `bridge/go.mod`; a newer Go fetches it unless `GOTOOLCHAIN=local`), and network access to Maven Central and JetBrains'
repositories on the first build.

```sh
cd plugin && ./gradlew buildPlugin -PideDir=      # downloads the IDE named by platformVersion
                                                   # or -PideDir=/path/to/installed/backend to use that one
ls build/distributions/idelink-*.zip
cd ../bridge && CGO_ENABLED=0 go build -trimpath -buildvcs=false -ldflags=-buildid= -o idelink-bridge .
```

With Go 1.27.3 the bridge is byte-identical to the release's `idelink-bridge`, so `sha256sum` checks it against
`SHA256SUMS`. Then continue at step 2 with your own files and their `sha256sum`.

## For agents

You're running on the IDE host as your user. You can do steps 1, 5 and 6 yourself, and help with the rest:

- **Do**: download the release (tell your user first: it's network access), verify the attestations and hashes,
  or build from source; run `claude mcp add` (step 5); run `--doctor` and read its output (step 6); check the
  backend's loaded version with the `grep -o` in step 3.
- **Guide, don't do**:
  - step 2 needs root. Give your user the exact command with the real hash filled in and ask them to read the
    script first; don't run `sudo` yourself unless they explicitly ask you to.
  - step 3 needs the IDE stopped. Don't kill your user's IDE: ask them to close it, then you may unzip and
    clear the queued zip yourself once they confirm, and they reopen it.
  - step 4 happens on their workstation.
  - step 7 is theirs alone, by design: the approval is the one thing a process on this host can't fake. Tell
    them a notification will appear and to allow it only now. If a call is denied, don't retry in a loop.
- After step 5, the tools appear only in a **new** Claude Code session; say so.
- If `--doctor` or a call fails, match the message in [troubleshooting.md](troubleshooting.md) before changing
  anything.
- Never print `idea.log` whole: it contains remote-development session tokens.
