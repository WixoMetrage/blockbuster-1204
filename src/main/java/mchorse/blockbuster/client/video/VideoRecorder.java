package mchorse.blockbuster.client.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * The recorder core: asks a {@link FrameSource} for one output frame at a time
 * and pushes the frames it delivers through a {@link FrameQueue} that an
 * encoder thread drains into the selected {@link FrameSink} (ffmpeg or the PNG
 * fallback).
 *
 * <p>wixo.1: the source may deliver a frame a few calls after it was captured
 * (asynchronous PBO readback, CDC §4.3 R6), so frames are counted when
 * <i>delivered</i> and {@link #stopRecording()} flushes the frames still in
 * flight before closing the sink: every captured frame is encoded exactly once,
 * in order.</p>
 */
public class VideoRecorder
{
    private static final Logger LOGGER = LoggerFactory.getLogger("blockbuster-video");

    private boolean recording;
    private boolean failed;
    private VideoParams params;
    private FrameSource source;
    private FrameSink sink;
    private FrameQueue queue;

    /** Set from inside a delivery when the pipeline must stop after the current call. */
    private boolean abort;

    private int frames;

    private final FrameSource.Consumer submit = this::submit;

    private CompletableFuture<Boolean> finalization = CompletableFuture.completedFuture(true);

    /** {@code video.debug} (CDC §2.6): log readback timings every {@value #DEBUG_EVERY} frames. */
    public boolean debug;

    private static final int DEBUG_EVERY = 120;
    private int debugCalls;
    private long debugNanos;
    private long debugWindowStart;

    public boolean isRecording()
    {
        return this.recording;
    }

    /**
     * Whether the last recording ended because the pipeline failed (sink could
     * not be opened, or the encoder thread died). Drives the premature-stop modal.
     */
    public boolean hasFailed()
    {
        return this.failed;
    }

    public VideoParams params()
    {
        return this.params;
    }

    /** Output frames handed to the sink pipeline so far. */
    public int frames()
    {
        return this.frames;
    }

    /**
     * Begin a recording.
     *
     * @param source          where each output frame's pixels come from — the GL
     *                        readback in game, a generator in tests
     * @param ffmpegAvailable result of {@link FfmpegLocator#checkAvailable}
     *                        (false ⇒ the PNG-sequence fallback is selected)
     */
    public void startRecording(VideoParams params, FrameSource source, String ffmpegPath, boolean ffmpegAvailable, boolean encoderLog)
    {
        if (this.recording)
        {
            return;
        }

        this.startRecording(params, source, SinkFactory.select(params, ffmpegPath, ffmpegAvailable, encoderLog));
    }

    /** Begin a recording into an already selected sink (the seam tests use). */
    public void startRecording(VideoParams params, FrameSource source, FrameSink sink)
    {
        if (this.recording)
        {
            return;
        }

        this.params = params;
        this.source = source;
        this.failed = false;
        this.abort = false;
        this.frames = 0;
        this.debugCalls = 0;
        this.debugNanos = 0L;
        this.debugWindowStart = 0L;

        try
        {
            this.sink = sink;
            this.sink.begin(params.width(), params.height(), params.format());
        }
        catch (IOException e)
        {
            LOGGER.error("Failed to open video sink; aborting recording", e);
            VideoMessages.error("blockbuster.video.msg.start_failed", e.getMessage());

            this.sink = null;
            this.failed = true;

            return;
        }

        this.queue = new FrameQueue(this.sink, VideoConfig.ENCODER_QUEUE_CAPACITY, params.frameByteSize());
        this.queue.start();

        this.recording = true;
    }

    /**
     * Capture one output frame. Call once per frame the capture clock marks as a
     * real output frame ({@link CaptureClock#canRender()}), from the render
     * thread — the production source issues GL calls.
     */
    public void recordFrame()
    {
        if (!this.recording)
        {
            return;
        }

        long start = this.debug ? System.nanoTime() : 0L;

        try
        {
            this.source.capture(this.submit);
        }
        catch (Exception e)
        {
            LOGGER.error("Frame capture failed", e);

            this.abort = true;
        }

        if (this.debug)
        {
            long end = System.nanoTime();

            this.debugNanos += end - start;

            if (this.debugWindowStart == 0L)
            {
                this.debugWindowStart = start;
            }

            if (++this.debugCalls == DEBUG_EVERY)
            {
                /* Total = wall time per output frame (render + capture); the
                 * difference is what the game itself spends rendering. */
                double total = (end - this.debugWindowStart) / 1e6 / DEBUG_EVERY;
                double capture = this.debugNanos / 1e6 / DEBUG_EVERY;

                LOGGER.info("[debug] {} frames delivered | total {} ms/frame ({} fps) | capture + hand-off {} ms | rest (game render) {} ms",
                    this.frames, String.format("%.1f", total), String.format("%.1f", 1000 / total),
                    String.format("%.1f", capture), String.format("%.1f", total - capture));

                String stages = this.source == null ? "" : this.source.debugStats();

                if (!stages.isEmpty())
                {
                    LOGGER.info("[debug]   inside capture: {}", stages);
                }

                this.debugCalls = 0;
                this.debugNanos = 0L;
                this.debugWindowStart = end;
            }
        }

        if (this.abort)
        {
            this.failed = true;
            this.stopRecording();
        }
    }

    private boolean submit(ByteBuffer frame)
    {
        if (this.abort)
        {
            return false;
        }

        if (frame.remaining() != this.params.frameByteSize())
        {
            /* A short frame would desync every following frame in the raw stream. */
            LOGGER.error("Frame source produced {} bytes, expected {}; stopping recording",
                frame.remaining(), this.params.frameByteSize());

            this.abort = true;

            return false;
        }

        try
        {
            if (!this.queue.submit(frame))
            {
                this.abort = true;

                return false;
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            this.abort = true;

            return false;
        }

        this.frames++;

        return true;
    }

    /**
     * Finalize the recording: deliver the frames still in flight, drain the
     * encoder queue and close the sink. Safe to call when not recording, and
     * safe to call twice (the teardown path must never throw into the editor).
     */
    public void stopRecording()
    {
        if (!this.recording)
        {
            return;
        }

        this.recording = false;

        try
        {
            if (!this.abort)
            {
                this.source.flush(this.submit);
            }
        }
        catch (Exception e)
        {
            LOGGER.error("Failed to flush the last frames", e);
            this.failed = true;
        }
        finally
        {
            this.source.release();
            this.source = null;
        }

        FrameQueue queue = this.queue;
        FrameSink sink = this.sink;
        int frames = this.frames;

        this.queue = null;
        this.sink = null;

        if (queue != null && queue.error() != null)
        {
            this.failed = true;
        }

        CompletableFuture<Boolean> done = new CompletableFuture<>();
        Thread finalizer = new Thread(() -> done.complete(finish(queue, sink, frames)), "blockbuster-video-finalizer");

        this.finalization = done;
        finalizer.start();
    }

    /**
     * The end of a recording, off the render thread (wixo.1, CDC R6): drain the
     * encoder queue, close the sink — for ffmpeg that is the container trailer,
     * which can take a while at 4K — check the result and tell the user.
     *
     * @return whether the file was written successfully
     */
    static boolean finish(FrameQueue queue, FrameSink sink, int frames)
    {
        String error = null;

        if (queue != null)
        {
            queue.stop();

            if (queue.error() != null)
            {
                error = queue.error().getMessage();
            }
        }

        if (sink != null)
        {
            try
            {
                sink.end();
            }
            catch (IOException e)
            {
                error = error == null ? e.getMessage() : error + " — " + e.getMessage();
            }
        }

        if (sink == null)
        {
            return false;
        }

        if (error == null)
        {
            LOGGER.info("Video finished: {} ({} frames)", sink.output(), frames);
            VideoMessages.success("blockbuster.video.msg.done", sink.output(), frames);

            return true;
        }

        LOGGER.error("Video failed: {} — {}", sink.output(), error);
        VideoMessages.error("blockbuster.video.msg.failed", sink.output(), error);

        for (String line : sink.diagnostic())
        {
            VideoMessages.error("blockbuster.video.msg.log_line", line);
        }

        return false;
    }

    /**
     * The finalization of the last stopped recording: completes with
     * {@code true} once the file is written, {@code false} when it failed.
     */
    public CompletableFuture<Boolean> finalization()
    {
        return this.finalization;
    }
}
