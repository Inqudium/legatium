package eu.inqudium.legatium.common;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.regex.Pattern;

/**
 * Fuzzes the W3C traceparent parser of both twins - the one component
 * that parses a wire header carrying a caller-controlled value.
 *
 * Invariants under test: parse() never throws for any input; an accepted
 * result is always a well-formed (traceId, spanId) pair (lowercase hex
 * of fixed length, neither all zeros); and a structurally valid version-00
 * header built from fuzzed hex is always accepted (positive oracle), so the
 * parser cannot silently start rejecting conformant traffic.
 *
 * Runs as a regression test (checked-in inputs plus the empty input) in every
 * build; the scheduled Fuzz workflow explores for real (JAZZER_FUZZ=1).
 */
class TraceparentFuzzTest {
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");
    private static final String HEX = "0123456789abcdef";

    @FuzzTest(maxDuration = "10m")
    void parser_upholds_its_contract(FuzzedDataProvider data) {
        // What is tested: Traceparent.parse against arbitrary input - the negative oracle (never throws,
        //   accepts only well-formed lowercase-hex ids of fixed length, neither all zeros) and the positive
        //   oracle (a structurally valid version-00 header built from fuzzed hex always parses).
        // Success criteria: no exception and no verdict that contradicts either oracle for any input
        //   Jazzer generates, the checked-in corpus included.
        // Why it matters: the header is peer-controlled; a throw would break a call on the fail-open
        //   path, a false accept would join the event to a foreign trace, a false reject would drop it.
        if (data.consumeBoolean()) {
            // Positive oracle: a conformant version-00 header must parse.
            String traceId = hex(data, 32, true);
            String spanId = hex(data, 16, true);
            String flags = hex(data, 2, false);
            String header = "00-" + traceId + "-" + spanId + "-" + flags;
            kotlin.Pair<String, String> parsed = Traceparent.INSTANCE.parse(header);
            if (parsed == null) {
                throw new IllegalStateException("conformant header rejected: " + header);
            }
            if (!parsed.getFirst().equals(traceId) || !parsed.getSecond().equals(spanId)) {
                throw new IllegalStateException("ids mangled for: " + header + " -> " + parsed);
            }
            return;
        }

        String value = data.consumeBoolean() ? null : data.consumeRemainingAsString();
        kotlin.Pair<String, String> parsed = Traceparent.INSTANCE.parse(value);
        if (parsed == null) {
            return;
        }
        String traceId = parsed.getFirst();
        String spanId = parsed.getSecond();
        if (!TRACE_ID.matcher(traceId).matches() || traceId.chars().allMatch(c -> c == '0')) {
            throw new IllegalStateException("invalid traceId accepted from: " + value);
        }
        if (!SPAN_ID.matcher(spanId).matches() || spanId.chars().allMatch(c -> c == '0')) {
            throw new IllegalStateException("invalid spanId accepted from: " + value);
        }
    }

    /** Fuzz-chosen lowercase hex of the given length; nonZero forces at least one non-zero digit. */
    private static String hex(FuzzedDataProvider data, int length, boolean nonZero) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(HEX.charAt(data.consumeInt(0, 15)));
        }
        if (nonZero && sb.chars().allMatch(c -> c == '0')) {
            sb.setCharAt(data.consumeInt(0, length - 1), HEX.charAt(data.consumeInt(1, 15)));
        }
        return sb.toString();
    }
}
