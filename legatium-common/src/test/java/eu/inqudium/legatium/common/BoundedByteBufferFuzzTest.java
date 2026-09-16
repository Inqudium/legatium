package eu.inqudium.legatium.common;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Fuzzes the shared buffer beneath both twins' captures: arbitrary interleavings of single-byte and
 * ranged writes, sizing hints and cut-backs, rendered with different charsets.
 *
 * Invariants under test: no sequence may throw; the buffered size is exactly what the cap admits of
 * what was written, whatever the hint; render() is null exactly for a zero total, never throws for
 * any byte content or charset, and announces truncation exactly when the total exceeds the size.
 *
 * Runs as a regression test (the empty input plus any checked-in inputs) in every build; the scheduled
 * Fuzz workflow explores for real (JAZZER_FUZZ=1).
 */
class BoundedByteBufferFuzzTest {
    private static final List<Charset> CHARSETS =
            List.of(StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1, StandardCharsets.UTF_16, StandardCharsets.US_ASCII);

    @FuzzTest(maxDuration = "10m")
    void buffer_upholds_its_contract(FuzzedDataProvider data) {
        // What is tested: BoundedByteBuffer under a random sequence of writes, hints and truncations
        //   with a random cap - the size model, the hint's harmlessness, and render's three outcomes.
        // Success criteria: no exception and no invariant violation for any input Jazzer generates.
        // Why it matters: every logged body of both twins comes out of this array; a wrong size after a
        //   cut-back or a throw on an odd sequence would surface inside the client's read.
        int maxBytes = data.consumeInt(0, 1 << 16);
        BoundedByteBuffer buffer = new BoundedByteBuffer(maxBytes);
        int expectedSize = 0;
        long total = 0;

        int ops = data.consumeInt(0, 64);
        for (int i = 0; i < ops && data.remainingBytes() > 0; i++) {
            switch (data.consumeInt(0, 3)) {
                case 0 -> {
                    buffer.write(data.consumeByte());
                    expectedSize = Math.min(maxBytes, expectedSize + 1);
                    total += 1;
                }
                case 1 -> {
                    byte[] bytes = data.consumeBytes(data.consumeInt(0, 4096));
                    int offset = bytes.length == 0 ? 0 : data.consumeInt(0, bytes.length - 1);
                    int length = data.consumeInt(0, bytes.length - offset);
                    buffer.write(bytes, offset, length);
                    expectedSize = Math.min(maxBytes, expectedSize + length);
                    total += length;
                }
                case 2 -> buffer.expect(data.consumeLong());
                case 3 -> {
                    int keep = data.consumeInt(0, expectedSize);
                    buffer.truncate(keep);
                    expectedSize = keep;
                    total = keep;
                }
            }
            if (buffer.getSize() != expectedSize) {
                throw new IllegalStateException("size drifted: expected " + expectedSize + ", got " + buffer.getSize());
            }
        }

        Charset charset = data.pickValue(CHARSETS);
        String rendered = buffer.render(charset, total);
        if ((rendered == null) != (total == 0)) {
            throw new IllegalStateException("null contract violated: total=" + total + ", rendered=" + rendered);
        }
        if (rendered != null && (total > expectedSize) != rendered.contains("[truncated, ")) {
            throw new IllegalStateException("truncation note wrong: total=" + total + ", size=" + expectedSize + ", rendered=" + rendered);
        }
    }
}
