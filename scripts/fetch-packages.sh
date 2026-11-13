#!/usr/bin/env bash
# Stages the @deepseek-ai/dsh tarball into app/src/main/assets/packages/ so the
# app can install it offline on the device (remaining registry dependencies are
# fetched at first run). dsh is the only agent this build ships.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/versions.env

ASSETS="app/src/main/assets/packages"
mkdir -p "$ASSETS"

REGFLAGS=()
if [ -n "${NPM_REGISTRY:-}" ]; then
  REGFLAGS=(--registry "$NPM_REGISTRY")
fi

echo "== packing @deepseek-ai/dsh@$DSH_VERSION"
npm pack "@deepseek-ai/dsh@$DSH_VERSION" --pack-destination "$ASSETS" "${REGFLAGS[@]}"

ls -lh "$ASSETS"
echo "== done"
