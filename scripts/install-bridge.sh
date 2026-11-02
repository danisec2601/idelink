#!/bin/sh
# Installs the bridge root-owned and immutable where the plugin expects it. Refuses a file whose sha256 differs.
# Usage: sudo sh install-bridge.sh <idelink-bridge file> <expected sha256>
set -eu
[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }
[ $# = 2 ] || { echo "usage: install-bridge.sh <idelink-bridge file> <expected sha256>" >&2; exit 2; }
src=$1 want=$2
dir=/usr/local/libexec/idelink
dst=$dir/idelink-bridge
stage=$dir/.staging

install -d -o root -g root -m 0755 "$dir"
# Hash the root-owned copy, not the source, so nothing can swap the file between check and install.
install -o root -g root -m 0755 "$src" "$stage"
got=$(sha256sum "$stage" | cut -d' ' -f1)
if [ "$got" != "$want" ]; then
    rm -f "$stage"
    echo "sha256 mismatch: got $got, want $want; nothing installed" >&2
    exit 1
fi
if [ -e "$dst" ]; then chattr -i "$dst" 2>/dev/null || true; fi
mv -f "$stage" "$dst"
if ! chattr +i "$dst" 2>/dev/null; then
    echo "note: chattr +i unsupported here; root ownership and mode 0755 are what the plugin checks"
fi
ls -l "$dst"
sha256sum "$dst"
