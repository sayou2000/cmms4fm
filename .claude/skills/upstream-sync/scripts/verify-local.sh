#!/usr/bin/env bash
# Local checks after a merge, before the push. What this proves and what it does not:
#   backend  compile + test-compile         -> code and tests compile (not that tests pass)
#   tests    only the classes you name      -> those pass under JDK 25 with ByteBuddy experimental
#   frontend lock consistency + vite build  -> it bundles; there is no type check in this project
#   mcp      npm test (tsc + suite)         -> real
# The full backend suite only runs in CI on JDK 17.
#
#   bash .claude/skills/upstream-sync/scripts/verify-local.sh [TestClassA,TestClassB]
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 1
TESTS="${1:-}"
FAIL=0
result() { printf '%-10s %s\n' "$1" "$2"; }

if [ -f api/mvnw.cmd ] && command -v cmd.exe >/dev/null 2>&1; then MVN="./mvnw.cmd"; else MVN="mvn"; fi
# Lombok 1.18.30 from the pom dies under JDK 25 with "TypeTag :: UNKNOWN"; the override fixes
# that. If an error looks like "Lombok is not running", compile once WITHOUT it: the override
# can shift which error javac reports first and hide the real one.
LOMBOK="-Dlombok.version=1.18.42"

echo "--- backend: test-compile"
OUT=$(cd api && $MVN -B -o test-compile -DskipTests "$LOMBOK" 2>&1)
if ! grep -q 'BUILD SUCCESS' <<<"$OUT" && grep -q 'offline mode' <<<"$OUT"; then
  echo "    new dependency, retrying online"
  OUT=$(cd api && $MVN -B test-compile -DskipTests "$LOMBOK" 2>&1)
fi
if grep -q 'BUILD SUCCESS' <<<"$OUT"; then result backend ok; else result backend FAIL; grep -E '^\[ERROR\]' <<<"$OUT" | head -15; FAIL=1; fi

if [ -n "$TESTS" ]; then
  echo "--- backend: tests $TESTS"
  # failIfNoSpecifiedTests=false is needed for nested classes, but it also skips a misspelt or
  # non-existent class without a word - so check the names first.
  for t in ${TESTS//,/ }; do
    [ -n "$(find api/src/test -name "${t%%#*}.java" -print -quit)" ] || { echo "    NOT FOUND: $t (no such test class - nothing was tested for it)"; FAIL=1; }
  done
  OUT=$(cd api && $MVN -B -o test "$LOMBOK" "-Dtest=$TESTS" -Dsurefire.failIfNoSpecifiedTests=false \
        "-DargLine=-Dnet.bytebuddy.experimental=true --add-opens java.base/java.lang=ALL-UNNAMED" 2>&1)
  grep -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$|FAIL' <<<"$OUT" | tail -3
  grep -q 'BUILD SUCCESS' <<<"$OUT" && result tests ok || { result tests FAIL; FAIL=1; }
fi

echo "--- frontend: lockfile matches package.json (the image runs npm ci)"
if (cd frontend && npm ls --depth=0 >/dev/null 2>&1); then result lockfile ok
else result lockfile FAIL; (cd frontend && npm ls --depth=0 2>&1 | grep -E 'missing|invalid|ERR' | head -10); FAIL=1; fi

echo "--- frontend: build"
if (cd frontend && npm run build >/dev/null 2>&1); then result frontend ok; else result frontend FAIL; FAIL=1; fi

echo "--- mcp: npm test"
OUT=$(cd mcp && npm test 2>&1)
grep -E 'ℹ (pass|fail) ' <<<"$OUT"
grep -qE 'ℹ fail 0' <<<"$OUT" && result mcp ok || { result mcp FAIL; grep -E 'error TS|✖' <<<"$OUT" | head -10; FAIL=1; }

echo
[ $FAIL = 0 ] && echo "ALL LOCAL CHECKS PASSED" || echo "SOME CHECKS FAILED"
exit $FAIL
