package mchorse.blockbuster.client.video;

import mchorse.blockbuster.Blockbuster;

/**
 * The {@code video.*} config category (S18, a documented port addition):
 * defaults, argument templates, and the <b>live accessors</b> the recorder
 * reads.
 *
 * <p><b>S22/P250 — the constants are no longer the wiring.</b> S18 handed the
 * live registration to S19 P208 and P208 shipped without it, so every setting
 * here stayed a {@code public static final} constant and the recorder read the
 * constant. That was not merely "the feature is off": javac <b>constant-folds</b>
 * a {@code static final boolean}/{@code int}, so
 * {@code VideoConfig.DEFAULT_ALPHA ? BGRA : BGR} compiled down to a bare
 * {@code getstatic BGR} and the P203 alpha arm — {@code AlphaSinkPolicy}, the
 * BGRA readback, the alpha-capable sink selection — was <b>absent from the
 * shipped jar</b>, as were the P199/P200 motion-blur and held-frame arms.
 * {@link mchorse.blockbuster.Blockbuster#onConfigRegister} now registers the
 * category and the methods below read those {@code Value*} objects, so the
 * branches are real invocations and both arms survive compilation.</p>
 *
 * <p>The {@code DEFAULT_*} constants stay: they are the defaults handed to the
 * {@code Value*} constructors (one source of truth for file default and
 * headless fallback) and the templates the argument tests assert against.
 * <b>Do not read a {@code DEFAULT_*} constant from recorder code</b> — read the
 * accessor, or the setting silently stops being configurable again.</p>
 */
public final class VideoConfig
{
    private VideoConfig()
    {}

    /* ---------------------------------------------------------------- */
    /* Live accessors — every recorder read goes through these.          */
    /* ---------------------------------------------------------------- */

    /** {@code video.ffmpeg_path} — the configured binary, pre-{@link FfmpegLocator} resolution. */
    public static String ffmpegPath()
    {
        return Blockbuster.videoFfmpegPath.get();
    }

    /** {@code video.arguments} — the opaque (BGR) ffmpeg template. */
    public static String arguments()
    {
        return Blockbuster.videoArguments.get();
    }

    /** {@code video.arguments_audio} — opaque template with the {@code %AUDIO_TRACK%} mux. */
    public static String argumentsAudio()
    {
        return Blockbuster.videoArgumentsAudio.get();
    }

    /** {@code video.arguments_alpha} — the alpha (BGRA) ffmpeg template (P203). */
    public static String argumentsAlpha()
    {
        return Blockbuster.videoArgumentsAlpha.get();
    }

    /** {@code video.arguments_alpha_audio} — alpha template with the audio mux. */
    public static String argumentsAlphaAudio()
    {
        return Blockbuster.videoArgumentsAlphaAudio.get();
    }

    /** {@code video.width} — {@code 0} means "the window's framebuffer width". */
    public static int width()
    {
        return Blockbuster.videoWidth.get();
    }

    /** {@code video.height} — {@code 0} means "the window's framebuffer height". */
    public static int height()
    {
        return Blockbuster.videoHeight.get();
    }

    /** {@code video.frame_rate} — output frames per second (P199 clock rate). */
    public static int frameRate()
    {
        return Blockbuster.videoFrameRate.get();
    }

    /** {@code video.motion_blur} — 0 = off; N renders at {@code fps * 2^N} and averages down. */
    public static int motionBlur()
    {
        return Blockbuster.videoMotionBlur.get();
    }

    /** {@code video.held_frames} — 1 = every rendered frame is an output frame. */
    public static int heldFrames()
    {
        return Blockbuster.videoHeldFrames.get();
    }

    /** {@code video.alpha} — transparent capture (P203): BGRA readback + alpha-capable sink. */
    public static boolean alpha()
    {
        return Blockbuster.videoAlpha.get();
    }

    /** {@code video.audio} — mux the scene's {@code .wav} into the container (P202). */
    public static boolean audio()
    {
        return Blockbuster.videoAudio.get();
    }

    /** {@code video.export_path} — empty means {@code config/blockbuster/movies}. */
    public static String exportPath()
    {
        return Blockbuster.videoExportPath.get();
    }

    /** {@code video.encoder_log} — per-recording {@code <name>.log} instead of a shared one. */
    public static boolean encoderLog()
    {
        return Blockbuster.videoEncoderLog.get();
    }

    /**
     * {@code video.custom_resolution_shaders} — whether a recording at a size
     * other than the window's renders at that size even when an Iris shader pack
     * is active (wixo.1). Off ⇒ the window is rendered natively and scaled.
     */
    public static boolean customResolutionShaders()
    {
        return Blockbuster.videoCustomResolutionShaders.get();
    }

    /** Default for {@code video.custom_resolution_shaders}. */
    public static final boolean DEFAULT_CUSTOM_RESOLUTION_SHADERS = true;

    /**
     * Video-only ffmpeg argument template. Placeholders: {@code %WIDTH%},
     * {@code %HEIGHT%}, {@code %FPS%}, {@code %FILTERS%}, {@code %NAME%}.
     */
    public static final String DEFAULT_ARGUMENTS =
        "-f rawvideo -pix_fmt bgr24 -s %WIDTH%x%HEIGHT% -r %FPS% -i - -vf %FILTERS% -c:v libx264 -preset ultrafast -tune zerolatency -qp 18 -pix_fmt yuv420p %NAME%.mp4";

    /**
     * ffmpeg argument template with an audio second input ({@code %AUDIO_TRACK%})
     * muxed (P202 audio muxing). {@code -af apad} pads the audio with silence
     * indefinitely, so {@code -shortest} trims to the <b>video</b> stream: the
     * recording runs until the capture stops, and an audio track shorter than
     * the session no longer makes ffmpeg exit early (which surfaced as a
     * broken-pipe "Video encoder sink failed" and the premature-stop modal).
     */
    public static final String DEFAULT_ARGUMENTS_AUDIO =
        "-f rawvideo -pix_fmt bgr24 -s %WIDTH%x%HEIGHT% -r %FPS% -i - -i %AUDIO_TRACK% -vf %FILTERS% -af apad -c:v libx264 -preset ultrafast -tune zerolatency -qp 18 -pix_fmt yuv420p -c:a aac -b:a 128k -shortest %NAME%.mp4";

    /**
     * Alpha-capture ffmpeg template (P203, documented port addition): reads raw
     * {@code bgra} frames and encodes ProRes 4444 ({@code yuva444p10le}) into a
     * {@code .mov}, the shipped alpha preset. yuv420p templates cannot carry
     * alpha, so an alpha recording either uses a template like this one or falls
     * back to the PNG sequence ({@link AlphaSinkPolicy}).
     */
    public static final String DEFAULT_ARGUMENTS_ALPHA =
        "-f rawvideo -pix_fmt bgra -s %WIDTH%x%HEIGHT% -r %FPS% -i - -vf %FILTERS% -c:v prores_ks -profile:v 4444 -pix_fmt yuva444p10le %NAME%.mov";

    /**
     * Alpha-capture ffmpeg template with an audio second input (P203 + P202 mux;
     * same {@code -af apad} + {@code -shortest} video-length trim as
     * {@link #DEFAULT_ARGUMENTS_AUDIO}).
     */
    public static final String DEFAULT_ARGUMENTS_ALPHA_AUDIO =
        "-f rawvideo -pix_fmt bgra -s %WIDTH%x%HEIGHT% -r %FPS% -i - -i %AUDIO_TRACK% -vf %FILTERS% -af apad -c:v prores_ks -profile:v 4444 -pix_fmt yuva444p10le -c:a aac -b:a 128k -shortest %NAME%.mov";

    /**
     * Default alpha-capture toggle (P203, documented port addition). Off = opaque
     * BGR video / green-screen-sky compositing as 1.12.2; on = alpha-preserving
     * clear, hidden HUD/hand, BGRA capture into an alpha-capable sink.
     * Registered as {@code video.alpha} with this default; read via
     * {@link #alpha()} — never read this constant from recorder code.
     */
    public static final boolean DEFAULT_ALPHA = false;

    /**
     * Default scene-audio muxing toggle ({@code video.audio}, P202). On: the
     * scene's {@code .wav} (resolved by the client's scene-audio tracker) is
     * handed to the audio ffmpeg template as {@code %AUDIO_TRACK%}. Off: video
     * is always silent. Registered as {@code video.audio}; read via {@link #audio()}.
     */
    public static final boolean DEFAULT_AUDIO = true;

    /**
     * Default encoder-logging toggle ({@code video.encoder_log}, P201). Off: all
     * recordings share one {@code video.log} next to the output; on: each
     * recording gets its own {@code <name>.log}.
     */
    public static final boolean DEFAULT_ENCODER_LOG = false;

    /** Default ffmpeg binary path (resolved via {@link FfmpegLocator}). */
    public static final String DEFAULT_FFMPEG_PATH = "ffmpeg";

    /** Default output frame rate. */
    public static final int DEFAULT_FRAME_RATE = 60;

    /** Default motion-blur level (0 = off; range 0..6). */
    public static final int DEFAULT_MOTION_BLUR = 0;

    /** Default held-frames count (1 = every rendered frame is an output frame). */
    public static final int DEFAULT_HELD_FRAMES = 1;

    /**
     * Number of reusable frame buffers in the encoder queue (P200 backpressure).
     * Small: the point is to overlap GL readback with the sink write by one or
     * two frames, not to buffer the whole movie in RAM.
     */
    public static final int ENCODER_QUEUE_CAPACITY = 3;
}
