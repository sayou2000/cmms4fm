# Upstream-Synchronisationsbericht

**Datum der Durchführung:** 29. September 2026
**Ziel-Branch:** direkt `main` (Testinstanz; bewusste Entscheidung, kein PR)

---

## 1. Kontext

* **Merge-Base:** `37015ac6` (04.09.2026) — Endstand des Syncs vom 07.09.
* **Upstream:** 105 Commits bis `d614aecb` (27.09.2026), 132 Dateien.
* **Fork:** 109 Commits gegenüber Upstream, seit dem letzten Sync nichts Neues.
* 26 Dateien auf beiden Seiten geändert, davon **10 mit Konflikt**.

Hauptthema Upstream wie schon im September: Logik vom Controller in den Service,
die Zugriffsprüfungen wandern mit. Dazu Validierung von Portal-Meldungen gegen
die Firma, Sanitizing bei Signup und LDAP, kein Passwort-Reset für gesperrte
Nutzer, Super-Admin-Signup nur noch per Einladung, Sentry und Microsoft Clarity
(beide ohne Konfiguration inaktiv), Tag `v1.9.0`.

## 2. Konflikte und Auflösung

| Datei | Auflösung |
|---|---|
| `ReadingService`, `ReadingController` | Upstreams Struktur komplett, inklusive der `canBeViewedBy`-Prüfungen, die vorher im Controller lagen. Upstreams eingebettetes `processMeterTriggers` entfällt; `create`/`patch` publizieren `ReadingRecorded` **nach** dem Speichern, der Alarm bleibt in `MeterTriggerFanout`. Die Alarm-Logik hat Upstream nur verschoben, nicht verändert (zeilenweise verglichen) — der Fanout braucht keine Anpassung. |
| `MultiPartsService`, `MultiPartsController` | Upstreams Signaturen mit `User`. Die Auflösung der Teile-Referenzen (`PartService.resolveRequestedParts`, `setParts`) wieder in `create` und `patch` eingesetzt; die Firma kommt aus `user.getCompany()` statt aus einem Zusatzparameter. |
| `RequestService`, `RequestServiceTest` | Beides: Upstreams `validateRequestAgainstCompany` mit `TenantAspect` und die Event-Publisher des Forks. Konflikt war nur die Feldliste bzw. die Imports. |
| `db/master.xml` | Beide Changelogs, zuerst der des Forks (`automation_engine`, auf der Instanz schon angewendet), dann Upstreams `user_settings_language`. |
| `.github/workflows/main-ci.yml` | Bleibt gelöscht (Pipeline läuft über GHCR und Coolify). |
| `frontend/config-overrides.js` | Bleibt gelöscht (Vite). Sentry-Source-Maps nicht portiert, Sentry wird nicht genutzt. |
| `frontend/package-lock.json` | Fork-Version, danach `npm install` nur für die zwei neuen Pakete (+114 Zeilen, keine bestehende Version verschoben). |

## 3. Bewusste Abweichung in einer sauber gemergten Datei

* **`frontend/package.json`: `@sentry/webpack-plugin` entfernt.** Nur für Webpack,
  der Build läuft über Vite. `@sentry/react` und `@microsoft/clarity` bleiben, weil
  `App.tsx` und `index.tsx` sie importieren.

In der Divergenz-Tabelle der `CLAUDE.md` steht jetzt die Multi-Parts-Auflösung; der Eintrag zu den Domain-Events nennt den neuen Ort
des Alarms.

## 4. Geprüft, ohne Handlungsbedarf

* **Telemetrie ist aus.** `getRuntimeValue` trimmt, das Leerzeichen-Default aus
  `docker-compose.yml` wird zu `""`, Clarity startet nicht, `Sentry.init` bekommt
  keine DSN. Im Backend ist `SENTRY_DSN` nicht im `api`-Block von `docker-compose.yml`
  aufgeführt, kommt also gar nicht an. Falls das je geändert wird: dort steht
  `send-default-pii: true`.
* **Divergenz-Tabelle unberührt geblieben:** 503-Behandlung in `UserService` und
  `LdapService`, Signup-Härtung, Premium-Freischaltung, `findForExport`, vertauschte
  Custom-Field-Typen im Frontend (Upstream hat die Dateien nicht angefasst),
  `AssetServiceTest` (die neuen Upstream-Tests stubben `setCustomFields` nicht).
* **Default-Superadmin:** Upstream verhindert jetzt den Passwort-Reset für gesperrte
  Nutzer — ergänzt die Absicherung per `enabled = false`.

## 5. Verifikation

* `mvn test-compile` (mit `-Dlombok.version=1.18.42`, online wegen der neuen
  Abhängigkeit `sentry-spring-boot-starter-jakarta`): BUILD SUCCESS.
* `npm run build` im Frontend: erfolgreich. Beweist nur, dass es bündelt — eine
  Typprüfung gibt es im Frontend nicht.
* `npm ls` im Frontend: Lockfile und `package.json` konsistent, `npm ci` im Image
  funktioniert also.
* Tests laufen lokal nicht (JDK 25, Mockito) — maßgeblich ist die CI auf JDK 17.
* **CI nach dem Push:** erster Lauf scheiterte an einem 502 von Maven Central (keine Tests gelaufen), der zweite an einem echten Fehler: 2138 von 2139 grün, rot war `UserServiceTest.superAdminRole_withoutInvitationEmailOrInvitation_throwsForbidden`. Upstreams neue Super-Admin-Prüfung (403) steht im Fork hinter der Signup-Härtung, die jeden Beitritt ohne Einladung schon mit 406 abweist — der Super-Admin wird weiterhin abgelehnt, nur eine Zeile früher. Der Test ist angepasst, die Tabellenzeile „Signup hardening" nennt ihn.
* **Deploy-Pipeline vorab repariert:** Der Coolify-Trigger, der am 07.09. mit
  TLS-Timeout scheiterte, lief beim Rerun in 6 s durch.

## 5a. Nachtrag: Storage-Image

Der Merge hielt zunächst `minio/minio:RELEASE.2025-04-22T22-12-26Z` fest, um den Wechsel auf
`pgsty/silo` separat mit Backup zu machen. **Das Deployment scheiterte daran:** MinIO hat das
Repository auf Docker Hub entfernt (`pull access denied, repository does not exist`, die Hub-API
antwortet 404). Genau das war Upstreams Grund für den Wechsel. Coolify zieht Images, bevor es den
laufenden Stand stoppt, die alte Version lief also weiter.

Jetzt übernommen: Upstreams Block unverändert (`pgsty/silo`, `user: "0:0"`). Ein Backup gab es
nicht, auf ausdrücklichen Wunsch — die Instanz ist eine Testinstanz, die hochgeladenen Dateien
sind verzichtbar. **Lehre:** ein Image-Pin gegen Upstream braucht vorher die Prüfung, dass das
Image noch ziehbar ist.

## 6. Offen

* ~~MCP-Fixture auffrischen~~ — erledigt: Live-Dokument und Fixture haben dieselben 374
  Operationen, sieben Schemas haben Felder gewonnen oder verloren (u. a. `VendorPatchDTO.companyName`,
  `User.language`, `WorkOrderPatchDTO` ohne `completedBy`/`completedOn`). Fixture ersetzt, `npm test`
  in `mcp/`: 70/70.
* **Nächster Sync:** Drei Wochen Abstand brachten 105 Commits und zehn Konflikte,
  vier Tage im September einen. Das Intervall aus der `CLAUDE.md` ist die Obergrenze.
