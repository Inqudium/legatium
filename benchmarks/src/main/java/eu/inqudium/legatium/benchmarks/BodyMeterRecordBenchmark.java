package eu.inqudium.legatium.benchmarks;

import eu.inqudium.legatium.common.ClientLoggingMetrics;
import eu.inqudium.legatium.common.ClientStack;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The cost of recording ONE body-size sample on the metrics owner, the opt-in path every measured
 * exchange takes twice (request and response side; the read-state counter is the same shape). Three
 * cases, rotating over {@code tagSets} distinct URI templates so a single hot cache entry is not
 * what gets measured:
 *
 * <ul>
 *   <li>{@link #owner}: the owner's {@code requestBodySize} - the production path, which resolves the
 *       summary once per tag set and caches it;</li>
 *   <li>{@link #registerPerCall}: the path the owner took before the cache - builder, three tags and a
 *       {@code Meter.Id} per call, resolved through Micrometer's deduplicating lookup;</li>
 *   <li>{@link #recordOnly}: {@code DistributionSummary.record} on a pre-resolved summary - the floor
 *       nothing above it can go below.</li>
 * </ul>
 *
 * <p>{@code -prof gc} is the metric that matters: the per-call allocations the cache removes are
 * short-lived and the JIT hides much of their time. The owner's cache is warmed for every tag set in
 * setup, so the measurement is the steady state a long-running host sees.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(3)
@State(Scope.Benchmark)
public class BodyMeterRecordBenchmark {

    private static final String HOST = "api.example.com";
    private static final String NAME = "things";
    private static final long BYTES = 1_024;

    /** Distinct tag sets the calls rotate over; powers of two, so the rotation is a mask. */
    @Param({"1", "16"})
    public int tagSets;

    private SimpleMeterRegistry registry;
    private ClientLoggingMetrics metrics;
    private String[] templates;
    private DistributionSummary[] resolved;
    private int next;

    @Setup
    public void setup() {
        registry = new SimpleMeterRegistry();
        metrics = ClientLoggingMetrics.Companion.forRegistry(registry, ClientStack.RESTCLIENT);
        templates = new String[tagSets];
        resolved = new DistributionSummary[tagSets];
        for (int i = 0; i < tagSets; i++) {
            templates[i] = "https://api.example.com/things/" + i + "/{id}";
            resolved[i] = summaryFor(templates[i]);
            metrics.requestBodySize(templates[i], HOST, NAME, BYTES);
        }
    }

    private int slot() {
        int i = next;
        next = (i + 1) & (tagSets - 1);
        return i;
    }

    /** What the owner did per call before the cache; the same builder chain, so the ids are identical. */
    private DistributionSummary summaryFor(String template) {
        return DistributionSummary.builder(ClientLoggingMetrics.REQUEST_BODY_SIZE_METER)
                .baseUnit("bytes")
                .description("Bytes of the body that actually flowed through the exchange")
                .tag("uri", template)
                .tag("host", HOST)
                .tag("name", NAME)
                .register(registry);
    }

    @Benchmark
    public void owner() {
        metrics.requestBodySize(templates[slot()], HOST, NAME, BYTES);
    }

    @Benchmark
    public void registerPerCall() {
        summaryFor(templates[slot()]).record(BYTES);
    }

    @Benchmark
    public void recordOnly() {
        resolved[slot()].record(BYTES);
    }
}
