package eu.inqudium.legatium.common;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
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
 * Invariants under test: construction rejects exactly its documented cases
 * (blank entries, wildcard exclude or unmasked) - a rejection without one of
 * those triggers, or an acceptance despite one, fails; select() never throws and
 * selects exactly the included-minus-excluded names that carry a value, once
 * each; a value configured as masked and not unmasked never appears in the
 * output in plaintext but always as the stable length:hash fingerprint, and a
 * value not masked appears verbatim; the default masker is deterministic and
 * matches its documented shape.
 *
 * Runs as a regression test (checked-in inputs plus the empty input) in every
 * build; the scheduled Fuzz workflow explores for real (JAZZER_FUZZ=1).
 */
class HeaderMaskingFuzzTest {
    private static final Pattern FINGERPRINT = Pattern.compile("\\d+:[0-9a-f]{16}");

    @FuzzTest(maxDuration = "10m")
    void selection_and_masking_uphold_their_contract(FuzzedDataProvider data) {
        // What is tested: HeaderLogProperties construction and select() plus the default masker against
        //   arbitrary name lists and header maps - the documented rejection cases exactly, no throw from
        //   select(), include-minus-exclude once per name, masked values only as the fingerprint.
        // Success criteria: no exception and no oracle violation for any input Jazzer generates - a
        //   masked value in plaintext or a rejection outside the documented cases fails the run.
        // Why it matters: header names and values are peer- and operator-controlled input on every
        //   exchange; a plaintext leak through an unforeseen name shape is a secret in the logs.
        List<String> includes = consumeNames(data);
        List<String> excludes = consumeNames(data);
        List<String> masked = consumeNames(data);
        List<String> unmasked = consumeNames(data);

        // The documented rejection, decided ONCE up front: blank entries, or the '*' wildcard in excludes or
        // unmasked. The constructor must reject exactly these inputs - no others, and none of these silently.
        boolean documentedRejection =
                hasBlank(includes)
                        || hasBlank(excludes)
                        || hasBlank(masked)
                        || hasBlank(unmasked)
                        || excludes.contains(HeaderLogProperties.WILDCARD)
                        || unmasked.contains(HeaderLogProperties.WILDCARD);
        HeaderLogProperties properties;
        try {
            properties = new HeaderLogProperties(includes, excludes, masked, unmasked);
        } catch (IllegalArgumentException rejected) {
            if (!documentedRejection) {
                throw new IllegalStateException("undocumented rejection: " + rejected.getMessage(), rejected);
            }
            return;
        }
        if (documentedRejection) {
            throw new IllegalStateException("documented rejection not applied for " + includes + excludes + masked + unmasked);
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
                properties.select(
                        headers.keySet(),
                        HeaderValueMasker.DEFAULT,
                        name -> lookupIgnoreCase(headers, name)); // case-insensitive, like HttpHeaders

        boolean maskAll = masked.contains(HeaderLogProperties.WILDCARD);
        // Selection oracle: exactly the included-minus-excluded names that carry a value, once each.
        Set<String> expectedNames = new HashSet<>();
        Collection<String> candidates =
                includes.contains(HeaderLogProperties.WILDCARD) ? headers.keySet() : includes;
        for (String name : candidates) {
            String lower = name.toLowerCase(Locale.ROOT);
            boolean excluded = excludes.stream().anyMatch(e -> e.toLowerCase(Locale.ROOT).equals(lower));
            if (!excluded && lookupIgnoreCase(headers, name) != null) {
                expectedNames.add(lower);
            }
        }
        if (includes.isEmpty() && !selected.isEmpty()) {
            throw new IllegalStateException("empty includes selected " + selected);
        }
        Set<String> selectedNames = new HashSet<>();
        for (kotlin.Pair<String, String> entry : selected) {
            if (!selectedNames.add(entry.getFirst().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("header selected twice: " + entry.getFirst());
            }
        }
        if (!selectedNames.equals(expectedNames)) {
            throw new IllegalStateException("selection mismatch: expected " + expectedNames + ", got " + selectedNames);
        }
        for (kotlin.Pair<String, String> entry : selected) {
            String name = entry.getFirst();
            String value = entry.getSecond();
            String original = lookupIgnoreCase(headers, name);
            // Locale.ROOT mirrors Kotlin's lowercase(), which the library uses -
            // the oracle must speak the library's dialect.
            boolean unmaskedName =
                    unmasked.stream()
                            .anyMatch(u -> u.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)));
            boolean shouldMask =
                    !unmaskedName
                            && (maskAll
                                    || masked.stream()
                                            .anyMatch(m ->
                                                    m.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))));
            if (shouldMask) {
                if (!FINGERPRINT.matcher(value).matches()) {
                    throw new IllegalStateException("masked value is not a fingerprint: " + name + "=" + value);
                }
                if (original != null && !original.isEmpty() && value.equals(original)) {
                    throw new IllegalStateException("masked value leaked in plaintext: " + name);
                }
            } else if (!value.equals(original)) {
                // Plaintext oracle: an unmasked (or not-masked) name renders its original value verbatim.
                throw new IllegalStateException("plaintext value altered: " + name + "=" + value + " (original " + original + ")");
            }
        }

        String probe = data.consumeRemainingAsString();
        String fingerprint = HeaderValueMasker.DEFAULT.mask(probe);
        if (!fingerprint.equals(HeaderValueMasker.DEFAULT.mask(probe))) {
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

    /**
     * Blank as the production code means it: Kotlin's {@code isBlank()} treats a char as whitespace when
     * {@link Character#isWhitespace(int)} OR {@link Character#isSpaceChar(int)} says so, which covers the
     * space separators Java's {@link String#isBlank()} leaves out (U+00A0, U+2007, U+202F). The nightly
     * fuzz run of 2026-09-05 found exactly that gap: a name of such spaces was rejected as blank by the
     * constructor while this oracle, then on {@code String::isBlank}, had not predicted the rejection
     * (regression input {@code blank-unicode-space-name.bin}).
     */
    private static boolean hasBlank(List<String> names) {
        return names.stream().anyMatch(name -> name.chars().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)));
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
