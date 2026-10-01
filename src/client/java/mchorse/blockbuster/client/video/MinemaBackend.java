package mchorse.blockbuster.client.video;

import mchorse.aperture.camera.minema.MinemaIntegration;
import mchorse.aperture.camera.minema.RecordingFilename;
import mchorse.blockbuster.Blockbuster;
import mchorse.blockbuster.utils.BlockbusterPaths;
import mchorse.mclib.client.gui.utils.GuiUtils;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.function.Supplier;

/**
 * The client-side {@link MinemaIntegration.Backend} (S18 P202): translates the
 * recording panel's start/stop into the built-in recorder pipeline. Owns the
 * P202 half of recording — building the {@link VideoParams} from the
 * {@code video} config, resolving the scene audio track for muxing, probing
 * {@code ffmpeg}, and driving the P199 {@link CaptureClock} — and hands the pure
 * GL half (framebuffer swap + PBO readback via {@link VideoRecorder}) to the
 * {@link Capture} seam that P200/P203 install.
 *
 * <p>Installed from {@code ApertureClient} via {@link #install()}; before that
 * (headless / dedicated server) the {@link MinemaIntegration} facade keeps its
 * safe no-op backend.</p>
 *
 * <p><b>Audio muxing (P202):</b> {@link #audioResolver} is the seam the S16 audio
 * timeline / S11 scene playback installs to return the scene's {@code .wav} when
 * one is attached and {@code video.audio} is on. When it returns a file,
 * {@link VideoParams#hasAudio()} is true and {@code SinkFactory} selects the
 * audio ffmpeg template ({@code %AUDIO_TRACK%}). v1 only supports
 * <b>start-aligned</b> audio; a non-zero audio shift (S16 {@code PacketAudioShift})
 * is not applied — logged as a limitation.</p>
 *
 * Legacy behavior parity: the legacy panel handed a filename to Minema which
 * applied its own timestamp when empty; here the backend applies the identical
 * {@code yyyy-MM-dd_HH.mm.ss} timestamp so the encoded file matches the panel's
 * ghost text.
 */
public class MinemaBackend implements MinemaIntegration.Backend
{
    private static final Logger LOGGER = LoggerFactory.getLogger("blockbuster-video");

    /**
     * Scene audio resolver (P202 muxing seam). Returns the {@code .wav} to mux, or
     * {@code null} for silent video. Installed by
     * {@link VideoCaptureWiring#install()} (S22 P234) from the client's
     * {@link SceneAudioTracker}, which follows the S11/S16 scene audio packets;
     * the headless default is no audio.
     */
    public static Supplier<File> audioResolver = () -> null;

    /**
     * The GL capture controller (P200/P203). Owns the framebuffer swap, the color
     * texture the recorder reads back, and the per-frame {@code recordFrame()}
     * binding. The default is a no-op that only tracks active state so the P202
     * start/stop coupling works headlessly and in-game before P200/P203 wire the
     * real readback.
     */
    public interface Capture
    {
        void start(VideoParams params, String ffmpegPath, boolean ffmpegAvailable, boolean encoderLog);

        void stop();

        boolean isActive();
    }

    /**
     * The headless / pre-install default: tracks active state only, so the P202
     * start/stop coupling works without a GL context. The live client replaces
     * it with {@link VideoCapture} in {@link VideoCaptureWiring#install()}
     * (S22 P234) — until that landed, this no-op was the reason the built-in
     * recorder produced no file.
     */
    public static Capture capture = new Capture()
    {
        private boolean active;

        @Override
        public void start(VideoParams params, String ffmpegPath, boolean ffmpegAvailable, boolean encoderLog)
        {
            this.active = true;

            LOGGER.info("Recording started: {}x{} @ {}fps -> {} (audio: {})",
                params.width(), params.height(), params.fps(),
                new File(params.exportDir(), params.name()), params.hasAudio());
        }

        @Override
        public void stop()
        {
            this.active = false;

            LOGGER.info("Recording stopped");
        }

        @Override
        public boolean isActive()
        {
            return this.active;
        }
    };

    private String name = "";

    /** Install this backend into the {@link MinemaIntegration} facade. */
    public static void install()
    {
        MinemaIntegration.backend = new MinemaBackend();
    }

    @Override
    public boolean isRecording()
    {
        return capture.isActive();
    }

    @Override
    public void toggleRecording(boolean state) throws Exception
    {
        if (state)
        {
            if (this.isRecording())
            {
                return;
            }

            this.start();
        }
        else
        {
            /* S22 P234: stop is NOT symmetrical with start. A recording whose
             * sink failed to open leaves the capture inactive while the P199
             * clock is already running; an `isRecording()`-only guard would skip
             * the stop and strand the client in fixed-timestep playback. Stop
             * whenever either half is live. */
            if (!this.isRecording() && !CaptureClock.isActive())
            {
                return;
            }

            this.stop();
        }
    }

    private void start()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        int windowWidth = mc.getWindow().getFramebufferWidth();
        int windowHeight = mc.getWindow().getFramebufferHeight();

        CaptureResolution.Decision size = resolveSize(windowWidth, windowHeight);

        VideoParams params = buildParams(this.name, this.resolveAudio(), size.width(), size.height());

        String ffmpegPath = FfmpegLocator.resolve(VideoConfig.ffmpegPath());
        boolean available = FfmpegLocator.checkAvailable(ffmpegPath);

        reportStart(params, size, windowWidth, windowHeight, ffmpegPath, available);

        params.exportDir().mkdirs();

        /* S21 P272.1: tell the user what a shader pack costs this take BEFORE
         * the take, rather than handing them a broken file afterwards. Silent on
         * every install without a pack. */
        ShaderPackVideoCompat.reportAtRecordStart(
            params.format().hasAlpha(), Blockbuster.chromaSky.get());

        /* P199 fixed-timestep clock takes over the render clock. */
        CaptureClock.start(params.fps(), params.heldFrames(), params.motionBlur());

        capture.start(params, ffmpegPath, available, VideoConfig.encoderLog());
    }

    private void stop()
    {
        capture.stop();
        CaptureClock.stop();

        /* P270: drop the custom-resolution swap and put the six world
         * sub-framebuffers back at the window size. Unconditional — disengage()
         * is a no-op when the recording was at window size. */
        CustomResolutionCapture.disengage();
    }

    @Override
    public void setName(String name)
    {
        this.name = name == null ? "" : name;
    }

    @Override
    public void openMovies()
    {
        File dir = moviesDir();

        dir.mkdirs();
        GuiUtils.openFolder(dir.getAbsolutePath());
    }

    private File resolveAudio()
    {
        try
        {
            return audioResolver.get();
        }
        catch (Exception e)
        {
            LOGGER.warn("Audio resolver failed; recording without audio", e);

            return null;
        }
    }

    /**
     * The export directory: {@code video.export_path} when the user set one,
     * otherwise {@code config/blockbuster/movies}.
     *
     * <p>S18 open question 2 ("config-side {@code config/blockbuster/movies} vs.
     * a game-dir {@code movies/} like Minema") is <b>resolved config-side</b>
     * (S22/P250): the folder then travels with the rest of the Blockbuster data
     * (models, records, scenes, audio) and survives world switches, which is
     * what every other Blockbuster output path already does. Users who want the
     * Minema layout set {@code video.export_path} — that key exists precisely so
     * this default is not a lock-in.</p>
     */
    public static File moviesDir()
    {
        String path = VideoConfig.exportPath();

        if (path != null && !path.trim().isEmpty())
        {
            return new File(path.trim());
        }

        return BlockbusterPaths.configRoot().resolve("movies").toFile();
    }

    /**
     * Resolve the recording's pixel size against the window's framebuffer, and
     * engage the custom-resolution render path when the two differ.
     *
     * <p><b>S22 P270 closed {@code SEAM(P200-window)}.</b> Until P270 this method
     * clamped every non-window request to the window with a warning, because
     * {@link CaptureFramebuffer} was built but not installed and swapping the
     * client framebuffer alone would have letterboxed every frame. The swap, the
     * {@code WindowMixin} size override and the six sub-framebuffer resizes now
     * land together ({@link CustomResolutionCapture}), so the request is
     * honoured — and the clamp survives only as the fallback for the cases that
     * genuinely cannot work (an Iris shader pack, Fabulous graphics, a size the
     * driver refuses), each with a warning naming both sizes.</p>
     *
     * <p>The size arithmetic itself is {@link CaptureResolution} — pure, and
     * reproducing Minema's {@code getFrameWidth()}/{@code useFrameSize()}
     * verbatim. Engagement has to happen <i>here</i>, before
     * {@link #buildParams}, because the encoder is told the frame size once at
     * start and cannot renegotiate it: a size that fails to allocate must fall
     * back before {@link VideoParams} exists, not after.</p>
     */
    public static CaptureResolution.Decision resolveSize(int windowWidth, int windowHeight)
    {
        CaptureResolution.Decision decision = CaptureResolution.resolve(
            VideoConfig.width(), VideoConfig.height(), windowWidth, windowHeight,
            CustomResolutionCapture.blocker());

        if (decision.custom() && !CustomResolutionCapture.engage(decision.width(), decision.height()))
        {
            decision = decision.scaled("blockbuster.video.reason.driver");
        }

        LOGGER.info("Capture resolution {}x{} (window {}x{}, mode {}{})",
            decision.width(), decision.height(), windowWidth, windowHeight, decision.mode(),
            decision.reason() == null ? "" : ", reason " + decision.reason());

        return decision;
    }

    /**
     * The in-game report of a recording that is starting (CDC R4): one white
     * line with what is being recorded, a yellow line for each degradation,
     * a red line when ffmpeg cannot be run.
     */
    private static void reportStart(VideoParams params, CaptureResolution.Decision size, int windowWidth, int windowHeight,
        String ffmpegPath, boolean ffmpegAvailable)
    {
        boolean shaders = ShaderPackVideoCompat.isShaderPackInUse();

        VideoMessages.info("blockbuster.video.msg.start", params.width(), params.height(),
            ffmpegAvailable ? "H.264" : "PNG",
            new VideoMessages.Tr(shaders ? "blockbuster.video.msg.shaders_on" : "blockbuster.video.msg.shaders_off"));

        if (size.mode() == CaptureResolution.Mode.SCALED)
        {
            VideoMessages.warning("blockbuster.video.msg.scaled", windowWidth, windowHeight, new VideoMessages.Tr(size.reason()));
        }
        else if (size.mode() == CaptureResolution.Mode.CROPPED)
        {
            VideoMessages.warning("blockbuster.video.msg.cropped", windowWidth, windowHeight, new VideoMessages.Tr(size.reason()));
        }

        if (!ffmpegAvailable)
        {
            VideoMessages.error("blockbuster.video.msg.no_ffmpeg", ffmpegPath);
        }
    }

    /**
     * Build the {@link VideoParams} for a recording (P200/P201 config +
     * {@link RecordingFilename} timestamp fallback). Pure — headlessly testable.
     *
     * @param name   the panel filename (empty ⇒ a fresh timestamp is applied,
     *               matching the ghost text the recorder showed)
     * @param audio  the resolved audio track, or {@code null}
     * @param width  output width ({@code 0} ⇒ window size, resolved by the caller)
     * @param height output height
     */
    public static VideoParams buildParams(String name, File audio, int width, int height)
    {
        String resolved = (name == null || name.isEmpty())
            ? RecordingFilename.timestamp(System.currentTimeMillis())
            : name;

        return new VideoParams(
            width,
            height,
            VideoConfig.frameRate(),
            VideoConfig.motionBlur(),
            VideoConfig.heldFrames(),
            /* P203 alpha capture: BGRA when video.alpha is on, so the readback
             * keeps the channel and SinkFactory/AlphaSinkPolicy pick an
             * alpha-capable sink. Default off ⇒ opaque BGR.
             *
             * S22/P250: this reads the LIVE config value. It used to read the
             * `static final boolean DEFAULT_ALPHA = false` constant, which javac
             * folded — the BGRA arm never reached the class file, so P203's
             * alpha capture and (via fps/motion_blur/held_frames below) P199's
             * motion blur shipped as dead code. */
            VideoConfig.alpha() ? VideoFormat.BGRA : VideoFormat.BGR,
            moviesDir(),
            resolved,
            audio
        );
    }
}
