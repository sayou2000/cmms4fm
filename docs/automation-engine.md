# Automatisierungs-Engine: Stand und Erweiterung

Arbeitsdokument. Es beschreibt, **was heute läuft**, **wie ein weiterer Auslöser entsteht** und
**was als nächstes ansteht**.

Die Begründungen und Entscheidungen stehen in
[`workflow-engine-konzept.md`](workflow-engine-konzept.md); dieses Dokument wiederholt sie nicht.
Arbeitsteilung: das Konzept sagt *warum es so gebaut ist* und bleibt stabil, dieses hier sagt
*was gerade wahr ist*.

Stand: 2026-09-04.

---

## 1. Ist-Stand

### 1.1 Was gebaut ist

| Schicht | Dateien | Zustand |
|---|---|---|
| Änderungserfassung | `automation/capture/**` | Läuft. Hibernate-Listener → Transaktions-Sammler → Ereignis nach dem Commit |
| Ereignis | `automation/event/EntityChangedEvent`, `CommittedEntityChange`, `AutomationListener`, `CurrentActor` | Läuft. Zwei Wege, siehe §1.3 |
| Regelmodell | `automation/model/**` | Läuft. Vier Tabellen, Liquibase, Enums als `VARCHAR` |
| Auswertung | `automation/eval/RuleEvaluator`, `EntityFieldResolver`, `CustomFieldResolver` | Läuft. **Alle Felder** aller beobachteten Entitäten |
| Aktionen | `automation/action/**` — Auftrag anlegen, benachrichtigen, Merkmal setzen | Läuft, anlagenzentriert (§1.5) |
| Ausführung | `AutomationEngine`, `AutomationRuleRunner`, `AutomationRunService` | Läuft. Je Regel eigene Transaktion, Protokoll in noch einer |
| Metadaten | `AutomationMetaService`, `GET /automation-rules/meta` | Läuft, vollständig abgeleitet |
| GUI | `frontend/.../Settings/Features/Automation/**` | Läuft, `/app/settings/features/automation` |
| Schalter | `AUTOMATION_ENABLED`, `AUTOMATION_MAX_DEPTH`, `AUTOMATION_MAX_CHANGES_PER_TRANSACTION` | Standard aus / 3 / 200 |

69 Tests im Paket `com.grash.automation`. Ein Ende-zu-Ende-Test über eine echte Transaktion fehlt
weiter; die Registrierung des Hibernate-Listeners wird faktisch von den Integrationstests
abgedeckt, weil ein Fehler dort den Anwendungskontext gar nicht starten lässt.

### 1.2 Woher die Ereignisse kommen

**Alles, was Hibernate schreibt.** `AutomationChangeListener` hängt an `POST_INSERT` und
`POST_UPDATE` und fragt Hibernate, welche Spalten die Anweisung wirklich berührt hat
(`PostUpdateEvent.getDirtyProperties()`). Beobachtet wird, was in `TrackedEntities` steht:

| Entität | `EntityType` | Präfix für Bedingungen |
|---|---|---|
| `Asset` | `ASSET` | `asset.` |
| `WorkOrder` | `WORK_ORDER` | `workOrder.` |
| `Request` | `REQUEST` | `request.` |
| `Part` | `PART` | `part.` |
| `PurchaseOrder` | `PURCHASE_ORDER` | `purchaseOrder.` |

Daraus ergeben sich **zehn lebende Trigger** — Anlegen und Ändern je Entität — ohne dass sie
irgendwo aufgezählt werden. Eine Entität hinzuzufügen heißt: eine Zeile in `TrackedEntities`.

Das ersetzt die vorherige Bauweise, in der jeder Dienst eine eigene Publikationsstelle mit einem
eigenen, handgeschriebenen Feldvergleich brauchte. Diese Vergleiche waren untereinander
uneinig — Aufträge hatten zwei Änderungspfade mit zwei verschiedenen Diffs — und beim
Anlagenstatus war der Diff sogar strukturell blind, weil `triggerDownTime` den Status erst nach
der Berechnung schrieb. Diese Falle (F2 im Konzept) ist damit nicht behoben, sondern
**unmöglich geworden**: der Diff ist die Spaltenliste der ausgeführten Anweisung.

### 1.3 Zwei Ereigniswege, und warum

- **`CommittedEntityChange`** — von der Erfassung, publiziert aus
  `TransactionSynchronization.afterCommit`. Wird von einem gewöhnlichen `@EventListener`
  aufgenommen, weil es an dieser Stelle keine Transaktion mehr gibt, an der ein
  `@TransactionalEventListener` hängen könnte.
- **`EntityChangedEvent`** — von Hand aus einem Dienst, für die *semantischen* Änderungsarten.
  Ob ein Update „genehmigt" bedeutet, steht in keiner Spalte. Wird von einem
  `@TransactionalEventListener(AFTER_COMMIT)` aufgenommen.

Beide laufen in `AutomationEngine.handle` zusammen. Zwei Typen statt einem, weil ein einziger
Typ zwei Listener bräuchte — und der nicht-transaktionale würde für ein von Hand publiziertes
Ereignis **vor** dem Commit feuern. Die Regel läse dann je nach Timing den alten Zustand.

Seit dem E2-Umbau hört an `EntityChangedEvent` noch ein zweiter Konsument mit: `FanoutListener`
schickt Webhook, Benachrichtigung und Mail. Der Unterschied ist wichtig, weil er entscheidet, wo
neuer Code hingehört — die Regel-Engine liegt hinter `AUTOMATION_ENABLED` und ist standardmäßig
aus, der Fan-out ersetzt Verhalten, das es immer gab, und läuft deshalb immer. Aus demselben
Grund kann der Fan-out **nicht** an `CommittedEntityChange` hängen: die Erfassung sammelt bei
ausgeschalteter Engine gar nichts. Siehe [`domaenen-events.md`](domaenen-events.md).

### 1.4 Der Sammler: warum nicht pro Anweisung publiziert wird

Eine Anfrage kann dieselbe Zeile mehrfach schreiben. `AssetService.patch` schreibt die Anlage,
`triggerDownTime` danach ihren Status — zwei UPDATEs, jedes meldet nur seine eigenen Spalten.
Pro Anweisung publiziert entstünden zwei Ereignisse mit je einer halben Wahrheit: eine Regel mit
Feldfilter `status` sähe das eine, eine mit Filter `name` das andere, keine die Änderung als
Ganzes. `TransactionChangeCollector` führt sie je Zeile zusammen und publiziert einmal.

Drei Eigenschaften dieses Sammlers sind tragend und getestet:

- **Nichts vor dem Commit.** Sonst liest eine Regel auf dem anderen Thread den alten Zustand.
- **Kaskaden werden geerbt.** Ein Schreibvorgang, den eine Regelaktion auslöst, wird wie jeder
  andere gemeldet. Ohne `CascadeContext` käme er mit neuer `correlationId` und Tiefe 0 an — und
  beide Schleifenschutz-Mechanismen wären blind. Eine Regel „wenn Anlage sich ändert, setze
  Merkmal" würde sich selbst endlos auslösen. Das ist der Preis der generischen Erfassung, und
  er ist mit einem ThreadLocal bezahlt, der von der laufenden Regel bis zum Flush ihrer Aktionen
  reicht.
- **Massenschreibvorgänge sind begrenzt.** Ein CSV-Import von 5000 Anlagen würde sonst 5000
  Ereignisse erzeugen. Ab `AUTOMATION_MAX_CHANGES_PER_TRANSACTION` (200) wird der Rest der
  Transaktion mit einer Warnung verworfen — sichtbar, weil eine halb ausgewertete Massenänderung
  schlimmer wäre als eine gar nicht ausgewertete.

Bei `AUTOMATION_ENABLED=false` sammelt die Erfassung nichts. Ein Standard-Aus-Schalter soll
heißen, dass der Mechanismus nicht da ist.

### 1.5 Was die Bedingungen können — und was die Aktionen noch nicht

`EntityFieldResolver` leitet die Felder aus dem **JPA-Metamodell** ab: jede skalare Spalte und
jede `@ManyToOne`-Beziehung jeder beobachteten Entität, mit dem Wertetyp aus dem Java-Typ. Ein
Operator wird nur angeboten, wo er entscheiden kann:

| Java-Typ | Wertetyp | Operatoren |
|---|---|---|
| `String` | TEXT | ist / ist nicht / enthält / gefüllt / leer / wechselt auf |
| Enum | ENUM | ist / ist nicht / wechselt auf |
| `boolean` | BOOLEAN | ist / wechselt auf |
| Zahl | NUMBER | ist / ist nicht / < / ≤ / > / ≥ / gefüllt / leer |
| `Date` | DATE | gefüllt / leer / wechselt auf |
| `@ManyToOne` | ENTITY_* | ist / ist nicht / gefüllt / leer / wechselt auf |

Datumsfelder bekommen bewusst **keinen** Vergleich: „älter als 3 Tage" ist ein Zeit-Auslöser
(Konzept §10 Stufe 2), kein Vergleich mit einem Zeitstempel. `-to-many`-Beziehungen fehlen, weil
eine Bedingung über „die Ersatzteile" einen Quantor bräuchte, den das Modell nicht hat.

**Die Aktionen sind dagegen weiter anlagenzentriert**, und das ist die offensichtlichste Lücke:

- `ActionParameters.PLACEHOLDERS` kennt vier Namen, drei davon `${trigger.asset.…}`. Bei einem
  Auftrags-Trigger bleibt nur `${trigger.id}`.
- `SetCustomFieldHandler` schreibt nur auf Anlagen.
- `CustomFieldResolver` liest nur Anlagen-Merkmale, obwohl es Merkmale auch für Aufträge,
  Standorte, Teile, Zähler, Lieferanten und Kunden gibt. Für Meldungen nicht:
  `CustomFieldEntityType` hat kein `REQUEST`, nur `PURCHASE_REQUEST`.

### 1.6 Was offen bleibt

- **Akteur:** `createdBy` bleibt bei allem leer, was die Engine anlegt (Konzept §9.1). Folge:
  niemand wird benachrichtigt, und ein Benutzer ohne „Aufträge anderer sehen" findet einen
  regelerzeugten Auftrag nicht in seiner Liste.
- **Semantische Auslöser: erledigt.** Genehmigt / abgelehnt / abgeschlossen haben seit dem
  E2-Umbau eine Publikationsstelle; `LIVE_SEMANTIC_TRIGGERS` enthält fünf Einträge
  (`REQUEST:APPROVED`, `REQUEST:REJECTED`, `WORK_ORDER:CLOSED`, `PURCHASE_ORDER:APPROVED`,
  `PURCHASE_ORDER:REJECTED`). Wer eine weitere ergänzt, liest
  [`domaenen-events.md`](domaenen-events.md) — dort steht auch, warum das Publizieren über
  `SemanticEventPublisher` läuft und nicht direkt über `ApplicationEventPublisher`.
- **Alt-Engine:** läuft unverändert weiter und wird an denselben Stellen aufgerufen. Beim Testen
  verwirrend: eine doppelt angelegte Aufgabe kann aus einer Alt-Regel kommen.

---

## 2. Rezept: ein neuer Auslöser

Es gibt jetzt zwei Sorten, und sie kosten sehr unterschiedlich viel.

### 2a — Eine weitere Entität beobachten (klein)

1. Zeile in `TrackedEntities`. Die Klasse muss `CompanyAudit` erweitern, sonst fehlt die Company
   und das Ereignis wird verworfen.
2. `AutomationMetaServiceTest.areDerivedFromTheWatchedEntities` zählt die lebenden Trigger; die
   Zahl wächst mit. Das ist die beabsichtigte Kopplung.
3. Platzhalter in `ActionParameters` ergänzen, damit Aktionen die auslösende Entität überhaupt
   referenzieren können.
4. i18n-Schlüssel für die wichtigsten Felder in `en.ts` und `de.ts`. Ohne sie zeigt der Editor
   den vom Server gelieferten lesbaren Ersatztext („Serial number"), nicht den rohen Schlüssel —
   Nachziehen ist also jederzeit möglich, nicht dringend.

Alles andere — Bedingungen, Feldfilter, Metadaten, GUI — folgt von selbst.

### 2b — Eine semantische Änderungsart (größer)

Für „genehmigt", „abgelehnt", „abgeschlossen", die in keiner Spalte stehen. Das ausführliche
Rezept samt Fan-out steht in [`domaenen-events.md`](domaenen-events.md) §7; hier nur der Teil,
den die Engine sieht:

1. `semanticEventPublisher.publish(changeType, entityType, id, companyId, actorId)` im Dienst,
   **innerhalb** der Transaktion. Den Akteur als Argument, wenn die Methode ihn schon hat — die
   Überladung ohne ihn greift auf `CurrentActor.userIdOrNull()` zurück.
2. Eintrag in `AutomationMetaService.LIVE_SEMANTIC_TRIGGERS` als `ENTITY_TYPE:CHANGE_TYPE`.
3. Prüfen, ob dieselbe Änderung schon als `UPDATED` gemeldet wird — dann feuern zwei Trigger für
   ein Geschehen. Das ist zulässig und gewollt (die Regel wählt den passenden), muss aber beim
   Testen bekannt sein.
4. Löst die Aktion selbst eine Änderung aus, `event.child(...)` verwenden. Beispiel: die
   Genehmigung einer Meldung legt einen Auftrag an.
5. Test im Dienst braucht `@Mock ApplicationEventPublisher`.

### Checkliste

```
2a  [ ] Zeile in TrackedEntities (Klasse erweitert CompanyAudit)
    [ ] Platzhalter in ActionParameters
    [ ] i18n für die wichtigsten Felder
    [ ] Trigger-Zähler im AutomationMetaServiceTest
2b  [ ] SemanticEventPublisher.publish im Dienst, in der Transaktion, Company + Akteur
    [ ] Eintrag in LIVE_SEMANTIC_TRIGGERS
    [ ] Folgeereignisse per event.child(...)
    [ ] @Mock ApplicationEventPublisher im Dienst-Test
    [ ] CLAUDE.md-Divergenztabelle, wenn eine Upstream-Datei berührt wurde
```

---

## 3. Was als nächstes ansteht

**1. Aktionen von der Anlage lösen.** Die Bedingungen können jetzt alles, die Aktionen nicht.
Konkret: `${trigger.workOrder.…}`, `${trigger.request.…}`, `SET_CUSTOM_FIELD` für Aufträge, und
`CustomFieldResolver` für die übrigen `CustomFieldEntityType`-Werte. Ohne das sind die zehn
lebenden Trigger nur zur Hälfte nutzbar. **Der nächste Schritt.**

**2. Semantische Auslöser — erledigt** (Case E2, [`domaenen-events.md`](domaenen-events.md)).
Fünf Kombinationen sind live. Offen bleibt daran der Anschluss an die KI-Triage und die Frage,
ob die Triage ihr eigenes `RequestCreatedEvent` behält oder auf `EntityChangedEvent` umzieht —
zwei Ereignismechanismen für dasselbe Geschehen sind eine Altlast in Zeitlupe.

**3. Zuweisung in `CREATE_WORK_ORDER`.** Solange `createdBy` leer bleibt, landet ein
regelerzeugter Auftrag anonym in der Liste und benachrichtigt niemanden. Ein Parameter
„zuweisen an" behebt die praktische Hälfte des offenen Akteur-Punkts.

**4. Danach Konzept §10 Stufe 2: Zeit als Auslöser.** Ab hier bringt kein weiterer reaktiver
Trigger mehr so viel: „nichts ist passiert" ist der in der Praxis häufigste Auslöser, und kein
Ereignis kann ihn liefern.

Bewusst **nicht** geplant: Zählertrigger (`EntityType` hat kein `METER`, und der Pfad legt
selbst schon einen Auftrag an), Standorte und Lieferanten (kein Use Case), Löschungen
(`loadTriggerEntity` lädt frisch per Id — die Zeile ist weg).

---

## 4. Use Cases erheben

Vier Fragen je Kandidat, in dieser Reihenfolge:

1. **Was löst aus?** Anlegen oder Feldänderung einer der fünf Entitäten — oder es ist eine
   Zeitregel und damit Stufe 2.
2. **Woran erkennt man den Fall?** Jetzt: jedes Feld der auslösenden Entität, plus Anlagen-
   Merkmale. Wenn die Antwort „das weiß nur der Meister" ist, fehlt ein Datenfeld, und *das* ist
   die Aufgabe.
3. **Was soll passieren?** Auf die drei Aktionen abbilden — und deren Anlagen-Schieflage aus
   §1.5 mitdenken.
4. **Wer merkt es, wenn es ausbleibt?** Ohne Antwort ist die Regel Dekoration.

Zwei Werkzeuge, die schon da sind:

- **Das Lauf-Protokoll** (*Automatisierungsregeln → Läufe*) beantwortet „ist mein Ereignis
  angekommen?". Kein Eintrag heißt: kein Ereignis. Eintrag mit `SKIPPED` heißt: Ereignis da,
  Bedingung nicht erfüllt. Das trennt die beiden Fehlerbilder, die sich sonst gleich anfühlen.
- **Der Editor selbst.** Er zeigt nur, was die Engine wirklich kann: ausgegraute Trigger sind
  nicht verdrahtet, und die Feldliste eines Auslösers enthält genau die Namen, die sein Diff
  melden kann.
