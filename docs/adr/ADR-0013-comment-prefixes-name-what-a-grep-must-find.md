# ADR-0013: Comment prefixes name what a grep must find

**Status:** Accepted  
**Date:** 2026-09-20  
**Deciders:** Dirk Haase (maintainer)  
**Related:** ADR-0001 (the ADR series as the reference target a
comment cites), ADR-0003 (the shared classes whose invariants both
twins rely on), ADR-0008 (the meter owner whose lock order is the
first `Invariant:` of the catalog)

## Context

The comments of this codebase are dense on purpose: they state the
constraints the code cannot express, and three rounds of the comment
audit (`docs/assessment/COMMENT_AUDIT-2026-09-05T01-57-47.md`, `.R2.md`
and `.R3.md`) found them to carry knowledge rather than dead weight.
What they do not do is tell a reader, or a `grep`, WHICH KIND of
constraint a sentence states. The house style marks the load-bearing
word in capitals (`BEFORE`, `LAST`, `FIRST`, `ONE`); that stock grew
from about 296 to 396 occurrences over the three rounds and reads
well, but it is not an inventory. Nobody can ask the repository for
its invariants, and nobody upgrading Reactor Netty or Spring Boot can
ask it which statements about those libraries the module relies on.

The fix rounds of September 2026 made the gap visible. They
introduced a lock order for the body-meter cache, a write order for a
volatile handoff, a freeze-before-emission rule, an assumption about
Reactor Netty's connection retry, one about Boot declaring its
customizers under their interface type and one about how Spring's
body skip drains a response. Each is stated well where it stands.
None is recognisable as a member of a class, so a dependency bump
finds none of them, and a review cannot tell a statement that must be
re-verified from one that explains a local choice.

One prefix already exists and shows the shape: every log field in
`ClientLogField` opens its KDoc with `ELK:` followed by the index
mapping, and the test-evidence and ELK documentation grep for it. All
three audit rounds proposed to extend that shape to five to seven
prefixes; this record does so.

## Decision

**A comment that states an invariant, an assumption about a third
party, a workaround, a rejected alternative, a guard's purpose or a
compatibility constraint opens the sentence that makes the statement
with one of the prefixes below.** The prefix is the greppable name of
the category; the sentence after it is the rule, in the house form
"decision + reason".

### The vocabulary

| Prefix | States | Ask the repository for it when |
|---|---|---|
| `Invariant:` | A property that holds at every point the code can be observed, or an ordering the code relies on (a write order, a lock order, "this runs LAST"). Prefer a `check`/`require` when it is cheap; the prefix marks the ones that are not. | changing concurrency, ordering or a field another thread reads |
| `Assumption:` | A fact about a third party - Spring, Boot, Reactor, Netty, the JDK, a connector - the module relies on but cannot enforce, with the version it was verified against where one matters. | bumping a dependency: `grep -rn "Assumption:" */src/main` is the checklist of that upgrade |
| `Workaround:` | Code or configuration shaped around a defect or limitation of a third party, with what breaks when it is removed. | bumping a dependency: the work list of things to try removing |
| `Rationale:` | A rejected alternative with its failure mode - "not X, because X did Y". Not for every why: the house form states decision and reason in one sentence without a prefix; this prefix marks the alternative that must not be retried. | about to "simplify" a construction that looks roundabout |
| `Safety:` | Why a guard, a catch or a rethrow exists in a fail-open path, and what escaping it would cost (a lost event, a failed call, a corrupted response). | touching a `try`/`catch` of the twins |
| `Compatibility:` | A shape kept for a consumer, a peer, the sibling project or a wire contract - the reason a rename or a removal is a breaking change. | renaming, removing or widening anything a host or the log index sees |
| `ELK:` | The index mapping of a log field and the access pattern that earns it (existing convention of `ClientLogField`). | changing the component template |

Six of the seven prefixes are new; `ELK:` is recorded here so the
vocabulary has one home.

### Grammar

- The prefix opens the sentence, followed by a colon and a space,
  then the statement: `Invariant: [totalBytes] is written LAST in
  every mutation and read FIRST, so …`. In a KDoc paragraph, in a
  `//` line comment and in a POM comment alike.
- **One prefix per statement**, on the sentence that makes it. A
  comment that explains a local choice, narrates a consequence or
  points to a canonical place carries no prefix; most comments carry
  none. A prefix on every comment would be the emphasis capitals
  over again.
- The capital-letter emphasis stays. It marks the word; the prefix
  marks the kind.
- A version belongs to an `Assumption:` when the fact can change with
  an upgrade: `Assumption: … (probed on Reactor Netty 1.3.7)`.
- The statement after the prefix must carry the rule without the
  reference (ADR-FORMAT: never a bare reference). `Invariant: see
  ADR-0008.` is not a comment.

### Scope of application

- New comments of a category carry the prefix as they are written.
- Existing comments are prefixed **when they are touched** or when a
  comment audit lists them - not as a campaign. The first eight, from
  the third audit round, are prefixed with this record: the lock order
  of the body-meter cache and the emission-time freeze of the reactive
  capture (`Invariant:`), the volatile write order of the blocking
  capture and the origin count before the gauge (`Invariant:`), the
  Reactor Netty retry, Boot's interface-type declaration of its
  customizers, Spring's `takeWhile` body skip and its
  `hasMessageBody()` rule (`Assumption:`), and Dokka's out-of-band
  `pluginsConfiguration` in the root POM (`Workaround:`).
- Test comments keep their own three-question block (`What is
  tested:` / `Success criteria:` / `Why it matters:`); the prefixes
  of this record are for production and build comments.

### What is not enforced

No build step checks the prefixes: whether a sentence states an
invariant is not machine-decidable, and a `grep` for missing prefixes
would only count sentences. The review question stays the guard -
"Can this comment become wrong without something turning red? If it
can, which kind is it?" - and the comment audit reports the count of
prefixed statements per category, so the catalog's growth is
visible. The CI step that rejects `[Symbol]` links in line comments
is unaffected.

## Consequences

**Positive:**

- `grep -rn "Invariant:" */src/main` is the module's invariant
  catalog; `grep -rn "Assumption:\|Workaround:"` is the checklist of
  every dependency bump. Both were impossible before.
- A reviewer of a fix commit sees at the prefix that a sentence is a
  claim about a neighbour or a third party and must be re-verified,
  not merely reworded - the failure mode of the fix rounds the third
  audit round described.
- The comment audit's abort criterion "comments of the defined
  categories without a prefix → 0" becomes measurable per touched
  file.

**Negative:**

- Seven words to learn, and a judgement call per comment: a statement
  that is an assumption AND a workaround takes the prefix that names
  what a reader must do (re-verify, or try removing).
- The existing stock is prefixed incrementally, so for a while the
  catalog is incomplete and a grep undercounts. The audit reports say
  by how much.
- A prefix invites ceremony. "Rationale:" in front of every reason
  would defeat the purpose; the grammar above is the guard, and the
  audit treats a prefixed sentence that states no member of its
  category as a finding.

**Neutral:**

- The prefixes do not replace ADR references: a comment may open with
  `Invariant:` and end with `(ADR-0008)`. The prefix says what kind
  of statement it is, the ADR number where the decision trail is.
- The sibling project limesium may adopt the same vocabulary; each
  project keeps its own record.
