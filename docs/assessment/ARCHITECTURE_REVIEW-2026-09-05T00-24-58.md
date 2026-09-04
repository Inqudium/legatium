# Architecture & Appropriateness Analysis: Legatium

1. Identification of the Codebase
   - **Repository:** `https://github.com/Inqudium/legatium.git`
   - **Commit-Hash:** `7aa90eb08069cd164cd7cf79035b2abf264214e2` (Full)
   - **Reference (Branch/Tag):** `refs/heads/main`; kein Tag ist am Commit ausgewiesen
   - **Working Tree:** sauber; `main...origin/main`
2. Scope of the Analysis
   - **Included production code:** `./legatium-common/src/main/`, `./legatium-restclient-logging/src/main/`, `./legatium-webclient-logging/src/main/`
   - **Included test code:** der vollständige Testbestand unter `./legatium-common/src/test/`, `./legatium-restclient-logging/src/test/` und `./legatium-webclient-logging/src/test/` ist ausdrücklich Analysegegenstand; die Testarchitektur wird in voller Phase-2-Tiefe bewertet
   - **Included architecture/build context:** `./pom.xml`, die drei Modul-POMs, `./README.md`, `./CONTRIBUTING.md`, `./docs/GUIDE.md`, `./docs/adr/`, die Modul-Guides sowie relevante Dateien unter `./.github/workflows/` und `./.github/scripts/`
   - **Excluded:** Build-Ausgaben unter `./target/` und `./*/target/`, veröffentlichte/geshadete Artefakte, Logos und sonstige nicht ausführbare Assets; binäre Fuzz-Korpora wurden als Testtopologie inventarisiert, nicht byteweise analysiert; frühere Dateien unter `./docs/assessment/` sind schreibgeschützte Historie und kein ungeprüfter Befundbestand
   - **Documented production scale/SLA/team size:** `[MISSING - please supply]`; Repository-Metadaten und Git-Historie zeigen einen Maintainer/Autor, belegen aber weder tatsächliche Teamgröße noch Last-, Latenz- oder Verfügbarkeitsziele
3. Analysis Environment & Tools
   - **Target Environment:** Java 21, Kotlin 2.4.10, Spring Boot 4.1.1
   - **Build system:** Apache Maven, Multi-Modul-Reaktor; dokumentierte Build-Voraussetzung JDK 24+, CI mit JDK 25
   - **Local analysis environment:** Oracle JDK 26.0.1, Apache Maven 3.9.15, Linux x86_64
   - **Analysis tools used:** vollständige Quelltextlektüre, `rg`, `sed`, `nl`, `diff`, Git-Metadaten und lokale JDK-Quellen; keine SonarQube-/IDE-/detekt-Ausführung
   - **Verification depth:** reine statische Analyse; gemäß Pure-Analysis-Vorgabe wurde kein Maven-Build gestartet. Der historische Remediation-Vermerk in `./docs/assessment/CODE_ANALYSIS-2026-09-04T23-18-42.md` nennt 328 grüne Tests nach dem aktuellen Fixstand, wurde hier aber nicht neu ausgeführt.
4. Placement & Output
   - **Working directory (workdir):** `/home/dirk/IdeaProjects/legatium` (absoluter Referenzpunkt; alle relativen Pfade beziehen sich darauf)
   - **Report output path:** `./docs/assessment/ARCHITECTURE_REVIEW-2026-09-05T00-24-58.md`
   - **Scope root (relative to the workdir):** `./`
   - **Path convention for findings:** `<path relative to the workdir>:<line>`
   - **Timestamp:** `2026-09-05T00:24:58+02:00`, Europe/Berlin

---

## 1. Executive Summary

Legatium ist eine kleine Spring-Boot-Bibliothek mit einer grundsätzlich passenden Trennung zwischen gemeinsamem Vertrag, blockierendem RestClient/RestTemplate-Adapter und reaktivem WebClient-Adapter. Die hohe Komplexität der reaktiven Signal- und Cancel-Behandlung ist durch den sichtbaren Lifecycle und konkrete Race-Szenarien belastbar gerechtfertigt; ebenso sind Fail-open-Grenzen, begrenzte Body-Tees und die Vier-Connector-Matrix tragend statt dekorativ. Die frühere Architektur-Review wurde am aktuellen Commit weitgehend umgesetzt: gemeinsame Metrik- und Aktivierungslogik sowie gemeinsame Vertragsmodelle liegen nur noch einmal vor, während stack-spezifische Lifecycle-Mechanik getrennt bleibt. Es gibt keine kritische oder hohe Fehlanpassung. Drei mittlere Befunde verbleiben: Der Metrik-Unterbau ist relativ zum Kernversprechen und zu den belegten Betriebsanforderungen sehr groß, die flächendeckende Test-Evidenz-Zeremonie verursacht laufende Pflegekosten, und die komplexe Shade-/Friend-Path-Distributionsgrenze wird nicht am tatsächlich ausgelieferten Artefakt geprüft. Die Tendenz ist damit nicht zu fehlenden Schichten oder einem Big Ball of Mud, sondern zu Gold-Plating an Observability- und Assurance-Rändern. ADR-0003, die reaktive Zustandsmaschine, Fuzzing sowie die bewusst getrennten Body-Capture-Implementierungen wurden ausdrücklich gegen einfachere Alternativen geprüft und wegen realer Kräfte nicht als Befund gewertet.

**Kompaktes Testurteil:** Die Produktionsarchitektur ist sehr gut isoliert testbar: Zeit, Korrelation und Maskierung haben kleine Seams, die Entry Points lassen sich ohne Spring-Kontext konstruieren, und die reaktive Semantik wird mit `StepVerifier` und gezielten Nebenläufigkeitstests geprüft. Die genutzte Pyramide ist gesund und unit-lastig; nur fünf Klassen/Basisklassen tragen `@SpringBootTest`, ohne Datenbank, Broker oder Container, während die Connector-Verträge echte Engines dort starten, wo das die Aussagekraft erhöht.

- Größte Anomalie: 8.827 Testzeilen stehen 4.101 Produktionszeilen gegenüber; 284 deklarierte Test-/Fuzz-Methoden tragen eine verpflichtende Evidenz- und Stage-Kommentierung.
- Größte Lücke: Die Tests laufen vor `package` gegen `legatium-common`; kein Consumer-Smoke-Test prüft die beiden geshadeten Standalone-JARs und deren reduzierte POMs.
- Ungenutzte bzw. fehlgeleitete Evidenz: Zehn von zwölf Methoden der beiden `TwinContractTest`-Klassen prüfen gemeinsame Typen erneut, nicht die ausgelieferten Twin-Artefakte.

## 2. Problem Baseline & Methodology

### Problem-Baseline

Das System ist keine Geschäftsanwendung und besitzt weder Persistenz noch Messaging. Es instrumentiert ausgehende HTTP-Aufrufe und emittiert pro Exchange eine strukturierte `adapter_*`-Zeile: synchron über einen `ClientHttpRequestInterceptor`, reaktiv über eine `ExchangeFilterFunction`. Der gemeinsame Kern verantwortet Konfiguration, Auswahl und Maskierung von Headern, Korrelationsidentität, MDC, Timeout-/Outcome-Klassifikation und Micrometer-Metriken. Body-Logging und Body-Messung sind optional; beide Stacks beobachten die Bytes passiv, aber mit stackgerechtem Lifecycle.

Ein quantifiziertes Lastprofil, Latenzbudget, eine Nutzerzahl, Betriebs-SLOs oder eine dokumentierte Teamgröße fehlen (`[MISSING - please supply]`). Als reale Kräfte sind dagegen sichtbar: eine Bibliothek darf den beobachteten Call nicht beschädigen, beide Client-Paradigmen sollen denselben Operatorvertrag liefern, WebClient-Responses sind lazy und cancellable, RestClient-Responses enden erst beim Close, und die Artefakte sollen einzeln konsumierbar sein. Die dokumentierte operative Reife ist für die Codegröße hoch: sieben ADRs, CI, CodeQL, SBOM/OSV, SLSA, Fuzzing, JaCoCo, MkDocs und Dokka.

### Dokumentierte Architekturabsicht

- `./docs/adr/ADR-0002-trace-id-is-the-request-id.md`: Trace-Identität hat Vorrang vor privater Korrelation.
- `./docs/adr/ADR-0003-legatium-common-inlined-by-shade.md`: byte-identische Twin-Logik liegt in `legatium-common`, wird ohne Relocation in beide JARs geshadet und bleibt unveröffentlicht; die dokumentierten Kosten sind Friend Paths, gleiche Twin-Versionen und fehlende JPMS-Unterstützung bei beiden Twins.
- `./docs/adr/ADR-0004-counting-correlation-id-default.md`: der Zählergenerator vermeidet potenziell blockierende Zufallsarbeit im per-call-Pfad. Trotz fehlender Messung ist die Nichtblockierungsanforderung des reaktiven Twins eine konkrete Kraft; der Generator wurde deshalb nicht als Befund gewertet.
- ADR-0005/0006/0007 begründen Mask-by-default, outcome-gesteuertes Body-Logging und das Operatorvokabular.

### Technologie-Kohärenz

Die Paradigmen werden nicht vermischt: `spring-web` bleibt im blockierenden Twin, `spring-webflux`/Reactor im reaktiven Twin. Es gibt keinen blockierenden Treiber hinter einer vermeintlich reaktiven Anwendungsarchitektur; der reaktive Code ist gerade der Adapter für WebClient. Die gemeinsam genutzten Typen sind Spring-nah, was für eine Spring-Instrumentierungsbibliothek angemessen ist und keine künstlich frameworkfreie Domain-Schicht verlangt. Die zwei veröffentlichten Module vermeiden unnötige Client-Transitives; der gemeinsame Modulcut folgt dem tatsächlich gemeinsamen Vertrag.

### Testtopologie und Methode

Statisch gezählt wurden 32 Kotlin-Produktionsdateien mit 4.101 Zeilen, zwei Auto-Configuration-Ressourcen sowie 46 Testquellen und 35 Testressourcen. Die Testquellen/-ressourcen umfassen 8.827 textuell gezählte Zeilen und 284 deklarierte `@Test`-/`@FuzzTest`-Methoden; durch den vierfach ausgeführten `ConnectorContract` liegt die Laufzeitfallzahl höher. Es gibt fünf `@SpringBootTest`-Deklarationen, zwei `ApplicationContextRunner`-Suites, zwölf `StepVerifier`-Verwendungen und keine Mocking-Bibliothek. Der Großteil der Core- und Adapterlogik ist ohne Container prüfbar; echte Spring-Kontexte, Tracing-Bridges und vier reale WebClient-Connectoren werden nur an Framework-Grenzen eingesetzt.

Die Einheiten wurden zuerst nach Mismatch-Risiko gerankt und anschließend ab Score 5 abwärts geprüft. Jede Kandidaten-Abstraktion wurde gegen Problem, rechtfertigende Kraft, Kosten, einfachste tragfähige Alternative und Reversibilität gehalten. Frühere Assessments wurden als Hypothesenquelle gelesen; ihre Befunde wurden nur nach Prüfung am aktuellen Commit berücksichtigt. Nicht ausgeführt wurden Build, Tests, Benchmarks, Laufzeitprofiling und ein Consumer-Test der erzeugten JARs; fehlende reale Nutzungs- und Lastdaten begrenzen insbesondere die Konfidenz von Befund 1.

## 3. Statistics

| Severity | Anzahl |
|---|---:|
| Critical | 0 |
| High | 0 |
| Medium | 3 |
| Low | 0 |
| **Gesamt** | **3** |
| **Systemische Muster** | **3** |

| Umfang | Anzahl |
|---|---:|
| Kotlin-Produktionsdateien | 32 |
| Produktionszeilen (inkl. Kommentare/Leerzeilen) | 4.101 |
| Testquellen | 46 |
| Testressourcen | 35 |
| Testzeilen (textuell gezählt) | 8.827 |
| deklarierte `@Test`-/`@FuzzTest`-Methoden | 284 |

## 4. Ranking Table

| Unit | Score | Rationale |
|---|---:|---|
| `./legatium-webclient-logging/src/main/kotlin/.../{ClientRequestLoggingFilter,ObservedResponse,ObservedBody,CapturingDecorators,Exchange,BoundedBodyCapture}.kt` | 5 | Zentraler reaktiver Hot Path mit Signal-Ownership, Cancellation-Races, Puffer-Lifecycle und Exactly-once-Abschluss; hohe Dichte, aber überwiegend tragend |
| `./legatium-common/src/main/kotlin/.../ClientLoggingMetrics.kt` plus beide `*MetricsTest.kt` | 5 | 435 Produktions- und 1.106 dedizierte Testzeilen, globale Registry-/Gauge-Semantik und sieben Meter-IDs; Befund 1 |
| `./pom.xml`, Twin-POMs und `./docs/adr/ADR-0003-legatium-common-inlined-by-shade.md` | 5 | Architektur- und Distributionsgrenze mit Shading, Dependency-Reduced POM, Friend Paths, Split Package und Versionskopplung; Befund 3 liegt im Nachweis, nicht in der entschiedenen Struktur selbst |
| `./legatium-restclient-logging/src/main/kotlin/.../{ClientRequestLoggingInterceptor,CapturingClientHttpResponse,Exchange,BoundedBodyCapture}.kt` | 4 | Zentraler blockierender Hot Path, Close-/Stream-Lifecycle und Cross-Thread-Handoff; Komplexität durch reale Client-Semantik gerechtfertigt |
| Beide `./legatium-*/src/main/kotlin/.../ExchangeLogEmitter.kt` | 4 | Je ein zentraler Event-Assembler mit bewusster Twin-Duplizierung; ADR-0003 akzeptiert die Unterschiede unterhalb der 90-%-Schwelle |
| Test-Evidenz-Pipeline: `./CONTRIBUTING.md`, `./.github/scripts/generate-test-catalog.py`, `./.github/workflows/docs.yml` und alle Tests | 4 | Pervasive Konvention und eigener Source-/Surefire-Parser; Befund 2 |
| `./legatium-common/src/main/kotlin/.../{ClientIdentity,Mdc,HeaderValueMasker,HeaderLogProperties,ClientActivation,Timeouts,Traceparent,FailOpenDiagnostics}.kt` | 3 | Gemeinsame Grenzen für fremde Daten, Thread-Local-Restoration und Fail-open; kohäsiv und isoliert testbar |
| `./legatium-common/src/main/kotlin/.../CorrelationIdGenerator.kt` plus ADR-0004 und Tests | 3 | Besondere Implementierung mit großem Nachweisaufwand; reaktive Nichtblockierung und expliziter ADR tragen die Komplexität |
| Beide Auto-Konfigurationen und ihre `ApplicationContextRunner`-Tests | 2 | Dünne, stack-spezifische Spring-Wiring-Schicht; Container wird nur für den Wiring-Vertrag benutzt |
| Fuzz-Ziele, Connector-Matrix und Tracing-Integration | 2 | Breite, aber durch fremde Eingaben und dokumentierte Connector-/Tracing-Kompatibilität gerechtfertigte Evidenz |
| DTO-/Enum-artige Shared-Typen, Auto-Configuration-Imports und binäre Seeds | 1 | Kleine, lokale Vertragsträger beziehungsweise passive Ressourcen mit geringem Mismatch-Risiko |

## 5. Findings Checklist

### 🔴 Critical

Keine Befunde.

### 🟠 High

Keine Befunde.

### 🟡 Medium

- [x] 1. [`./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientLoggingMetrics.kt:82`, `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientLoggingMetrics.kt:119`, `./legatium-restclient-logging/pom.xml:45`, `./legatium-webclient-logging/pom.xml:49`] {Medium} {Confidence: medium} {Over-Engineering / Boundaries & Responsibilities} Der Observability-Unterbau ist relativ zum Kernversprechen und zu den belegten Betriebsanforderungen zu groß und verpflichtend gekoppelt
  - Actual structure: Ein 435-zeiliger Owner verwaltet sieben Meter-IDs in sechs dokumentierten Familien, Vorregistrierung, eine private Fallback-Registry, Konflikterkennung für fremde Meter und einen schwach referenzierten Owner-Cache pro Registry/Stack. Beide veröffentlichten Twins deklarieren Micrometer direkt. 603 RestClient- plus 503 WebClient-Testzeilen prüfen die Metrikmechanik; Request-ID-, Open-Exchange- und Event-Zähler werden auf jedem aktiven Call berührt, Body-Meter sind opt-in.
  - Solved problem / justifying force: `failopen` und `exchanges.open` kompensieren reale blinde Flecken des Fail-open- beziehungsweise Late-Emission-Designs; auch die übrigen Meter haben dokumentierte Interpretationen. Nicht sichtbar sind jedoch ein konkreter Consumer, ausgelieferte Dashboards/Alerts, eine regulatorische Pflicht oder gemessene Betriebsanforderungen, die die vollständige Familie samt kollisionsfester Fallback-Infrastruktur rechtfertigen. `./CONTRIBUTING.md:14-20` beschreibt den Scope weiterhin primär als eine Logzeile und warnt vor einer Ausweitung um Metrik-Frameworks.
  - Cost: Rund 10,6 % aller Produktionszeilen und 12,5 % aller Testzeilen gehören allein dem dedizierten Metrikowner und seinen zwei Tests; zusätzlich verteilt sich Bookkeeping über Entry Points, Exchanges, Captures und Emitter. Die Bibliothek muss dadurch Registry-Lebensdauer, Meter-ID-Kollisionen, Fallback-Semantik und Cardinality dauerhaft als eigene Produktverträge pflegen.
  - Simpler alternative: Die nachweislich tragenden Fehler-/Liveness-Signale als kleinen Kern behalten und Korrelation-/Body-Telemetrie erst bei belegtem Consumerbedarf beziehungsweise explizit aktivierter Metrikintegration anbieten. Ohne Host-Registry sollte die Logfunktion nicht eigens eine vollständige unexportierte Registry-Lebenswelt aufbauen müssen.
  - Reversibility: Vor dem in `./CHANGELOG.md` noch als „Unreleased“ geführten 1.0-Release moderat; nach Veröffentlichung werden Meter-Namen und Tag-Vokabulare zu externen Dashboard-Verträgen und eine Reduktion teuer. Wegen unbekannter realer Consumeranforderungen ist die Konfidenz bewusst nur mittel.
  - **Fixed (2026-09-05), Umfang bewusst begrenzt:** Die Meter-Familie bleibt - sie spiegelt Limesiums `endpoint.*`-Familie eins zu eins, und die Body-Meter sind bereits opt-in; die fehlende Begründung ist nun `docs/adr/ADR-0008-six-meters-consumed-not-exported.md` (Blind-Spot-Regel für jedes Meter, Namen mit 1.0 eingefroren, Wiedereröffnungsbedingungen). `CONTRIBUTING.md` nennt die sechs Meter als Scope statt „metrics frameworks“ als außerhalb. Ohne Host-Registry übergeben beide Auto-Konfigurationen eine leere `CompositeMeterRegistry` (No-op-Meter) statt einer privaten `SimpleMeterRegistry`. Das Registrierungsverhalten des gemeinsamen Owners wird einmal in `legatium-common` getestet (`ClientLoggingMetricsTest`, 11 Fälle für beide Stacks); aus beiden Twin-Metriktests sind die fünf gemeinsamen Fälle entfernt, sie behalten die Lifecycle-Fälle ihres Entry Points.

- [x] 2. [`./CONTRIBUTING.md:90`, `./.github/scripts/generate-test-catalog.py:21`, `./.github/scripts/generate-test-catalog.py:34`, `./.github/workflows/docs.yml:57`] {Medium} {Confidence: high} {Testability & Test Architecture / Over-Engineering} Die Test-Evidenz-Pipeline macht dokumentarische Zeremonie zu einer flächendeckenden zweiten Testpflicht
  - Actual structure: Jede Testmethode soll einen dreiteiligen Block (`What is tested`, `Success criteria`, `Why it matters`) plus Given/When/Then-Stufen tragen. Im aktuellen Bestand wurden 284 `What`-, 284 `Success`- und 283 `Why`-Marker sowie 673 Stage-Marker gezählt. Ein eigener 216-zeiliger Python-Parser koppelt Kotlin-Quellformat, Methodennamen und Surefire-XML an eine generierte Website; der Docs-Workflow führt dafür erneut den Maven-Install/Testpfad aus. Die drei Java-Fuzztests werden vom auf `*Test.kt` begrenzten Parser nicht als Quellrationale ausgewertet.
  - Solved problem / justifying force: Eine öffentlich nachvollziehbare Testliste ist ein reales Ergebnis, und Surefire-Status sowie Laufzeiten lassen sich sinnvoll generieren. Eine regulatorische, vertragliche oder Nutzeranforderung, die für auch triviale Wahrheitstabellen eine individuelle Prosa-Evidenz verlangt, ist nicht dokumentiert (`[MISSING - please supply]`).
  - Cost: Die Tests sind mit 8.827 Zeilen mehr als doppelt so groß wie die Produktion; die Pflicht vervielfacht Änderungen an Testnamen und -struktur, schafft driftfähige Aussagen neben Testname und Assertions und macht die Dokumentation von einem projektspezifischen Source-Parser abhängig. Ein fehlender oder anders formatierter Marker wird so zu einem Docs-Build-Problem, ohne die getestete Semantik zu verbessern.
  - Simpler alternative: Testergebnis, Klassenname, Methodennamen und Laufzeiten direkt aus Surefire generieren; eine ausführliche Begründung nur auf Klassen-/Use-Case-Ebene oder für Score-4/5-Integrations-, Nebenläufigkeits- und Vertragsfälle verlangen. Triviale lokale Tests sollten durch sprechenden Namen und Assertions hinreichend dokumentiert sein.
  - Reversibility: Gering; Konvention und Generator können schrittweise vereinfacht werden, ohne Produktions- oder Testverhalten zu ändern. Bestehende rationale Texte müssen nicht sofort entfernt werden.
  - **Fixed (2026-09-05), Konvention beibehalten:** Der Drei-Fragen-Block ist die Konvention des gesamten Inqudium-Ökosystems (Vorlage tabellarium, Limesium mit identischem Generator); der Parser bricht nur bei unvollständigen Blöcken ab, ein fehlender läuft still durch, und Rationale- plus Stage-Zeilen machen rund 1.500 der 8.827 Testzeilen aus. Umgesetzt wurden die beiden konkreten Lücken: `generate-test-catalog.py` liest jetzt auch die Java-`@FuzzTest`-Ziele unter `src/test/java` (deren Blöcke vollständig vorhanden waren, aber nie ausgewertet wurden) und ordnet Surefires `method(FuzzedDataProvider)[n]`-Aufrufe dem Methodennamen zu; `CONTRIBUTING.md` schreibt die schon praktizierte Einzeiler-Form `// Given/When/Then` für Wahrheitstabellen-Tests fest.

- [x] 3. [`./pom.xml:328`, `./pom.xml:351`, `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/TwinContractTest.kt:14`, `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/TwinContractTest.kt:14`] {Medium} {Confidence: high} {Testability & Test Architecture / Under-Engineering} Der Nachweis sitzt vor der riskanten Shade-Grenze und prüft deshalb nicht das Produkt, das Konsumenten erhalten
  - Actual structure: Surefire führt die Twin-Tests in der `test`-Phase gegen das Reactor-Modul `legatium-common` aus; erst danach in `package` inline-t Shade die Common-Klassen und erzeugt eine dependency-reduced POM. Es gibt weder Failsafe-/Invoker-/Consumer-Modul noch einen JAR-/POM-Smoke-Test nach dem Packaging. Gleichzeitig enthalten die zwei `TwinContractTest`-Dateien 246 Zeilen und zwölf Methoden; zehn davon prüfen Typen aus dem gemeinsamen Modul, acht prüfen in beiden Twins denselben Vertragsaspekt erneut. Nur je ein Message-Test übt tatsächlich den jeweiligen Entry Point aus.
  - Solved problem / justifying force: ADR-0003 begründet die Ein-Artefakt-Distribution und ihre Friend-Path-/Split-Package-Kosten nachvollziehbar. Die Twin-Tests sollen Vertragsdrift verhindern, können aber weder fehlende geshadete Klassen noch eine versehentlich veröffentlichte Common-Abhängigkeit oder die gemeinsame Laufzeit beider fertiger JARs erkennen; sie laufen an der falschen Seite der relevanten Grenze.
  - Cost: Doppelte Literalpflege erzeugt wiederholte Evidenz ohne zusätzlichen Schutz, während ein Fehler in der komplexesten Buildentscheidung erst beim Konsumenten oder Release auffallen kann. Das ist zugleich über-engineerte Inward-Prüfung und unter-engineerte Artifact-Prüfung.
  - Simpler alternative: Gemeinsame Literalverträge einmal in `legatium-common` testen, die zwei Entry-Point-/Message-Verträge bei den Twins behalten und einen kleinen Post-Package-Consumer-Smoke-Test gegen jedes fertige Standalone-JAR ergänzen. Dieser sollte mindestens Common-Klassen, Auto-Configuration-Metadaten und die Abwesenheit der unveröffentlichten Common-Abhängigkeit aus Konsumentensicht prüfen.
  - Reversibility: Gering bis moderat; es betrifft nur Test-/Buildnachweis. Die Produktionsarchitektur und der durch ADR-0003 entschiedene Modulcut müssen dafür nicht geändert werden.
  - **Fixed (2026-09-05):** Die gemeinsamen Literale (Meter-Namen, Fallback-Tags, Read-States, MDC-Keys, Outcome-Vokabular, Fail-open-Stufen, Request-ID-Quellen) pinnt einmal `SharedContractTest` in `legatium-common`; der Fingerprint-Pin war dort bereits (`HeaderValueMaskerTest`). Jeder Twin-`TwinContractTest` behält zwei Methoden: den eigenen `ClientStack` (client-Tag, vorregistrierte Outcomes) und den Message-Text seines Emitters - 12 Methoden werden zu 4 plus 5. Neu ist das eigenständige Projekt `consumer-smoke/` (kein Reactor-Kind, wie Limesiums `benchmarks/`): ein `@SpringBootTest` gegen die installierten Twin-Jars prüft, dass die Common-Klassen aus genau den zwei Twin-Jars und aus keinem `legatium-common`-Artefakt aufgelöst werden, dass beide Auto-Konfigurationen über die Imports-Dateien der Jars verdrahten und dass je ein Aufruf pro Client gegen einen lokalen Peer eine Exchange-Zeile erzeugt. Der CI-Job `consumer-smoke` installiert den Reaktor, löscht `legatium-common` aus dem lokalen Repository und baut erst dann den Consumer. ADR-0003 erhält das Amendment vom 2026-09-05, READMEs, Modul-Guides und CHANGELOG folgen.

### 🟢 Low

Keine zusätzlichen Befunde. Die verbliebenen kleinen Einzelabstraktionen haben entweder zwei reale Twin-Nutzer, eine konkrete Test-/Host-Seam oder eine dokumentierte Lifecycle-Kraft; ihre Entfernung wäre überwiegend Geschmacksurteil.

## 6. Systemic Patterns

- **P1 — Observability beobachtet die Observability.** Zählbasis: sieben Meter-IDs in `ClientLoggingMetrics`, 435 Owner-Zeilen und 1.106 Zeilen in den beiden dedizierten Metriktests. Das Muster reicht in beide Entry Points, beide Emitter und beide Body-Captures; Befund 1 bewertet die Reichweite einmal statt pro Meter.
- **P2 — Testprosa als ausführbarer Nebenvertrag.** Zählbasis: 284 deklarierte Test-/Fuzz-Methoden, 851 Rationale-Markerzeilen und 673 Given/When/Then-Marker über alle drei Module, verarbeitet durch einen 216-zeiligen Generator. Befund 2 fasst die systemische Pflege- und Kopplungswirkung zusammen.
- **P3 — Twin-Parität wird innerhalb statt außerhalb der Packaging-Grenze geprüft.** Zählbasis: zwei `TwinContractTest`-Dateien mit zusammen 246 Zeilen und zwölf Methoden; zehn Methoden lesen gemeinsame Typen, acht wiederholen denselben Vertragsaspekt in beiden Modulen, null Tests konsumieren die nach `package` entstandenen Standalone-JARs. Befund 3 beschreibt die daraus entstehende Assurance-Lücke.

Die höchste Produktionskomplexität — reaktive Handover-/Cancel-Operatoren, Exactly-once-Abschluss, passive Body-Tees und Fail-open — bildet **kein** systemisches Mismatch: Sie folgt direkt aus den Framework-Lifecycles, hat gezielte isolierte und echte Connector-Tests und würde durch eine scheinbar einfachere Implementierung reale Vertragsfälle verlieren. Es wurden keine Code-, Build- oder Testdateien verändert; dieser neue, timestamped Bericht ist die einzige Schreiboperation der Analyse.
