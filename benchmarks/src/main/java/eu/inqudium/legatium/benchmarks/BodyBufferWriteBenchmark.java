package eu.inqudium.legatium.benchmarks;

import eu.inqudium.legatium.common.BoundedByteBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * The WRITE path of the shared body buffer against the two streams it could have been built on:
 * one operation = buffering ONE body of {@code bodyKb} into a fresh buffer, either byte by byte
 * ({@code chunk = 1}, the {@code InputStream.read()} shape of a byte-wise reader through the
 * blocking twin's tee) or in 8 KiB chunks (the bulk-read shape). The loop is the operation - one
 * body - not amplification of a smaller op.
 *
 * <p>Six buffers: the {@link BoundedByteBuffer} without and with a sizing hint (a trustworthy
 * Content-Length; the hint sizes the first array), {@link ByteArrayOutputStream} default (32) and
 * presized, {@link FastByteArrayOutputStream} default (256-byte first block) and presized. The cap
 * equals the body, so every buffer keeps every byte and the difference is the growth strategy
 * alone - plus the one structural difference the JDK stream carries: its writes are
 * {@code synchronized}. A seventh case, {@link #boundedBufferCapped16k}, writes the same body
 * into a 16 KiB cap (the shipped default): what the discard path beyond the cap costs. That case
 * measures something of its own at {@code bodyKb = 256} only - at 1 and 16 KiB the cap is not below
 * the body, and the variant is byte-identical to {@link #boundedBuffer}; those two rows are its
 * control, not a second measurement.
 *
 * <p>Every benchmark returns the BUFFER OBJECT to the blackhole, not its size: a buffer that does
 * not escape lets the JIT scalar-replace it and drop the dead stores into its array, and the
 * first run of this benchmark did exactly that for some variants (a presized JDK stream "wrote"
 * 1 KiB in 44 ns). With the object escaping, every store is real.
 *
 * <p>{@code -prof gc} is the metric that matters here: bytes allocated per body. Time is the
 * secondary signal, dominated at {@code chunk = 1} by the per-call overhead - which for the JDK
 * stream includes a monitor enter and exit per byte.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(3)
@State(Scope.Benchmark)
public class BodyBufferWriteBenchmark {

    private static final int BULK_CHUNK = 8192;
    private static final int DEFAULT_CAP = 16 * 1024;

    /** Body size; the 16 KiB point is the shipped default cap, 256 KiB a cap a debugging profile might set. */
    @Param({"1", "16", "256"})
    public int bodyKb;

    /** 1 = byte-wise writes, 8192 = bulk chunks. */
    @Param({"1", "8192"})
    public int chunk;

    private byte[] body;

    @Setup
    public void setup() {
        body = new byte[bodyKb * 1024];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) ('a' + (i % 26));
        }
    }

    @Benchmark
    public BoundedByteBuffer boundedBuffer() {
        BoundedByteBuffer buffer = new BoundedByteBuffer(body.length);
        writeBounded(buffer);
        return buffer;
    }

    @Benchmark
    public BoundedByteBuffer boundedBufferHinted() {
        BoundedByteBuffer buffer = new BoundedByteBuffer(body.length);
        buffer.expect(body.length);
        writeBounded(buffer);
        return buffer;
    }

    @Benchmark
    public BoundedByteBuffer boundedBufferCapped16k() {
        BoundedByteBuffer buffer = new BoundedByteBuffer(DEFAULT_CAP);
        writeBounded(buffer);
        return buffer;
    }

    @Benchmark
    public ByteArrayOutputStream byteArrayOutputStream() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        writeStream(stream);
        return stream;
    }

    @Benchmark
    public ByteArrayOutputStream byteArrayOutputStreamPresized() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream(body.length);
        writeStream(stream);
        return stream;
    }

    @Benchmark
    public FastByteArrayOutputStream fastByteArrayOutputStream() throws IOException {
        FastByteArrayOutputStream stream = new FastByteArrayOutputStream();
        writeFast(stream);
        return stream;
    }

    @Benchmark
    public FastByteArrayOutputStream fastByteArrayOutputStreamPresized() throws IOException {
        FastByteArrayOutputStream stream = new FastByteArrayOutputStream(body.length);
        writeFast(stream);
        return stream;
    }

    private void writeBounded(BoundedByteBuffer buffer) {
        if (chunk == 1) {
            for (byte b : body) {
                buffer.write(b);
            }
        } else {
            for (int offset = 0; offset < body.length; offset += BULK_CHUNK) {
                buffer.write(body, offset, Math.min(BULK_CHUNK, body.length - offset));
            }
        }
    }

    private void writeStream(ByteArrayOutputStream stream) {
        if (chunk == 1) {
            for (byte b : body) {
                stream.write(b);
            }
        } else {
            for (int offset = 0; offset < body.length; offset += BULK_CHUNK) {
                stream.write(body, offset, Math.min(BULK_CHUNK, body.length - offset));
            }
        }
    }

    private void writeFast(FastByteArrayOutputStream stream) throws IOException {
        if (chunk == 1) {
            for (byte b : body) {
                stream.write(b);
            }
        } else {
            for (int offset = 0; offset < body.length; offset += BULK_CHUNK) {
                stream.write(body, offset, Math.min(BULK_CHUNK, body.length - offset));
            }
        }
    }
}
