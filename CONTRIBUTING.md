# Contributing to Legatium

Thank you for considering a contribution! This document explains how to get set up,
what the project expects from changes, and how to submit them.

## Ground rules

- Be respectful; the [Code of Conduct](CODE_OF_CONDUCT.md) applies to all project spaces.
- For anything larger than a trivial fix, please **open an issue first** and discuss the
  change before writing code. This avoids wasted work if the change doesn't fit the
  project's scope.
- Security issues must **not** be reported as public issues — see [SECURITY.md](SECURITY.md).

## Project scope

Legatium does one thing: emit exactly one structured `adapter_*` log line per outbound
HTTP exchange, via two paradigm twins (RestClient/RestTemplate and WebClient) with
**identical fields and identical configuration**, plus the six Micrometer meter families that
cover the line's blind spots ([ADR-0008](docs/adr/ADR-0008-six-meters-consumed-not-exported.md);
a new meter needs a blind spot none of the existing ones covers). Contributions that widen this
scope (other metrics or tracing backends, other protocols, other clients) are likely out of
scope — ask first. The inbound side belongs to the sibling project
[Limesium](https://github.com/Inqudium/limesium).

The twin symmetry is a hard invariant: a new field or configuration property must land
in **both** modules, with the shared contract in
`docs/adapter-logging-reference.yml` updated and the
contract tests passing in both.

## Development setup

Prerequisites:

- JDK 24+ to BUILD (CI uses 25; `.mvn/jvm.config` passes flags a pre-24 JVM
  rejects) - the published artifacts still target Java 21
- Maven 3.9+ (or use your IDE's bundled Maven)

Build and test everything:

```
mvn verify
```

This compiles both modules, runs all tests, and runs the `ktlint` style check.
A PR must pass `mvn verify` cleanly. Test coverage (JaCoCo) is collected in the
same run and written per module to `target/site/jacoco/`; skip it locally with
`-Djacoco.skip=true` if you need a faster loop.

### Dependency vulnerability scan

CI additionally scans the **resolved** dependency graph against the
[OSV](https://osv.dev/) database and fails on any known advisory. It runs on
every push and pull request, and weekly — a newly published advisory has to
surface even when nothing was committed. To reproduce it locally (requires a
container runtime):

```bash
mvn cyclonedx:makeAggregateBom        # SBOM of the resolved graph → target/bom.json
docker run --rm -v "$PWD:/repo" \
  ghcr.io/google/osv-scanner-action:v2.5.1 --lockfile=/repo/target/bom.json
```

The scan uses an SBOM rather than `pom.xml` because most versions come from
the Spring Boot BOM and never appear in `pom.xml`; test-scoped dependencies
are excluded, since they reach no consumer.

When an advisory appears, prefer fixing it — usually a version pin in
`<dependencyManagement>` with a rationale comment, even when the affected
artifact is transitive. Only if an advisory is genuinely unfixable *and*
provably not exploitable here, record it in an `osv-scanner.toml` with the
reason and the date it was assessed — do not remove the gate.

### Static analysis (CodeQL)

The dependency scan covers *published advisories in dependencies*; the
`CodeQL` workflow covers the complementary half — this project's own code
(`java-kotlin`) and the workflow definitions themselves (`actions`). It runs
on every push and pull request and weekly, and results land in the
repository's **Security → Code scanning** tab rather than in the build log.
A finding there is triaged like a review comment: fix it, or dismiss it in
the UI with a written reason.

### Code style

- Build from the root (or with `-am`): the twins compile against `legatium-common` through
  the reactor's friend paths (ADR-0003); a lone `-pl <twin>` build fails with "internal in file".
- Kotlin code is checked with **ktlint** (via `ktlint-maven-plugin`, bound to `verify`).
  Run `mvn ktlint:format` to auto-format before committing.
- Match the surrounding code's comment density and naming; comments should state
  constraints the code can't express, not narrate the code.

### Tests

- KDoc `[Symbol]` links are checked: Dokka runs with `failOnWarning` in CI (job `build`) and in
  the Docs workflow, so an unresolvable link fails the pull request. Link production symbols
  with `[Symbol]` rather than naming them in prose or backticks; a type that is not imported
  needs its fully qualified name in the link. Dokka reads KDoc only: a `[Symbol]` in a `//` line
  comment only LOOKS checked, so the CI job rejects it - name the symbol in backticks there, or
  move the sentence into the enclosing declaration's KDoc.
- Every behavior change needs a test in the module it touches.
- Integration tests against a real engine (the request-factory and connector contracts, the
  Boot-builder suites) pin the module's **classification** of an engine failure - level, outcome,
  the absent status - and that the engine raised *an* exception, not **which** exception type or
  message the engine chose: that is engine-internal detail the contract does not promise, and
  pinning it per engine would turn every engine upgrade into a test edit for no safety gained
  (decision of 2026-09-19, defect analyses of that day, findings 19 resp. 20). Unit tests against
  the module's own fakes may pin messages, because there the message is the test's own input.
- Changes to the shared field/configuration contract need the reference file and the
  contract tests in **both** modules updated.
- Test classes follow the existing `*Test.kt` naming (Surefire picks up `**/*Test.kt`).
- Test rationale comments follow the existing three-line pattern at the top of the
  test body (`What is tested:` / `Success criteria:` / `Why it matters:`), followed
  by the `Given/When/Then` stage comments - for a truth-table test with no
  separate arrange/act/assert phases a single `// Given/When/Then` line is enough.
  The Java fuzz targets carry the same block. The
  [test-evidence page](https://inqudium.github.io/legatium/tests/test-evidence/) and
  the [coverage reports](https://inqudium.github.io/legatium/coverage/) on the docs
  site are GENERATED by the Docs workflow from the Surefire and JaCoCo output
  (`.github/scripts/`); never edit `docs/tests/` by hand or check it in. Your
  test's rationale comment is what appears there — another reason to keep the
  pattern intact.

### Consumer smoke test (the shaded jars)

The reactor's tests run BEFORE packaging, against the `legatium-common` module;
the jars a consumer receives - common classes inlined, dependency-reduced POM -
are never loaded by them. The standalone project `consumer-smoke/` (not a
reactor child) depends on both twins like an application and checks, on the
installed jars, that the shared classes resolve from exactly the two twin jars,
that both auto-configurations wire up, and that one call per client ends in
one exchange line. CI runs it on every push (`consumer-smoke` job); locally:

```bash
mvn -DskipTests -Djacoco.skip=true install
rm -rf ~/.m2/repository/eu/inqudium/legatium-common   # a consumer never has it
mvn -f consumer-smoke/pom.xml verify
```

Keep `legatium.version` in `consumer-smoke/pom.xml` and in `benchmarks/pom.xml`
equal to the reactor's `revision`. CI derives the version from the root POM for
both standalone builds, so a stale pin is visible only in a local build against
a clean `~/.m2` - the rule keeps the two builds the same.

### Fuzzing (Jazzer `@FuzzTest`)

The components that parse or bound caller-controlled data - body capture,
header masking, the `traceparent` parser - are fuzzed with
[Jazzer](https://github.com/CodeIntelligenceTesting/jazzer)'s JUnit 5
integration: `*FuzzTest.java` classes under `src/test/java`, stating their
invariants in the test body. They run in two modes:

- **Regression mode, in every build.** `mvn verify` executes each fuzz test
  against its checked-in inputs (`src/test/resources/**/<Class>Inputs/`)
  plus the empty input - cheap, deterministic, part of the normal suite.
- **Fuzzing mode, nightly.** The `Fuzz` workflow sets `JAZZER_FUZZ=1` and
  runs each target in its own job (Jazzer fuzzes only one `@FuzzTest` per
  JVM), each capped by its `@FuzzTest(maxDuration = ...)`.

A finding is written into the seed-corpus directory next to the test
sources (the nightly run also uploads it as a workflow artifact): commit it
there and it becomes a permanent regression input; then fix the code. New
parsing/bounding surface should bring a fuzz target stating its invariants,
like the existing ones do - in JAVA, not Kotlin: the OpenSSF Scorecard
fuzzing detector only recognizes Jazzer in `*.java` files.

To fuzz locally (no Docker needed):

```bash
JAZZER_FUZZ=1 mvn -Dtest=TraceparentFuzzTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Submitting changes

1. Fork the repository and create a topic branch from `main`.
2. Make your change with tests; keep commits focused and messages descriptive.
3. Ensure `mvn verify` passes.
4. Open a pull request against `main` describing **what** changed and **why**.
   Link the issue it addresses, if any.

By submitting a contribution you agree that it is licensed under the
[Apache License 2.0](LICENSE), the project's license, and you certify the
[Developer Certificate of Origin](https://developercertificate.org/) — i.e. you have
the right to submit the work under that license.

## Releasing (maintainers)

A release is one commit, one tag, one GitHub release and one Maven Central
deployment - in that order, because the tag is what both the release workflow
and the Central deployment build from. Versions follow semantic versioning: a
cycle with `### Added` entries is a minor release, a cycle of `### Changed` and
`### Fixed` entries only is a patch release, a migration note under `### Changed`
makes it a major one.

1. **Release commit** on a topic branch, merged by pull request like any change
   (branch protection requires the `build` check; `gh pr merge --auto --merge`
   merges it once green). It changes exactly:
   - `pom.xml`: `<revision>` to the version, `<project.build.outputTimestamp>`
     to the release date at midnight UTC (`YYYY-MM-DDT00:00:00Z`) - the
     timestamp is what makes the jars reproducible, see the README;
   - `consumer-smoke/pom.xml` and `benchmarks/pom.xml`: `<legatium.version>` to the version;
   - `README.md`: a new first row in the compatibility table;
   - `CHANGELOG.md`: a `## [1.1.0] - YYYY-MM-DD` heading directly under the
     (then empty) `## [Unreleased]` heading, the `[Unreleased]:` link changed
     to `compare/1.1.0...HEAD`, and a `[1.1.0]:` link to the release tag.
   Commit message: `(chore) release 1.1.0`.
2. **Tag and GitHub release.** On the merged `main`, tag the merge commit with
   the bare version (no `v` prefix) and create the release with the changelog
   section as its notes:

   ```bash
   git tag 1.1.0 && git push origin 1.1.0
   gh release create 1.1.0 --title 1.1.0 --notes-file notes.md --verify-tag
   ```

   `notes.md` is the version's changelog section without its heading. Publishing
   the release triggers `release.yml`: it builds the twins on the tag with
   `-Drevision`, attaches the jars and the aggregate SBOM, generates SLSA
   provenance (`multiple.intoto.jsonl`) and deploys to GitHub Packages.
3. **Maven Central.** From a checkout of the tag, with the GPG key and the
   `central` server (Central Portal user token) in `~/.m2/settings.xml`:

   ```bash
   mvn --batch-mode -Prelease -DskipTests -Djacoco.skip=true deploy
   ```

   The `release` profile attaches sources and the Dokka-rendered javadoc jar,
   signs everything and uploads one bundle; `legatium-common` is skipped, the
   twins carry it inlined. The plugin runs with `autoPublish=false`, so the
   deployment stops at **VALIDATED** and prints its deployment id. Publish it
   either in the Central Portal (Deployments, "Publish") or through the
   portal API:

   ```bash
   TOKEN=$(printf '%s:%s' "$CENTRAL_USER" "$CENTRAL_PASSWORD" | base64 -w0)
   curl -X POST -H "Authorization: Bearer $TOKEN" \
     https://central.sonatype.com/api/v1/publisher/deployment/<deployment-id>
   curl -X POST -H "Authorization: Bearer $TOKEN" \
     "https://central.sonatype.com/api/v1/publisher/status?id=<deployment-id>"
   ```

   The status moves from `PUBLISHING` to `PUBLISHED` within minutes; the
   artifacts appear on `repo1.maven.org` some minutes after that. Publishing is
   irreversible - do the check of step 4 against the release assets first.
4. **Verify the bytes.** The jars attached to the GitHub release, the jars the
   deployment uploaded and, once synced, the jars on Central are the same bytes
   (README, "Reproducible builds"):

   ```bash
   sha256sum legatium-restclient-logging/target/legatium-restclient-logging-1.1.0.jar legatium-webclient-logging/target/legatium-webclient-logging-1.1.0.jar
   gh release download 1.1.0 --pattern '*.jar' --dir /tmp/assets && sha256sum /tmp/assets/*.jar
   curl -sL https://repo1.maven.org/maven2/eu/inqudium/legatium-restclient-logging/1.1.0/legatium-restclient-logging-1.1.0.jar | sha256sum
   ```

   A mismatch means a build input differs from the tag (JDK below 24, a stale
   `~/.m2` snapshot of `legatium-common`, a local change) - rebuild from a clean
   checkout of the tag before publishing.
5. **Next development version.** A follow-up pull request
   `(chore) start 1.1.1-SNAPSHOT` sets `<revision>` and the version pins of
   step 1 to the next patch `-SNAPSHOT`.

## Reporting bugs

Please include:

- Legatium version and module (`legatium-restclient-logging` or `legatium-webclient-logging`)
- Spring Boot version and client engine (JDK `HttpClient`, Apache, Reactor Netty, ...)
- Relevant configuration (`adapter-logging.*` properties)
- What you expected, what happened, and a minimal reproduction if possible
