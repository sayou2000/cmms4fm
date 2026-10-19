---
name: upstream-sync
description: Bring upstream Grashjs/cmms changes into this fork (cmms4fm) quickly and safely - analyse, merge, resolve conflicts by the fork's standing rules, verify, push, watch the deploy and document it. Use when Lars asks to sync, update or check the upstream ("upstream übernehmen", "neuer Stand im Quellprojekt", "Fork aktualisieren", "commits behind Grashjs"), or when GitHub's "Sync fork" button refuses.
---

# Upstream sync for cmms4fm

The fork takes from `Grashjs/cmms` and gives nothing back (decided 2026-08-26 — never propose
a PR upstream). A merge only flows one way, so syncing never influences the main project.

Every rule below was paid for in an earlier sync. The history is in `docs/sync-upstream-*.md`;
the list of places where this fork differs is the divergence table in `CLAUDE.md`
("Keeping in step with upstream"). That table is the authority — read it every time.

Talk to Lars in German, short and without jargon. Name security findings explicitly.

## Ground rules

- **Merge, never squash or rebase a sync.** It drops the merge base and the next sync replays
  every upstream commit against code already taken.
- **Working tree must be clean.** Lars works in parallel in this repo. If `analyze.sh` reports
  a dirty tree, stop and ask. Stage only the paths you changed — never `git add -A`.
- **Target:** direct push to `main` is accepted (test instance, decided 2026-09-29) — but ask
  for the go each time after the analysis; approval does not carry over between syncs.
- **No infrastructure data in the repo** (it is public): no server IP, Coolify UUID, SSH key
  path. Those live in Claude's memory (`project_cmms4fm.md`), not in this skill.

## 1. Analyse (read-only)

```bash
bash .claude/skills/upstream-sync/scripts/analyze.sh
```

It fetches, simulates the merge with `git merge-tree` (no file is touched) and reports:
divergence, upstream commits, conflicts, files changed on both sides, **upstream files that hit
a divergence-table row**, a risk scan of what the clean merge would bring (images, dependencies,
env variables, Liquibase changelogs, security commits) and whether every container image can
still be pulled.

The conflicts are the small part. **The risk lives in files that merge cleanly** — on
2026-09-29 the storage image swap, Sentry and Clarity all arrived without a conflict. Read the
"changed on both sides" and "table rows" lists, and for each hit open the upstream diff:
`git diff <merge-base> upstream/main -- <file>`.

## 2. Report and ask

Before merging, give Lars: number of commits, what upstream changed (themes, not commit
titles), each conflict with its planned resolution, anything from the risk scan that changes
behaviour here, and a recommendation. Then ask for the go. For a handful of commits without
conflicts this is three lines.

## 3. Merge and resolve

```bash
git merge --no-commit --no-ff upstream/main
```

Standing resolutions — apply without re-deciding:

| Situation | Resolution |
|---|---|
| `.github/workflows/main-ci.yml` modify/delete | Stays deleted (`git rm`). Pipeline is `deploy.yml` + `tests.yml`, GHCR and Coolify. Upstream's CI fixes do not apply: tests deliberately run *beside* the build here. |
| `frontend/config-overrides.js` modify/delete | Stays deleted (Vite, not CRA). |
| `frontend/package-lock.json` conflict | Take ours (`git checkout --ours`), then `npm install` in `frontend/` so only upstream's *new* packages are added. Drop webpack-only packages (`@sentry/webpack-plugin` etc.) from `package.json` first. |
| `db/master.xml` conflict | Union: fork's changelog first (already applied on the instance), then upstream's. Check every included file exists. |
| Upstream moves controller logic into a service | Take upstream's structure **whole**, including the permission checks that move with it, then re-insert the fork's hook inside the service method. Never keep the old fat controller. |
| Upstream inlines side effects the fork moved to events (meter alarm, notifications, webhooks) | Drop upstream's inline block, keep the `publishDomainEvent(...)` after the save. Then compare upstream's moved logic with the fork's fanout handler: a *changed* rule belongs in `event/fanout/`, never back in the service. |
| Both sides change a method signature | Prefer upstream's signature; derive what the fork needed from it (e.g. the company from `user.getCompany()`) rather than adding a parameter. |
| Upstream fixes something the fork fixed differently | "Both, not either" is the usual answer; where upstream's fix covers ours, take theirs — it shrinks the divergence. |
| An upstream test contradicts stricter fork behaviour | Keep the behaviour, adapt the test, add the test to the table row so the next sync re-applies it. |

Before you finish resolving, check for leftover markers: `git diff --name-only --diff-filter=U`
must be empty and `git grep -lE '^(<<<<<<<|>>>>>>>) '` must find nothing.

### Rules for what merged cleanly

- **Container images:** never pin or keep an image without checking that it can still be pulled
  (the analysis does this). On 2026-09-29 the fork held `minio/minio` back after MinIO had
  deleted the repository; the deploy failed on the pull.
- **Env variables:** a variable reaches the api container only if the `api` block of
  `docker-compose.yml` names it. A new switch needs both that block and `application.yml`.
- **Telemetry** (Sentry, Clarity, Intercom): confirm it stays inert without its key —
  `frontend/src/config.ts` `getRuntimeValue` trims, so `${X:- }` becomes empty.
- **Security code** (`UserService`, `LdapService`, `CustomUserDetailsService`, auth filters):
  diff upstream's version against the table rows; these are where a clean merge silently
  removes a fork fix.

## 4. Verify locally

```bash
bash .claude/skills/upstream-sync/scripts/verify-local.sh [TestClassA,TestClassB]
```

Compiles backend and tests (with the Lombok override JDK 25 needs), runs the named test classes
locally (ByteBuddy experimental mode makes Mockito work under JDK 25), checks the frontend lock
file against `package.json` (the image runs `npm ci`), builds the frontend and runs the MCP
suite. Name the test classes of every service you resolved by hand.

Say what this proves: compile and bundle, plus the tests you named. The full suite runs only in
CI on JDK 17. The frontend has no type check at all — `npm run build` only bundles.

## 5. Commit and push

Commit the merge on its own (`git commit`, no `-a`). Message: what upstream brought, each
conflict with its resolution, each deliberate departure from upstream. End with the attribution
line from the system reminder. Docs (report, table) go in a second commit. Then
`git push origin main`.

## 6. Watch the deploy

```bash
bash .claude/skills/upstream-sync/scripts/watch-deploy.sh
```

Waits for `tests.yml` and `deploy.yml` of HEAD, sorts failures into noise (Maven Central 5xx →
rerun; EAS Update without `EXPO_TOKEN` → known, unrelated; Coolify unreachable → rerun the
trigger job once Coolify answers) and real ones, then waits until the instance serves the new
frontend bundle, the api is up, storage and MCP answer, and the login still answers 403 for an
unknown email.

`deploy.yml` does not wait for the tests, so a red test run is already live. Fix forward
quickly. If the frontend bundle never changes, Coolify failed after the GitHub run went green —
ask Lars for the Coolify deployment log (he can paste it); an image pull failure leaves the old
stack running.

## 7. Aftercare

1. **MCP fixture** — the MCP tests run against a saved copy of the API description, which a sync
   can make stale. This fetches it from the running instance, shows what changed, refuses a copy
   with host-specific `servers`, replaces the fixture and runs the MCP suite:
   ```bash
   bash .claude/skills/upstream-sync/scripts/refresh-mcp-fixture.sh
   ```
   Commit `mcp/test/fixtures/api-docs.json` on its own if it changed.
2. **Report** — `docs/sync-upstream-YYYY-MM-DD.md` in German, following
   [references/report-template.md](references/report-template.md). For a few commits without
   conflicts the merge commit message is enough.
3. **Divergence table** — add a row for every new departure, correct rows whose file moved,
   remove rows that no longer apply.
4. Tell Lars what is live, what is open, and anything he has to do himself (Coolify settings).

## Calibration

| Sync | Commits | Conflicts | Cost |
|---|---|---|---|
| 2026-08-26 | 264 | 25 | an afternoon, one bad resolution caught by CI |
| 2026-09-07 | 10 | 1 trivial | minutes |
| 2026-09-29 | 105 | 10 | hours; one test fix and one image pin fixed after the push |
| 2026-10-17 | 6 | 0 | minutes |

The work grows faster than the interval. Sync at least monthly and before any larger change.
