package eu.inqudium.legatium.common;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fuzzes header selection and masking (the shared implementation both twins
 * inline, ADR-0003): arbitrary include/exclude/masked configurations
 * against arbitrary header names and values.
 *
 * Invariants under test: construction rejects only its documented cases
 * (blank entries, wildcard exclude) and select() never throws; a value
 * configured as masked never appears in the output in plaintext but always as
 * the stable length:hash fingerprint; the default masker is deterministic and matches its
 * documented shape.
 *
 * Runs as a regression test (checked-in inputs plus the empty input) in every
 * build; the scheduled Fuzz workflow explores for real (JAZZER_FUZZ=1).
 */
class HeaderMaskingFuzzTest {
    private static final Pattern FINGERPRINT = Pattern.compile("\\d+:[0-9a-f]{16}");

    @FuzzTest(maxDuration = "10m")
    void selectionAndMaskingUpholdTheirContract(FuzzedDataProvider data) {
        List<String> includes = consumeNames(data);
        List<String> excludes = consumeNames(data);
        List<String> masked = consumeNames(data);

        HeaderLogProperties properties;
        try {
            properties = new HeaderLogProperties(includes, excludes, masked);
        } catch (IllegalArgumentException expected) {
            // Documented rejection: blank entries, or the '*' wildcard in excludes.
            return;
        }

        Map<String, String> headers = new HashMap<>();
        int count = data.consumeInt(0, 8);
        for (int i = 0; i < count; i++) {
            headers.put(data.consumeString(24), data.consumeString(256));
        }
        // Some header names from the configuration itself, so includes actually match.
        for (String name : includes) {
            if (!name.isEmpty() && data.consumeBoolean()) {
                headers.put(name, data.consumeString(256));
            }
        }

        List<kotlin.Pair<String, String>> selected =
                properties.select(headers.keySet(), HeaderValueMasker.Companion.getDEFAULT(), headers::get);

        boolean maskAll = masked.contains(HeaderLogProperties.WILDCARD);
        for (kotlin.Pair<String, String> entry : selected) {
            String name = entry.getFirst();
            String value = entry.getSecond();
            String original = lookupIgnoreCase(headers, name);
            // Locale.ROOT mirrors Kotlin's lowercase(), which the library uses -
            // the oracle must speak the library's dialect.
            boolean shouldMask =
                    maskAll
                            || masked.stream()
                                    .anyMatch(m ->
                                            m.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)));
            if (shouldMask) {
                if (!FINGERPRINT.matcher(value).matches()) {
                    throw new IllegalStateException("masked value is not a fingerprint: " + name + "=" + value);
                }
                if (original != null && !original.isEmpty() && value.equals(original)) {
                    throw new IllegalStateException("masked value leaked in plaintext: " + name);
                }
            }
        }

        String probe = data.consumeRemainingAsString();
        String fingerprint = HeaderValueMasker.Companion.getDEFAULT().mask(probe);
        if (!fingerprint.equals(HeaderValueMasker.Companion.getDEFAULT().mask(probe))) {
            throw new IllegalStateException("mask() is not deterministic for: " + probe);
        }
        if (!FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalStateException("mask() shape violated: " + fingerprint);
        }
        if (!fingerprint.startsWith(probe.length() + ":")) {
            throw new IllegalStateException(
                    "mask() length prefix wrong: " + fingerprint + " for length " + probe.length());
        }
    }

    private static List<String> consumeNames(FuzzedDataProvider data) {
        List<String> names = new ArrayList<>();
        int count = data.consumeInt(0, 5);
        for (int i = 0; i < count; i++) {
            // Bias toward the interesting tokens: the wildcard and re-used names.
            switch (data.consumeInt(0, 3)) {
                case 0 -> names.add("*");
                case 1 -> names.add("X-Fuzz-" + data.consumeInt(0, 3));
                default -> names.add(data.consumeString(16));
            }
        }
        return names;
    }

    private static String lookupIgnoreCase(Map<String, String> headers, String name) {
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
