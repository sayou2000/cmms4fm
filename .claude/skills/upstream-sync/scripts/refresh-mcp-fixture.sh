#!/usr/bin/env bash
# Replaces the MCP test fixture with the API description the running instance serves, after
# showing what changed and checking it carries no host-specific data (the repo is public).
#
#   bash .claude/skills/upstream-sync/scripts/refresh-mcp-fixture.sh [live-url]
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 1
LIVE="${1:-https://cmms.corefm.de}"
FIXTURE=mcp/test/fixtures/api-docs.json
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

# Only the named group serves the document; /v3/api-docs answers 404 on purpose (mcp/README.md).
code=$(curl -s -m 30 -o "$TMP/live.json" -w '%{http_code}' "$LIVE/api/v3/api-docs/atlas-cmms")
[ "$code" = "200" ] || { echo "instance answered $code - is the api up?"; exit 1; }

python -I - "$TMP/live.json" "$FIXTURE" <<'PY'
import json, sys
live = json.load(open(sys.argv[1], encoding='utf-8'))
fix = json.load(open(sys.argv[2], encoding='utf-8'))

text = json.dumps(live)
servers = [s.get('url') for s in live.get('servers', [])]
allowed = {'/api', 'https://api.atlas-cmms.com', 'http://localhost:8080'}
if set(servers) - allowed:
    sys.exit(f"STOP: servers block holds {sorted(set(servers) - allowed)} - host-specific, not for a public repo")

ops = lambda d: {(m.upper(), p) for p, v in d['paths'].items() for m in v if m in ('get', 'post', 'put', 'patch', 'delete')}
L, F = ops(live), ops(fix)
print(f"operations: fixture {len(F)}, live {len(L)}")
for label, items in (("new", L - F), ("gone", F - L)):
    if items: print(f"  {label}: " + ", ".join(f"{m} {p}" for m, p in sorted(items)[:30]))
ls, fs = live['components']['schemas'], fix['components']['schemas']
changed = [k for k in ls if json.dumps(ls[k], sort_keys=True) != json.dumps(fs.get(k), sort_keys=True)]
print(f"schemas changed or new: {len(changed)}")
for k in changed[:15]:
    lp, fp = set(ls[k].get('properties', {})), set(fs.get(k, {}).get('properties', {}))
    print(f"  {k}: +{sorted(lp - fp)} -{sorted(fp - lp)}")
if not changed and L == F:
    print("fixture is current - nothing to do"); sys.exit(3)
# Same format as before: minified, trailing newline.
open(sys.argv[2], 'w', encoding='utf-8', newline='\n').write(json.dumps(live, ensure_ascii=False, separators=(',', ':')) + '\n')
print("fixture replaced")
PY
rc=$?
[ $rc = 3 ] && exit 0
[ $rc = 0 ] || exit $rc
(cd mcp && npm test 2>&1 | grep -E 'ℹ (pass|fail) |✖' | head -20)
echo "A renamed or removed endpoint from the curated table fails catalog.test.ts - fix curated.ts, not the test."
