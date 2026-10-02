package mchorse.blockbuster.client.video;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * The capture output seam (P200). Everything downstream of the GL readback —
 * the ffmpeg pipe ({@code FfmpegSink}, P201), the PNG-sequence fallback
 * ({@code PngSequenceSink}, P201), and the test doubles ({@code CountingSink},
 * P206) — implements this interface, so the whole encode pipeline is unit
 * testable with no GL context and no external {@code ffmpeg} binary.
 *
 * <p>Contract: exactly one {@link #begin} before any {@link #frame}, and a
 * single {@link #end} to finalize (closing stdin is what tells ffmpeg to write
 * the container trailer). Frames arrive in the {@link VideoFormat} declared at
 * {@link #begin}, bottom-up (GL row order) — sinks that need top-down output
 * (the PNG fallback) flip rows themselves; the ffmpeg sink lets the
 * {@code vflip} filter do it.</p>
 */
public interface FrameSink extends AutoCloseable
{
    /**
     * Open the sink for a recording of the given dimensions and pixel layout.
     * The width/height are the already-even-clamped {@link VideoParams} values.
     */
    void begin(int width, int height, VideoFormat format) throws IOException;

    /**
     * Consume one full frame. {@code data} holds exactly
     * {@code width * height * format.bytesPerPixel()} bytes between its position
     * and limit; the sink must drain it fully (the buffer is recycled after the
     * call returns).
     */
    void frame(ByteBuffer data) throws IOException;

    /**
     * Finalize the output. Must be called exactly once; safe backend teardown.
     * Runs on the finalizer thread, never on the render thread (wixo.1, CDC R6),
     * and throws when the output is not a valid file (encoder exit code).
     */
    void end() throws IOException;

    /** Where the recording ends up, for the "video finished" message. */
    default String output()
    {
        return "";
    }

    /** The last lines of the encoder's own log, to show why it failed (CDC R4). */
    default java.util.List<String> diagnostic()
    {
        return java.util.Collections.emptyList();
    }

    @Override
    default void close() throws IOException
    {
        this.end();
    }
}
