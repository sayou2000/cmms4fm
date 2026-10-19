#!/usr/bin/env bash
# After the push: waits for the GitHub workflows of one commit, sorts known noise from real
# failures, then waits until the instance actually serves the new build.
#
# deploy.yml does NOT wait for tests.yml - a red test run is already live by the time it is
# red. That is accepted for a test instance; it is why this script reports both.
#
#   bash .claude/skills/upstream-sync/scripts/watch-deploy.sh [sha] [live-url]
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 1
SHA="${1:-$(git rev-parse HEAD)}"
LIVE="${2:-https://cmms.corefm.de}"
BEFORE=$(curl -s -m 15 "$LIVE/" | grep -oE '/assets/index-[A-Za-z0-9_-]+\.js' | head -1)

run_for() { gh run list --workflow "$1" --commit "$SHA" --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null; }

classify() {  # $1 run id -> prints known cause if the failure is noise
  local log; log=$(gh run view "$1" --log-failed 2>/dev/null)
  if grep -qE 'Could not transfer artifact.*(50[234]|Bad Gateway)' <<<"$log"; then
    echo "NOISE: Maven Central answered 5xx, no test ran. Rerun: gh run rerun $1 --failed"
  elif grep -q 'EXPO_TOKEN' <<<"$log"; then
    echo "KNOWN: EAS Update has no EXPO_TOKEN secret (red since 2026-08). Unrelated to the sync."
  elif grep -qE 'SSL connection timeout|Coolify refused' <<<"$log"; then
    echo "INFRA: the Coolify trigger could not reach Coolify. Images are in GHCR; once Coolify answers: gh run rerun $1 --failed"
  else
    echo "REAL: read it -> gh run view $1 --log-failed"
    grep -E '\[ERROR\] .*(expected|Tests run:.*Fail)|✖|error TS' <<<"$log" | head -10
  fi
}

echo "commit $SHA"
for wf in tests.yml deploy.yml; do
  id=""; for _ in $(seq 1 20); do id=$(run_for $wf); [ -n "$id" ] && break; sleep 6; done
  [ -z "$id" ] && { printf '%-11s no run for this commit\n' "$wf"; continue; }
  if gh run watch "$id" --exit-status --interval 30 >/dev/null 2>&1; then
    extra=""; [ $wf = tests.yml ] && extra=$(gh run view "$id" --log 2>/dev/null | grep -oE 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' | tail -1)
    printf '%-11s ok   %s\n' "$wf" "$extra"
  else
    printf '%-11s FAIL ' "$wf"; classify "$id"
  fi
done
other=$(gh run list --commit "$SHA" --json name,conclusion --jq '.[] | select(.conclusion=="failure") | .name' 2>/dev/null | grep -vE '^(Tests|Build and Deploy to GHCR)$' | sort -u)
[ -n "$other" ] && echo "other red workflows: $other (EAS Update is known noise)"

echo "--- live ($LIVE): waiting for the new frontend bundle (Coolify pulls, then swaps)"
NOW="$BEFORE"
for _ in $(seq 1 30); do
  NOW=$(curl -s -m 15 "$LIVE/" | grep -oE '/assets/index-[A-Za-z0-9_-]+\.js' | head -1)
  [ -n "$NOW" ] && [ "$NOW" != "$BEFORE" ] && break; sleep 20
done
[ "$NOW" != "$BEFORE" ] && echo "frontend   new bundle $NOW" || echo "frontend   UNCHANGED after 10 min ($NOW) - read the Coolify deployment log (an image pull failure leaves the old stack running)"
for _ in $(seq 1 30); do  # Spring Boot plus Liquibase takes one to three minutes
  c=$(curl -s -o /dev/null -m 15 -w '%{http_code}' "$LIVE/api/auth/me"); case $c in 5*|000) sleep 20;; *) break;; esac
done
echo "api        HTTP $c on /api/auth/me (401 = up and refusing an anonymous caller, which is right)"
echo "storage    HTTP $(curl -s -o /dev/null -m 15 -w '%{http_code}' "$LIVE/storage/minio/health/live")"
echo "mcp        $(curl -s -m 15 "$LIVE/mcp/healthz" | grep -oE '"(status|readyAt|profile|tools)":("[^"]*"|[0-9]+)' | tr '\n' ' ')"
echo "login      unknown email -> HTTP $(curl -s -o /dev/null -m 15 -X POST -H 'Content-Type: application/json' -d '{"email":"nobody@example.invalid","password":"x","type":"client"}' -w '%{http_code}' "$LIVE/api/auth/signin") (must be 403, same as a wrong password; 503 means the account-enumeration fix was lost)"
