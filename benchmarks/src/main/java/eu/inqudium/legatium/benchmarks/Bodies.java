package eu.inqudium.legatium.benchmarks;

import java.nio.charset.StandardCharsets;

/**
 * The bodies the buffer benchmarks fill their inputs with - one place, so every benchmark
 * measures the same bytes.
 */
final class Bodies {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz";

    private Bodies() {}

    /** {@code length} bytes cycling through the lowercase ASCII alphabet. */
    static byte[] ascii(int length) {
        return cycling(ALPHABET, length);
    }

    /** {@code length} bytes cycling through the UTF-8 encoding of {@code pattern}. */
    static byte[] cycling(String pattern, int length) {
        byte[] encoded = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[length];
        for (int i = 0; i < length; i++) {
            body[i] = encoded[i % encoded.length];
        }
        return body;
    }
}
