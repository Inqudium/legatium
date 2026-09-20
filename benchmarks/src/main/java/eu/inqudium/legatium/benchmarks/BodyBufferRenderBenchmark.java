package eu.inqudium.legatium.benchmarks;

import eu.inqudium.legatium.common.BoundedByteBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.springframework.util.FastByteArrayOutputStream;

/**
 * The RENDER path: one operation = turning ONE buffered body of {@code bodyKb} into the string
 * the log line carries. The buffers are filled once in setup - a body that fits the cap - so the
 * measured work is the decoding and the string construction alone.
 *
 * <p>Five renderings. The {@link BoundedByteBuffer} complete ({@code total == size}: the
 * {@code String(bytes, charset)} branch) and truncated ({@code total == size + 1}: the prefix
 * decoded with {@code endOfInput = false} so a cut multi-byte sequence is left out, plus the
 * truncation note). {@link ByteArrayOutputStream#toString(java.nio.charset.Charset)} and
 * {@link FastByteArrayOutputStream#toString(java.nio.charset.Charset)}, the streams' own complete
 * renderings. And the NAIVE truncated rendering the buffer replaces:
 * {@code new String(bytes, 0, size, charset) + note}, which renders a cut sequence as a
 * replacement character - the boundary care is what the buffer's truncated path pays for.
 *
 * <p>{@code content}: pure ASCII (the JDK's fast paths and the compact-string case) and text of
 * one ASCII and one two-byte character alternating ("aä" - Latin1 output, but no ASCII fast
 * path). {@code -prof gc} is the metric behind the buffer's footprint claims; time is the
 * secondary signal.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(3)
@State(Scope.Benchmark)
public class BodyBufferRenderBenchmark {

    /** The two texts: pure ASCII, and one ASCII with one two-byte character alternating. */
    public enum Content {
        ASCII,
        UMLAUT
    }

    /** The shipped default cap and a debugging-profile cap. */
    @Param({"16", "256"})
    public int bodyKb;

    @Param({"ASCII", "UMLAUT"})
    public Content content;

    private byte[] body;
    private String note;
    private BoundedByteBuffer buffer;
    private ByteArrayOutputStream stream;
    private FastByteArrayOutputStream fastStream;

    @Setup
    public void setup() throws IOException {
        int length = bodyKb * 1024;
        body = content == Content.ASCII ? Bodies.ascii(length) : Bodies.cycling("aä", length);
        // Mirrors the truncation note BoundedByteBuffer.render appends, so the naive rendering
        // stays byte-comparable with the buffer's; the literal is private there and cannot be
        // imported, so a change to it must be carried here.
        note = "... [truncated, " + (body.length + 1) + " bytes total]";
        buffer = new BoundedByteBuffer(body.length);
        buffer.write(body, 0, body.length);
        stream = new ByteArrayOutputStream(body.length);
        stream.write(body, 0, body.length);
        fastStream = new FastByteArrayOutputStream(body.length);
        fastStream.write(body, 0, body.length);
    }

    @Benchmark
    public String boundedBufferComplete() {
        return buffer.render(StandardCharsets.UTF_8, body.length);
    }

    @Benchmark
    public String boundedBufferTruncated() {
        return buffer.render(StandardCharsets.UTF_8, body.length + 1L);
    }

    @Benchmark
    public String byteArrayOutputStream() {
        return stream.toString(StandardCharsets.UTF_8);
    }

    @Benchmark
    public String fastByteArrayOutputStream() {
        return fastStream.toString(StandardCharsets.UTF_8);
    }

    @Benchmark
    public String naiveTruncated() {
        return new String(body, 0, body.length, StandardCharsets.UTF_8) + note;
    }
}
