# Performance Assessment: BoundedByteBuffer at 500 client calls per second

1. Identification of the codebase
   - **Repository:** `https://github.com/Inqudium/legatium.git`
   - **Commit hash (HEAD):** `dbb0a1bb` (merge of PR #23 on top of PR #22 and PR #14 - the buffer as shipped)
   - **Reference (branch):** `refs/heads/docs/bench-report-utf8-fast-path`
2. Input
   - **Evidence:** `./docs/assessment/BENCH_REPORT-2026-09-17T17-07-53.md`, sections 3 and 6 (JMH 1.37, 3 forks × 8 measurement iterations, `-prof gc`, JDK 26 on a Ryzen AI 9 workstation); raw output under `./benchmarks/results/`. Every number below is either quoted from that report or derived from it by the arithmetic shown.
   - **Changes assessed:** PR #14 (scratch-buffer truncated rendering, 256-byte floor, 64 KiB hint ceiling, range check, `Long` doubling, `total` precondition), PR #22 (UTF-8 fast path of the truncated rendering), PR #23 (the benchmark module).
3. Questions
   - What the buffer costs at **500 client calls per second**, in CPU and in allocation, per configuration.
   - **Code effort against result**, per change.
   - **Memory peaks and GC pressure** the chosen implementation can produce, with the thresholds where a configuration turns it from negligible into a GC concern.
4. Assumptions (stated once, used throughout)
   - **Load model:** 500 exchanges/s. "Parallel" is read as 500 in flight at once, i.e. 500/s at a 1 s mean exchange duration - the conservative reading for retained memory (at 100 ms mean duration the in-flight count is 50 and every live-memory figure below shrinks tenfold).
   - **Bodies:** both request and response captured (`logRequestBody` and `logResponseBody` both set), bodies as large as the cap or larger (the worst case; a 2 KiB JSON answer under a 16 KiB cap costs an eighth). Bulk reads (8 KiB chunks) unless "byte-wise" is named. ASCII unless named.
   - **Modes:** ALWAYS renders every body; ON_FAILURE captures every body and renders about 1 % (a 1 % failure rate is assumed); measure-only buffers nothing.
   - **Not in the numbers:** the twins' tees, the emitter, the logger and the appender - the buffer is the unit. Absolute times are JDK 26 workstation figures; ratios and bytes transfer (BENCH_REPORT, section 3 of its metadata).

---

## 1. Executive summary

### 1.1 The buffer at 500 calls per second

At the shipped default (16 KiB cap) the buffer is **invisible**: about 2.6 µs of CPU and 66 KB of allocation per exchange with both bodies captured and rendered, which is **0.13 % of one core and 33 MB/s** of young-generation allocation - a G1 young collection every half a minute on a 2 GiB heap. Truncation, a missing Content-Length and non-ASCII text each add tens of percent to that, never a multiple. The read shape of the application matters more than any option of the buffer: a byte-wise `InputStream.read()` loop costs 2.3 % of a core where bulk reads cost 0.13 %.

### 1.2 The cap is the lever

The configuration that changes the picture is the cap. At **256 KiB** the same load costs 4.6 % of a core and **1 GB/s** of allocation - a young collection every second or two, with 256 MiB of live buffers overflowing the survivor space - and from **512 KiB** upwards every buffer and every rendered string becomes a G1 humongous object on a 2 GiB heap. Caps of that size are debugging windows, not steady state.

### 1.3 Memory peaks

500 in-flight exchanges retain **at most 16 MiB** at the default cap and **256 MiB** at 256 KiB; the transient peak of the renderings stays below 30 MiB at any realistic thread count. The **64 KiB hint ceiling** (PR #14) is the one change with a large, measurable effect on this variable: without it, 500 in-flight exchanges against a 16 MiB cap could be made to retain **16 GiB** by peers declaring a large Content-Length and sending one byte; with it, **64 MiB**.

### 1.4 Code effort against result

**How large the effort is against the alternatives.** The buffer as shipped is 270 lines of production code with 511 lines of tests (20 cases), grown from 164 lines through PR #14 and PR #22. The cheapest alternative that meets ADR-0003's requirements - a cap that counts beyond itself, a cut-back to a mark, a sizing hint, a boundary-aware truncated rendering - is a `ByteArrayOutputStream` subclass of about 70 lines (section 5.2); it would need the same tests, so the buffer's extra effort is about **200 lines of production code**. `FastByteArrayOutputStream` is no baseline: it cannot provide the cut-back at all.

**What the extra effort buys, measured, as factors relative to the buffer (1.0×).** On byte-wise reads the `ByteArrayOutputStream` subclass would cost **3.4× to 7.3×** the buffer's CPU (presized and default; the inherited monitor on every `write(int)`, which a subclass cannot shed) and `FastByteArrayOutputStream` **1.9× to 2.1×** (section 5.1) - at 500 calls/s the difference between 2.3 % of a core and 7.7-14 %. On retained memory under adversarial Content-Length declarations, both streams presized to the declaration would hold **256×** what the buffer holds (16 GiB against 64 MiB for 500 in-flight exchanges under a 16 MiB cap, section 4.1), because neither clamps a declared length and the subclass would have to add the buffer's ceiling by hand; `FastByteArrayOutputStream` without a presize additionally retains **up to 2×** the buffer's bytes per exchange (its blocks are the data, section 5.3). On bulk reads, the common shape, all three are **1.0×** within 10 % in time and equal in bytes: there the 200 lines buy nothing measurable. Lazy allocation for measure-only mode and the range and `total` checks are behaviour, not factors.

**Whether that is proportionate.** Yes. This class sits on every exchange of every client the twins wrap, cannot choose the application's read shape, and takes the peer's Content-Length as input - the two properties the 200 lines address are exactly the two an attacker or a careless reader controls. The marginal part of the effort is the render-path work of PR #14 and PR #22 (about half of the added lines and 8 of the 20 tests), which bought about **1 µs and 2 KB per truncated body** - 0.05 % of a core at this load - and a rendering peak of 3N instead of 4N. That part would not have been justified by throughput; it is justified as the smaller peak and the correctness hardening that came with it (the `total` precondition, the range check, the `Long` doubling), and it should be the last change to this class for a while.

### 1.5 The stream candidates

Neither candidate is an alternative at this load, for different reasons (section 5). `ByteArrayOutputStream` matches the buffer byte for byte in memory and within 10 % in bulk time, but its `synchronized` writes cost 6-11× the CPU on byte-wise reads (14 % of a core at 500/s for the read-loop shape, against 2.3 %), and it has no cap, no cut-back and no boundary-aware truncation - a subclass could add the first two and would inherit the lock. `FastByteArrayOutputStream` is unsynchronized and as fast as the buffer in bulk and 1.7× slower byte-wise, but it retains up to 2× the cap per in-flight exchange without a presize (its blocks are the data), coalesces them into a fresh array before every `toString`, and offers no way to cut back to a mark - the blocking twin's `reset` cannot be built on it. The byte-array design (ADR-0003) is thus confirmed by measurement, not only by the feature list.

## 2. Five hundred client calls per second

Per-exchange costs are the sum of two captures and two renderings from BENCH_REPORT §3.1, §3.3 and §6; the request body arrives as one array and is written in one call, so its capture equals the hinted case.

### 2.1 Default cap (16 KiB), bodies at the cap

| Scenario | CPU per exchange | Allocation per exchange | At 500/s: CPU | At 500/s: allocation |
|---|---|---|---|---|
| ALWAYS, Content-Length known, bodies fit | 2 × 0.69 + 2 × 0.62 = **2.6 µs** | 2 × 16.4 + 2 × 16.4 = **66 KB** | 1.3 ms/s = **0.13 % of a core** | **33 MB/s** |
| ALWAYS, response chunked (no hint), bodies exceed the cap (truncated) | 0.69 + 1.09 + 2 × 1.35 = **4.5 µs** | 16.4 + 24.6 + 2 × 32.9 = **107 KB** | 2.2 ms/s = 0.22 % | 53 MB/s |
| ALWAYS, as above, two-byte text | 0.69 + 1.09 + 2 × 8.5 = 18.8 µs | 16.4 + 24.6 + 2 × 38.4 = 118 KB | 9.4 ms/s = 0.9 % | 59 MB/s |
| ON_FAILURE (1 % rendered), Content-Length known | 2 × 0.69 + 0.01 × 1.2 = 1.4 µs | 2 × 16.4 + 0.3 = 33 KB | 0.7 ms/s = 0.07 % | 16 MB/s |
| Measure-only (no body logging) | 2 × 0.05 (the capped write's compare loop, bulk) | 0 | ≈ 0 | 0 |
| Byte-wise reader (`InputStream.read()` loop), ALWAYS | 2 × 22 + 2 × 0.62 = 45 µs | 66 KB | 23 ms/s = **2.3 %** | 33 MB/s |
| The same byte-wise reader over `ByteArrayOutputStream` (the road not taken) | 2 × 140 + 2 × 0.7 = 281 µs | 66 KB | 141 ms/s = **14 %** | 33 MB/s |

Reading: with bulk reads the buffer costs a tenth of a percent of a core; the shape of the application's reads matters more than any option of the buffer, and the byte-wise shape is exactly where the bare array (ADR-0003) pays for itself against the JDK stream (6-11×, BENCH_REPORT §4.2). The truncated rendering after PR #22 is within 7 % of the naive `String(bytes) + note` (§6), so nothing on the render path is left to win for ASCII.

### 2.2 A 256 KiB cap, bodies at the cap

| Scenario | CPU per exchange | Allocation per exchange | At 500/s: CPU | At 500/s: allocation |
|---|---|---|---|---|
| ALWAYS, Content-Length known, bodies fit | 2 × 19.7 + 2 × 10.9 = 61 µs | 2 × 459 + 2 × 262 = 1.44 MB | 31 ms/s = 3.1 % | **720 MB/s** |
| ALWAYS, no hint, truncated | 2 × 22.9 + 2 × 23.1 = 92 µs | 2 × 516 + 2 × 524 = 2.08 MB | 46 ms/s = **4.6 %** | **1.04 GB/s** |
| ON_FAILURE (1 % rendered), Content-Length known | 2 × 19.7 + 0.2 = 40 µs | 2 × 459 + 5 = 0.92 MB | 20 ms/s = 2 % | 460 MB/s |

Reading: CPU stays modest, allocation does not - a gigabyte per second is a young collection every one to two seconds on a 2 GiB heap (section 4). The hinted case allocates 1.75× the cap per body here because the hint is clipped at 64 KiB and the array doubles twice more (BENCH_REPORT §4.3); presizing to the cap would save 200 KB per body at the price the ceiling exists to avoid (section 4.1). Whoever configures a cap of this size for a 500/s client should do it for a debugging window, not as a steady state.

## 3. Code effort against result

Sizes from `git diff --shortstat` of `legatium-common` per PR; effects from the BENCH_REPORT.

| Change | Effort | Measured result | Assessment |
|---|---|---|---|
| PR #14 - truncated rendering through a scratch buffer into a presized builder (replacing the cap-sized `CharBuffer`) | part of +389/−47 lines and 8 of 11 new tests | Allocation per truncated rendering 2N (526.7 KB at 256 KiB) - the same as the naive concatenation; time 1.56× the naive path for ASCII, 1.11× for two-byte text, 28 % fewer bytes for two-byte text | The footprint goal (3N instead of 4N per rendering, the retained bytes included) was reached; the time was a step back for ASCII that PR #22 then repaired. On its own: a memory-peak change of 1× cap per rendering thread, worth about 16 KiB per concurrent rendering at the default cap. **Small effect, moderate effort** - justified by the peak, not by throughput. |
| PR #14 - 256-byte floor without a hint | ~10 lines + 2 tests | 1 KiB byte-wise: 1.87 KB allocated instead of 1.84 KB before the floor (eight doublings from 1 byte were nine small arrays of 511 B in total); time unchanged within noise | **Negligible measured effect**; the floor's argument is allocation COUNT (nine arrays down to one), which `-prof gc` bytes do not show. Cheap, harmless, keep. |
| PR #14 - 64 KiB hint ceiling | ~10 lines + 1 test | Truthful 256 KiB declaration: 459 KB allocated and 19.7 µs instead of 262 KB and 10.5 µs (1.75× bytes, 1.9× time); no effect at caps ≤ 64 KiB | **Large effect on the right variable**: bounds what a peer can make the client retain (section 4.1: 16 GiB → 64 MiB at 500 in flight under a 16 MiB cap). The measured cost falls only on large caps with truthful large bodies, a configuration section 2.2 already discourages. Best effort-to-result ratio of the set. |
| PR #14 - range check, `Long` doubling, `total` precondition | ~15 lines + 3 tests | Not performance changes; the range check is one bounds check per ranged write (no measurable time at 8 KiB chunks: 1.09 vs 1.08 µs for the capped case) | Correctness hardening at zero measured cost. |
| PR #22 - UTF-8 fast path (`utf8Cut` + `String(bytes, 0, cut)`) | +163/−46 lines, 3 new tests, 2 rewritten | Truncated ASCII: 37.1 → 23.1 µs at 256 KiB, 2.31 → 1.35 µs at 16 KiB, bytes unchanged; truncated two-byte text: 182 → 155 µs but 439 → 612 KB (+39 %) | **About 1 µs per truncated body at the default cap** - 0.05 % of a core at 500/s - bought with 30 lines of cut logic and a parity test that pins it to the decoder. Defensible because the truncated ASCII rendering is the common truncated case and the logic is small and proven; but the allocation regression for non-ASCII text shows it was a trade, not a free win. |
| PR #23 - benchmark module | +395 lines, a CI job, 44 minutes of machine time per full run | The evidence behind every row of this table, including the discovery that the first write run measured dead code | The only way the other rows became numbers. Its recurring cost is the smoke job's `mvn install` per PR. |

Overall: the two render-path PRs together (about 500 changed lines, 20 tests) moved a per-exchange cost of 2-4 µs by about 1 µs and a per-rendering peak by 1× cap. At 500 calls/s and the default cap that is 0.5 ms of CPU per second and 16 KiB of peak per rendering thread. The **class itself** - 270 lines against a `ByteArrayOutputStream` - is the effort with the clearest return: 6-11× on byte-wise reads (§4.2) and the cap, the reset, the hint and the truncation semantics the streams do not offer. The **hint ceiling** is the change with the best ratio. The rest is correctness and evidence, and should be described as such rather than as optimization.

## 4. Memory peaks and GC pressure

### 4.1 Retained memory (what 500 in-flight exchanges hold at once)

Each capture retains its array until the exchange is emitted; the array is sized by the hint (≤ 64 KiB), by the floor, or by doubling up to the cap.

| Cap | Bodies at the cap, both captured | Peers declare huge lengths and send one byte: without the ceiling | With the 64 KiB ceiling (shipped) |
|---|---|---|---|
| 16 KiB (default) | 500 × 2 × 16 KiB = **16 MiB** | 16 MiB | 16 MiB |
| 256 KiB | **256 MiB** | 256 MiB | 500 × 2 × 64 KiB = 64 MiB |
| 16 MiB | **16 GiB** (infeasible: the cap must be sized against concurrency) | **16 GiB from 500 bytes of body** | **64 MiB** |

The middle column is the attack the ceiling closes: Content-Length is the peer's word, and before PR #14 a single byte reserved the cap. The right column is the exposure now - bounded by concurrency × 128 KiB whatever the cap. Note that the ceiling does not cap what a TRUTHFUL large body retains (left column): that is the cap's own job, and a 16 MiB cap at 500 in flight is a misconfiguration regardless of the buffer.

Unhinted growth adds transient garbage of about one cap per body (the chain 256 … cap/2 sums to just under the cap) but retains only the final array.

### 4.2 Transient peaks at rendering

Per rendering, on top of the retained array (N = buffered bytes): complete rendering 1N (the string); truncated ASCII 2N (prefix string plus the concatenated result, momentarily both alive); truncated two-byte text 2.3N (the JDK's Latin1 attempt array, the trimmed prefix and the result). Renderings are short (1-25 µs at the sizes measured) and one per exchange, so the simultaneous peak is bounded by the number of threads emitting at the same instant, T: at most T × 2 × 2.3N. For the default cap and T = 24 (one per hardware thread) that is 1.8 MiB; for a 256 KiB cap, 28 MiB. Not a sizing concern at any realistic T; the 4N → 3N change of PR #14 moved this figure by T × 2 × 1N, i.e. 0.8 MiB at the default cap and 12 MiB at 256 KiB.

### 4.3 Allocation rate and collection frequency

Allocation rates from section 2, against G1's young generation on a 2 GiB heap (young sized between 5 % and 60 % of the heap by default, so 100 MiB to 1.2 GiB; a fixed `-Xmn` narrows the range):

| Configuration | Allocation | Young collections | Survivor copying per collection |
|---|---|---|---|
| Default cap, ALWAYS, Content-Length known | 33 MB/s | one every 3-36 s | ≤ 16 MiB live buffers - a few ms |
| Default cap, ALWAYS, truncated, no hint | 53 MB/s | one every 2-23 s | ≤ 16 MiB |
| 256 KiB cap, ALWAYS, truncated, no hint | 1.04 GB/s | **one every 0.1-1.2 s** | ≤ 256 MiB live buffers - tens of ms per collection, and survivor space overflow promotes them to the old generation, where they die unreclaimed until a mixed collection |

The buffer's garbage is short-lived by construction (arrays die at emission, strings when the appender is done), which is the friendly case for a generational collector - as long as the in-flight set fits the survivor space. At the default cap it does with room to spare. At 256 KiB and 500 in flight it does not on a 2 GiB heap (survivor space is a fraction of the young generation), and the premature promotion turns cheap young collections into old-generation churn. That is the mechanism behind the section 2.2 recommendation, and it depends on concurrency × cap, not on the buffer's code.

### 4.4 The humongous threshold

G1 allocates any object of at least half a region outside the young generation. Region size is the heap divided by 2048, rounded to a power of two between 1 and 32 MiB: 1 MiB regions on a 2 GiB heap, 2 MiB on 4 GiB, 4 MiB on 8 GiB. The buffer's array, the truncated rendering's prefix string and the result string are each about one cap in size, so:

| Heap | Region | Humongous from | A cap of |
|---|---|---|---|
| ≤ 2 GiB | 1 MiB | 512 KiB | 512 KiB or more makes every capture and every rendered string humongous |
| 4 GiB | 2 MiB | 1 MiB | 1 MiB or more |
| 8 GiB | 4 MiB | 2 MiB | 2 MiB or more |

Humongous allocations bypass the young generation, fragment the region set and are reclaimed eagerly only when unreferenced at a young collection; at 500/s with several such objects per exchange they are the fastest route to a full collection. The default and the 256 KiB point are below every row; a debugging profile with a cap in the megabytes should either raise the region size (`-XX:G1HeapRegionSize`) or accept that it runs under a different collector regime. The 64 KiB hint ceiling keeps the hinted first allocation below the threshold everywhere; only the doubling to the cap and the renderings cross it.

## 5. The other candidates: `ByteArrayOutputStream` and `FastByteArrayOutputStream`

The benchmark measured both streams under the same operations as the buffer (BENCH_REPORT §3.1-3.3, §6). This section puts them through the same three questions. "Presized" is the stream's constructor argument set to the cap - the equivalent of the buffer's hint.

### 5.1 At 500 calls per second

Same model as section 2: both bodies captured, bodies at the cap, one exchange = two captures plus two renderings; the per-exchange figure is `2 × capture + 2 × rendering` from BENCH_REPORT §3.1, §3.2, §3.3 and §6. "Length known" means the buffer's hint or the stream's presize. The streams have no truncated rendering of their own, so every row renders complete (`toString(UTF_8)`). One aspect per table.

**CPU per exchange (µs)**

| Scenario | `BoundedByteBuffer` | `ByteArrayOutputStream` | `FastByteArrayOutputStream` |
|---|---|---|---|
| Default cap, bulk reads, length known | 2.6 | 2.7 | 2.6 |
| Default cap, bulk reads, length unknown | 3.4 | 4.0 | 3.4 |
| Default cap, byte-wise reads, length known | 46 | 155 | 87 |
| Default cap, byte-wise reads, length unknown | 39 | 281 | 81 |
| 256 KiB cap, bulk reads, length known | 61 | 43 | 43 |

**Share of one core at 500 exchanges/s** (CPU per exchange × 500, as a percentage of one second)

| Scenario | `BoundedByteBuffer` | `ByteArrayOutputStream` | `FastByteArrayOutputStream` |
|---|---|---|---|
| Default cap, bulk reads, length known | 0.13 % | 0.14 % | 0.13 % |
| Default cap, bulk reads, length unknown | 0.17 % | 0.20 % | 0.17 % |
| Default cap, byte-wise reads, length known | 2.3 % | **7.7 %** | 4.3 % |
| Default cap, byte-wise reads, length unknown | 2.0 % | **14 %** | 4.1 % |
| 256 KiB cap, bulk reads, length known | 3.1 % | 2.1 % | 2.2 % |

**Allocation per exchange (KB)**

| Scenario | `BoundedByteBuffer` | `ByteArrayOutputStream` | `FastByteArrayOutputStream` |
|---|---|---|---|
| Default cap, bulk reads, length known | 66 | 66 | 66 |
| Default cap, bulk reads, length unknown | 82 | 82 | 82, plus about 33 for the coalescing copy `toString` makes over a multi-block stream (structural, not measured: the benchmark renders presized streams) |
| Default cap, byte-wise reads, length known | 66 | 66 | 66 |
| Default cap, byte-wise reads, length unknown | 82 | 82 | 82, plus about 33 as above |
| 256 KiB cap, bulk reads, length known | 1 440 | 1 050 | 1 050 |

**Allocation rate at 500 exchanges/s (MB/s)**

| Scenario | `BoundedByteBuffer` | `ByteArrayOutputStream` | `FastByteArrayOutputStream` |
|---|---|---|---|
| Default cap, bulk reads, length known | 33 | 33 | 33 |
| Default cap, bulk reads, length unknown | 41 | 41 | 41, plus about 16 |
| Default cap, byte-wise reads, length known | 33 | 33 | 33 |
| Default cap, byte-wise reads, length unknown | 41 | 41 | 41, plus about 16 |
| 256 KiB cap, bulk reads, length known | 720 | 524 | 524 |

Reading: in bulk the three are the same instrument - the buffer's growth and rendering are the streams' growth and rendering, and the only row where a stream wins is the presized 256 KiB case, where the buffer's 64 KiB hint ceiling costs it 200 KB per body (section 4.1 says why that is bought deliberately). Byte-wise, the JDK stream's monitor per `write(int)` is the difference between 2 % and 14 % of a core at this load; Spring's stream halves that gap but does not close it (its `write(int)` checks and advances a block cursor, the buffer's a single compare and store). The read-loop shape is not exotic: `InputStream.read()` loops appear in hand-written parsers, in `Scanner`-style consumers and in any `Reader` without a `BufferedReader` in front, and the tee cannot choose the application's read shape.

### 5.2 Code effort against result

What each candidate would have needed to serve as the twins' capture (ADR-0003's requirements: a byte cap that counts beyond it, a cut-back to a mark for the blocking tee's `reset`, a sizing hint, lazy allocation for measure-only mode, and a truncated rendering that leaves a cut multi-byte sequence out):

| Requirement | `ByteArrayOutputStream` | `FastByteArrayOutputStream` | `BoundedByteBuffer` |
|---|---|---|---|
| Cap with counting beyond it | Not offered; a subclass clips in overridden `write` methods and counts in a field (~20 lines) | Not offered; the class is not final, but its writes go through private block bookkeeping - a wrapper, not a subclass, clips and counts (~25 lines) | Built in |
| Cut back to a mark | `reset()` only to zero; a subclass can assign the protected `count` (~5 lines, relying on a protected field's semantics) | `reset()` only to zero; the block list is private and `toByteArrayUnsafe` fuses it - **not achievable without reimplementing the class** | Built in (`truncate`) |
| Sizing hint | Constructor presize; a caller-side clamp to the cap and to a ceiling (~5 lines) | Constructor presize (one block); same clamp | Built in, with the 64 KiB ceiling |
| Lazy allocation | Constructor allocates 32 bytes; harmless | Constructor allocates nothing until the first write | Built in |
| Truncated rendering that leaves a cut sequence out | A subclass reaches `buf` and `count` and runs the same decoder logic (~40 lines - the buffer's `renderTruncated` verbatim) | `toByteArrayUnsafe()` gives the fused array (a copy when multi-block), then the same logic | Built in |
| Unsynchronized writes | **No** - every `write` is `synchronized`, inherited and unremovable | Yes | Yes |
| Total | A ~70-line subclass that inherits the lock and depends on protected internals | Cap and hint by wrapper, cut-back impossible, one extra copy per rendering | 270 lines with 20 tests |

Reading: a `ByteArrayOutputStream` subclass is the only candidate that meets the requirements, at roughly a quarter of the buffer's line count - and it would have paid the lock on every byte (5.1) and tied the capture to `buf`/`count` semantics of a JDK class that documents them as implementation details. The 200 lines the buffer adds over such a subclass buy the unsynchronized path, the growth policy with floor and ceiling, the range and total checks, and the tests. `FastByteArrayOutputStream` fails the cut-back requirement outright; it is built for the opposite access pattern (write once, read through `getInputStream`).

### 5.3 Memory peaks and GC pressure

| Aspect | `ByteArrayOutputStream` | `FastByteArrayOutputStream` | `BoundedByteBuffer` |
|---|---|---|---|
| Allocation per body, bulk, length unknown | 2× cap in total (doubling from 32 with a copy per step); retains 1×, the rest is garbage | 2× cap in total (blocks of doubling size, no copies) - **retains all of it**: the blocks are the data | 2× cap in total (doubling from 256 with a copy per step); retains 1× |
| Retained by 500 in-flight exchanges, 256 KiB cap, length unknown | 256 MiB | **up to 512 MiB** (each stream holds 256 + 512 + … + 128 KiB + a last block of 256 KiB, filled to the cap: 511 KiB) | 256 MiB |
| Same, length known (presized / hinted) | 256 MiB | 256 MiB (one block) | 256 MiB, allocated as 64 + 128 + 256 KiB per body (ceiling), 192 KiB of it garbage |
| Rendering, complete | 1× cap (the string; `toString(Charset)` runs the String constructor over `buf` directly) | 1× cap when one block; **2× when multi-block** (`toString` fuses the blocks into a new array first) | 1× cap |
| Rendering, truncated (naive over the array) | 2× cap (prefix string plus concatenation) | 2× cap, plus the fused array when multi-block | 2× cap (ASCII); 2.3× (two-byte text) |
| Adversarial `Content-Length` (one byte sent), 16 MiB cap, 500 in flight | Presized to the declaration: 16 GiB unless the caller clamps | Presized to the declaration: 16 GiB unless the caller clamps | 64 MiB (ceiling) |
| Humongous threshold (section 4.4) | Same as the buffer: array and strings of cap size | The default stream's blocks stay below half the cap each - **the only candidate whose growth path avoids humongous allocation** - until `toString` fuses them into one cap-sized array | Same as `ByteArrayOutputStream` |

Reading: on bytes allocated per body the three are equal; on bytes RETAINED the JDK stream and the buffer are equal and Spring's stream can hold twice as much per exchange without a presize, which at 500 in flight is the difference between 256 and 512 MiB live under a 256 KiB cap - and the retained blocks are then fused on every rendering, so the transient peak is higher too. Spring's one structural advantage, growth without humongous arrays, is lost at the rendering and only matters for caps of 512 KiB and more, where recommendation 2 applies anyway. The hint ceiling is the buffer's own, and it is the largest difference in this table: neither stream protects the caller from a declared length, and a subclass would have had to add the same clamp.

## 6. Recommendations

1. **Keep the default cap of 16 KiB** for steady-state body logging at this rate; every figure in this assessment is comfortable there.
2. **Treat caps of 256 KiB and above as debugging windows**, and size them against concurrency: retained memory is concurrency × 2 × cap, allocation is 500/s × 4 × cap, and from 512 KiB (2 GiB heap) the objects are humongous.
3. **Prefer ON_FAILURE over ALWAYS** where the log volume allows: it halves the allocation at the default cap and removes the rendering from the hot path entirely.
4. **Do not spend further effort on the render path.** The truncated ASCII rendering is within 7 % of the naive path in both time and bytes; the remaining non-ASCII allocation gap (39 % on truncated renderings only) would need a content check before the path choice and is worth doing only if GC metrics of a deployment logging truncated non-ASCII bodies show it.
5. **Leave the hint ceiling at 64 KiB.** It is the change with the largest effect on the variable that matters (retained memory under adversarial declarations), and its cost falls only on configurations recommendation 2 already discourages.
6. **Do not revisit the stream candidates.** `ByteArrayOutputStream` would cost the lock on every byte and a subclass over protected internals for the same memory; `FastByteArrayOutputStream` cannot provide the blocking twin's cut-back and retains up to twice the cap per exchange without a presize. The measured case for the byte-array design is section 5.
