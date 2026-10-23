package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The cause-graph walk the by-name classifications share: which throwables it visits, and that it ends.
 * The classifications themselves are pinned by their own suites; this one pins the walk once for all of
 * them.
 */
class CauseGraphTest {
    @Test
    fun `should visit the throwable, every cause and every suppressed exception exactly once`() {
        // What is tested: the reach of the walk - the root, the cause chain, suppressed exceptions on any
        //   link, and causes of suppressed exceptions.
        // Success criteria: the predicate sees each of the five throwables once, the root first; an
        //   always-false predicate makes the walk return false.
        // Why it matters: a classification that misses a link reports a timeout or a cancellation as a
        //   plain failure, and the twins' classifications must see the same throwables.
        // Given
        val deepCause = IllegalStateException("deep")
        val suppressed = IOException("suppressed", deepCause)
        val cause = IOException("cause").apply { addSuppressed(suppressed) }
        val sibling = IOException("sibling")
        val root = RuntimeException("root", cause).apply { addSuppressed(sibling) }
        val seen = mutableListOf<Throwable>()

        // When
        val matched =
            CauseGraph.any(root) {
                seen.add(it)
                false
            }

        // Then
        assertThat(matched).isFalse()
        assertThat(seen.first()).isSameAs(root)
        assertThat(seen).containsExactlyInAnyOrder(root, cause, sibling, suppressed, deepCause)
    }

    @Test
    fun `should stop at the first match and treat null as no graph`() {
        // What is tested: the short-circuit and the null case.
        // Success criteria: a match deep in the graph returns true; null returns false without calling
        //   the predicate.
        // Why it matters: null is the "no failure" of every caller, which must never classify.
        // Given
        val root = RuntimeException("root", IOException("io", SocketTimeoutException("Read timed out")))
        val calls = AtomicInteger()

        // When/Then
        assertThat(CauseGraph.any(root) { it is SocketTimeoutException }).isTrue()
        assertThat(
            CauseGraph.any(null) {
                calls.incrementAndGet()
                true
            },
        ).isFalse()
        assertThat(calls.get()).isZero()
    }

    @Test
    fun `should terminate on a cyclic cause graph`() {
        // What is tested: the visited-set bound of the walk, for the three shapes a cycle takes - a cause
        //   pointing back along the chain, a suppressed exception pointing back to its root, and an
        //   exception suppressing itself.
        // Success criteria: each graph returns false instead of looping.
        // Why it matters: a cycle is legal in a cause graph, and the walk runs on the caller's thread.
        // Given
        val causeA = RuntimeException("a")
        val causeB = RuntimeException("b", causeA)
        causeA.initCause(causeB)
        val first = IOException("first")
        val second = IOException("second", first)
        first.addSuppressed(second)
        val selfSuppressing = RuntimeException("cycle").also { it.addSuppressed(it) }

        // When/Then
        assertThat(CauseGraph.any(causeA) { false }).isFalse()
        assertThat(CauseGraph.any(second) { false }).isFalse()
        assertThat(CauseGraph.any(selfSuppressing) { false }).isFalse()
    }

    @Test
    fun `should match a type by its own name or the name of a superclass`() {
        // What is tested: the by-name hierarchy match - the type itself, a superclass, and a type whose
        //   hierarchy carries none of the names.
        // Success criteria: true, true, false.
        // Why it matters: the classifications match engine types by name so the twins need no engine
        //   dependency; a subclass (HttpClient 4's pool timeout under its connect timeout) must match too.
        // Given
        val names = setOf("java.io.InterruptedIOException")

        // When/Then
        assertThat(CauseGraph.hasTypeNamed(InterruptedIOException::class.java, names)).isTrue()
        assertThat(CauseGraph.hasTypeNamed(SocketTimeoutException::class.java, names)).isTrue()
        assertThat(CauseGraph.hasTypeNamed(IOException::class.java, names)).isFalse()
    }

    @Test
    fun `should apply a class predicate to a type and each of its superclasses`() {
        // What is tested: the hierarchy primitive under the by-name match - a predicate on a superclass,
        //   on the type itself, and one no class of the hierarchy satisfies.
        // Success criteria: true, true, false.
        // Why it matters: a per-class rule (the blocking twin's message match) must see the same
        //   hierarchy as the by-name match, or a subclass of a matched engine type slips through.
        // Given
        val type = SocketTimeoutException::class.java

        // When/Then
        assertThat(CauseGraph.hasType(type) { it == InterruptedIOException::class.java }).isTrue()
        assertThat(CauseGraph.hasType(type) { it == type }).isTrue()
        assertThat(CauseGraph.hasType(type) { it == IllegalStateException::class.java }).isFalse()
    }
}
