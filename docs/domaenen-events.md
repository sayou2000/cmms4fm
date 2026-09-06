# Domänen-Ereignisse und Fan-out

Arbeitsdokument zu Case **E2** aus [`ki-readiness-architektur-analyse.md`](ki-readiness-architektur-analyse.md):
semantische Domänen-Ereignisse publizieren und die Kerntransaktionen von ihren Nebenwirkungen
entkoppeln.

Es beschreibt, **welche Ereignisse es gibt**, **wer sie publiziert**, **wer sie konsumiert** und
vor allem **was bewusst in der Transaktion geblieben ist**. Die Begründungen zur Regel-Engine
selbst stehen in [`workflow-engine-konzept.md`](workflow-engine-konzept.md) und
[`automation-engine.md`](automation-engine.md); dieses Dokument wiederholt sie nicht.

Stand: 2026-09-06.

---

## 1. Das Problem, in einem Satz

Einzelne Dienstmethoden trugen einen großen Fan-out in einer Transaktion: `RequestService.approve`
legte den Auftrag an, feuerte den Webhook, schrieb In-App-Benachrichtigungen an ein fest
verdrahtetes `LIMITED_ADMIN`-Publikum und baute die Mail — alles zwischen `findById` und `return`.
Dasselbe Muster in `WorkOrderService.changeStatus`, und der Zählerstand-Alarm lag sogar im
Controller.

Drei Folgen, und nur die erste ist Ästhetik:

1. **Die Konsumenten liefen zu früh.** `WebhookDispatchService.dispatchWebhook` und
   `NotificationService.createMultiple` sind beide `@Async`. Sie verließen den Thread der noch
   offenen Transaktion — ein Webhook-Empfänger, der die gemeldete Id zurückfragt, konnte ein 404
   bekommen. Nicht immer, sondern je nach Timing.
2. **Ein Fehler im Nebenweg riss den Hauptweg mit.** Eine kaputte Mail-Vorlage konnte die
   Genehmigung zurückrollen, über die sie berichten sollte.
3. **Jede neue Reaktion musste in eine fremde Transaktion hinein.** Eskalation, Bericht,
   KI-Analyse — alles hätte `approve` bearbeiten müssen. Das ist die strukturelle Schwäche, die
   E2 benennt.

---

## 2. Was jetzt publiziert wird

Publikationsstelle ist `automation/event/SemanticEventPublisher` — ein Ort statt fünf, weil
zwei Fehler dort systematisch sind und beide **lautlos** passieren:

- **Ohne Transaktion feuert kein `@TransactionalEventListener`.** Das Ereignis wird publiziert,
  angenommen und verworfen, ohne Logzeile. Genau darin saß `PurchaseOrderController.respond`, eine
  Controller-Methode ohne `@Transactional`. Der Publisher prüft das jetzt und schreibt einen
  Fehler ins Log, statt die Stille zu erlauben.
- **Ohne Company fehlt der Mandant.** Die Engine verwirft ein solches Ereignis ohnehin, ein
  Konsument hätte nichts, worauf er einschränken könnte. Es wird verworfen und benannt.

| Auslöser | Ereignis | Publiziert von |
|---|---|---|
| Meldung genehmigt | `REQUEST:APPROVED` | `RequestService.approve` |
| Meldung abgelehnt | `REQUEST:REJECTED` | `RequestService.cancel` |
| Auftrag abgeschlossen | `WORK_ORDER:CLOSED` | `WorkOrderService.changeStatus` (Übergang **nach** COMPLETE) |
| Bestellung genehmigt | `PURCHASE_ORDER:APPROVED` | `PurchaseOrderController.respond` |
| Bestellung abgelehnt | `PURCHASE_ORDER:REJECTED` | `PurchaseOrderController.respond` |
| Auftragsstatus geändert (beliebig) | `WorkOrderStatusChanged` | `WorkOrderService.changeStatus` |
| Zählerstand geschrieben | `ReadingRecorded` | `ReadingService.create` / `update` |

Die ersten fünf sind `EntityChangedEvent` und stehen zugleich in
`AutomationMetaService.LIVE_SEMANTIC_TRIGGERS`, sind also im Regel-Editor auswählbar. Die
letzten beiden nicht — dazu §4.

### Was **nicht** publiziert wird, und warum

Die Genehmigung einer Meldung legt einen Auftrag an. Dieses Folgeereignis wird hier
**nicht** gemeldet: `WorkOrder` steht in `TrackedEntities`, die Erfassungs-Pipeline meldet seine
Anlage also bereits. Ein zusätzliches `child(CREATED, WORK_ORDER, …)` würde jede
WORK_ORDER:CREATED-Regel doppelt ausführen. Dieselbe Begründung steht in `CLAUDE.md` für
`AssetService` — wenn ein Upstream-Merge so etwas wieder einbaut, ist es zu löschen, nicht zu
mergen.

---

## 3. Wer konsumiert

Zwei Konsumentenfamilien am selben `EntityChangedEvent`, und der Unterschied zwischen ihnen ist
tragend:

| Konsument | Läuft | Schalter |
|---|---|---|
| `automation/event/AutomationListener` → Regel-Engine | `@Async(automationExecutor)`, AFTER_COMMIT | `AUTOMATION_ENABLED`, **Standard aus** |
| `event/fanout/FanoutListener` → Webhook, Benachrichtigung, Mail, Zähleralarm | `@Async(fanoutExecutor)`, AFTER_COMMIT | keiner, **immer an** |

Der Fan-out ersetzt Verhalten, das es vorher immer gab, und darf deshalb nicht hinter dem
Regel-Schalter liegen. Das ist auch der Grund, warum er **nicht** an `CommittedEntityChange`
hängen kann: `TransactionChangeCollector` sammelt bei `AUTOMATION_ENABLED=false` gar nichts, ein
Fan-out darauf wäre auf einer Standardinstallation tot.

`FanoutListener` ist absichtlich dünn — Routing plus `try/catch` — und delegiert an drei
`@Transactional`-Handler (`RequestFanout`, `WorkOrderFanout`, `MeterTriggerFanout`).
`@Async` und `@Transactional` an *derselben* Methode überlassen die Reihenfolge der beiden
Advices der Konfiguration; dieselbe Aufteilung benutzen `AutomationListener` und
`RequestTriageListener`.

Ein eigener Executor, kein geteilter: `AsyncConfig` gibt drei Threads und eine Warteschlange von
elf für Exporte, Importe, Mail, Kommentare, Demodaten und Triage. Ein voller Puffer heißt dort
`AbortPolicy` — die Aufgabe wird abgelehnt, und niemand erfährt es, weil die Transaktion längst
zurückgekehrt ist. `fanoutExecutor` nutzt `CallerRunsPolicy`: unter Last kostet es Latenz statt
einer verlorenen Benachrichtigung.

---

## 4. Zwei Ereignisarten — und warum es nicht eine ist

`ChangeType` ist das Vokabular, gegen das eine **Regel** geschrieben wird: CREATED, UPDATED,
ARCHIVED, CLOSED, APPROVED, REJECTED. Zwei Vorgänge passen da nicht hinein:

- **`WorkOrderStatusChanged`.** Die Statusmeldung an den Melder gilt für *jeden* Statuswechsel,
  nicht nur den Abschluss. Der nächstliegende `ChangeType` wäre `UPDATED` — den publiziert die
  Erfassungs-Pipeline für jeden Auftragsschreibvorgang aber schon, ein zweiter würde jede
  WORK_ORDER:UPDATED-Regel doppelt auslösen. Außerdem trägt das Ereignis den **vorherigen**
  Status, den nach dem Commit nichts mehr rekonstruieren kann.
- **`ReadingRecorded`.** `EntityType` hat kein `METER`, und der Pfad legt den Auftrag selbst an,
  beschreibt also keine Bedingung. Bewusst kein Regel-Trigger (siehe `automation-engine.md` §3).

Das ist der Preis, und er ist benannt: **zwei Ereignisarten sind eine Altlast in Zeitlupe.** Die
Regel dagegen ist einfach — solange ein Vorgang als `ChangeType` ausdrückbar ist, ist er einer;
erst wenn nicht, bekommt er einen eigenen Record. Beide gehen durch denselben Publisher und
damit durch dieselbe Transaktionsprüfung.

---

## 5. Was in der Transaktion geblieben ist

Die Grenze verläuft zwischen **ausgehenden Nebenwirkungen** und **fachlichen Schreibvorgängen**:

| Bleibt in der Transaktion | Warum |
|---|---|
| Anlage des Auftrags aus der Meldung | Rückgabewert des Aufrufs; der Klient bekommt ihn |
| `assetService.stopDownTime` beim Abschluss | Ein Auftrag, der fertig ist, während die Anlage noch als „außer Betrieb" gilt, ist schlimmer als eine langsame Anfrage |
| Stoppen laufender Arbeitszeiten | dito |
| PM-Reschedule (`scheduleNextWorkOrderJobAfterCompletion`) | dito |
| Zubuchen der Teile bei Bestellgenehmigung | dito — und der Diff je Teil existiert nur inline |
| Alt-`WorkflowService` | siehe §6 |
| `reviewEligibilityService` | braucht `platform` aus dem HTTP-Aufruf, nach dem Commit nicht mehr rekonstruierbar |

Diese Grenze ist eine **Zwischenstufe, keine Endform**. Fachliche Schreibvorgänge nach draußen zu
verlagern ist erst vertretbar, wenn die Zustellung mindestens einmal garantiert ist — das ist
Case **E1** (Transactional Outbox). Ohne ihn hieße „PM-Reschedule ist ein Konsument": bei einem
JVM-Absturz zwischen Commit und Task ist die Wartung nicht neu geplant, und nichts merkt es.

Eine Nebenwirkung hat der Umbau schon behoben: `PurchaseOrderController.respond` hatte gar keine
Transaktion. Die Teile wurden einzeln zugebucht und der Bestellstatus danach separat geschrieben
— ein Fehler dazwischen ließ die Teile gebucht und die Bestellung auf PENDING, also erneut
genehmigbar und **doppelt** buchbar. Das `@Transactional`, das der Publisher braucht, schließt
das mit.

---

## 6. Offen

- **Zwei Regel-Maschinen.** Der alte `WorkflowService` läuft weiter und wird an denselben Stellen
  inline aufgerufen. Die Migration auf die neue Engine ist der bewusste spätere Schritt (E2
  Punkt 4, Begründung in `workflow-engine-konzept.md` §4.1) — beim Testen ist zu wissen, dass eine
  doppelt angelegte Aufgabe aus einer Alt-Regel kommen kann.
- **Kein Outbox.** Der Ereignisstrom ist verlustbehaftet: ein Absturz zwischen Commit und
  Async-Task verliert die Benachrichtigung dauerhaft. `CallerRunsPolicy` deckt die Sättigung ab,
  nicht den Absturz. Case E1.
- **Der Webhook bleibt ein Zweitsystem.** 22 Dispatch-Stellen mit handgebauten Payloads, ohne
  Timeout, ohne Retry, ohne Zustellprotokoll. Dieser Umbau hat drei davon hinter den Commit
  gezogen, mehr nicht.
- **Eine Eigenart wurde bewusst mitgenommen statt behoben:** die Melder-Mail in
  `WorkOrderFanout.onStatusChanged` prüft die Einstellung „Aktualisierungen zu Meldungen per
  Mail", der zweite Zweig der Bedingung macht die Prüfung aber wirkungslos. Ein Melder, der die
  Mails abbestellt hat, bekommt sie trotzdem. Das ist Upstream-Verhalten; es beim Verschieben
  stillschweigend zu korrigieren wäre eine Entscheidung über Benachrichtigungseinstellungen
  gewesen, nicht über Architektur.

---

## 7. Rezept: eine weitere Nebenwirkung entkoppeln

1. **Publizieren**, im Dienst, **innerhalb** der Transaktion, über `SemanticEventPublisher` —
   mit dem Akteur als Argument, wenn die Methode ihn schon hat. Nur wo sie ihn nicht hat, greift
   die Überladung auf `CurrentActor` zurück.
2. Passt der Vorgang auf einen `ChangeType`? Dann `publish(...)` **und** ein Eintrag in
   `AutomationMetaService.LIVE_SEMANTIC_TRIGGERS`, sonst ein eigener Record und
   `publishDomainEvent(...)`.
3. Prüfen, ob die Erfassungs-Pipeline dieselbe Änderung schon meldet. Bei einer beobachteten
   Entität ist CREATED/UPDATED bereits vergeben.
4. Den verschobenen Code in einen `@Transactional`-Handler unter `event/fanout/` heben und in
   `FanoutListener` verdrahten. Der Handler lädt frisch per Id — was das Ereignis nicht trägt,
   liest er aus dem committeten Datensatz, und was danach verschwunden ist, überspringt er mit
   einer Warnung statt mit einer Exception.
5. Was das Ereignis tragen **muss**: alles, was nach dem Commit nicht mehr rekonstruierbar ist.
   Der vorherige Status ist das Musterbeispiel.
6. Im Dienst-Test `@Mock SemanticEventPublisher` ergänzen, sonst ist die Publikation ein NPE
   mitten in der getesteten Methode.

```
[ ] publish im Dienst, in der Transaktion, Company + Akteur
[ ] ChangeType-Eintrag in LIVE_SEMANTIC_TRIGGERS, oder eigener Record
[ ] geprüft, dass die Erfassung dieselbe Änderung nicht schon meldet
[ ] Handler unter event/fanout/, @Transactional, lädt per Id, überspringt Fehlendes
[ ] alles Nicht-Rekonstruierbare reist im Ereignis
[ ] @Mock SemanticEventPublisher im Dienst-Test
[ ] CLAUDE.md-Divergenztabelle, wenn eine Upstream-Datei berührt wurde
```
