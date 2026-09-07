# Upstream-Synchronisationsbericht

**Datum der Durchführung:** 07. September 2026
**Ziel-Branch:** `chore/upstream-sync` (vor Übernahme in `main`)

---

## 1. Kontext & Betrachtete Zeitperiode

* **Gemeinsamer Ausgangspunkt (Merge-Base):** `12b996b1` (03.09.2026) —
  *feat: add Service Refactoring Guide* — also genau der Stand, auf dem der
  Sync vom 03.09. endete. Die Merge-Base ist intakt; der Vorgänger-Sync wurde
  korrekt als Merge-Commit übernommen.
* **Upstream (`upstream/main`):** 10 Commits, alle vom 04.09.2026, bis `37015ac6`.
* **Fork (`main`):** 107 Commits seit der Merge-Base (MCP-Server, Domain-Events,
  Automation-Change-Capture, KI-Readiness-Doku).

**Warum GitHubs „Sync fork" nicht ging:** Der Knopf kann ausschließlich
Fast-Forward. Sobald der Fork eigene Commits hat — hier 107 — bietet GitHub nur
noch „Discard commits" an, also das Wegwerfen der eigenen Arbeit. Das ist kein
Fehler und kein zu reparierender Zustand: für einen Fork mit eigener Entwicklung
ist der lokale Merge der Normalfall, nicht die Ausnahme.

## 2. Inhalt der 10 Upstream-Commits

Ein einziges Thema, das bekannte Muster „Logik vom Controller in den Service":

* `MeterController` / `VendorController` verlieren ihre Geschäftslogik
  (−84 bzw. −60 Zeilen) und nutzen `@CurrentUser User`.
* `MeterService` / `VendorService` erhalten dafür `getSearchCriteria`, `getById`,
  `create(PostDTO, User)`, `patch(id, PatchDTO, User)`, `deleteByIdAndUser`.
  Die alten Signaturen `create(Meter, User)` und `update(id, PatchDTO, Company)`
  fallen weg, `findBySearchCriteria` liefert jetzt `Page<Meter>` statt `Page<MeterShowDTO>`.
* `VendorService` bekommt einen `EntityManager`; das Detachment vor dem Edit-Check
  wurde entfernt (in `MeterService` erst entfernt, dann per Revert zurückgenommen).
* `VendorPatchDTO` bekommt ein **`@NotNull companyName`**.
* `PartController.search` verliert den `TenantAspectUtils`-Wrapper.
* `Helper`: Technician-`viewPermissions` enthalten nun `PARTS_AND_MULTIPARTS`.

## 3. Konflikte und deren Auflösung

Genau **ein** Konflikt, und ein triviale:

### 3.1 `.github/workflows/main-ci.yml` (modify/delete)
* **Ursache:** Der Fork hat die Datei in `1a3350fb` gelöscht, als die Pipeline auf
  GHCR und Coolify umgestellt wurde (ersetzt durch `deploy.yml` + `tests.yml`).
  Upstream hat sie geändert.
* **Upstream-Inhalt:** Die Deploy-Jobs (Koyeb, Netlify, Docker-Push) hingen an
  `if: !failure() && !cancelled()`, was auch bei übersprungenen Tests durchläuft;
  neu ist `needs.test.result == 'success' || 'skipped'`.
* **Lösung: bleibt gelöscht.** Der Fix betrifft eine Pipeline, die dieser Fork nicht
  mehr fährt, und er ist auf `deploy.yml` **nicht** übertragbar: dort laufen die Tests
  laut Kommentar bewusst *neben* dem Build und sollen ihn nicht blockieren.

Alle acht Java-/YAML-Dateien, die Upstream angefasst hat, waren gegenüber der
Merge-Base **unverändert im Fork** (`git diff 12b996b1..main` ist für sie leer) —
daher der reibungslose Merge. Keine der Dateien steht in der Divergenz-Tabelle
der `CLAUDE.md`; die Tabelle brauchte keine Änderung.

## 4. Geprüfte Folgewirkungen

* **Weggefallene Service-Signaturen:** Kein Aufrufer außerhalb der beiden Services
  nutzte `create`/`update`/`findBySearchCriteria`. Die restlichen Aufrufer
  (`ReadingController`, `WorkOrderMeterTriggerController`, `MeterTriggerFanout`,
  `ImportService`, `AsyncExportService`, `AssetService`, `LocationService`,
  `PartService`, `TaskBaseService`) hängen an `findById`, `importMeter`, `saveAll`,
  `findByIdsAndCompany`, `findByCompanyForExport`, `findByNameIgnoreCaseAndCompany` —
  alle unverändert.
* **Keine doppelten Webhooks:** Upstreams neuer `VendorService.create` versendet
  `NEW_VENDOR` direkt. Das tat der Fork an derselben Stelle auch schon; das
  Event-Fanout des Forks (`event/fanout/`) deckt nur WorkOrder, Request, Reading
  und Meter-Trigger ab. Kein Doppelversand.
* **`@NotNull companyName` ist eine API-Verschärfung:** Ein `PATCH /vendors/{id}`
  ohne `companyName` antwortet jetzt mit 400. Das Frontend-Formular führt das Feld
  und verlangt es selbst per Yup, die MCP-Tool-Schemas werden aus dem
  OpenAPI-Dokument erzeugt — beide folgen automatisch. Betroffen wären nur
  handgeschriebene externe Clients.
* **Kompiliert:** `mvn compile` und `mvn test-compile` (offline, mit dem in der
  Projekt-Memory dokumentierten `-Dlombok.version=1.18.42`) beide BUILD SUCCESS.
  Ohne diesen Override stirbt Lombok 1.18.30 unter dem lokalen JDK 25 — gegen
  einen `git worktree` auf dem unveränderten `main` gegengeprüft, dort derselbe
  Fehler, also Umgebung und nicht Merge. **Tests wurden lokal nicht ausgeführt**
  (Mockito/ByteBuddy unter JDK 25); das bleibt Aufgabe der CI auf JDK 17.

## 5. Kalibrierung

Zehn Commits, ein trivialer Konflikt, keine Nacharbeit — gegenüber 264 Commits
mit 25 Konflikten beim ersten Sync. Der Vier-Tage-Abstand ist der Grund, und der
Aufwand steigt schneller als das Intervall. Die Regel aus `CLAUDE.md` — monatlich
oder vor jedem größeren Vorhaben — hat sich hier von der günstigen Seite bestätigt.
