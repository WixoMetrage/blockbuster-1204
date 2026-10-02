package mchorse.blockbuster.client.video;

import java.nio.ByteBuffer;

/**
 * Where a recording's pixels come from: the asynchronous PBO readback of the
 * main framebuffer in game ({@code PboFrameSource}), a synthetic generator in
 * tests.
 *
 * <p>The source may hold frames in flight (wixo.1, CDC §4.3 R6): a GPU readback
 * started for frame {@code N} is only mapped a couple of frames later, so the
 * download overlaps rendering. {@link #capture} therefore hands out whichever
 * frames are <i>ready</i>, oldest first, and {@link #flush} hands out the rest
 * at the end of the recording. Every captured frame is delivered exactly once,
 * in order.</p>
 */
public interface FrameSource
{
    /** Receives finished frames. The buffer is only valid during the call. */
    interface Consumer
    {
        /** @return false to stop the recording (the encoder failed) */
        boolean accept(ByteBuffer frame);
    }

    /** Capture the current output frame and deliver any frames now ready. */
    void capture(Consumer out);

    /** Deliver every frame still in flight (end of recording). */
    default void flush(Consumer out)
    {}

    /** Per-stage timings since the last call, for {@code video.debug}; empty when not measured. */
    default String debugStats()
    {
        return "";
    }

    /** Free the source's resources (GL objects) — render thread. */
    default void release()
    {}
}
