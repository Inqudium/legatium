# Code- & Defect-Analyse: Legatium (legatium-common, legatium-restclient-logging, legatium-webclient-logging, consumer-smoke)

1. Identification of the codebase
   - **Repository:** `https://github.com/Inqudium/legatium.git`
   - **Commit hash:** `1e7560e9e4fe3b031b254485578105dc2cecb5c5` (vollständig; Merge-Commit von PR #7)
   - **Reference (branch/tag):** `refs/heads/main`; nächstliegendes Tag `1.0.0` (24 Commits davor, `git describe`: `1.0.0-24-g1e7560e`)
   - **Working tree:** zum Analysezeitpunkt sauber, `main` gleich `origin/main`
2. Scope of the analysis
   - **Eingeschlossen:** `./legatium-common/src/main/kotlin/`, `./legatium-restclient-logging/src/main/kotlin/`, `./legatium-webclient-logging/src/main/kotlin/` sowie die Auto-Configuration-Importdateien unter `src/main/resources/`. **Testcode ist Teil der Analyse** und ein eigenständiges Analyseobjekt: `./legatium-common/src/test/` (Kotlin und Java-Fuzz-Ziele), `./legatium-restclient-logging/src/test/`, `./legatium-webclient-logging/src/test/`, `./consumer-smoke/src/test/java/` und `./consumer-smoke/pom.xml`.
   - **Als Vertrags- und Umgebungskontext konsultiert:** `./pom.xml`, die drei Modul-POMs, `./CONTRIBUTING.md`, `./docs/adr/` (ADR-0001 bis ADR-0011), `./docs/GUIDE.md`, die Modul-READMEs und -Guides, `./.github/workflows/`, die JaCoCo- und Surefire-Ausgaben unter `./*/target/` aus dem letzten lokalen Build sowie die lokal aufgelösten Jars und Source-Jars von Spring Framework 7.0.9, Reactor Core 3.8.7, Micrometer 1.17.1 und `context-propagation` 1.2.1.
   - **Ausgeschlossen:** Build-Ausgaben unter `./target/` und `./*/target/` (nur als Messwerte gelesen, nicht als Analysegegenstand), geshadete Artefakte, Dokumentationsassets (Logos), die Skripte unter `./.github/scripts/` und die generierte Site. Fuzz-Korpora unter `src/test/resources/` wurden auf Existenz und Umfang geprüft, nicht byteweise.
   - **Nicht Gegenstand:** ein Security-Audit; sicherheitsrelevante Nebenwirkungen werden nur genannt, wo ein funktionaler Vertrag betroffen ist.
3. Analysis environment & tools
   - **Zielplattform:** Java 21 (Bytecode-Ziel), Kotlin 2.4.20, Spring Boot 4.1.1 (Spring Framework 7.0.9, Reactor Core 3.8.7, Micrometer 1.17.1); Build benötigt JDK 24+.
   - **Lokale Analyseumgebung:** Oracle JDK 26.0.1, Apache Maven 3.9.15, Linux x86_64. CI läuft auf Temurin 25.
   - **Buildsystem:** Maven-Multimodul-Reaktor (`legatium-common`, `legatium-restclient-logging`, `legatium-webclient-logging`); `consumer-smoke` ist ein eigenständiges Maven-Projekt außerhalb des Reaktors.
   - **Vorhandene statische/automatisierte Prüfungen:** ktlint 3.7.1 (`verify`), JaCoCo (Reports, kein Mindestschwellwert), CodeQL, OpenSSF Scorecard, OSV über CycloneDX-SBOM, drei Jazzer-Fuzz-Ziele, Dokka mit `failOnWarning`. Keine Konfiguration für detekt, SpotBugs, Error Prone, NullAway, ArchUnit oder Sonar gefunden.
   - **Für die Analyse verwendet:** vollständige Quelltextlektüre aller Dateien im Scope (drei parallele Modul-Lesungen, deren Kandidaten anschließend am Code und an den Framework-Jars nachgeprüft wurden), `grep`, `javap` und die Source-Jars von Spring/Reactor/Micrometer für Framework-Behauptungen, ein kurzes `java`-Skript für das `java.net.URI`-Verhalten, JaCoCo-CSV und Surefire-XML aus dem letzten lokalen Build.
   - **Lokale Verifikation:** Der Code dieses Commits wurde in derselben Sitzung mit `mvn verify` gebaut (grün: ktlint, 387 Surefire-Tests, Consumer-Smoke), und die CI-Checks des Merge-Commits sind grün. Es wurden **keine Reproduktionstests für die Befunde geschrieben** (reine Analyse); die Befunde sind statisch verifiziert und, wo angegeben, gegen Framework-Quellen geprüft.
4. Placement & Output
   - **Working directory (workdir):** `/home/dirk/IdeaProjects/legatium` (absoluter Bezugspunkt; alle relativen Pfade beziehen sich darauf)
   - **Report output path:** `./docs/assessment/DEFECT_ANALYSIS-2026-09-16T20-19-34.md`
   - **Zeitstempel:** 2026-09-16T20:19:34+02:00, Europe/Berlin
   - **Scope root (relative to the workdir):** `./`
   - **Path convention for finding locations:** `./pfad:zeile` (z. B. `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/ClientRequestLoggingInterceptor.kt:121`)
   - **Historische Assessments:** `./docs/assessment/CODE_ANALYSIS-2026-09-04T20-56-15.md`, `./docs/assessment/CODE_ANALYSIS-2026-09-04T23-18-42.md`, `./docs/assessment/ARCHITECTURE_REVIEW-2026-09-04T21-49-30.md`, `./docs/assessment/ARCHITECTURE_REVIEW-2026-09-05T00-24-58.md`, `./docs/assessment/CODE_STYLE-2026-09-04T22-19-30.md`, `./docs/assessment/COMMENT_AUDIT-2026-09-05T01-57-47.md` wurden als Historie gelesen und nicht verändert. Die sechs Befunde der Analyse vom 2026-09-04T23-18-42 (dort als behoben markiert) wurden am aktuellen Commit nachgeprüft und sind sämtlich behoben (siehe Abschnitt 2); kein früherer Befund wurde ungeprüft übernommen.

---

## 1. Executive Summary

Legatium ist auf diesem Stand eine kleine, sehr bewusst konstruierte Bibliothek: ein expliziter Fail-open-Vertrag mit `Exception`-Grenze, exactly-once-Abschlüsse über CAS, injizierte Zeit- und ID-Quellen, begrenzte Body-Captures und seit den ADRs 0009 bis 0011 ein einheitliches Schichtenmodell für den Aufrufer-Kontext auf beiden Stacks. Im Happy Path wurde kein Datenverlust, kein Deadlock und kein durch die Bibliothek verursachter Request-Abbruch gefunden; die sechs Befunde der Vorgängeranalyse sind am Code nachweislich behoben.

Es verbleiben **21 Befunde: kein Critical, kein High, vier Medium und siebzehn Low**, dazu **sechs systemische Muster**. Die Medium-Befunde liegen sämtlich an Vertrags- und Fehlergrenzen, nicht im Kernfluss: (1) Der Lesezustand des Response-Bodys, der Tag `state` des Meters `adapter.response.body.read`, wird auf den beiden Twins an unterschiedlichen Beobachtungspunkten festgelegt, sodass dieselbe body-lose 204-Antwort auf dem RestClient als `unread` und auf dem WebClient als `complete` zählt und ein per `toBodilessEntity()` bewusst verworfener Body auf dem WebClient als vollständig gelesen gilt. (2) In beiden Twins läuft die optionale Startzeile außerhalb der `Throwable`-Grenze, die die Gauge `adapter.logging.exchanges.open` gegen einen `Error` schützt; auf dem blockierenden Twin bleibt dann zusätzlich der Call-Scope-MDC auf einem gepoolten Thread liegen. (3) Der Consumer-Smoke-Test, das einzige Tor für die geshadeten Artefakte, liest die WebClient-Zeile, bevor ihre Emission garantiert stattgefunden hat. (4) Die Request-Seite des reaktiven Tees (Zero-Copy-Variante, `writeAndFlushWith`, Tee-Fehler, Error-Pfad) ist ungetestet. Die Low-Befunde sind Robustheits- und Dokumentationslücken sowie Testqualität.

**Kompaktes Testurteil.** Als Safety Net ist die Suite stark: 387 Surefire-Tests, deterministische Zeit- und ID-Seams, keine `Thread.sleep`-Synchronisation in Unit-Tests, echte Spring-Kontexte, fünf RestClient-Engines und vier WebClient-Connectoren gegen einen realen Peer, Tracing-Integration mit echter Brave-Bridge, Barrier-getriebene Race-Tests und drei Fuzz-Ziele mit eigenen Orakeln. Die Pyramide ist gesund: die schnellen Unit-Tests tragen die Last, die Integrationsschicht ist die einzige zeitabhängige und ist mit dokumentierten Rändern (200 ms gegen 1,5 s, Linux-only Tarpit) eng gehalten. Die gravierendsten Lücken:
- Der einzige Consumer-seitige Beweis (Smoke-Test) ist der eine nicht-deterministische Test der gesamten Suite.
- Die Fail-open-Maschinerie, auf der das Versprechen der Bibliothek ruht, wird auf dem RestClient-Twin nur an drei Stufen geprüft; sieben von zwölf Guard-Körpern werden von keinem Test ausgeführt.
- Die Request-Seite des reaktiven Tees (Zero-Copy, Streaming-Writes) hat keinen einzigen Test.
- Der Lesezustands-Vertrag wird nur an den Pfaden geprüft, die zufällig mit der Dokumentation übereinstimmen; ein 204-Integrationstest prüft Felder, nicht den Zähler.
- Fünf gemeinsame Klassen haben keinen direkten Test im Modul, das sie besitzt; zwei Concurrency-Tests im ID-Generator sind kein Concurrency-Beweis; Logger-Level werden gesetzt, aber nie restauriert.

## 2. Scope & Methodology

- **Architektur und Flüsse:** `legatium-common` hält Konfiguration, Identität, Maskierung, MDC-Scope, Klassifikation, Metriken und die Fail-open-Guards; es wird per Shade in beide Twins inliniert (ADR-0003) und per `-Xfriend-paths` kompiliert. Der blockierende Twin hängt als `ClientHttpRequestInterceptor` an RestClient/RestTemplate, übernimmt den serialisierten Request als `ByteArray`, kapselt die Response und emittiert beim `close()`. Der reaktive Twin hängt als `ExchangeFilterFunction` an WebClient, führt die Response durch einen eigenen `MonoOperator` (`ObservedResponse`), den Body durch einen eigenen `FluxOperator` (`ObservedBody`) und emittiert am terminalen Body-Signal; seit ADR-0010 stellt er den Aufrufer-Kontext aus dem Reactor Context wieder her. Persistenz, Messaging, Coroutinen und projektseitige Virtual-Thread-Konfiguration existieren nicht.
- **Testcode im Scope:** ja, als Risikoindikator und als eigenständiges Analyseobjekt. Bewertet wurden Aussagekraft, Determinismus, Isolation (Logback-, MDC-, `ContextRegistry`-Zustand), Sham-Concurrency und Abdeckung der Risikopfade.
- **Phase 1:** Alle 121 Dateien im Scope wurden gerankt (Abschnitt 4); Score-4/5-Dateien wurden zeilenweise gegen Fehler-, Cancel-, Close-, Retry-, Fail-open- und Nebenläufigkeitspfade geprüft, Score-3-Dateien vollständig gelesen und mit ihren Aufrufern abgeglichen, Score-1/2-Dateien gelesen und auf Verträge geprüft.
- **Phase 2:** drei parallele, vollständige Modul-Lesungen; jeder Kandidat wurde anschließend am Code nachgeprüft. Framework-Behauptungen wurden gegen die aufgelösten Jars verifiziert: Springs `IntrospectingClientHttpResponse.hasMessageBody()` (1xx/204/304 und `Content-Length: 0` ergeben `false`, `DefaultRestClient` referenziert die Klasse), `DefaultClientResponse.releaseBody()` (`body(toDataBuffers()).map(release).then()`), `DataBuffer.toByteBuffer(int, ByteBuffer, int, int)` (abstrakt, nicht deprecated), Boots `spring.reactor.context-propagation`-Default `limited`, Reactors `contextCapture`-Verhalten in `Mono.block()`, `java.net.URI.getHost()` für Registry-basierte Authorities.
- **Phase 3:** Kandidaten wurden gegen ADRs, KDoc-Entscheidungen und CONTRIBUTING.md geprüft; reine Stil- und Geschmacksfragen wurden verworfen (Abschnitt 5 nennt nur Befunde, die Abschnitt 6 nicht als Muster absorbiert). Konvention ist der Maßstab, nicht die Abweichung davon.
- **Nachprüfung der Vorgängerbefunde (CODE_ANALYSIS-2026-09-04T23-18-42):** (1) Masker-Default beider Vier-Argument-Konstruktoren jetzt `HeaderValueMasker.forKey(properties.maskingKey)`; (2) `adapter.request.body.size` wird auf dem RestClient nur mit erhaltener Response gesampelt (`ExchangeLogEmitter.recordBodySizes`); (3) `BoundedBodyCapture.expectBytes` und `declaredBodyLength` mit `Content-Encoding`-Regel; (4) `CapturingClientHttpResponse.guarded` um jede Delegate-Operation einschließlich `close()`; (5) `ObservedResponse` mit `DELIVERING`-Zustand und threadbasierter Cancel-Unterscheidung; (6) `registerOrFallback` mit `taken`-Probe für die gleichartige Host-Gauge. Alle sechs sind auf diesem Commit behoben.
- **Nicht ausgeführte Schritte:** keine Reproduktionstests, keine Benchmarks, kein Lasttest. Das Race im Smoke-Test und die Cancel-Heuristik über Scheduler-Grenzen wurden aus Code und Reactor-Quellen hergeleitet, nicht unter Last reproduziert.
- **Blindstellen:** Connector-Verhalten außerhalb der aufgelösten Versionen wurde nicht extrapoliert. Die kryptographische Stärke der Maskierung war nicht Gegenstand. Die MkDocs-Site wurde nicht gebaut.

## 3. Statistics

### Befunde

| Severity | Anzahl |
|---|---|
| 🔴 Critical | 0 |
| 🟠 High | 0 |
| 🟡 Medium | 4 |
| 🟢 Low | 17 |
| **Summe** | **21** |
| Systemische Muster | 6 |

### Kategorien

| Kategorie | Befunde |
|---|---|
| correctness (Metrik-Semantik, Koordinaten) | 1, 6, 11 |
| robustness / resources | 2, 5, 7, 9, 10 |
| reactive | 10, 11 |
| performance | 19 |
| test quality | 3, 4, 8, 12, 13, 14, 15, 16, 17, 18 |
| maintainability | 8, 20, 21 |

### Umfang

| Kennzahl | Wert | Basis |
|---|---|---|
| Dateien im Scope | 121 | Abschnitt 4 |
| Produktive Kotlin-Dateien | 34 (20 common, 7 RestClient, 7 WebClient) | `find`-Inventar |
| Test-Quelldateien | 48 Kotlin + 4 Java (3 Fuzz, 2 Smoke) | `find`-Inventar |
| Surefire-Tests | 387 (davon 3 im Consumer-Smoke außerhalb des Reaktors) | `tests=`-Attribute der Surefire-XML |
| JaCoCo, nur modul-eigene Tests | common 78,5 % Instr. / 70,6 % Branch; RestClient 87,1 % / 83,7 %; WebClient 87,7 % / 83,7 % | `jacoco.csv` des letzten Builds; gemeinsame Klassen werden über die Twin-Tests abgedeckt und erscheinen im common-Wert nicht |

## 4. File-Ranking Table

Score 5 = höchste Defektwahrscheinlichkeit (Komplexität, Nebenläufigkeit, Ressourcen, Hot Path); Tests: Gewicht als Sicherheitsnachweis. Pfade relativ zum Workdir; die Modulpräfixe sind abgekürzt: `C/` = `./legatium-common/src/`, `R/` = `./legatium-restclient-logging/src/`, `W/` = `./legatium-webclient-logging/src/`, jeweils gefolgt von `main/kotlin/eu/inqudium/legatium/<modul>/` bzw. `test/kotlin/eu/inqudium/legatium/<modul>/`.

### Produktionscode

| Datei | Score | Begründung |
|---|---|---|
| `W/…/ObservedResponse.kt` | 5 | handgeschriebener `MonoOperator`; Übergabe OPEN→DELIVERING→RESPONDED mit Cross-Thread-Cancel-CAS und Thread-Identitäts-Heuristik |
| `W/…/ObservedBody.kt` | 5 | handgeschriebener `FluxOperator` im Body-Hot-Path: Tee, terminaler Besitz, Cancel-Heuristik, Lesezustands-Marken |
| `R/…/ClientRequestLoggingInterceptor.kt` | 5 | Hot Path; besitzt Exchange-Lifecycle, alle Exception-/Error-Pfade, MDC-Scope, Gauge; Drei-Thread-Handoff |
| `R/…/CapturingClientHttpResponse.kt` | 5 | Ressourcen-Lifecycle (Close, Double-Close), Body-Tee, exactly-once-Emissionspunkt, threadübergreifendes Memo |
| `C/…/ClientLoggingMetrics.kt` | 5 | globaler `WeakHashMap`-Cache, Fallback-Registry, lazy Meter pro Tag-Set im Emissionspfad, Guard-Schichtung |
| `W/…/ClientRequestLoggingFilter.kt` | 4 | Wiring pro Subscription, exactly-once-Abschluss, Error-Pfad, Request-Rebuild, Gauge |
| `W/…/CapturingDecorators.kt` | 4 | nicht-vorrückende `DataBuffer`-Kopie, Mono/Flux-Spezialisierung, Zero-Copy-Zweig (ungetestet) |
| `R/…/ExchangeLogEmitter.kt` | 4 | Fail-open-Schichtung, geschachtelte MDC-Scopes, Klassifikation, Body-Gating, Metriken vor Level-Gate |
| `R/…/BoundedBodyCapture.kt` | 4 | Bytezählung, volatile Publikationsreihenfolge, Truncation, Declared-Length-Regel |
| `C/…/Mdc.kt` | 4 | thread-lokaler Overlay mit Rollback bei Teil-Install und best-effort-Restore; Korrektheit hängt an Thread-Affinität der Aufrufer |
| `W/…/ExchangeLogEmitter.kt` | 3 | Freeze-first-Emission, Klassifikation, geschachtelte Ambient-/MDC-Scopes, Fail-open |
| `W/…/BoundedBodyCapture.kt` | 3 | lock-geschützte Zählung/Truncation/Freeze; Arithmetik geprüft |
| `R/…/CallerMdcSnapshot.kt` | 3 | Thread-Identitätsprüfung, Rollback bei Teil-Install, best-effort-Restore |
| `C/…/BodyCapture.kt` | 3 | Charset-Decoder-Schleife mit Kapazitätsarithmetik, Multi-Byte-Truncation |
| `C/…/HeaderLogProperties.kt` | 3 | Selektions-/Maskierungslogik pro Aufruf, Case-Folding, Vorrangregeln (gefuzzt) |
| `C/…/ClientActivation.kt` | 3 | Host-/Pfad-Ableitung aus der URI, `PathContainer`-Dekodierung, ungeschützter Aufrufort in beiden Twins |
| `C/…/ClientIdentity.kt` | 3 | Vorrang trace > header > generated, Re-Entry-Behandlung |
| `C/…/CorrelationIdGenerator.kt` | 3 | geteilter `AtomicLong`, Breitenvertrag, unsigned Rendering |
| `C/…/HeaderValueMasker.kt` | 2 | `MessageDigest`/`Mac` pro Aufruf; einfach, Known-Answer-gepinnt |
| `C/…/ClientLoggingProperties.kt` | 2 | Konstruktor-Validierung, `toString`-Redaktion; kein Zustand |
| `C/…/Timeouts.kt` | 2 | begrenzter Graph-Walk über cause/suppressed |
| `C/…/Traceparent.kt` | 2 | Regex-gesicherter Parser; Fixture- und Fuzz-abgedeckt |
| `C/…/RequestTarget.kt` | 2 | `java.net.URI`-Host-Eigenheiten (Befund 6) |
| `C/…/FailOpenDiagnostics.kt` | 2 | zwei Inline-Guards, Interrupt-Flag |
| `C/…/DeclaredCharset.kt` | 2 | Exception-Mapping von Springs Media-Type-Parser |
| `W/…/AmbientContextRestorer.kt` | 2 | Klassen-Präsenz-Erkennung, dünner Micrometer-Wrapper |
| `R/…/ClientLoggingAutoConfiguration.kt` | 2 | Bedingungen, Customizer-Reihenfolge, Back-off |
| `W/…/ClientLoggingAutoConfiguration.kt` | 2 | dito |
| `R/…/Exchange.kt` | 2 | Datenhalter mit volatilen Feldern und Abschluss-CAS |
| `C/…/CorrelationHeader.kt` | 1 | reines Prädikat |
| `C/…/ClientLogFields.kt` | 1 | Enum plus drei Builder-Erweiterungen |
| `C/…/BodyLogMode.kt` | 1 | Wahrheitstabelle |
| `C/…/AdapterName.kt` | 1 | Einzeiler |
| `C/…/NanoTimeSource.kt` | 1 | injizierte Uhr |
| `C/…/Classification.kt` | 1 | Wertehalter |
| `W/…/Exchange.kt` | 1 | Datenhalter plus Zustands-Enum |
| `R/…/resources/META-INF/spring/….AutoConfiguration.imports`, `W/…` dito | 1 | je eine Zeile, per Test gepinnt |

### Testcode

| Datei | Score | Begründung |
|---|---|---|
| `R/…/ClientRequestLoggingInterceptorTest.kt` | 5 | 35 deterministische Tests: Level/Outcome-Matrix, Close-Emission, Cross-Thread-MDC, Error-Pfad, Re-Entry |
| `W/…/ClientRequestLoggingFilterTest.kt` | 5 | 36 Tests: Zustandsautomat, beide Cancel-Heuristiken, Barrier-getriebenes DELIVERING-Race, ADR-0010 |
| `C/…/ClientLoggingMetricsTest.kt` | 5 | einziger direkter Beweis für Registrierung/Fallback/Guard beider Stacks |
| `R/…/ClientRequestLoggingInterceptorBodyAndHeaderTest.kt` | 4 | Header-Maskierung, Tee-Wahrheit, Outcome-Gate, Decoding-Grenze |
| `R/…/ClientRequestLoggingMetricsTest.kt` | 4 | Gauge-Lifecycle, engine-ähnlicher Stream für die Content-Length-Regel, drei Fail-open-Stufen |
| `R/…/RequestFactoryContract.kt` | 4 | Timeout-Typen und gzip je Engine an echten Sockets; zeitabhängig |
| `W/…/ClientRequestLoggingFilterBodyAndHeaderTest.kt` | 4 | 22 Tests: Tees, Truncation, Outcome-Gate, Decoding-Grenze |
| `W/…/ConnectorContract.kt` | 4 | 4 Szenarien × 4 Engines; einziger Beweis für Tee und Timeout-Namen auf echten Buffern |
| `W/…/ClientRequestLoggingFilterIntegrationTest.kt` | 4 | Reactor Netty Ende-zu-Ende inkl. ADR-0010-Join auf Event-Loop-Thread |
| `C/…/MdcScopeTest.kt` | 4 | Fehlerinjektion in den JVM-globalen MDC-Adapter; Rollback/Restore |
| `C/src/test/java/…/HeaderMaskingFuzzTest.java` | 4 | unabhängiges Orakel für Selektion und Maskierung |
| `./consumer-smoke/src/test/java/eu/inqudium/legatium/smoke/ShadedTwinsSmokeTest.java` | 4 | einziger Consumer-seitiger Beweis der geshadeten Artefakte (Befund 3) |
| `R/…/ClientRequestLoggingInterceptorIntegrationTest.kt` | 3 | echte Boot-Verdrahtung; Engine in den Rationales falsch benannt (Befund 14) |
| `R/…/ClientLoggingAutoConfigurationTest.kt` | 3 | Bedingungen/Back-off solide; Ordnungs-Assertion leer (Befund 13) |
| `R/…/ClientRequestLoggingTracingIntegrationTest.kt` | 3 | pinnt Observation-vor-Interceptor mit echter Brave-Bridge |
| `R/…/BoundedBodyCaptureTest.kt` | 3 | Truncation-Grenzen, Count-only, Lesezustandsübergänge |
| `R/…/CallerMdcSnapshotTest.kt` | 3 | Capture-Filter, Install/Restore auf anderem Thread, Same-Thread-No-op |
| `R/src/test/java/…/BoundedBodyCaptureFuzzTest.java` | 3 | Invarianten-Fuzzing; 9 eingecheckte Seeds |
| `W/…/ClientRequestLoggingMetricsTest.kt` | 3 | Gauge-Lifecycle, Fail-open-Stufen; ein Test falsch attribuiert (Befund 12) |
| `W/…/BoundedBodyCaptureTest.kt` | 3 | Freeze, spätes onNext, Truncation, Lesezustand; deterministischer `ManualPublisher` |
| `W/…/ClientLoggingAutoConfigurationTest.kt` | 3 | Bean-Menge, Back-off, `FilteredClassLoader`, Importdatei |
| `W/…/ClientRequestLoggingTracingIntegrationTest.kt` | 3 | echte Brave-Bridge, traceparent-vor-Filter-Konvention |
| `C/…/CountingCorrelationIdGeneratorTest.kt` | 3 | Format/Breite/Ordnung; Concurrency-Tests schwach (Befund 16) |
| `C/…/HeaderLogPropertiesTest.kt` | 3 | ADR-0005-Vorrangbeweise |
| `C/…/ClientLoggingReferenceConfigTest.kt` | 3 | Binder-Lockstep, reflektiv abgeleitete Schlüsselmenge |
| `C/…/ClientLogFieldTest.kt` | 3 | Wire-Name/Template-Lockstep |
| `C/…/TraceparentTest.kt`, `C/src/test/java/…/TraceparentFuzzTest.java` | 3 | Fixture-Konformanz, positives und negatives Orakel |
| `C/…/TimeoutsTest.kt` | 3 | echte Netty-Klassen, Zyklen, suppressed |
| `C/…/FailOpenDiagnosticsTest.kt` | 3 | Interrupt-Flag und Handler-Confinement |
| `W/…/AmbientContextRestorerTest.kt` | 2 | Erkennung und additiver Vertrag |
| `R/…/TwinContractTest.kt`, `W/…/TwinContractTest.kt` | 2 | Literal-Pins |
| `R/…/UriTemplateAttributeTest.kt`, `W/…/UriTemplateAttributeTest.kt` | 2 | reflektive Pins privater Spring-Konstanten |
| `R/…/{HttpComponents,JdkClient,Jetty,ReactorNetty,Simple}RequestFactoryIntegrationTest.kt` | 2 | nur Parametrisierung des Contracts |
| `W/…/{ReactorNetty,JdkHttpClient,Jetty,HttpComponents}ConnectorIntegrationTest.kt` | 2 | nur Engine-Factories |
| `R/…/PeerServer.kt`, `W/…/PeerServer.kt` | 2 | echter ephemerer Port, schlafende `/slow`-Route |
| `R/…/Tarpit.kt`, `W/…/Tarpit.kt` | 2 | Linux-Accept-Queue-Trick, dokumentiert |
| `R/…/TestSupport.kt`, `W/…/TestSupport.kt`, `C/…/CapturedLogger.kt` | 2 | Helfer; `CapturedLogger` setzt Logger-Level ohne Restore (Befund 15); `MdcAccessorGuard` berührt die globale `ContextRegistry` (geschützt) |
| `W/…/AwaitingAppender.kt` | 2 | Semaphore-Await, MDC per `prepareForDeferredProcessing` gepinnt (korrekt) |
| `C/…/ClientLoggingPropertiesTest.kt`, `C/…/HeaderValueMaskerTest.kt`, `C/…/SharedContractTest.kt`, `C/…/CorrelationHeaderTest.kt` | 2 | Validierungsgrenzen, Known-Answer, Literal-Pins, Akzeptanzklassen |
| `C/…/BodyLogModeTest.kt`, `C/…/MdcAdapterSwap.kt`, `C/…/TraceparentConformanceFixture.kt`, `C/src/test/resources/traceparent/conformance.txt` | 1 | Wahrheitstabelle, Reflexionshelfer, Fixture-Leser, Daten |
| `./consumer-smoke/src/test/java/eu/inqudium/legatium/smoke/SmokeApplication.java` | 1 | leere Boot-App |
| `./consumer-smoke/pom.xml` | 2 | handgepinnte Version (Befund 20) |
| `R/src/test/resources/logback-test.xml`, `W/src/test/resources/logback-test.xml` | 1 | Konsolenmuster |

## 5. Findings

Severity bewertet Auswirkung mal realistische Eintrittswahrscheinlichkeit; Confidence beschreibt, wie sicher der Mechanismus aus Projekt- und Framework-Quellen folgt. Befunde sind über alle Gruppen fortlaufend nummeriert.

### Critical

Keine Befunde.

### High

Keine Befunde.

### Medium

- [x] 1. [./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/BoundedBodyCapture.kt:106 und ./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ObservedBody.kt:46] {Medium} {Confidence: high} {correctness / Metrik-Semantik} Der Lesezustand `state` von `adapter.response.body.read` wird auf den Twins an unterschiedlichen Beobachtungspunkten festgelegt und widerspricht dem dokumentierten Zweck des Zählers
  - **Symptom → Ursache:** Auf dem RestClient wechselt der Zustand nur beim Öffnen des Body-Streams von `UNREAD` weg (`markStarted`, Zeile 106-110). Springs `RestClient`/`RestTemplate` öffnen den Body für 1xx-, 204-, 304-Antworten und bei `Content-Length: 0` nie (`IntrospectingClientHttpResponse.hasMessageBody()`, gegen Spring Framework 7.0.9 verifiziert; `DefaultRestClient` referenziert die Klasse). Der Emitter zählt jede Antwort mit `exchange.response != null` (`./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/ExchangeLogEmitter.kt:351-356`), also jede 204-Antwort als `unread`; der Zweig `expectedBytes == 0 → COMPLETE` ist über die ausgelieferten Clients nicht erreichbar. Auf dem WebClient dagegen setzt `ObservedBody.subscribe` (Zeile 46-49) beim Subscribe `PARTIAL` und `onComplete` (Zeile 114-119) `COMPLETE`; Springs `releaseBody()` ist `body(toDataBuffers()).map(release).then()` (verifiziert), wird von `toBodilessEntity()` und `exchangeToMono`'s Release-Pfad aufgerufen und läuft vollständig durch den Tee. Ein bewusst verworfener Body zählt dort als `complete`, seine vollen Bytes landen auf `adapter.response.body.size`, und im Modus `always` wird sein Text geloggt; `bodyToMono(Void.class)` (`takeWhile`) liefert für denselben verworfenen Body `partial`. Dieselbe 204-Antwort ergibt `unread` auf dem RestClient und `complete` auf dem WebClient.
  - **Triggering condition:** deterministisch, sobald `measure-response-body-size=true` und eine body-lose Antwort (RestClient) bzw. ein per Spring-API verworfener Body (WebClient) auftritt.
  - **Impact:** falsche Ergebnisse eines opt-in Meters, dessen einziger dokumentierter Zweck (`./docs/GUIDE.md` §7.4/§7.5, KDoc `RESPONSE_BODY_READ_METER`, ADR-0008) "Call Site verwirft bezahlten Payload" ist; Dashboards auf dem `unread`-Anteil sind pro Route nicht vertrauenswürdig, und der Twin-Vertrag des `state`-Tags gilt nur nominell. Kein Absturz, kein Datenverlust.
  - **Fix strategy:** Den Beobachtungspunkt pro Twin auf denselben Begriff festlegen — eine body-lose Antwort (deklarierte Länge 0, Status 1xx/204/304) beim Handover als abgeschlossen oder als eigener Zustand markieren statt auf ein Öffnen zu warten, und auf dem reaktiven Twin einen von Springs Release-Pfad getriebenen Drain vom Lesen der Anwendung unterscheiden oder den Vertrag ausdrücklich enger fassen; Guide, `BodyReadState`-KDoc und ADR-0008 dann angleichen und je Twin einen 204- und einen `toBodilessEntity()`-Fall auf den Zähler pinnen.
  - **Status:** Behoben (2026-09-16): RestClient zählt body-lose Antworten (1xx/204/304, `Content-Length: 0`) bei der Übergabe als `complete`; Beobachtungspunkte pro Twin in `BodyReadState`-KDoc, Guide §7.4 und beiden Twin-Guides dokumentiert; je Twin 204- und `toBodilessEntity()`-Fall auf den Zähler gepinnt.

- [x] 2. [./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/ClientRequestLoggingInterceptor.kt:121 und ./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ClientRequestLoggingFilter.kt:129] {Medium} {Confidence: high} {robustness / resources} Die optionale Startzeile läuft in beiden Twins außerhalb der `Throwable`-Grenze, die die Open-Exchange-Gauge und den Call-Scope schützt
  - **Symptom → Ursache:** Beide Entry Points öffnen die Gauge (`metrics.exchangeOpened()` im Wiring) und rufen `emitter.logRequestStart(exchange)` danach, aber vor dem `try`, dessen `catch (t: Throwable)` den Exchange per `abandonExchange` von der Gauge nimmt (RestClient Zeile 124-149, WebClient Zeile 132-145). `logRequestStart` nutzt `failOpen`, das per Entscheidung nur `Exception` einfängt (`./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/FailOpenDiagnostics.kt:35-42`). Ein `Error` aus dem Logging-Backend beim Rendern der Startzeile — die KDoc nennt `LinkageError` und `StackOverflowError` selbst als wahrscheinlichste Quelle — verlässt den Interceptor bzw. das `deferContextual`-Lambda ohne `abandonExchange`. Auf dem RestClient liegt der Aufruf zusätzlich außerhalb des `finally { closeCallScope(...) }` (Zeile 147-149), der Call-Scope-MDC bleibt also auf dem aufrufenden Thread. Der Kommentar in Zeile 116-120 begründet die Platzierung mit "nicht als Call-Failure fehlattribuieren"; das gilt für `Exception`, die `failOpen` ohnehin einschließt, nicht für den einzigen Fall, der entkommen kann.
  - **Triggering condition:** nur mit `adapter-logging.log-request-start=true` und einem `Error` im Backend während der Startzeile; selten, aber genau die Klasse, gegen die die Gauge laut KDoc "truthful" bleiben soll.
  - **Impact:** `adapter.logging.exchanges.open` dauerhaft um eins zu hoch (falscher "Responses werden nie geschlossen"-Alarm); auf dem RestClient trägt ein gepoolter Servlet- oder Executor-Thread `adapter_request_id`/`adapter_method`/`adapter_route` dieses Aufrufs auf jede spätere fremde Log-Zeile, bis der Thread neu gescoped wird.
  - **Fix strategy:** Die Startzeile in beiden Twins in den bestehenden `try` vor den Wire-Call ziehen, damit ein entkommender `Error` denselben Weg nimmt wie beim Wire-Call (`abandonExchange` plus, auf dem RestClient, `finally`-Restaurierung); je Twin ein Test mit einem `Error` werfenden Appender oder TurboFilter, der Gauge gleich null und sauberen MDC prüft.
  - **Status:** Behoben (2026-09-16): Startzeile in beiden Twins in den `try` gezogen; je Twin ein Test mit `LinkageError` werfendem TurboFilter (Gauge null, MDC sauber, kein Event).

- [x] 3. [./consumer-smoke/src/test/java/eu/inqudium/legatium/smoke/ShadedTwinsSmokeTest.java:136] {Medium} {Confidence: high} {test quality / Nicht-Determinismus} Der Consumer-Smoke-Test liest die WebClient-Exchange-Zeile, bevor ihre Emission garantiert stattgefunden hat
  - **Symptom → Ursache:** Nach `webClient.get()…bodyToMono(String.class).block()` liest der Test sofort `captured.list` und erwartet zwei Events (Zeile 136-140). Der reaktive Twin emittiert aber erst NACH der Weitergabe des terminalen Signals: `ObservedBody.onComplete` ruft `actual.onComplete()` und dann `onTerminal(exchange)` (`./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ObservedBody.kt:114-119`). `block()` wird innerhalb von `actual.onComplete()` freigegeben, der Test-Thread kann `hasSize(2)` erreichen, während der Reactor-Netty-Thread noch im Emitter steht. `ListAppender.list` ist eine unsynchronisierte `ArrayList`; auch ein bereits erfolgtes `append` ist nicht garantiert sichtbar. Die Twin-eigenen Tests lösen genau das mit `AwaitingAppender` (Semaphore pro Event, `CopyOnWriteArrayList`, `prepareForDeferredProcessing`); der Smoke-Test nicht. Derselbe Race hat in dieser Sitzung drei neue Filter-Tests beim ersten Lauf scheitern lassen, bis sie auf den wartenden Appender umgestellt wurden.
  - **Triggering condition:** nur unter Timing-Druck (kalter JIT, ausgelasteter CI-Runner); zuletzt lokal und in CI grün.
  - **Impact:** flakiger `consumer-smoke`-Job, das einzige Tor für die geshadeten Artefakte, mit der irreführenden Meldung "1 Event statt 2"; ein solcher Fehlschlag blockiert den Merge über Auto-Merge und untergräbt das Vertrauen in die Suite.
  - **Fix strategy:** Das zweite Event eventgetrieben abwarten (Latch- oder Semaphore-Appender mit begrenztem Timeout, wie `AwaitingAppender`) statt eine `ListAppender`-Liste direkt nach `block()` zu lesen.
  - **Status:** Behoben (2026-09-16): Smoke-Test wartet per Semaphore-Appender auf das zweite Event; CI leitet `legatium.version` aus dem Root-POM ab.

- [ ] 4. [./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/CapturingDecorators.kt:60 und :73] {Medium} {Confidence: high} {test quality / Abdeckungslücke an Ressourcenpfaden} Drei von vier Dekorator-Pfaden der Request-Seite und beide Fehlerpfade des Filters haben keinen Test
  - **Symptom → Ursache:** `writeAndFlushWith` (Streaming-Medientypen wie `text/event-stream`, `application/x-ndjson`), `ZeroCopyCapturingClientHttpRequestDecorator.writeWith(Path, position, count)` (die einzige Variante mit dem Cast `delegate as ZeroCopyHttpOutputMessage` und die, die `ResourceHttpMessageWriter` auf Reactor Nettys `sendfile`-Pfad hält), der Tee-Fehlerpfad `onTeeFailure` (`stage=wiring`) und `abandonExchange` (Error beim Assemblieren) werden von keinem Test ausgeführt. JaCoCo bestätigt: `ZeroCopyCapturingClientHttpRequestDecorator` 30 von 35 Instruktionen nicht ausgeführt, `CapturingClientHttpRequestDecorator` 62 von 96 nicht ausgeführt. Ebenfalls ungetestet: die Durchreichung der unbeobachteten Response nach gewonnenem Cancel (`ObservedResponse.kt:77-81`) und `Observer.onError` nach `ended` (`ObservedBody.kt:109-111`).
  - **Triggering condition:** immer, als Abdeckungslücke; der Schaden entsteht bei einer künftigen Änderung.
  - **Impact:** Eine Spring-Änderung am `ZeroCopyHttpOutputMessage`-Vertrag, ein falscher Cast oder ein nie feuerndes `doOnSuccess` würde Request-Size-Samples für Datei-Uploads still verlieren oder — schlimmer — Uploads auf gepufferte Writes degradieren, also genau die Regression, gegen die die Klasse laut KDoc existiert, ohne Build-Signal.
  - **Fix strategy:** Ein Unit-Test mit einer `ZeroCopyHttpOutputMessage` implementierenden Mock-Request, der die Zero-Copy-Variante und die Zählung prüft; ein `writeAndFlushWith`-Test über `MockClientHttpRequest` (etwa ein SSE-Inserter); ein werfender Tee (als `wiring` gezählt, Buffer unverändert geliefert); ein `AssertionError` beim Assemblieren (Gauge wieder null, kein Event).
  - **Status:** Teilweise behoben (2026-09-16): `CapturingDecoratorsTest` (Mono, Flux, `writeAndFlushWith`, Zero-Copy Ende-zu-Ende, Nicht-Zero-Copy-Zweig), Tee-Fehler (`stage=wiring`) und `AssertionError` beim Assemblieren gepinnt. Offen: Durchreichung nach gewonnenem Cancel (`ObservedResponse.kt:77-81`) und `onError` nach `ended`.

### Low

- [x] 5. [./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientLoggingMetrics.kt:271] {Low} {Confidence: high} {robustness} Ein dauerhaft werfender Host-`Counter` erzeugt zwei WARN-Zeilen pro Exchange
  - **Symptom → Ursache:** `registerOrFallback` warnt einmal pro Meter-Name (`reportedConflicts`), `updateQuietly` (Zeile 271-278) warnt bei jedem Aufruf; es läuft in `requestId(...)` und `eventEmitted(...)`, also zweimal pro Exchange.
  - **Triggering condition:** nur mit kaputtem Host-Meter, dann immer, proportional zum Traffic.
  - **Impact:** Log-Flut auf dem internen Logger unter Last, die die kuratierten Einmal-Warnungen übertönt.
  - **Fix strategy:** Die Update-Warnung einmal pro Meter-Name drosseln (wie `reportedConflicts`), den `stage=wiring`-Zähler pro Aufruf beibehalten.
  - **Status:** Behoben (2026-09-16): `updateQuietly` warnt einmal pro Meter-Name, zählt weiter pro Aufruf; Test in `ClientLoggingMetricsTest`.

- [x] 6. [./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/RequestTarget.kt:20 und ./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientActivation.kt:29] {Low} {Confidence: high} {correctness / Randfall} Host aus `java.net.URI.getHost()`, das für Registry-basierte Authorities null und für IPv6-Literale eckig geklammert ist
  - **Symptom → Ursache:** Für `http://billing_api:8080/x` (Unterstrich, üblich bei Compose-/Kubernetes-Dienstnamen) liefert `URI.getHost()` null und `getPort()` -1 (lokal mit einem `java`-Skript verifiziert): `adapter_url_host` fehlt, `adapter_route` und die Nachricht rendern `http:///x`, `exclude-hosts` kann den Peer nicht treffen (dieselbe Ableitung in `ClientActivation`), die Body-Meter taggen `host=UNKNOWN`. Für `http://[::1]:8080/` ist der Host `[::1]`, `exclude-hosts: ["::1"]` greift still nicht.
  - **Triggering condition:** nur Randfall; mehrere Engines lehnen solche URIs selbst ab (der Aufruf loggt dann als Failure mit host-loser Route).
  - **Impact:** irreführende Koordinaten und eine still wirkungslose Host-Ausnahme für eine kleine URI-Klasse.
  - **Fix strategy:** Bei `host == null` auf `rawAuthority` ohne Userinfo zurückfallen; die Klammerform für IPv6 in `exclude-hosts` dokumentieren oder normalisieren.
  - **Status:** Behoben (2026-09-16): `RequestTarget` fällt auf die Registry-Authority zurück, `hostName` normalisiert IPv6-Klammern; `ClientActivation` nutzt dieselbe Regel; direkte Tests in common, Guide §6.4 und Referenztabelle angepasst.

- [ ] 7. [./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/ClientRequestLoggingInterceptor.kt:313] {Low} {Confidence: high} {robustness / correctness} Wire- und Zähler-Seiteneffekte liegen vor den fehlbaren Schritten des Wirings
  - **Symptom → Ursache:** `metrics.requestId(...)` (313), `headers.set(...)` (315) und das Attribut (316) laufen vor Host-Beans, die werfen können: `properties.requestHeaders.select(... masker ...)` (336), `nanoTime.nanoTime()` (344). Wirft einer davon, liefert `wireOrNull` null und der Aufruf läuft ungeloggt — aber die generierte Correlation-ID ist bereits auf der Leitung und `adapter.logging.correlation.id{source=generated}` bereits gezählt.
  - **Triggering condition:** nur bei werfendem Host-Masker oder werfender Zeitquelle.
  - **Impact:** Bookkeeping-Inkonsistenz (Correlation-Summe größer als Events-Summe ohne `emission`-Fehler), ein Header ohne zugehörige Zeile.
  - **Fix strategy:** Die reinen Teile (Ziel, Header-Selektion, Charset, Captures, Snapshot) zuerst berechnen, die Request-Mutation und die Herkunftszählung zuletzt, direkt vor `exchangeOpened()`.
  - **Status:** Weitgehend behoben (2026-09-16): Ziel, Charset, Zeitquelle, Caller-MDC und Captures laufen vor der Header-Mutation, der Herkunftszähler direkt vor `exchangeOpened()`. Restfenster: ein werfender Host-Masker in der Header-Selektion, die den gesetzten Header sehen muss - in der KDoc benannt.

- [x] 8. [./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/ExchangeLogEmitter.kt:93 und ./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ExchangeLogEmitter.kt:158] {Low} {Confidence: high} {maintainability / Fail-open-Buchführung} Ein Scope-Close-Fehler nach erfolgreich geschriebener Zeile wird als verlorene Zeile gezählt
  - **Symptom → Ursache:** `restore…Quietly(exchange).use { MdcScope(...).use { log } }` — `use {}` wirft einen fehlschlagenden `close()` weiter, hinein in `failOpen`, das ihn als `stage=arrival` (RestClient-Startzeile) bzw. `stage=emission` (WebClient-Abschlussereignis, Zeile 158-162) zählt, obwohl die Zeile existiert und `adapter.logging.events` schon inkrementiert wurde. Der RestClient-Abschlusspfad trennt denselben Fall bewusst per `restoreQuietly` als `wiring` (Zeile 169-179, KDoc 308-311).
  - **Triggering condition:** nur bei werfendem MDC-Adapter oder werfendem Host-Accessor beim Restore.
  - **Impact:** verzerrt die Abgleichs-Wahrheit `events` vs. `failopen{stage=emission}` aus ADR-0008.
  - **Fix strategy:** Emission und Scope-Abbau trennen; Close-Fehler um die Scopes fangen und als `wiring` zählen, wie es der RestClient-Abschlusspfad bereits tut.
  - **Status:** Behoben (2026-09-16): Startzeile (beide Twins) und WebClient-Abschluss bauen die Scopes per `restoreQuietly` ab (`stage=wiring`).

- [x] 9. [./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/ClientLoggingAutoConfiguration.kt:103 und ./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ClientLoggingAutoConfiguration.kt:87] {Low} {Confidence: high} {robustness / stillschweigende Annahme} "Late, not last": ungeordnete Host-Customizer laufen NACH dem Modul, ihre Filter und Interceptoren damit INNERHALB des Logging-Filters
  - **Symptom → Ursache:** `CUSTOMIZER_ORDER = Ordered.LOWEST_PRECEDENCE - 10`. Ein `WebClientCustomizer`/`RestClientCustomizer` ohne `@Order` hat `LOWEST_PRECEDENCE` und wird von `orderedStream()` nach dem Modul angewandt; sein Filter wird später angehängt und läuft innerhalb. README und Guide (§3.3 beider Twins) versprechen, das Modul laufe "inside the filters of earlier customizers" — das gilt nur für Customizer, die explizit unter `LOWEST_PRECEDENCE - 10` geordnet sind, nicht für den Normalfall.
  - **Triggering condition:** jeder ungeordnete Host-Customizer mit Auth- oder Retry-Filter.
  - **Impact:** Ein Retry-Filter aus ungeordnetem Customizer ergibt eine Zeile für N Versuche, Gauge und Dauer umspannen alle; der Header eines Auth-Filters fehlt in `adapter_request_headers`. Kein Absturz, aber der dokumentierte Beobachtungspunkt stimmt für die meisten Hosts nicht.
  - **Fix strategy:** Entweder dokumentieren, dass nur explizit früher geordnete Customizer als "earlier" zählen, oder die Ordnung auf `LOWEST_PRECEDENCE` setzen und das "room below"-Argument streichen; ein Auto-Configuration-Test mit ungeordnetem Host-Customizer pinnt die Wahl.
  - **Status:** Entschieden und dokumentiert (2026-09-16): Ordnung bleibt `LOWEST_PRECEDENCE - 10`; README, Guide §3.3 und KDoc beider Twins sagen jetzt, dass ein ungeordneter Host-Customizer danach angewandt wird; Test mit geordnetem und ungeordnetem Host-Customizer in beiden Auto-Configuration-Tests.

- [x] 10. [./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ObservedBody.kt:72] {Low} {Confidence: medium} {reactive / Heuristikgrenze} Die Thread-Identitäts-Heuristik liest einen frühen Ausstieg des Konsumenten als Abbruch, sobald eine Scheduler-Grenze zwischen Tee und Konsument liegt
  - **Symptom → Ursache:** `if (deliveringOn !== Thread.currentThread()) exchange.cancelled = true`. Bei `bodyToFlux(DataBuffer).publishOn(scheduler).take(1)` kehrt `publishOn.onNext` nach dem Einreihen zurück, der Cancel von `take` kommt vom Worker-Thread außerhalb einer Zustellung → `cancelled` auf WARN, im Modus `on-failure` werden beide Bodies eines gesunden Aufrufs geloggt. Umgekehrt liest ein von einer Größenbegrenzung getriebener Abbruch (`DataBufferLimitException` aus `DataBufferUtils.join`, Cancel aus der Zustellung heraus) als `success`.
  - **Triggering condition:** jeder Scheduler-Hop zwischen Response-Body und früh endendem Konsumenten.
  - **Impact:** falsche Disposition und falsches Level für eine Klasse legitimer reaktiver Konsumenten; kein Absturz.
  - **Fix strategy:** Die Grenze der Heuristik in der KDoc und im Guide explizit benennen (der realistische Weg) oder das Signal verfeinern, etwa einen Cancel bei ausstehender, vom Konsumenten selbst angeforderter Demand als konsumentengetrieben werten.
  - **Status:** Dokumentiert (2026-09-16): Grenze der Heuristik (Scheduler-Hop, `DataBufferLimitException`) in der `ObservedBody`-KDoc und im WebClient-Guide §4.2.

- [x] 11. [./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ObservedBody.kt:114 und ./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ExchangeLogEmitter.kt:137] {Low} {Confidence: high} {correctness / Dauer und Reihenfolge} Die Dauer wird erst gesampelt, nachdem der terminale Handler des Aufrufers zurückgekehrt ist
  - **Symptom → Ursache:** `actual.onComplete()` bzw. `actual.onError(t)` läuft vor `onTerminal(exchange)`, und `elapsedNanos` wird erst in `emitExchange` gebildet. Synchrone Arbeit des Aufrufers im terminalen Handler (Dekodierung eines gejointen Bodys, `onErrorResume`, ein synchrones `retry`, das in `onError` resubscribed) fließt in `adapter_duration_ms` ein; mit synchron antwortendem Connector wird der komplette zweite Versuch inklusive seiner Zeilen VOR der Failure-Zeile des ersten geloggt.
  - **Triggering condition:** jede synchrone Arbeit in terminalen Handlern; sichtbar in Tests mit `retry()` über synchrone Stubs.
  - **Impact:** leicht überhöhte Dauern, verwirrende Zeilenreihenfolge bei Retries mit Startzeile; kein Datenverlust.
  - **Fix strategy:** `nanoTime` vor der Weitergabe des terminalen Signals am `Exchange` festhalten, die Emission nach der Weitergabe belassen und die Reihenfolge-Inversion dokumentieren.
  - **Status:** Dokumentiert (2026-09-16): Reihenfolge Signal-vor-Emission und die eingeschlossene synchrone Terminalarbeit in `ObservedBody`-KDoc, Guide §5-Feldtabelle (`adapter_duration_ms`) und WebClient-Guide §4.2; Code bewusst unverändert (Gegenstück zur Response-Occupancy des blockierenden Twins).

- [x] 12. [./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ClientRequestLoggingFilter.kt:256 und ./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/ClientRequestLoggingMetricsTest.kt:350] {Low} {Confidence: high} {maintainability / test quality} Unerreichbarer Guard um `exchangeCompleted()` und ein Test, dessen Rationale das beobachtete Verhalten diesem Guard zuschreibt
  - **Symptom → Ursache:** `exchangeCompleted()` ist `openExchanges.decrementAndGet()` auf einem privaten `AtomicLong` (`./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientLoggingMetrics.kt:248-250`) und kann nicht werfen. Der Test "should confine a terminal-callback failure…" installiert ein werfendes Events-Meter, das `updateQuietly` in common einfängt; der `catch` des Filters wird nie betreten, der gezählte `stage=wiring` stammt aus common.
  - **Fix strategy:** Guard entfernen (oder `exchangeCompleted` fehlbar machen und das testen) und den What/Why-Block des Tests auf `updateQuietly` umschreiben.
  - **Status:** Behoben (2026-09-16): toter Guard in `finish` entfernt, KDoc von `complete` und Test-Rationale auf `updateQuietly` umgeschrieben.

- [x] 13. [./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/ClientLoggingAutoConfigurationTest.kt:64] {Low} {Confidence: high} {test quality} Die Assertion "als LETZTER Eintrag" ist leer
  - **Symptom → Ursache:** `interceptors.last()` bzw. `restTemplate.interceptors.last()` (auch Zeile 145-169) hält trivial, weil der Kontext keinen konkurrierenden Customizer und keine `additionalInterceptors` enthält; die Liste hat ein Element. Ein Wegfall von `@Order` bliebe grün.
  - **Fix strategy:** Einen Host-Customizer mit Default-Ordnung und einen `additionalInterceptors`-Eintrag registrieren und die Position des Modul-Interceptors dahinter prüfen; optional einen zweimal re-eintretenden Retry-Interceptor mit zwei Events.
  - **Status:** Behoben (2026-09-16): siehe Befund 9 - Positionstest mit konkurrierenden Customizern in beiden Twins.

- [x] 14. [./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/ClientRequestLoggingInterceptorIntegrationTest.kt:29] {Low} {Confidence: high} {test quality / Evidenz} Die Suite dokumentiert "the JDK HTTP engine", der auto-erkannte Engine auf diesem Classpath ist Apache HttpComponents 5
  - **Symptom → Ursache:** Boots `ClientHttpRequestFactoryBuilder.detect()` prüft HttpComponents zuerst; `httpclient5` ist Test-Abhängigkeit des Moduls. Nur die drei Tests mit explizitem `JdkClientHttpRequestFactory` laufen auf dem JDK-Client; die Rationale-Blöcke in sechs Tests (u. a. Zeile 80-87, 127-137, 214-224, 322-331, 348-355) nennen die falsche Engine und erscheinen so auf der generierten Test-Evidence-Seite.
  - **Fix strategy:** Factory im Builder explizit pinnen oder die Rationales auf "Boots auto-erkannte Engine (Apache HC5 auf diesem Classpath)" umformulieren.
  - **Status:** Behoben (2026-09-16): Klassen-KDoc und Rationale nennen die auto-erkannte Engine (Apache HC5 auf diesem Classpath); die Tests mit explizit gepinntem JDK-Client bleiben korrekt benannt.

- [x] 15. [./legatium-common/src/test/kotlin/eu/inqudium/legatium/common/CapturedLogger.kt:22, ./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/TestSupport.kt, ./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/TestSupport.kt:52] {Low} {Confidence: high} {test quality / Isolation} Logger-Level werden gesetzt, aber nie restauriert
  - **Symptom → Ursache:** Alle drei `CapturedLogger`-Kopien setzen `logger.level = INFO` im `init` und entfernen im `detach()` nur den Appender; `ClientRequestLoggingMetricsTest` beider Twins setzt zusätzlich `Level.OFF` in einem Test und verlässt sich auf das nächste `setUp`. Die Integrationstests setzen den Produktions-Logger `adapter-http-exchange` auf INFO ohne Restore. Heute unschädlich (klassenspezifische Logger-Namen, keine Parallelität), aber ordnungsgekoppelt.
  - **Fix strategy:** Vorheriges Level im Konstruktor merken und in `detach()` restaurieren; `OFF` in einem `finally` zurücksetzen.
  - **Status:** Behoben (2026-09-16): alle drei `CapturedLogger`-Kopien merken und restaurieren das vorherige Level (deckt die `Level.OFF`-Stellen ab); die drei WebClient-Integrationsklassen restaurieren den Produktions-Logger.

- [x] 16. [./legatium-common/src/test/kotlin/eu/inqudium/legatium/common/CountingCorrelationIdGeneratorTest.kt:332] {Low} {Confidence: high} {test quality} Executor wird bei Fehlschlag nicht freigegeben; der zweite Concurrency-Test beweist keine Nebenläufigkeit
  - **Symptom → Ursache:** `pool.shutdownNow()` folgt auf `done.await(...)` statt in `finally`; eine Assertion oder ein Timeout lässt 16 bzw. 8 Non-Daemon-Threads für die JVM-Lebensdauer zurück (Fork-Hang-Risiko). Der Test "should keep the id format intact under concurrent access" (Zeile 363-373) prüft nur eine Regex, die `AtomicLong.getAndIncrement()` plus String-Rendering unter Contention nicht anders verletzen kann als sequenziell.
  - **Fix strategy:** Pool in `try/finally` (oder `use` auf JDK 21+) und den Format-Test in den Distinctness-Test falten oder streichen.
  - **Status:** Behoben (2026-09-16): Pool in `try/finally`; Format-Test in den Distinctness-Test gefaltet.

- [x] 17. [./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/ClientRequestLoggingFilterTest.kt:963] {Low} {Confidence: medium} {test quality / Sham-Concurrency-Risiko} Der Barrier-Test ignoriert das Ergebnis von `worker.join(5_000)` und lässt Worker-Fehler entweichen
  - **Symptom → Ursache:** Die `check(...)`-Fehler in `hookOnNext` entkommen in Reactors `onNext` auf dem Worker-Thread; ein hängender oder fehlschlagender Worker zeigt sich nur indirekt über `log.events.single()`, und ein Non-Daemon-Worker, der nie zurückkehrt, hält die geforkte JVM am Leben.
  - **Fix strategy:** Nach dem Join `worker.isAlive` prüfen, Worker-Ausnahmen über `Future` oder Uncaught-Exception-Handler einsammeln, Worker als Daemon starten.
  - **Status:** Behoben (2026-09-16): Daemon-Worker, `isAlive`-Prüfung nach dem Join, Uncaught-Exception-Handler assertiert.

- [ ] 18. [./legatium-common/src/test/kotlin/eu/inqudium/legatium/common/ und ./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/] {Low} {Confidence: high} {test quality / Abdeckungslücke} Gemeinsame Klassen ohne direkten Test im besitzenden Modul; Fail-open-Guard-Körper des RestClient-Twins ungetestet
  - **Symptom → Ursache:** `ClientActivation`, `RequestTarget`, `ClientIdentity`, `decodeTruncated` und `declaredCharsetOrUtf8` haben keinen Test in `legatium-common` (per `grep -rl` gezählt: 0 in common, je 1-2 pro Twin); `ClientIdentity.resolve(generatedEarlier=…)` wird nur vom RestClient-Twin getrieben, `masked: []` nur per Kotlin-Konstruktion statt über Boots Binder, der dokumentierte Rest-Pfad von `forRegistry` (`ClientLoggingMetrics.kt:353-356`) und die Overflow-Schleife von `decodeTruncated` nie. Im RestClient-Twin führen laut JaCoCo die Catch-Körper von `openCallScope`, `closeCallScope`, `captureCallerMdcQuietly`, `restoreQuietly`, `recordBodySizesQuietly` sowie beide `onInterrupted`-Handler und die Rollback-/Restore-Fehlerpfade von `CallerMdcSnapshot` kein Test aus (sieben von zwölf Guard-Stellen). `RequestFactoryContract.kt:152-182` prüft auf der gzip-Route nur den Body, nie den Lesezustand, obwohl die Engine-KDocs Aussagen dazu treffen.
  - **Fix strategy:** Direkte Unit-Tests der fünf Klassen in common, ein Binder-Test für `masked: []`, ein MDC-Adapter-Swap-Test pro Fail-open-Stelle (Vorbild `MdcScopeTest`), eine `InterruptedException` werfende Zeitquelle für den Interrupt-Zweig, `readStateCount("complete") == 1.0` auf der gzip-Route je Engine.
  - **Status:** Teilweise behoben (2026-09-16): direkte Tests für `RequestTarget` und `ClientActivation` in common. Offen: `ClientIdentity`, `decodeTruncated`, `declaredCharsetOrUtf8`, Binder-Test `masked: []`, die sieben Guard-Körper des RestClient-Twins, gzip-Lesezustand je Engine.

- [ ] 19. [./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientLoggingMetrics.kt:308] {Low} {Confidence: high} {performance} Die Body-Meter bauen Builder und Meter-ID bei jeder Emission neu
  - **Symptom → Ursache:** `responseBodyRead` und `recordBodySize` rufen pro Exchange `registerOrFallback { …builder(...).register(registry) }`; jeder Aufruf alloziert Builder und `Meter.Id`, wendet die `MeterFilter`-Kette des Hosts an und macht den Map-Lookup. Opt-in und korrekt, aber im Hot Path.
  - **Fix strategy:** Aufgelöste Meter pro `(uri, host, name[, state])` in einer begrenzten `ConcurrentHashMap` im Owner cachen; die Kardinalität ist durch dieselben Tags bereits begrenzt.
  - **Status:** Offen (2026-09-16): bewusst nicht umgesetzt - opt-in Hot Path ohne Messung; ein Cache pro Tag-Set wäre eine Optimierung mit eigener Kardinalitätsbuchführung.

- [x] 20. [./consumer-smoke/pom.xml:42] {Low} {Confidence: high} {maintainability} Twin-Version von Hand gepinnt
  - **Symptom → Ursache:** `<legatium.version>1.0.1-SNAPSHOT</legatium.version>` muss manuell gleich `revision` im Root-POM gehalten werden; der Release-Workflow übergibt `-Drevision`, aber nichts parametrisiert diese Datei. Drift scheitert laut, aber aus einem Nicht-Produktgrund.
  - **Fix strategy:** Version in CI aus dem Root-POM ableiten (`help:evaluate`) und als `-Dlegatium.version` übergeben.
  - **Status:** Behoben (2026-09-16): CI leitet die Version per `help:evaluate` aus dem Root-POM ab und übergibt `-Dlegatium.version`; der Pin im POM bleibt für lokale Läufe.

- [x] 21. [./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ClientRequestLoggingFilter.kt:139 und weitere] {Low} {Confidence: high} {maintainability / Doku-Drift} KDoc- und Kommentaraussagen, die dem Code widersprechen
  - **Symptom → Ursache:** (a) `ClientRequestLoggingFilter.kt:139-141` behauptet, ein `Error` sei "fatal to Reactor" und umgehe jeden Signal-Hook; das gilt nur für `Exceptions.throwIfFatal`-Klassen, ein `AssertionError` wird von `MonoDeferContextual` als Fehlersignal weitergereicht — das gewählte Verhalten ist konsistent, die Begründung falsch. (b) `ClientRequestLoggingFilter.kt:238` sagt, `next()` cancele "right after taking the value"; `ObservedResponse.kt:31-32` und `MonoNext` sagen korrekt "davor". (c) Drei Stellen im RestClient-Twin nennen Status/Header "SNAPSHOTTED" (`ClientRequestLoggingInterceptor.kt:225-229`, `Exchange.kt:70-72`, `CapturingClientHttpResponse.kt:116`), `exchange.responseHeaders` hält aber eine lebende Referenz (bei Jetty eine `HttpFields`-Sicht). (d) `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/BoundedBodyCapture.kt:12-14` sagt "nothing to mark complete", die Klasse hat `markStarted`/`markCompleted`/`expectBytes`.
  - **Fix strategy:** Die vier Stellen an den Code angleichen; die Aussagen zum Lesezustand zusammen mit Befund 1 überarbeiten.
  - **Status:** Behoben (2026-09-16): (a) und (b) im Filter, (c) in Interceptor/Exchange/CapturingClientHttpResponse, (d) in der RestClient-`BoundedBodyCapture`-KDoc angepasst.

## 6. Systemic Patterns

### P1 — Die `Throwable`-Grenze wird am Wire-Call eingehalten, an der Startzeile nicht

- **Vorkommen:** 2 Stellen (Befund 2), je eine pro Twin.
- **Zählbasis:** beide Entry Points mit ihren `try`/`catch (Throwable)`/`finally`-Blöcken gegen die Aufrufstellen von `logRequestStart` abgeglichen.
- **Muster:** Die Bibliothek verspricht, dass ihre eigene Buchführung (Gauge, Call-Scope) auch einen `Error` überlebt, und löst es am Wire-Call ein; die optionale Startzeile ist die eine Stelle vor dem `try`, die das Versprechen unterläuft.
- **Richtung:** Alles, was nach `exchangeOpened()` und nach dem Öffnen eines Scopes läuft, gehört in den geschützten Block.

### P2 — Der Beobachtungspunkt des Lesezustands ist pro Twin und pro Spring-API ein anderer

- **Vorkommen:** 3 Stellen (Befund 1): RestClient body-los → `unread`; WebClient `releaseBody()` → `complete`; WebClient `takeWhile`-Skip → `partial`.
- **Zählbasis:** die Marken `markStarted`/`markCompleted`/`expectBytes` beider Captures gegen Springs `hasMessageBody()`, `releaseBody()` und den Body-Skip verfolgt.
- **Muster:** Nachfolger des Musters P2 der Vorgängeranalyse ("Beobachtungswahrheit an einer Lifecycle-Seam zu früh oder zu spät festgelegt"): Der Tee beobachtet korrekt, was an seiner Seam geschieht, der Vertrag nennt es aber "Konsum der Anwendung". Springs Clients konsumieren auf drei Wegen, die die Seam nicht unterscheidet.
- **Richtung:** Den Vertrag des `state`-Tags an dem festmachen, was beide Twins beobachten können, und die Restfälle ausdrücklich benennen.

### P3 — Fail-open-Guards: getestet an den Stufen, ungetestet in den Körpern; Close-Fehler uneinheitlich verbucht

- **Vorkommen:** 7 von 12 Guard-Körpern im RestClient-Twin ohne Ausführung (JaCoCo, Befund 18), 1 toter Guard im WebClient-Twin (Befund 12), 3 Stellen mit Close-Fehler als "verlorene Zeile" statt `wiring` (Befund 8: RestClient-Startzeile, WebClient-Startzeile und -Abschluss).
- **Zählbasis:** `reportQuietly`-/`failOpen`-Aufrufe per Lesen gezählt (12 RestClient, ~15 WebClient, 8 common) und gegen `jacoco.csv` bzw. die `use {}`-Schichtung abgeglichen.
- **Muster:** Die Grenze `Exception` ist bewusst und dokumentiert; was fehlt, ist der Beweis, dass jeder Guard-Körper hält, und eine einheitliche Buchführung, wenn der Scope-Abbau nach erfolgreicher Emission scheitert.
- **Richtung:** Ein Fehlerinjektions-Test pro Guard (Vorbild `MdcScopeTest`) und eine gemeinsame Regel "Emission gezählt, Abbau gezählt als `wiring`".

### P4 — Test-Helfer verändern JVM-globalen Zustand ohne vollständige Restaurierung

- **Vorkommen:** 3 `CapturedLogger`-Kopien plus 2 `Level.OFF`-Stellen (Befund 15); Executoren ohne `finally` in 2 Tests (Befund 16); Worker-Fehler ohne Einsammlung in 1 Test (Befund 17). Sauber geschützt dagegen: `ContextRegistry` (`MdcAccessorGuard`, 3 Klassen), `MDC` (3 Klassen), TurboFilter (1 Klasse), MDC-Adapter-Swap (1 Klasse).
- **Zählbasis:** grep über `src/test` nach `logger.level`, `Level.OFF`, `Executors.`, `Thread(`, `ContextRegistry`, `MDC.` und Lesen der Setup-/Teardown-Blöcke.
- **Muster:** Die Suite ist nur deshalb ordnungsunabhängig, weil keine Parallelität konfiguriert ist und Logger-Namen klassenspezifisch sind; die Invarianten sind gekoppelt, nicht garantiert.
- **Richtung:** Jeder Helfer restauriert, was er verändert hat; Executoren und Worker in `try/finally` bzw. als Daemon mit eingesammelten Fehlern.

### P5 — Dokumentation und Kommentare laufen dem Code voraus oder hinterher

- **Vorkommen:** ~9 Stellen: Guide §7.4/§7.5 und die `BodyReadState`-/Meter-KDocs (Befund 1), README/Guide §3.3 beider Twins "earlier customizers" (Befund 9), vier Kommentare (Befund 21), sechs Rationale-Blöcke mit falscher Engine (Befund 14), ein Test-Rationale mit falschem Guard (Befund 12).
- **Zählbasis:** jede KDoc und jeder Rationale-Block der Score-4/5-Dateien gegen den beschriebenen Code gelesen.
- **Muster:** Das Projekt dokumentiert außergewöhnlich dicht; genau deshalb wiegt eine Aussage, die nicht mehr stimmt, schwerer als anderswo, zumal die Rationales auf die Evidence-Seite generiert werden.
- **Richtung:** Die genannten Stellen mit den zugehörigen Befunden bereinigen; Rationales, die eine Engine oder einen Guard benennen, an einen Test binden, der genau das prüft.

### P6 — "Late, not last": dieselbe Customizer-Ordnung in beiden Twins mit derselben Lücke

- **Vorkommen:** 2 Stellen (Befund 9), `CUSTOMIZER_ORDER = LOWEST_PRECEDENCE - 10` in beiden Auto-Configurations, plus 2 leere Ordnungs-Assertions (Befund 13).
- **Zählbasis:** grep nach `CUSTOMIZER_ORDER` und Lesen der Auto-Configuration-Tests.
- **Muster:** Die Ordnung ist bewusst gewählt und dokumentiert, aber gegen den Normalfall eines ungeordneten Host-Customizers nie geprüft; der Twin-Gleichlauf reproduziert die Lücke doppelt.
- **Richtung:** Eine Entscheidung (dokumentieren oder Ordnung ändern) und je ein Test mit konkurrierendem Customizer in beiden Twins.
