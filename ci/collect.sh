#!/usr/bin/env bash
# Collects the build output (jar + maven log), prints a summary, and pushes
# everything back into the branch under dist/ so the .jar can be fetched with git.
set -uo pipefail

OUT=/tmp/hd-out
DIST=dist
LOG=/tmp/mvn.log
SUM="${GITHUB_STEP_SUMMARY:-/dev/null}"
mkdir -p "$DIST"
rm -f "$DIST"/*.jar "$DIST"/BUILD-LOG.txt 2>/dev/null || true

shopt -s nullglob
jars=("$OUT"/*.jar)
status=1
[ -f "$OUT/status" ] && status=$(cat "$OUT/status")

{
  echo "## HolographicDisplays build"
  echo
  echo "- maven exit code: \`$status\`"
  echo "- commit: \`$GITHUB_SHA\`"
  echo "- run: $GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"
} | tee -a "$SUM"

if [ "${#jars[@]}" -gt 0 ]; then
  src="${jars[0]}"
  # Name the artifact after the version declared in the plugin.yml inside the jar.
  ver=$(unzip -p "$src" plugin.yml 2>/dev/null | awk -F': *' '/^version:/{print $2; exit}')
  [ -z "$ver" ] && ver="unknown"
  dest="$DIST/HolographicDisplays-$ver.jar"
  cp -f "$src" "$dest"
  {
    echo "- maven artifact: \`$(basename "$src")\`"
    echo "- plugin version: \`$ver\`"
    echo "- size: \`$(du -h "$dest" | cut -f1)\`"
    echo "- sha256: \`$(sha256sum "$dest" | cut -d' ' -f1)\`"
    echo
    echo '### plugin.yml packed in the .jar'
    echo '```yaml'
    unzip -p "$dest" plugin.yml
    echo '```'
    echo
    echo '### .jar contents (tail)'
    echo '```'
    unzip -l "$dest" | tail -n 12
    echo '```'
  } | tee -a "$SUM"
else
  echo "- no .jar produced" | tee -a "$SUM"
fi

{
  echo "build: $GITHUB_REPOSITORY@${GITHUB_SHA:-} run:$GITHUB_RUN_ID attempt:$GITHUB_RUN_ATTEMPT"
  echo "maven exit code: $status"
  echo
  if [ -f /tmp/hd-console.log ]; then
    echo "----- ci/build.sh console -----"
    cat /tmp/hd-console.log
    echo
  fi
  if [ -f "$LOG" ]; then
    echo "----- maven log -----"
    cat "$LOG"
  else
    echo "(no maven log)"
  fi
} > "$DIST/BUILD-LOG.txt"

# Push the results back to the branch that triggered the run.
BR="${GITHUB_REF_NAME:-}"
if [ "${SKIP_PUSH:-0}" = "1" ] || [ -z "$BR" ]; then
  echo "skip push (SKIP_PUSH=$SKIP_PUSH branch='$BR')" | tee -a "$SUM"
  exit 0
fi

rm -rf /tmp/hd-dist-backup && mkdir -p /tmp/hd-dist-backup
cp -f "$DIST"/* /tmp/hd-dist-backup/ 2>/dev/null || true

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git fetch -q origin "$BR"
git checkout -q -B hd-collect "origin/$BR"
mkdir -p "$DIST"
cp -f /tmp/hd-dist-backup/* "$DIST"/
if [ -n "$(git status --porcelain)" ]; then
  git add -f "$DIST"
  git commit -q -m "ci: build output [skip ci]"
  git push -q origin "HEAD:refs/heads/$BR" && echo "pushed to \`$BR\` ($DIST/)" | tee -a "$SUM"
else
  echo "no changes to push" | tee -a "$SUM"
fi
