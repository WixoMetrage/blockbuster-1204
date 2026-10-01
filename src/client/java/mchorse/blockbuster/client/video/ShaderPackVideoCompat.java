package mchorse.blockbuster.client.video;

import mchorse.blockbuster.client.compat.iris.IrisCompat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * S21 <b>P272.1</b> — shader-pack compatibility of the video pipeline.
 *
 * <h2>The handoff this closes</h2>
 *
 * <p>{@code plan/S18-video-capture.md} handed S21 the Iris/Sodium behaviour of
 * three things and S21 never absorbed it (found 2026-07-26 by batch W-G's
 * {@code PlanHandoffAuditTest}, filed as roadmap <b>P272.1</b>): the recorder's
 * framebuffer readback ({@link VideoRecorder} / {@link PboFrameSource}),
 * the green-screen sky ({@link ChromaSky}) and the alpha readback
 * ({@link ScreenshotCapture}). This class is the single decision table for all
 * three, and the only place that asks whether a pack is in use.</p>
 *
 * <h2>The detection seam is P217's, not a second one</h2>
 *
 * <p>{@link #shaderPack} defaults to {@link IrisCompat#isShaderPackInUse()} —
 * P217's reflective bind against Iris' stable {@code IrisApi} v0
 * ({@code isShaderPackInUse}), the same probe {@code OptifineHelper
 * .isShaderLoaded()} (P218.1) and {@link CustomResolutionCapture#blocker()}
 * (P270) already read. It is a field only so the decision table can be driven
 * headlessly; {@link VideoCaptureWiring#install()} assigns it explicitly so the
 * seam has a production writer (the S22 rule).</p>
 *
 * <h2>What was actually wrong — the three answers</h2>
 *
 * <h3>1. The readback captured an unshaded frame, not the wrong framebuffer</h3>
 *
 * <p>Both capture paths read {@code MinecraftClient.getFramebuffer()}'s colour
 * attachment from Fabric's {@code WorldRenderEvents.LAST}. Iris does <b>not</b>
 * replace that object — its {@code FinalPassRenderer} attaches
 * {@code Minecraft.getInstance().getMainRenderTarget().getColorTextureId()} to
 * its own FBO and writes the composited image straight into that texture (and
 * when the pack has no {@code final} program, copies {@code colortex0} into it
 * with {@code glCopyTexSubImage2D}). So the port reads the <i>right texture at
 * the wrong time</i>:</p>
 *
 * <ul>
 * <li>Iris runs its composite + final chain from {@code MixinLevelRenderer
 * .iris$endLevelRender}, an {@code @Inject(at = @At("RETURN"))} on
 * {@code WorldRenderer.render};</li>
 * <li>Fabric fires {@code WorldRenderEvents.LAST} from an {@code INVOKE}
 * injection on {@code WorldRenderer.renderChunkDebugInfo}, which is the
 * <i>last call</i> in that same method — i.e. strictly before the {@code RETURN}
 * Iris injects on;</li>
 * <li>meanwhile {@code IrisRenderingPipeline.beginLevelRendering} has redirected
 * every world draw into Iris' own {@code RenderTargets} (its {@code ClearPass}es
 * run there, not on the main target).</li>
 * </ul>
 *
 * <p>So at readback time the main colour texture still holds this frame's
 * vanilla {@code RenderSystem.clear} — a flat, unshaded image — and the pack's
 * output has not been written yet. <b>Fix:</b> when a pack is in use the
 * readback moves one seam later, to the {@code GameRenderer.renderWorld}
 * {@code RETURN} the port already owns ({@code GameRendererMixin}, P270/P143),
 * which is after Iris' finalize and still before the HUD. See
 * {@link Readback}.</p>
 *
 * <p>Technique credit: BBS solves the same problem the same way — its recorder
 * reads a texture snapshotted by {@code BBSRendering.onRenderBeforeScreen()},
 * hooked after {@code renderWorld} and before {@code InGameHud.render}. Nothing
 * is imported; only the timing point is.</p>
 *
 * <h3>2. The chroma sky does not survive a shader pack — so it is refused</h3>
 *
 * <p>{@code WorldRendererMixin}'s HEAD-cancel of {@code renderSky} still
 * <i>applies</i> under Iris (Iris instruments {@code renderSky} with
 * {@code ModifyVariable}/render-stage injections; it does not overwrite or
 * cancel it). The clear it performs is what stops working:</p>
 *
 * <ul>
 * <li>{@code RenderSystem.clear(GL_COLOR_BUFFER_BIT)} lands on whatever
 * framebuffer is bound, which under a pack is Iris' gbuffer framebuffer with
 * several draw buffers attached — it floods {@code colortex0..N} (normals,
 * specular, …) with the chroma colour rather than painting a backdrop;</li>
 * <li>{@code RenderSystem.setShaderFogColor} is inert: the pack computes fog and
 * atmosphere in its own deferred/composite programs;</li>
 * <li>essentially every pack re-derives the sky in {@code deferred}/
 * {@code composite} from the depth buffer, so the flat colour is overwritten
 * before the final pass;</li>
 * <li>and cancelling {@code renderSky} at HEAD skips Iris' own sky render-stage
 * bookkeeping ({@code iris$beginSky} and the sun/moon/star stage setters),
 * which can mis-stage the pack's subsequent draws.</li>
 * </ul>
 *
 * <p><b>Fix:</b> {@link #isChromaSkyAvailable()} is false under a pack, so
 * {@code RenderingHandler.isGreenSky()} answers false and neither the clear nor
 * the cloud cancel runs. The user gets the pack's own sky plus one logged
 * warning, instead of a corrupted gbuffer and a green screen that is not
 * green.</p>
 *
 * <p>Sodium alone is <b>not</b> a blocker and is deliberately not detected here:
 * Sodium's {@code renderSky} mixin only cancels while the camera is submerged,
 * and its {@code renderClouds} is an {@code @Overwrite} into which a HEAD
 * {@code @Inject} still applies — so the chroma sky and the cloud cancel both
 * survive it.</p>
 *
 * <h3>3. The alpha channel stops being meaningful — it degrades with a warning</h3>
 *
 * <p>The port's alpha capture (P203/P204) is <i>sky-only transparency</i>: the
 * chroma sky writes the config colour's alpha into the frame. Under a pack that
 * source is gone (point 2), and the alpha that does reach the main colour
 * texture is whatever the pack's {@code final} program wrote — in practice
 * opaque. There is no way to recover it without owning the pack's composite
 * chain, so this one is a genuine degradation: {@link #isAlphaMeaningful()} is
 * false, the requested pixel format is <b>kept</b> (the encoder is told the
 * frame size and format once and changing it would swap the sink under the
 * user), and the recording starts with a warning that the alpha will be opaque
 * rather than silently shipping a broken key.</p>
 *
 * <h2>Bit-identical without a pack</h2>
 *
 * <p>Every decision below is {@code shaderPack.getAsBoolean()} gated, and on a
 * vanilla install that probe is P217's cached "Iris absent" false. The readback
 * stays on {@code WorldRenderEvents.LAST}, the chroma sky stays available and
 * the alpha stays meaningful — the same code path, the same call order, the
 * same bytes out.</p>
 */
public final class ShaderPackVideoCompat
{
    private static final Logger LOGGER = LoggerFactory.getLogger("blockbuster-video");

    /**
     * Where the framebuffer readback happens this frame.
     *
     * <p>Exactly one of the two seams acts per frame; the other returns
     * immediately. That is what keeps "one output frame per
     * {@code CaptureClock} tick" true on both paths.</p>
     */
    public enum Readback
    {
        /**
         * Fabric {@code WorldRenderEvents.LAST} — inside
         * {@code WorldRenderer.render}, after the world and before the HUD. The
         * vanilla point, unchanged since P234/P258.
         */
        WORLD_RENDER_LAST,

        /**
         * {@code GameRenderer.renderWorld} {@code RETURN} — after
         * {@code WorldRenderer.render} has returned, therefore after Iris'
         * {@code finalizeLevelRendering}, and still before {@code InGameHud
         * .render}.
         *
         * <p>One documented delta on this path: {@code renderWorld} draws the
         * held item/hand after {@code WorldRenderer.render}, so a hand that is
         * on screen is in the capture. It is not on screen during a camera
         * playback ({@code GameRendererMixin} HEAD-cancels {@code renderHand}
         * while a camera context is active), which is what recordings are made
         * under; and under a pack Iris draws the hand into its own pipeline
         * anyway.</p>
         */
        AFTER_WORLD_RENDER
    }

    /**
     * The shader-pack probe. Defaults to P217's {@link IrisCompat}; reassigned
     * (to the same thing) by {@link VideoCaptureWiring#install()} so the seam
     * has a production writer, and overridable from tests so the decision table
     * can be driven without Iris or a GL context.
     */
    public static BooleanSupplier shaderPack = IrisCompat::isShaderPackInUse;

    /** One-shot latches, so a per-frame decision cannot spam the log. */
    private static boolean warnedChroma;
    private static boolean warnedReadback;

    private ShaderPackVideoCompat()
    {}

    /** Whether a shader pack currently owns the render pipeline. */
    public static boolean isShaderPackInUse()
    {
        try
        {
            return shaderPack.getAsBoolean();
        }
        catch (Throwable t)
        {
            /* Same contract as every other compat probe in the port: a broken
             * integration degrades to "no pack", never to a crashed frame. */
            return false;
        }
    }

    /** Which seam must perform the framebuffer readback this frame. */
    public static Readback readback()
    {
        return isShaderPackInUse() ? Readback.AFTER_WORLD_RENDER : Readback.WORLD_RENDER_LAST;
    }

    /**
     * Whether the readback belongs to {@code point}. Both capture seams call
     * this first, so exactly one of them acts.
     */
    public static boolean readbackAt(Readback point)
    {
        boolean here = readback() == point;

        if (here && point == Readback.AFTER_WORLD_RENDER && !warnedReadback)
        {
            warnedReadback = true;

            LOGGER.info("A shader pack is in use — capturing after the world render (post-composite) "
                + "instead of at WorldRenderEvents.LAST, which would record an unshaded frame");
        }

        return here;
    }

    /**
     * Whether the green-screen sky may run. False under a shader pack: the
     * clear would land on the pack's gbuffers and the pack's own sky would
     * overwrite it anyway (see the class javadoc).
     */
    public static boolean isChromaSkyAvailable()
    {
        if (!isShaderPackInUse())
        {
            return true;
        }

        if (!warnedChroma)
        {
            warnedChroma = true;

            LOGGER.warn("green_screen_sky is enabled but a shader pack owns sky rendering — the chroma sky is "
                + "disabled for this session. The pack draws its sky in its deferred/composite programs, so the "
                + "flat colour would be overwritten (and the clear would flood the pack's gbuffers). "
                + "Disable the shader pack to record a green screen.");
        }

        return false;
    }

    /**
     * Whether an alpha capture can produce a meaningful alpha channel. False
     * under a shader pack: the pack's final program owns the destination alpha.
     */
    public static boolean isAlphaMeaningful()
    {
        return !isShaderPackInUse();
    }

    /**
     * The degradations a recording started right now would suffer — pure, so the
     * whole table is one assertion in a headless test.
     *
     * @param packInUse       whether a shader pack owns the pipeline
     * @param alphaRequested  {@code video.alpha}
     * @param chromaRequested {@code green_screen_sky}
     * @return human-readable warnings, empty when nothing degrades
     */
    public static List<String> degradations(boolean packInUse, boolean alphaRequested, boolean chromaRequested)
    {
        List<String> out = new ArrayList<>();

        if (!packInUse)
        {
            return out;
        }

        out.add("a shader pack is in use: frames are captured after the pack's composite pass "
            + "(the held item/hand is inside that frame)");

        if (chromaRequested)
        {
            out.add("green_screen_sky is on but the shader pack draws its own sky — the chroma sky is disabled "
                + "and the recording will NOT be keyable");
        }

        if (alphaRequested)
        {
            out.add("video.alpha is on but the shader pack's final program writes the alpha channel — "
                + "the captured alpha will be opaque");
        }

        return out;
    }

    /** Live overload of {@link #degradations(boolean, boolean, boolean)}. */
    public static List<String> degradations(boolean alphaRequested, boolean chromaRequested)
    {
        return degradations(isShaderPackInUse(), alphaRequested, chromaRequested);
    }

    /**
     * Log the record-time report. Called once per recording from
     * {@code MinemaBackend.start()} — the plan's requirement that the user is
     * told <i>before</i> the take rather than handed a broken file after it.
     */
    public static void reportAtRecordStart(boolean alphaRequested, boolean chromaRequested)
    {
        boolean pack = isShaderPackInUse();

        for (String line : degradations(pack, alphaRequested, chromaRequested))
        {
            LOGGER.warn("Shader-pack video compatibility (P272.1) — {}", line);
        }

        /* wixo.1 (CDC R4): the user-visible half of the same report. */
        if (pack && chromaRequested)
        {
            VideoMessages.warning("blockbuster.video.msg.shader_chroma");
        }

        if (pack && alphaRequested)
        {
            VideoMessages.warning("blockbuster.video.msg.shader_alpha");
        }
    }

    /** Test hygiene: drop the seam override and the one-shot latches. */
    public static void reset()
    {
        shaderPack = IrisCompat::isShaderPackInUse;
        warnedChroma = false;
        warnedReadback = false;
    }
}
