package eu.inqudium.legatium.restclient.logging

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.channels.SocketChannel

/**
 * A peer whose TCP connect never completes - for provoking a connector's CONNECT timeout on loopback,
 * without a non-routable address (which a network may answer with a reset or an ICMP unreachable,
 * turning the timeout into a refusal).
 *
 * Mechanism: a listening socket with the smallest backlog that is never accepted from. Linux admits
 * `backlog + 1` established connections into the accept queue; once it is full, further SYNs are
 * DROPPED (not reset, `tcp_abort_on_overflow=0` by default), so the client's connect hangs in SYN
 * retransmit - the first retransmit comes after a second, longer than any connect timeout under test.
 * The fillers are non-blocking connects: the first ones complete into the queue, the rest hang, none
 * is waited for.
 *
 * ## Limitations
 *
 * - **Linux semantics.** Dropping a SYN on a full accept queue is Linux behaviour
 *   (`net.ipv4.tcp_abort_on_overflow=0`, the default). With that sysctl set to 1 the kernel answers
 *   with a RST instead, and the scenario ends as a refused connection - a `failure` - rather than a
 *   timeout. macOS and Windows keep their own overflow rules; the connector suites assume the Linux
 *   default, which is what CI (Ubuntu runners) and the development machines run.
 * - **Timing.** The trick holds only while the connect timeout under test is shorter than the client's
 *   first SYN retransmit (one second on Linux, `tcp_syn_retries` schedule): after that the retransmit
 *   might find room in the queue if a filler was closed meanwhile. The suites use 200 ms.
 * - **Loopback only.** Bound to the loopback address; the fillers and the connector under test must
 *   connect through the same kernel, which is what makes the queue accounting deterministic.
 * - **Capacity.** The number of established connections a backlog of 1 admits is a kernel detail
 *   (`backlog + 1` on current Linux). [FILLERS] is chosen well above it; if a future kernel admitted
 *   more, the connector's connect would complete and the test would fail visibly, not silently.
 */
internal class Tarpit : AutoCloseable {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val server = ServerSocket(0, 1, loopback)
    private val fillers =
        List(FILLERS) {
            SocketChannel.open().apply {
                configureBlocking(false)
                connect(InetSocketAddress(loopback, server.localPort))
            }
        }

    val baseUrl: String
        get() = "http://${loopback.hostAddress}:${server.localPort}"

    override fun close() {
        fillers.forEach { runCatching { it.close() } }
        server.close()
    }

    private companion object {
        /** Well above what any accept queue of backlog 1 admits. */
        const val FILLERS = 8
    }
}
