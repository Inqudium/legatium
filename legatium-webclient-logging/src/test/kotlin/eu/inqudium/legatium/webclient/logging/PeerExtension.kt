package eu.inqudium.legatium.webclient.logging

import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestInstancePostProcessor

/**
 * One [PeerServer] per test class, handed to every instance of that class: [beforeAll] starts the peer
 * and keeps it in the class-level [ExtensionContext.Store], which closes it - the peer is `AutoCloseable`
 * - after the class's last test; [postProcessTestInstance] sets it on each freshly built
 * [IntegrationFixture].
 *
 * This is what lets the fixture stay on JUnit's default per-method lifecycle: the peer is a per-class
 * resource, but Boot's `WebClient.Builder` is a prototype bean and must be injected afresh for every
 * test - a `PER_CLASS` instance held one builder for the whole suite, and the scenario that puts a
 * 200 ms connector on it left that connector on every later test's client.
 */
internal class PeerExtension :
    BeforeAllCallback,
    TestInstancePostProcessor {
    override fun beforeAll(context: ExtensionContext) {
        context.getStore(NAMESPACE).computeIfAbsent(PeerServer::class.java, { PeerServer() }, PeerServer::class.java)
    }

    override fun postProcessTestInstance(
        testInstance: Any,
        context: ExtensionContext,
    ) {
        // The method-level store falls back to the class-level one where beforeAll put the peer.
        val peer = requireNotNull(context.getStore(NAMESPACE).get(PeerServer::class.java, PeerServer::class.java)) { "no peer started for ${context.requiredTestClass}" }
        (testInstance as IntegrationFixture).peer = peer
    }

    private companion object {
        val NAMESPACE: ExtensionContext.Namespace = ExtensionContext.Namespace.create(PeerExtension::class.java)
    }
}
