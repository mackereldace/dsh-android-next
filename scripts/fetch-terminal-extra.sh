#!/usr/bin/env bash
# Stages the static ripgrep that backs dsh's glob/grep on Android.
#
# The file name is historical: upstream this script staged codex / claude /
# opencode plus the Alpine musl loader for the terminal picker. Those agents
# are gone from this build, but the CI workflows still call this exact path —
# and a workflow-file edit needs a token with `workflow` scope, so the name
# stays until the workflow is cleaned up.
#
# Node reports platform "android", for which @vscode/ripgrep has no package, so
# the linux-arm64 build is staged behind the name the resolver looks for. It is
# statically linked and runs as-is on bionic.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO="$(pwd)"
source scripts/versions.env

ASSETS="$REPO/app/src/main/assets/terminal-extra"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$ASSETS"

echo "== ripgrep $RIPGREP_VERSION (@vscode/ripgrep-linux-arm64, static)"
RG_META=$(curl -fsSL "https://registry.npmjs.org/@vscode/ripgrep-linux-arm64/$RIPGREP_VERSION")
RG_TARBALL=$(echo "$RG_META" | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>console.log(JSON.parse(d).dist.tarball))")
curl -fsSL "$RG_TARBALL" -o "$WORK/rg.tgz"
mkdir -p "$WORK/rgx"
tar -xzf "$WORK/rg.tgz" -C "$WORK/rgx"
cp "$WORK/rgx/package/bin/rg" "$ASSETS/rg"
chmod 755 "$ASSETS/rg"
echo "== ripgrep staged ($(du -h "$ASSETS/rg" | cut -f1))"

echo "{\"ripgrep\":\"$RIPGREP_VERSION\"}" > "$ASSETS/versions.json"

ls -la "$ASSETS"
echo "== done"
