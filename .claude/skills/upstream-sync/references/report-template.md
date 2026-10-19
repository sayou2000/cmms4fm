# Upstream-Synchronisationsbericht

<!-- Vorlage für docs/sync-upstream-YYYY-MM-DD.md. Auf Deutsch. Abschnitte ohne Inhalt weglassen.
     Vorbild mit allen Abschnitten: docs/sync-upstream-2026-09-29.md -->

**Datum der Durchführung:** TT. Monat JJJJ
**Ziel-Branch:** direkt `main` (Testinstanz) | `chore/upstream-sync` + PR

---

## 1. Kontext

* **Merge-Base:** `<sha>` (Datum) — Endstand des Syncs vom <Datum>.
* **Upstream:** N Commits bis `<sha>` (Datum), M Dateien.
* **Fork:** N eigene Commits gegenüber Upstream.
* X Dateien auf beiden Seiten geändert, davon **Y mit Konflikt**.

Hauptthemen Upstream in zwei, drei Sätzen.

## 2. Konflikte und Auflösung

| Datei | Auflösung |
|---|---|
| `…` | was übernommen, was behalten, warum |

## 3. Bewusste Abweichungen in sauber gemergten Dateien

* **`Datei`: was anders ist als bei Upstream.** Warum, und dass es in der Divergenz-Tabelle steht.

## 4. Geprüft, ohne Handlungsbedarf

* Was im Risiko-Scan auffiel und warum es hier harmlos ist (Telemetrie aus, Image ziehbar, …).
* Welche Zeilen der Divergenz-Tabelle berührt wurden und dass der Fork-Fix erhalten blieb.

## 5. Verifikation

* `verify-local.sh`: Backend kompiliert, Tests <Klassen> lokal grün, Frontend baut, MCP x/y.
* CI: <Anzahl> Tests grün (oder: welcher Fehler, Ursache, Fix).
* Live: neues Bundle, API, Storage, MCP, Login-Check.

## 6. Offen

* Was Lars selbst tun muss (z. B. Coolify-Variablen), was als eigener Schritt folgt.
