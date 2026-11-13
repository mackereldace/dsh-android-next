#!/usr/bin/env bash
# Deliberate no-op on the dsh-only branch.
#
# Upstream this built the official ZCode web UI + server bundle (~243 MiB of
# APK assets). Zcode is not part of this build. The CI workflows still call
# this path and a workflow edit needs a token with `workflow` scope, so the
# script stays as a no-op until the workflow is cleaned up.
set -euo pipefail
echo "== fetch-zcode-web: skipped (dsh-only build, Zcode UI not shipped)"
