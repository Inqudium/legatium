package eu.inqudium.legatium.restclient.logging;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Fuzzes the bounded tee target both twins rest on (the blocking variant; the
 * reactive one adds a lock and a freeze): arbitrary interleavings of
 * single-byte and array captures and read-state marks, decoded with
 * different charsets.
 *
 * Invariants under test: no capture sequence may throw; the total byte count
 * is exact; loggedValue() is null exactly for a zero-byte body, never throws
 * for any byte content or charset, and announces truncation whenever more
 * bytes flowed than the capture limit holds.
 *
 * Runs as a regression test (checked-in inputs plus the empty input) in every
 * build; the scheduled Fuzz workflow explores for real (JAZZER_FUZZ=1).
 */
class BoundedBodyCaptureFuzzTest {
    private static final Charset[] CHARSETS = {
        StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1, StandardCharsets.UTF_16, StandardCharsets.US_ASCII,
    };

    @FuzzTest(maxDuration = "10m")
    void capture_upholds_its_contract(FuzzedDataProvider data) {
        // What is tested: BoundedBodyCapture under a random sequence of single-byte, array and ranged
        //   writes with a random limit and charset - exact byte total, null loggedValue() only for a
        //   zero-byte body, truncation announced exactly when more bytes flowed than the limit holds.
        // Success criteria: no exception and no invariant violation for any input Jazzer generates,
        //   whatever the byte content and charset.
        // Why it matters: the capture sees every body byte of every exchange; a throw on an odd byte
        //   sequence would surface inside the client's read, a wrong count would corrupt the size meter.
        int maxBytes = data.consumeInt(0, 1 << 16);
        BoundedBodyCapture capture = new BoundedBodyCapture(maxBytes);
        long expectedTotal = 0;

        int ops = data.consumeInt(0, 64);
        for (int i = 0; i < ops && data.remainingBytes() > 0; i++) {
            switch (data.consumeInt(0, 3)) {
                case 0 -> {
                    capture.capture(data.consumeByte());
                    expectedTotal += 1;
                }
                case 1 -> {
                    byte[] bytes = data.consumeBytes(data.consumeInt(0, 4096));
                    // The wrapper contract guarantees a valid range; fuzz within it.
                    int offset = bytes.length == 0 ? 0 : data.consumeInt(0, bytes.length - 1);
                    int length = data.consumeInt(0, bytes.length - offset);
                    capture.capture(bytes, offset, length);
                    expectedTotal += length;
                }
                case 2 -> capture.markStarted();
                case 3 -> capture.markCompleted();
            }
        }

        if (capture.getTotalBytes() != expectedTotal) {
            throw new IllegalStateException(
                    "totalBytes drifted: expected " + expectedTotal + ", got " + capture.getTotalBytes());
        }
        Charset charset = data.pickValue(CHARSETS);
        String logged = capture.loggedValue(charset);
        if ((logged == null) != (expectedTotal == 0)) {
            throw new IllegalStateException(
                    "null contract violated: totalBytes=" + expectedTotal + ", logged=" + logged);
        }
        if (logged != null && expectedTotal > maxBytes && !logged.contains("[truncated, ")) {
            throw new IllegalStateException(
                    "missing truncation note: totalBytes=" + expectedTotal + ", maxBytes=" + maxBytes);
        }
    }
}
