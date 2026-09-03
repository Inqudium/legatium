package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CountingCorrelationIdGeneratorTest {
    private companion object {
        /** 36^8 - the number of counter values the production counter width can render. */
        private const val COUNTER_CAPACITY = 2_821_109_907_456L
    }

    @Nested
    inner class `Id format` {
        @Test
        fun `should render an id of exactly twenty-one lowercase alphanumeric characters`() {
            // Given
            val generator = CountingCorrelationIdGenerator(prefixSeed = 1L)

            // When
            val id = generator.nextCorrelationId()

            // Then
            assertThat(id).hasSize(21)
            assertThat(id).matches("[0-9a-z]{21}")
        }

        @Test
        fun `should pad a small seed to the full prefix width`() {
            // Given
            val generator = CountingCorrelationIdGenerator(prefixSeed = 1L)

            // When
            val id = generator.nextCorrelationId()

            // Then
            assertThat(id).startsWith("0000000000001")
        }

        @Test
        fun `should render seed 35 as the last single-digit value of base 36`() {
            // Given: 35 is the largest value that still occupies a single base-36 digit,
            // so this pins the digit alphabet at its upper end.
            val generator = CountingCorrelationIdGenerator(prefixSeed = 35L)

            // When
            val id = generator.nextCorrelationId()

            // Then
            assertThat(id).startsWith("000000000000z")
        }

        @Test
        fun `should render seed 36 as a carry into the second digit`() {
            // Given
            val generator = CountingCorrelationIdGenerator(prefixSeed = 36L)

            // When
            val id = generator.nextCorrelationId()

            // Then
            assertThat(id).startsWith("0000000000010")
        }

        @Test
        fun `should render a negative seed as an unsigned value without a sign character`() {
            // What is tested: that a negative seed is reinterpreted as an unsigned value rather than
            //   rendered with a minus sign.
            // Success criteria: the prefix still occupies exactly 13 characters drawn from the base-36
            //   alphabet and carries no sign character. The exact rendering is not asserted, because
            //   re-deriving it in the test would only duplicate the production code.
            // Why it matters: half of all values a random source produces are negative. Using `toString`
            //   instead of `toUnsignedString` would put a `-` into every second id, silently changing both
            //   the id length and the character set that downstream consumers see.
            // Given
            val generator = CountingCorrelationIdGenerator(prefixSeed = -1L)

            // When
            val id = generator.nextCorrelationId()

            // Then
            assertThat(id).hasSize(21)
            assertThat(id).doesNotContain("-")
            assertThat(id.take(13)).matches("[0-9a-z]{13}")
        }

        @Test
        fun `should produce a well-formed id when constructed without an explicit seed`() {
            // Given: the production path, which draws its seed from SecureRandom.
            val generator = CountingCorrelationIdGenerator()

            // When
            val id = generator.nextCorrelationId()

            // Then
            assertThat(id).matches("[0-9a-z]{21}")
        }
    }

    @Nested
    inner class `Counter behaviour` {
        @Test
        fun `should start the counter at zero`() {
            // Given
            val generator = CountingCorrelationIdGenerator(prefixSeed = 0L)

            // When
            val id = generator.nextCorrelationId()

            // Then
            assertThat(id).isEqualTo("0000000000000" + "00000000")
        }

        @Test
        fun `should increment the counter by one on every call`() {
            // Given
            val generator = CountingCorrelationIdGenerator(prefixSeed = 0L)

            // When
            val ids = (1..3).map { generator.nextCorrelationId() }

            // Then
            assertThat(ids).containsExactly(
                "000000000000000000000",
                "000000000000000000001",
                "000000000000000000002",
            )
        }

        @Test
        fun `should keep the counter width constant across a base-36 carry`() {
            // What is tested: that the counter keeps its fixed width across a base-36 carry.
            // Success criteria: call 36 renders as `...0000000z` and call 37 as `...00000010` - both eight
            //   characters wide.
            // Why it matters: this is the exact point where a missing `padStart` would first show up. Up to
            //   value 35 the counter happens to be one character wide either way, so a test that only
            //   checks the first few ids would pass against a broken implementation.
            // Given
            val generator = CountingCorrelationIdGenerator(prefixSeed = 0L)

            // When
            val ids = (1..37).map { generator.nextCorrelationId() }

            // Then
            assertThat(ids[35]).endsWith("0000000z")
            assertThat(ids[36]).endsWith("00000010")
            assertThat(ids).allSatisfy { assertThat(it).hasSize(21) }
        }

        @Test
        fun `should keep the counter width constant across a second base-36 carry`() {
            // Given: 1296 is 36^2, the next carry after the one covered above.
            val generator = CountingCorrelationIdGenerator(prefixSeed = 0L)
            repeat(1295) { generator.nextCorrelationId() }

            // When
            val atMaxTwoDigits = generator.nextCorrelationId()
            val afterCarry = generator.nextCorrelationId()

            // Then
            assertThat(atMaxTwoDigits).endsWith("000000zz")
            assertThat(afterCarry).endsWith("00000100")
        }

        @Test
        fun `should keep the fixed width up to the last counter value the width can hold`() {
            // What is tested: the 21-character contract at its real boundary - the last value the
            //   counter width can render - reached through the internal counterStart seam instead of
            //   2.8e12 warm-up calls.
            // Success criteria: the id at counter 36^8 - 1 still has exactly 21 characters and ends in
            //   eight `z`; the very next id grows to 22 characters, pinning the documented, deliberately
            //   unguarded overflow behavior (padStart silently stops applying).
            // Why it matters: beyond the width both load-bearing format properties - the fixed split
            //   point and the lexicographic ordering - break without any signal. This test is the
            //   executable guard the production KDoc points to: narrowing a width constant fails here
            //   instead of in production.
            // Given: a counter one step before the width boundary
            val generator = CountingCorrelationIdGenerator(prefixSeed = 0L, counterStart = COUNTER_CAPACITY - 1)

            // When
            val lastInWidth = generator.nextCorrelationId()
            val firstBeyond = generator.nextCorrelationId()

            // Then: the last in-width id honors the contract; the next one is the documented breakage
            assertThat(lastInWidth).hasSize(21)
            assertThat(lastInWidth).endsWith("zzzzzzzz")
            assertThat(firstBeyond).hasSize(22)
        }

        @Test
        fun `should produce a reproducible sequence for a given seed`() {
            // Given: the seed is the injection point that makes the generator testable
            // without any mocking library.
            val first = CountingCorrelationIdGenerator(prefixSeed = 4711L)
            val second = CountingCorrelationIdGenerator(prefixSeed = 4711L)

            // When
            val fromFirst = (1..5).map { first.nextCorrelationId() }
            val fromSecond = (1..5).map { second.nextCorrelationId() }

            // Then
            assertThat(fromFirst).isEqualTo(fromSecond)
        }

        @Test
        fun `should use different prefixes for different seeds`() {
            // Given
            val first = CountingCorrelationIdGenerator(prefixSeed = 1L)
            val second = CountingCorrelationIdGenerator(prefixSeed = 2L)

            // When
            val fromFirst = first.nextCorrelationId()
            val fromSecond = second.nextCorrelationId()

            // Then
            assertThat(fromFirst.take(13)).isNotEqualTo(fromSecond.take(13))
        }
    }

    @Nested
    inner class `Lexicographic ordering` {
        @Test
        fun `should hand out ids that sort in allocation order`() {
            // What is tested: that ids handed out by one instance sort lexicographically in the order they
            //   were allocated.
            // Success criteria: sorting the generated sequence leaves it unchanged. The range deliberately
            //   spans the carry at 36, which is where a width regression would break the ordering first.
            // Why it matters: this property is what makes the id usable as a tiebreaker when log entries
            //   share a timestamp. It is not enforced by any type - it rests entirely on the fixed field
            //   widths, and a change to those constants would drop it silently.
            // Given
            val generator = CountingCorrelationIdGenerator(prefixSeed = 123456789L)

            // When
            val ids = (1..200).map { generator.nextCorrelationId() }

            // Then
            assertThat(ids).isSorted
        }

        @Test
        fun `should hand out ids that sort in allocation order across a carry`() {
            // Given
            val generator = CountingCorrelationIdGenerator(prefixSeed = 0L)
            repeat(1290) { generator.nextCorrelationId() }

            // When
            val ids = (1..12).map { generator.nextCorrelationId() }

            // Then
            assertThat(ids).isSorted
        }
    }

    @Nested
    inner class `Thread safety` {
        @Test
        fun `should hand out distinct ids under concurrent access`() {
            // What is tested: that concurrent calls never hand out the same id twice.
            // Success criteria: the number of distinct ids equals the number of calls. Since uniqueness
            //   within an instance is a guarantee rather than a probability, any duplicate is a hard
            //   failure, not a flake.
            // Why it matters: the counter is the one piece of mutable shared state in the class. Replacing
            //   the AtomicLong with a plain Long - or, more plausibly, with a ThreadLocal in an attempt to
            //   avoid contention - would produce duplicates here.
            // Given
            val threads = 16
            val idsPerThread = 2_000
            val generator = CountingCorrelationIdGenerator(prefixSeed = 0L)
            val ids = ConcurrentHashMap.newKeySet<String>()
            val startSignal = CountDownLatch(1)
            val done = CountDownLatch(threads)
            val pool = Executors.newFixedThreadPool(threads)

            // When
            repeat(threads) {
                pool.submit {
                    startSignal.await()
                    repeat(idsPerThread) { ids.add(generator.nextCorrelationId()) }
                    done.countDown()
                }
            }
            startSignal.countDown()
            val finished = done.await(30, TimeUnit.SECONDS)
            pool.shutdownNow()

            // Then
            assertThat(finished).isTrue()
            assertThat(ids).hasSize(threads * idsPerThread)
        }

        @Test
        fun `should keep the id format intact under concurrent access`() {
            // Given
            val threads = 8
            val generator = CountingCorrelationIdGenerator(prefixSeed = 0L)
            val ids = ConcurrentHashMap.newKeySet<String>()
            val done = CountDownLatch(threads)
            val pool = Executors.newFixedThreadPool(threads)

            // When
            repeat(threads) {
                pool.submit {
                    repeat(500) { ids.add(generator.nextCorrelationId()) }
                    done.countDown()
                }
            }
            val finished = done.await(30, TimeUnit.SECONDS)
            pool.shutdownNow()

            // Then
            assertThat(finished).isTrue()
            assertThat(ids).allSatisfy { assertThat(it).matches("[0-9a-z]{21}") }
        }
    }
}
