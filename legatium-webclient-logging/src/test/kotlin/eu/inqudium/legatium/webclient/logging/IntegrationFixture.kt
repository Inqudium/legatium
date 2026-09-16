package eu.inqudium.legatium.webclient.logging

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * What every `@SpringBootTest` suite of the module shares: Boot's auto-configured builder, one
 * [PeerServer] per test class, and per test the production exchange logger raised to INFO with an
 * [AwaitingAppender] attached (through [log]) and the peer's record cleared. A subclass adds the
 * `@SpringBootTest` annotation with its own properties and its own scenarios.
 */
abstract class IntegrationFixture {
    @Autowired
    internal lateinit var webClientBuilder: WebClient.Builder

    /**
     * The production logger of this test, captured at INFO. Attached in `@BeforeEach`, not in an
     * initializer: the suite's Spring context starts AFTER the instance is built, and Boot's logging
     * initialisation on that start leaves the logger without an appender attached earlier - the first
     * test of a suite would see no events.
     */
    internal lateinit var log: AwaitingLogger

    @BeforeEach
    fun attachLogAndClearPeer() {
        log = AwaitingLogger()
        peer.received.clear()
    }

    @AfterEach
    fun detachLog() {
        log.detach()
    }

    companion object {
        /** The timeout where the timeout is the subject - well under [PeerServer.SLOW_ROUTE_DELAY]. */
        internal val SHORT: Duration = Duration.ofMillis(200)

        /** The foreign party of the suite, started once per test class and stopped after its last test. */
        internal lateinit var peer: PeerServer

        @JvmStatic
        @BeforeAll
        fun startPeer() {
            peer = PeerServer()
        }

        @JvmStatic
        @AfterAll
        fun stopPeer() {
            peer.close()
        }
    }
}
