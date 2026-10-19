#!/usr/bin/env bash
# Read-only analysis of a pending upstream sync. Touches neither the working tree nor any
# branch: the merge is simulated with `git merge-tree`. Safe to run at any time.
#
#   bash .claude/skills/upstream-sync/scripts/analyze.sh [target-ref]   (default: main)
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 1
TARGET="${1:-main}"
UP="upstream/main"

section() { printf '\n=== %s ===\n' "$1"; }

section "Fetch"
git remote get-url upstream >/dev/null 2>&1 || { echo "No 'upstream' remote. Add it: git remote add upstream https://github.com/Grashjs/cmms.git"; exit 1; }
git fetch upstream --no-tags -q && git fetch origin -q && echo "ok"

section "Working tree"
if [ -n "$(git status --porcelain)" ]; then
  echo "NOT CLEAN - other work in progress (Lars works in parallel). Do not merge over it:"
  git status --short | head -20
else
  echo "clean"
fi
echo "branch: $(git rev-parse --abbrev-ref HEAD)   $TARGET vs origin/main (ahead/behind): $(git rev-list --left-right --count "$TARGET"...origin/main | tr '\t' '/')"

section "Divergence"
BASE=$(git merge-base "$UP" "$TARGET")
read -r BEHIND AHEAD < <(git rev-list --left-right --count "$UP"..."$TARGET")
echo "merge-base: $(git log -1 --format='%h %ad %s' --date=short "$BASE")"
echo "upstream ahead (to take): $BEHIND    fork-only commits: $AHEAD"
[ "$BEHIND" = "0" ] && { echo "Nothing to sync."; exit 0; }
echo "upstream head: $(git log -1 --format='%h %ad %s' --date=short "$UP")"

section "Upstream commits"
git log --format='%h %ad %s' --date=short "$TARGET".."$UP" | head -60
[ "$BEHIND" -gt 60 ] && echo "... ($BEHIND total)"

section "Upstream files by area"
git diff --name-only "$BASE" "$UP" | awk -F/ '{a=$1; if($1=="api"||$1=="frontend"||$1=="mobile") a=$1"/"$2"/"$3; c[a]++} END{for(k in c) printf "%4d  %s\n", c[k], k}' | sort -rn | head -20
git diff --shortstat "$BASE" "$UP"

section "Simulated merge"
MT=$(git merge-tree --write-tree --name-only "$TARGET" "$UP" 2>&1); RC=$?
TREE=$(printf '%s\n' "$MT" | head -1)
CONFLICTS=$(printf '%s\n' "$MT" | grep '^CONFLICT' || true)
if [ -z "$CONFLICTS" ]; then echo "no conflicts (exit $RC)"; else printf '%s\n' "$CONFLICTS"; fi

section "Changed on both sides (includes clean merges - read these too)"
comm -12 <(git diff --name-only "$BASE" "$TARGET" | sort) <(git diff --name-only "$BASE" "$UP" | sort)

section "Divergence table rows touched (CLAUDE.md 'Keeping in step with upstream')"
# Every file upstream changed whose basename appears in the table is a place where a fix of
# this fork can be silently overwritten by a clean merge.
TABLE=$(awk '/^\| Area \| Files \|/{p=1;next} p&&/^\|/{print} p&&!/^\|/{exit}' CLAUDE.md)
# Here-strings, not `printf | grep -q`: under pipefail grep's early exit SIGPIPEs printf and
# turns every hit into a miss. -w keeps `User` from matching inside `UserService`.
git diff --name-only "$BASE" "$UP" | while read -r f; do
  b=$(basename "$f"); stem="${b%.*}"
  [ ${#stem} -lt 4 ] && continue
  case "$stem" in index|user|User|i18n|form|build|types|utils|App) continue;; esac  # too generic to mean a table row
  if grep -qw -- "$stem" <<<"$TABLE"; then
    row=$(grep -m1 -w -- "$stem" <<<"$TABLE" | cut -d'|' -f2 | sed 's/^ *//;s/ *$//')
    echo "$f  -> row: $row"
  fi
done

section "Risk scan in what the merge would produce"
if [ -n "$TREE" ] && git cat-file -e "$TREE" 2>/dev/null; then
  echo "- container images (docker-compose.yml):"
  IMG=$(diff <(git show "$TARGET":docker-compose.yml | grep -E '^\s+image:' | sed 's/^ *//')              <(git show "$TREE":docker-compose.yml   | grep -E '^\s+image:' | sed 's/^ *//')         | grep -E '^[<>]' | sed 's/^</    ours:  /; s/^>/    after: /')
  [ -n "$IMG" ] && printf '%s
' "$IMG" || echo "    unchanged"
  echo "- new/removed dependencies:"
  git diff "$TARGET" "$TREE" -- frontend/package.json mobile/package.json mcp/package.json | grep -E '^[+-]\s+"[@a-z]' | sed 's/^/    /' || true
  git diff "$TARGET" "$TREE" -- api/pom.xml | grep -E '^[+-]\s*<(artifactId|version)>' | sed 's/^/    pom /' || true
  echo "- env variables added or re-defaulted (application.yml / docker-compose.yml):"
  git diff "$TARGET" "$TREE" -- api/src/main/resources/application.yml docker-compose.yml | grep -E '^\+' | grep -oE '\$\{[A-Z_]+' | sort -u | sed 's/^\${/    /' || true
  echo "- new Liquibase changelogs:"
  git diff --name-only --diff-filter=A "$TARGET" "$TREE" -- api/src/main/resources/db/changelog | sed 's/^/    /'
  echo "- security-relevant upstream commits (auth, permission, sanitize, tenant, xss):"
  git log --format='    %h %s' "$TARGET".."$UP" | grep -iE 'auth|permission|sanitiz|tenant|xss|secur|access|password|token' || echo "    none by message"
else
  echo "(merge tree unavailable)"
fi

section "Image availability (every non-fork image after the merge)"
# The 2026-09-29 sync pinned minio/minio after MinIO had deleted the repository; the deploy
# failed on the pull. Check before pinning or taking any image.
if [ -n "$TREE" ]; then
  git show "$TREE":docker-compose.yml | grep -E '^\s+image:' | sed -E 's/^\s+image:\s*//; s/["'\'']//g' | sort -u | while read -r img; do
    case "$img" in
      ghcr.io/*|*'${'*) echo "    skip  $img (built by this fork or templated)"; continue;;
    esac
    repo="${img%%:*}"; tag="${img#*:}"; [ "$tag" = "$img" ] && tag=latest
    case "$repo" in */*) ;; *) repo="library/$repo";; esac
    code=$(curl -s -o /dev/null -m 15 -w '%{http_code}' "https://hub.docker.com/v2/repositories/$repo/tags/$tag")
    [ "$code" = "200" ] && echo "    ok    $img" || echo "    FAIL  $img  (Docker Hub answered $code - the deploy would fail on the pull)"
  done
fi

section "Next"
echo "Read the conflicts AND the 'changed on both sides' list against CLAUDE.md, then follow SKILL.md."
