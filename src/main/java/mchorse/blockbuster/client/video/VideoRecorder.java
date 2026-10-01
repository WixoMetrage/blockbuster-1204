package mchorse.blockbuster.client.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;

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

        try
        {
            this.sink = sink;
            this.sink.begin(params.width(), params.height(), params.format());
        }
        catch (IOException e)
        {
            LOGGER.error("Failed to open video sink; aborting recording", e);

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

        try
        {
            this.source.capture(this.submit);
        }
        catch (Exception e)
        {
            LOGGER.error("Frame capture failed", e);

            this.abort = true;
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

        if (this.queue != null)
        {
            this.queue.stop();

            if (this.queue.error() != null)
            {
                this.failed = true;
            }

            this.queue = null;
        }

        try
        {
            if (this.sink != null)
            {
                this.sink.end();
            }
        }
        catch (IOException e)
        {
            LOGGER.warn("Failed to finalize video sink", e);

            this.failed = true;
        }
        finally
        {
            this.sink = null;
        }
    }
}
