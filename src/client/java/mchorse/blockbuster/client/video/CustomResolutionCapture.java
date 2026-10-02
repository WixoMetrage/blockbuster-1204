package mchorse.blockbuster.client.video;

import mchorse.blockbuster.client.compat.iris.IrisCompat;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BooleanSupplier;

/**
 * S22 <b>P270</b> — the session controller for recording at a resolution other
 * than the window's, and the single source of truth the {@code WindowMixin}
 * reads when it lies to the engine about the window size.
 *
 * <h2>What this closes</h2>
 *
 * <p>{@code SEAM(P200-window)}. P234 built {@link CaptureFramebuffer} and never
 * installed it; P250 registered {@code video.width}/{@code video.height} and
 * clamped any non-window request to the window with a logged warning, because
 * the framebuffer swap alone would have rendered the world at window size,
 * aspect ratio and GUI scale into a differently sized target. This class is the
 * missing half: it engages the swap and the window-size override <b>together</b>,
 * for the duration of one recording, so the resolution is honoured end to end.</p>
 *
 * <h2>Why the window is lied to rather than rendered around</h2>
 *
 * <p>Because that <i>is</i> the 1.12.2 behaviour, not merely the BBS idiom.
 * Minema 3.7.1's {@code DisplaySizeModifier.doEnable()} — read out of the mod
 * jar in the reference instance — is:</p>
 *
 * <pre>
 * originalWidth  = Display.getWidth();
 * originalHeight = Display.getHeight();
 * if (config.useFrameSize())
 *     MC.resize(config.getFrameWidth(), config.getFrameHeight());   // Minecraft.resize == onResolutionChanged
 * </pre>
 *
 * <p>i.e. it told Minecraft outright that the window had become the capture
 * size, for the whole recording, and its {@code onResize} hook re-forced that
 * value and <b>cancelled</b> any real resize while recording. Everything
 * downstream — the projection aspect ratio, the framebuffer, the GUI scale —
 * followed from the one lie. The alternative ("render offscreen and never
 * deceive the game") has to intercept each of those consumers separately:
 * {@code GameRenderer.getBasicProjectionMatrix} takes its aspect from
 * {@code Window.getFramebufferWidth()/getFramebufferHeight()}, so an offscreen
 * target alone gives a correctly sized file with the <i>window's</i> field of
 * view stretched across it. That is a list that rots every time Mojang adds a
 * consumer, versus one gate that cannot.</p>
 *
 * <h2>How the blast radius is bounded</h2>
 *
 * <p>The port's lie is <b>strictly narrower than legacy's</b>: legacy's was
 * session-scoped and global, this one is live only between
 * {@code GameRenderer.renderWorld}'s HEAD and RETURN ({@link #isActive()}).
 * Everything outside the world render therefore sees the real window:</p>
 *
 * <ul>
 * <li><b>Mouse coordinates</b> — {@code GameRenderer.render} computes them from
 * {@code getScaledWidth()/getWidth()} <i>before</i> it calls {@code renderWorld}
 * (bytecode offsets 161-210 vs. 288), and {@code Mouse} runs on the input
 * thread. Untouched.</li>
 * <li><b>GUI scale and screen layout</b> — the HUD and every {@code Screen} are
 * drawn after {@code renderWorld} returns, from the restored framebuffer.
 * Untouched. (Legacy's <i>was</i> affected: Minema recorded the HUD by default,
 * {@code recordGui=true}, at the capture-resolution scale. The port never
 * records the HUD at all — the readback is on {@code WorldRenderEvents.LAST},
 * inside the world render, established by batch V-H/P258 — so there is nothing
 * for the GUI scale to be wrong <i>for</i>.)</li>
 * <li><b>Window resize / {@code onResolutionChanged}</b> — driven by GLFW
 * callbacks outside the render, so a resize mid-recording behaves normally
 * rather than being cancelled the way Minema cancelled it.</li>
 * <li><b>Fabulous graphics</b> (without shaders) — refused, see
 * {@link #blocker()}.</li>
 * </ul>
 *
 * <p>All GL work is delegated to the {@link Swap} seam so this controller is a
 * pure state machine and the GL adapter ({@link CaptureFramebuffer}) stays a
 * thin, untested edge — the headless rule. Actual pixels are eyeball-only
 * (checklist §44).</p>
 */
public final class CustomResolutionCapture
{
    private static final Logger LOGGER = LoggerFactory.getLogger("blockbuster-video");

    /**
     * The GL half: allocate/validate the capture target, swap it in for a world
     * render, swap it back out, and tear it down at the end of the recording.
     */
    public interface Swap
    {
        /**
         * Allocate (or re-validate) a capture target of exactly
         * {@code width x height}.
         *
         * @return false when the driver cannot provide it — Minema documented
         *         the same limit ("bound to the maximum texture resolution of
         *         your GPU"). The caller then records at the window size instead
         *         of promising the encoder a size the readback can never match.
         */
        boolean prepare(int width, int height);

        /** Swap the capture target in for one world render. */
        void begin(int width, int height);

        /** Swap the client's framebuffer back and preview the capture. */
        void end();

        /** End of recording: restore the world sub-buffers and free the target. */
        void release();
    }

    /**
     * The GL adapter. Installed by {@link VideoCaptureWiring#install()} with a
     * {@link CaptureFramebuffer}; the default refuses every custom size, so a
     * headless JVM (and a client where wiring never ran) simply records at the
     * window size.
     */
    public static Swap swap = new Swap()
    {
        @Override
        public boolean prepare(int width, int height)
        {
            return false;
        }

        @Override
        public void begin(int width, int height)
        {}

        @Override
        public void end()
        {}

        @Override
        public void release()
        {}
    };

    /**
     * Whether Fabulous graphics is on. Overridable for tests; the production
     * value is {@code MinecraftClient.isFabulousGraphicsOrBetter()} (read
     * through a method of this class so merely loading it headlessly does not
     * initialize {@code MinecraftClient}).
     */
    public static BooleanSupplier fabulousGraphics = CustomResolutionCapture::fabulousFromClient;

    private static boolean engaged;
    private static boolean insideWorldRender;
    private static boolean swapped;
    private static int width;
    private static int height;

    private CustomResolutionCapture()
    {}

    private static boolean fabulousFromClient()
    {
        return MinecraftClient.isFabulousGraphicsOrBetter();
    }

    /**
     * Why a custom capture resolution must not be attempted on this client, or
     * {@code null} when nothing stands in the way.
     *
     * <p>Both blockers are about something else owning the render targets:</p>
     *
     * <ul>
     * <li><b>Iris shader pack</b> — no longer a blocker by default (wixo.1, CDC
     * R2). Iris 1.7.2 re-reads {@code MinecraftClient.getFramebuffer()} every
     * frame: {@code IrisRenderingPipeline.beginLevelRendering} resizes its
     * render targets to it ({@code RenderTargets.resizeIfNeeded} +
     * {@code recalculateSizes} on every composite pass) and
     * {@code FinalPassRenderer.renderFinalPass} re-attaches its colour texture
     * whenever the id changes. The swap below happens at {@code renderWorld}
     * HEAD, before Iris' pipeline begins, so the whole shader pipeline runs at
     * the capture size. Only {@code video.custom_resolution_shaders = false}
     * restores the old refusal (the window is then scaled).</li>
     * <li><b>Fabulous graphics</b> (without a shader pack; Iris disables
     * Fabulous itself, {@code MixinDisableFabulousGraphics}) — {@code WorldRenderer}'s transparency
     * {@code PostEffectProcessor} holds the framebuffer it was <i>constructed</i>
     * with as its {@code minecraft:main} target (verified in the 1.20.4 bytecode:
     * {@code loadTransparencyPostProcessor()} passes {@code client.getFramebuffer()}
     * once, and {@code setupDimensions} reads {@code mainTarget.textureWidth}).
     * Swapping the client framebuffer would send the whole translucency composite
     * to the window buffer and leave the capture missing it.</li>
     * </ul>
     *
     * <p>Legacy had the same species of guard: Minema's {@code aaFastRenderFix}
     * exists because a custom resolution plus OptiFine's antialiasing/fast render
     * produced broken recordings, and it worked around it by resizing the real OS
     * window. Refusing is the honest version of that.</p>
     */
    public static String blocker()
    {
        if (IrisCompat.isShaderPackInUse())
        {
            return VideoConfig.customResolutionShaders() ? null : "blockbuster.video.reason.shaders_option";
        }

        if (fabulousGraphics.getAsBoolean())
        {
            return "blockbuster.video.reason.fabulous";
        }

        return null;
    }

    /**
     * Engage the custom-resolution path for a recording at {@code width x height}.
     *
     * @return false when the GL side refused the size; the caller must then fall
     *         back to the window size <b>before</b> building {@code VideoParams},
     *         because the encoder is told the frame size once and cannot
     *         renegotiate it.
     */
    public static boolean engage(int width, int height)
    {
        if (!swap.prepare(width, height))
        {
            return false;
        }

        CustomResolutionCapture.width = width;
        CustomResolutionCapture.height = height;
        engaged = true;

        return true;
    }

    /** End of recording: drop the swap and put the world sub-buffers back. */
    public static void disengage()
    {
        insideWorldRender = false;

        if (!engaged)
        {
            return;
        }

        engaged = false;

        try
        {
            if (swapped)
            {
                swapped = false;

                swap.end();
            }

            swap.release();
        }
        catch (Exception e)
        {
            /* Teardown must never crash the editor (the P202 stop() precedent). */
            LOGGER.warn("Failed to release the capture framebuffer", e);
        }
    }

    /** Whether a recording is running at a resolution other than the window's. */
    public static boolean isEngaged()
    {
        return engaged;
    }

    /**
     * The {@code WindowMixin} gate: true only inside {@code renderWorld} of a
     * custom-resolution recording.
     */
    public static boolean isActive()
    {
        return engaged && insideWorldRender;
    }

    /** The capture width the window reports while {@link #isActive()}. */
    public static int width()
    {
        return width;
    }

    /** The capture height the window reports while {@link #isActive()}. */
    public static int height()
    {
        return height;
    }

    /**
     * Vanilla's own rounding for a scaled (GUI-space) dimension:
     * {@code Window.setScaleFactor} computes {@code ceil(framebufferSize /
     * scaleFactor)}. Mirrored so a scaled read during the world render is
     * consistent with the framebuffer read next to it.
     */
    public static int scaled(int pixels, double scaleFactor)
    {
        if (scaleFactor <= 0)
        {
            return pixels;
        }

        int floor = (int) (pixels / scaleFactor);

        return pixels / scaleFactor > (double) floor ? floor + 1 : floor;
    }

    /** {@code GameRenderer.renderWorld} HEAD: turn the lie on and swap in. */
    public static void beginWorldRender()
    {
        if (!engaged)
        {
            return;
        }

        insideWorldRender = true;

        /* Self-heal: a frame that threw never reached its RETURN seam, so the
         * client would still be pointed at the capture buffer. restore() is
         * idempotent, so ending an already-ended swap costs nothing. */
        if (swapped)
        {
            swapped = false;

            swap.end();
        }

        swap.begin(width, height);
        swapped = true;
    }

    /** {@code GameRenderer.renderWorld} RETURN: swap back and turn the lie off. */
    public static void endWorldRender()
    {
        if (swapped)
        {
            swapped = false;

            swap.end();
        }

        insideWorldRender = false;
    }

    /** Test hygiene — shared statics must not leak between cases. */
    public static void reset()
    {
        engaged = false;
        insideWorldRender = false;
        swapped = false;
        width = 0;
        height = 0;
    }
}
