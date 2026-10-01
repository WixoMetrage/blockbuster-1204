package mchorse.blockbuster.client.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * The production {@link MinemaBackend.Capture} (S22 P234) — the piece that was
 * missing between the recording panel and the encode pipeline.
 *
 * <p>{@link MinemaBackend} always owned the P202 half (params from config, audio
 * resolution, ffmpeg probe, starting the P199 clock) but handed the GL half to a
 * {@code Capture} seam whose default was an anonymous no-op that only logged.
 * Since nothing ever replaced it, {@link VideoRecorder} was never constructed
 * and the whole pipeline behind it — {@code SinkFactory}, {@code FfmpegSink},
 * {@code PngSequenceSink}, {@code FrameQueue} — was unreachable in game: the
 * built-in recorder produced no file. This class closes that gap.</p>
 *
 * <p>Lifecycle: {@link #start} opens the sink and the encoder thread;
 * {@link #onFrame()} (bound to {@code WorldRenderEvents.LAST} by
 * {@link VideoCaptureWiring}) captures every frame the P199 clock marks as a
 * real output frame; {@link #stop} drains the queue and finalizes the
 * container.</p>
 *
 * <p><b>Self-abort.</b> When the encoder dies mid-recording the recorder stops
 * itself; this controller then also releases the fixed-timestep clock, so
 * {@code MinemaIntegration.isRecording()} flips false and the panel's per-frame
 * check raises the legacy {@code premature_stop} modal. Leaving the clock
 * running would freeze the client in fixed-timestep playback.</p>
 */
public class VideoCapture implements MinemaBackend.Capture
{
    private static final Logger LOGGER = LoggerFactory.getLogger("blockbuster-video");

    private final VideoRecorder recorder = new VideoRecorder();

    private boolean active;

    /** The recorder this controller drives (exposed for tests / debug overlays). */
    public VideoRecorder recorder()
    {
        return this.recorder;
    }

    @Override
    public void start(VideoParams params, String ffmpegPath, boolean ffmpegAvailable, boolean encoderLog)
    {
        if (this.active)
        {
            return;
        }

        this.recorder.startRecording(params, this.frameSource(params), ffmpegPath, ffmpegAvailable, encoderLog);
        this.active = this.recorder.isRecording();

        if (this.active)
        {
            LOGGER.info("Recording started: {}x{} @ {}fps -> {} (audio: {}, ffmpeg: {})",
                params.width(), params.height(), params.fps(),
                new File(params.exportDir(), params.name()), params.hasAudio(), ffmpegAvailable);
        }
        else
        {
            LOGGER.error("Recording failed to start: {}", new File(params.exportDir(), params.name()));
        }
    }

    /**
     * The capture input: always {@code MinecraftClient.getFramebuffer()}.
     *
     * <p><b>That stays true after P270 and is the point.</b> The
     * custom-resolution path reassigns {@code MinecraftClient.framebuffer} for
     * the duration of {@code GameRenderer.renderWorld}
     * ({@link CustomResolutionCapture}), and {@link #onFrame()} is driven from
     * {@code WorldRenderEvents.LAST}, which Fabric fires <i>inside</i>
     * {@code WorldRenderer.render} — i.e. inside that bracket. So the same
     * expression resolves to the capture framebuffer, at the capture size,
     * without this class knowing the feature exists. Resolving the framebuffer
     * per frame rather than caching it (see
     * {@link PboFrameSource#mainFramebuffer}) is what makes that work.</p>
     *
     * <p>The same holds for the P204 still-screenshot path
     * ({@code ScreenshotCapture.captureWorldFrame}), which reads its dimensions
     * off the framebuffer it finds: a still taken during a custom-resolution
     * recording comes out at the recording's resolution.</p>
     */
    protected FrameSource frameSource(VideoParams params)
    {
        return PboFrameSource.mainFramebuffer(params);
    }

    @Override
    public void stop()
    {
        this.active = false;

        this.recorder.stopRecording();

        LOGGER.info("Recording stopped after {} frames{}",
            this.recorder.frames(), this.recorder.hasFailed() ? " (encoder failed)" : "");
    }

    @Override
    public boolean isActive()
    {
        return this.active;
    }

    /**
     * Capture one frame if this render is an output frame. Bound to
     * {@code WorldRenderEvents.LAST} — after the world, before the HUD.
     *
     * <p><b>S21 P272.1.</b> This is the vanilla readback point and stays the
     * only one on an install without a shader pack. When a pack owns the
     * pipeline the readback moves to {@link #onWorldRenderEnd()}, because at
     * this point Iris has not yet run its composite/final chain and the main
     * colour texture holds an unshaded frame — see {@link ShaderPackVideoCompat}
     * for the bytecode trail. The gate is checked before the clock so exactly
     * one of the two seams consumes each output frame.</p>
     */
    public void onFrame()
    {
        if (!ShaderPackVideoCompat.readbackAt(ShaderPackVideoCompat.Readback.WORLD_RENDER_LAST))
        {
            return;
        }

        this.captureFrame();
    }

    /**
     * S21 <b>P272.1</b> — the shader-pack readback point:
     * {@code GameRenderer.renderWorld} RETURN, after Iris'
     * {@code finalizeLevelRendering} and before the HUD. A no-op unless a
     * shader pack is in use.
     */
    public void onWorldRenderEnd()
    {
        if (!ShaderPackVideoCompat.readbackAt(ShaderPackVideoCompat.Readback.AFTER_WORLD_RENDER))
        {
            return;
        }

        this.captureFrame();
    }

    private void captureFrame()
    {
        if (!this.active || !CaptureClock.canRender())
        {
            return;
        }

        this.recorder.recordFrame();

        if (!this.recorder.isRecording())
        {
            /* The recorder aborted itself (encoder died / source failure). Drop
             * out of fixed-timestep playback immediately; the panel sees
             * isRecording() == false next frame and shows the premature modal. */
            this.active = false;

            CaptureClock.stop();
        }
    }

    /**
     * Hard stop used on disconnect / world unload: tear the pipeline down and
     * release the render clock without going through the panel.
     */
    public void abort()
    {
        if (!this.active && !this.recorder.isRecording() && !CaptureClock.isActive()
            && !CustomResolutionCapture.isEngaged())
        {
            return;
        }

        LOGGER.warn("Aborting video recording (disconnected from world)");

        this.stop();
        CaptureClock.stop();

        /* P270: no recording panel is involved on this path, so the custom
         * resolution has to be dropped here or the world sub-framebuffers stay
         * at the capture size and every later frame renders into the wrong
         * shape. */
        CustomResolutionCapture.disengage();
    }
}
