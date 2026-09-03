package eu.inqudium.legatium.common

import org.springframework.core.io.ClassPathResource

/** Reader of the shared `traceparent/conformance.txt` fixture (format documented in the file). */
internal object TraceparentConformanceFixture {
    // Loaded ONCE and closed structurally: the fixture is immutable, and every earlier call left
    // the reader (and the classpath stream under it) to the GC - a file/ZIP handle per call.
    private val rows: List<List<String>> =
        ClassPathResource("traceparent/conformance.txt")
            .inputStream
            .bufferedReader()
            .use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('|') }

    /** `(header, expected trace id, expected parent/span id)` per valid line. */
    fun valid(): List<Triple<String, String, String>> = rows.filter { it[0] == "valid" }.map { Triple(it[1], it[2], it[3]) }

    fun invalid(): List<String> = rows.filter { it[0] == "invalid" }.map { it[1] }
}
