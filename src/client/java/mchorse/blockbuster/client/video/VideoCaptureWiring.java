package mchorse.blockbuster.client.video;

import mchorse.blockbuster.client.compat.iris.IrisCompat;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;

/**
 * S22 <b>P234</b> — video capture output: install the live capture controller
 * and the scene-audio resolver behind {@link MinemaBackend}.
 *
 * <h2>What was dark</h2>
 * <p>{@code MinemaBackend.install()} was already called from
 * {@code ApertureClient}, so the recording panel's start/stop reached the
 * backend — but the backend's two remaining seams were never assigned:</p>
 * <ul>
 *   <li>{@code MinemaBackend.capture} kept its anonymous no-op default, which
 *       only flipped a boolean and logged. {@link VideoRecorder} was therefore
 *       never constructed and everything behind it — {@code SinkFactory},
 *       {@code FfmpegSink}, {@code PngSequenceSink}, {@code FrameQueue},
 *       {@code FrameSource} — was unreachable. <b>Recording produced no
 *       file.</b></li>
 *   <li>{@code MinemaBackend.audioResolver} was {@code () -> null}, so even a
 *       scene with a {@code .wav} attached exported silent video.</li>
 * </ul>
 *
 * <p>Both are installed here, plus the per-frame capture hook
 * ({@code WorldRenderEvents.LAST}, gated by the P199 clock) and a disconnect
 * guard that tears an in-flight recording down instead of leaving the client
 * stuck in fixed-timestep playback.</p>
 *
 * <p>One {@code install()}, called by exactly one line in {@code ApertureClient}
 * next to the existing {@code MinemaBackend.install()} / {@code TrackingHooks
 * .install()} lines (S22 shared-file protocol).</p>
 */
public final class VideoCaptureWiring
{
    /** The live capture controller (kept addressable for the debug overlay/tests). */
    public static final VideoCapture CAPTURE = new VideoCapture();

    /** Minema F4 / Shift+F4 capture keybind (installed with the rest of the pipeline). */
    public static final VideoCaptureKeyHandler KEYS = new VideoCaptureKeyHandler();

    private static boolean installed;

    /** The {@code video.ffmpeg_path} value last probed on a world join (null = never). */
    private static String ffmpegProbed;

    private VideoCaptureWiring()
    {}

    /** Whether {@link #install()} has already run (the hooks register once). */
    public static boolean isInstalled()
    {
        return installed;
    }

    /** Install the P234 seams. Idempotent — the event hooks register once. */
    public static void install()
    {
        MinemaBackend.capture = CAPTURE;
        MinemaBackend.audioResolver = SceneAudioTracker::resolve;

        /* wixo.1 (CDC R4): recorder messages go to the chat, not only the log. */
        VideoMessages.sink = VideoChat::post;

        /* wixo.1 (CDC R1/R7): resolution presets and encoder selector in the config panel. */
        VideoConfigGui.register();

        /* video.debug: the Aperture camera state behind each captured frame. */
        VideoCapture.frameState = () -> "camera running=" + mchorse.aperture.ClientProxy.runner.isRunning()
            + " tick=" + mchorse.aperture.ClientProxy.runner.ticks
            + " partial=" + mchorse.aperture.ClientProxy.runner.lastPartialTick;

        /* S22 P270: the GL half of custom-resolution capture. Without this
         * assignment CustomResolutionCapture.swap keeps its refuse-everything
         * default and every recording is made at the window size — which is
         * exactly what shipped between P234 and P270. */
        CustomResolutionCapture.swap = new CaptureFramebuffer(MinecraftClient.getInstance());

        /* S21 P272.1: the shader-pack probe the video pipeline reads. It already
         * defaults to the same delegate, but a seam with no production writer is
         * not done (the S22 rule) — this is the writer, and
         * ShaderPackVideoCompatTest asserts it. */
        ShaderPackVideoCompat.shaderPack = IrisCompat::isShaderPackInUse;

        if (installed)
        {
            return;
        }

        installed = true;

        /* Minema F4 toggle + Shift+F4 configuration screen. */
        KEYS.register();

        /* After the world, before the HUD — the same timing point the P204
         * still-screenshot path uses, so neither the HUD nor the (already
         * hidden, P202) camera-editor root can leak into a frame. */
        WorldRenderEvents.LAST.register(context -> CAPTURE.onFrame());

        /* wixo.1 (CDC R5): on joining a world, say when ffmpeg cannot be run —
         * not only when a take is already starting. Probed again whenever the
         * configured path changed since the last probe (a path edited during
         * the session is checked at the next join). The probe spawns a
         * process, so it runs off the client thread. */
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
        {
            String configured = VideoConfig.ffmpegPath();

            if (configured.equals(ffmpegProbed))
            {
                return;
            }

            ffmpegProbed = configured;

            Thread probe = new Thread(() ->
            {
                String path = FfmpegLocator.resolve(configured);

                if (!FfmpegLocator.checkAvailable(path))
                {
                    VideoMessages.error("blockbuster.video.msg.no_ffmpeg_join", path);
                }
            }, "blockbuster-ffmpeg-probe");

            probe.setDaemon(true);
            probe.start();
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
        {
            CAPTURE.abort();
            SceneAudioTracker.reset();
        });
    }

    /**
     * S21 <b>P272.1</b> — the shader-pack readback seam, called from
     * {@code GameRendererMixin}'s {@code renderWorld} RETURN (the seam P143/P270
     * already own; no new mixin).
     *
     * <p>Both capture paths — the recorder and the P204 still screenshot — are
     * routed through here so the mixin carries one line and the "which seam owns
     * this frame" decision lives in exactly one place
     * ({@link ShaderPackVideoCompat#readback()}). Without a shader pack both
     * calls return immediately and the {@code WorldRenderEvents.LAST} path is
     * untouched.</p>
     *
     * <p>Ordering inside the RETURN seam matters: this runs <b>before</b>
     * {@code CustomResolutionCapture.endWorldRender()} so a capture framebuffer
     * (were one ever engaged alongside a pack, which {@code blocker()} forbids)
     * would still be the bound target.</p>
     */
    public static void onWorldRenderEnd()
    {
        CAPTURE.onWorldRenderEnd();
        ScreenshotCapture.captureWorldFrameAt(ShaderPackVideoCompat.Readback.AFTER_WORLD_RENDER);
    }
}
