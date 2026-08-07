package mchorse.blockbuster_pack.client.render;

import mchorse.blockbuster.Blockbuster;
import mchorse.blockbuster.api.Model;
import mchorse.blockbuster.api.ModelLimb;
import mchorse.blockbuster.api.ModelPose;
import mchorse.blockbuster.api.formats.obj.ShapeKey;
import mchorse.blockbuster.client.model.ModelCustom;
import mchorse.blockbuster.client.model.ModelCustomRenderer;
import mchorse.blockbuster.client.model.PoseContext;
import mchorse.blockbuster.client.render.IModelCustomMorph;
import mchorse.blockbuster.client.render.LayerBodyPart;
import mchorse.blockbuster.client.render.Nameplate;
import mchorse.blockbuster.client.render.PoseContexts;
import mchorse.blockbuster.client.render.RenderCustomActor;
import mchorse.blockbuster.client.render.RenderCustomModel;
import mchorse.blockbuster.client.textures.GifTexture;
import mchorse.blockbuster.common.OrientedBB;
import mchorse.blockbuster_pack.morphs.CustomMorph;
import mchorse.mclib.client.gui.framework.elements.utils.GuiDraw;
import mchorse.mclib.client.render.McLibRenderLayers;
import mchorse.mclib.client.render.RenderingUtilsClient;
import mchorse.mclib.utils.resources.ResourceLocation;
import mchorse.metamorph.api.morphs.AbstractMorph;
import mchorse.metamorph.client.render.IMorphRenderer;
import mchorse.metamorph.client.render.MorphRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Map;

/**
 * Client render body for {@link CustomMorph} (roadmap P54/P158).
 *
 * <p>This is the flagship morph draw: a Blockbuster {@code model.json} limb tree
 * posed by {@link ModelCustom} and drawn through {@link RenderCustomModel}. It
 * ports the two {@code @SideOnly(CLIENT)} bodies legacy declared on
 * {@code CustomMorph} — {@code render} (in-world) and {@code renderOnScreen} +
 * its private {@code drawModel} (GUI) — into the split source set, where they
 * cannot live on the morph class itself.</p>
 *
 * <p><b>Pose pass.</b> On 1.12.2 {@code RenderLivingBase.doRender} called
 * {@code setRotationAngles} for us; {@link RenderCustomModel#render} is our own
 * renderer and has no such inherited step, so this class runs it explicitly
 * between {@code setupModel} (which resolves the model and stamps materials /
 * shape keys / pose onto it) and the geometry pass. {@code setupModel} runs
 * again inside {@code render} and is idempotent — it re-resolves the same model
 * and does not touch the limb angles the pose pass just wrote.</p>
 *
 * <p><b>Missing model.</b> Legacy drew a red {@code blockbuster.morph_error}
 * label plus the model key when the key resolved to nothing (GUI), or a
 * nameplate gated on {@code model_block_missing_name_rendering}/F3 (world).
 * Both halves are ported — see {@link #drawMissingModel} and
 * {@link #renderMissingModelName}.</p>
 *
 * Legacy source: blockbuster-1.12/.../blockbuster_pack/morphs/CustomMorph.java (render/renderOnScreen/drawModel)
 */
public class CustomMorphRenderer implements IMorphRenderer<CustomMorph>
{
    /**
     * Legacy {@code ClientProxy.actorRenderer} — one shared renderer instance,
     * which is what makes {@link RenderCustomModel}'s matrix-capture ref-count
     * meaningful across nested morph renders.
     *
     * <p>A {@link RenderCustomActor}, as legacy's was: that subclass is the one
     * carrying the five-layer stack, so every morph draw picks up elytra, armor,
     * hat and held items while a bare {@code RenderCustomModel} (model blocks,
     * previews) still draws the model alone.</p>
     */
    public static final RenderCustomModel RENDERER = new RenderCustomActor();

    /* --------------------------------------------------------------------- */
    /* In-world                                                              */
    /* --------------------------------------------------------------------- */

    /**
     * <p><b>{@code entityYaw} is deliberately unread.</b> Legacy's
     * {@code RenderLivingBase.doRender} took the same parameter and never fed
     * it to {@code rotateCorpse} — it recomputed the body yaw from
     * {@code prevRenderYawOffset}/{@code renderYawOffset} instead. Body parts
     * and the model block both rely on that (they zero / overwrite the
     * entity's {@code bodyYaw} around the nested draw and pass 0 here), so the
     * yaw is read off the entity in
     * {@link RenderCustomModel.ApplyRotationsInput#of}, not from this
     * argument.</p>
     */
    @Override
    public void render(CustomMorph morph, LivingEntity entity, double x, double y, double z, float entityYaw, float partialTicks, MorphRenderContext context)
    {
        if (morph.model != null)
        {
            morph.fillObbs(false);
        }

        morph.updateModel();

        if (morph.model == null)
        {
            this.renderMissingModelName(morph, entity, x, y, z, context);

            return;
        }

        morph.parts.initBodyParts();

        ModelCustom model = this.setup(morph, entity, partialTicks);

        if (model == null)
        {
            return;
        }

        Identifier texture = resolveTexture(morph, model.model);

        /* GIF skins: resolve the current animation frame before the RenderLayer
         * is built (same seam ImageMorphRenderer uses). The base .gif id still
         * animates via GifTexture.getGlId once registered; resolving here keeps
         * the layer on a concrete frame texture and matches entity.age rather
         * than relying solely on the process-global entityTick override. */
        if (texture != null)
        {
            texture = GifTexture.resolveFrame(texture, entity.age, partialTicks);
        }

        RenderCustomModel.bindLastTexture(resolveSkin(morph, model.model), texture);

        MatrixStack matrices = context.matrices;
        VertexConsumerProvider consumers = context.consumers;

        if (matrices == null || consumers == null || texture == null)
        {
            return;
        }

        matrices.push();
        matrices.translate(x, y, z);

        try
        {
            /* keying picks the subtractive layer — legacy set that blend inside
             * ModelCustom.render; on 1.20.4 the blend is the buffer, so it is
             * picked here. */
            VertexConsumer consumer = consumers.getBuffer(McLibRenderLayers.model(texture, morph.keying));

            /* The rotation input comes off the entity, exactly as legacy's
             * doRender/applyRotations read it. It used to be hardcoded to
             * `normal()`, which pinned the body yaw at 0 (every morph faced one
             * direction) and made the ported bed and elytra branches dead code. */
            RENDERER.render(entity, partialTicks, RenderCustomModel.ApplyRotationsInput.of(entity, partialTicks),
                matrices, consumer, 1F, 1F, 1F, 1F, context.light, context.overlay);
        }
        finally
        {
            matrices.pop();
        }
    }

    /**
     * Legacy's {@code modelBlockRenderMissingName.get() || showDebugInfo} — two
     * independent triggers, and the config half defaults to on, so a broken
     * model key announces itself without anyone turning anything on.
     */
    public static boolean shouldRenderMissingName(boolean debugHud)
    {
        return Blockbuster.modelBlockRenderMissingName.get() || debugHud;
    }

    /**
     * The in-world half of legacy's missing-model fallback: a nameplate showing
     * the unresolvable model key, one block above the morph's origin.
     *
     * <p>The {@code glRevertRotationScale} legacy opened with is load-bearing —
     * a model block or body part can be drawing this morph inside its own
     * rotated, scaled frame, and without stripping that the label would be
     * skewed and mis-sized. {@link RenderingUtilsClient#applyRevertRotationScale}
     * is the ported form (it decomposes in world space, see its note).</p>
     *
     * <p>Legacy read the key through {@code getKey()}, which is the model name
     * plus the pose suffix — so a morph whose model is fine but whose pose key
     * is wrong still names what it looked for.</p>
     */
    private void renderMissingModelName(CustomMorph morph, LivingEntity entity, double x, double y, double z, MorphRenderContext context)
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null || context.matrices == null || context.consumers == null)
        {
            return;
        }

        if (!shouldRenderMissingName(mc.getDebugHud().shouldShowDebugHud()))
        {
            return;
        }

        MatrixStack matrices = context.matrices;

        matrices.push();

        try
        {
            /* Legacy passed (x, y + 1, z) to drawNameplate — a block above the
             * render origin, with the shift argument left at 0. */
            matrices.translate(x, y + 1D, z);

            RenderingUtilsClient.applyRevertRotationScale(matrices);

            Nameplate.draw(matrices, context.consumers, morph.getKey(), 0F,
                entity != null && entity.isSneaking(), context.light);
        }
        finally
        {
            matrices.pop();
        }
    }

    /* --------------------------------------------------------------------- */
    /* First-person arm                                                      */
    /* --------------------------------------------------------------------- */

    /**
     * Legacy {@code CustomMorph.renderHand}: point the shared renderer at this
     * morph, resolve the model, and draw the {@code right} or {@code left} limb
     * group as the first-person arm.
     *
     * <p><b>The return values are legacy's, and they are not symmetric.</b> A
     * morph whose model key resolves to nothing returns <i>false</i> — the
     * player's own arm is drawn, so a broken model leaves you with a hand rather
     * than with nothing. Everything else returns true, including a model that has
     * no arm limbs at all: that morph deliberately has no hand, and legacy hid
     * the vanilla one for it.</p>
     *
     * <p>Legacy never consulted {@code settings.hands} here — the override
     * replaced the base body outright — so neither does this.</p>
     */
    @Override
    public boolean renderHand(CustomMorph morph, PlayerEntity player, Hand hand)
    {
        MorphRenderContext context = MorphRenderContext.current();

        if (context == null || context.matrices == null || context.consumers == null)
        {
            return false;
        }

        morph.updateModel();

        RENDERER.current = new MorphAdapter(morph);
        RENDERER.setupModel(player, context.partialTicks);

        if (RENDERER.mainModel == null)
        {
            return false;
        }

        Identifier texture = resolveTexture(morph, RENDERER.mainModel.model);

        if (texture != null)
        {
            texture = GifTexture.resolveFrame(texture, player.age, context.partialTicks);
        }

        RenderCustomModel.bindLastTexture(resolveSkin(morph, RENDERER.mainModel.model), texture);

        if (texture == null)
        {
            /* Legacy skipped the bind when there was no location and drew with
             * whatever was bound; there is no such thing as "whatever is bound"
             * once the texture is part of the RenderLayer, so an unskinned model
             * draws nothing — but still claims the hand, as legacy did. */
            return true;
        }

        /* Not keyed, deliberately: legacy's renderRightArm/renderLeftArm
         * (RenderCustomModel.java:242,272) iterate the arm limbs themselves and
         * never go through ModelCustom.render, so the reverse-subtract blend
         * gated on `current.keying` never reached the first-person hand — it
         * drew under plain enableBlend. A keyed morph therefore has a normal
         * arm and a hole-punching body. Legacy quirk, kept. */
        RENDERER.renderArm(player, hand == Hand.OFF_HAND, context.matrices,
            context.consumers.getBuffer(RenderLayer.getEntityTranslucent(texture)),
            context.light, context.overlay);

        return true;
    }

    /* --------------------------------------------------------------------- */
    /* On screen (GUI)                                                       */
    /* --------------------------------------------------------------------- */

    @Override
    public void renderOnScreen(CustomMorph morph, PlayerEntity player, int x, int y, float scale, float alpha)
    {
        CustomMorph.setRenderingOnScreen(true);

        try
        {
            if (morph.model != null)
            {
                morph.fillObbs(false);
            }

            morph.updateModel();

            ModelCustom model = ModelCustom.MODELS.get(morph.getKey());

            if (model == null || morph.model == null)
            {
                this.drawMissingModel(morph, x, y);

                return;
            }

            Model data = model.model;

            /* Legacy gate: without any texture source there is nothing to draw
             * (an all-material OBJ counts through providesMtl). */
            if (data == null || (data.defaultTexture == null && !data.providesMtl && morph.skin == null))
            {
                return;
            }

            morph.parts.initBodyParts();

            float partialTicks = MorphRenderContext.current() == null ? 0F : MorphRenderContext.current().partialTicks;

            this.setup(morph, player, partialTicks);

            Identifier texture = resolveTexture(morph, data);

            if (texture != null)
            {
                int ticks = player == null ? 0 : player.age;

                texture = GifTexture.resolveFrame(texture, ticks, partialTicks);
            }

            RenderCustomModel.bindLastTexture(resolveSkin(morph, data), texture);

            this.drawModel(model, morph, player, x, y, scale * data.scaleGui * morph.scaleGui, alpha);
        }
        finally
        {
            CustomMorph.setRenderingOnScreen(false);
        }
    }

    /**
     * Legacy private {@code CustomMorph.drawModel}: draw the model straight into
     * the GUI, bypassing the entity render dispatcher (which would add a pile of
     * transforms). The transform chain is ported op-for-op:
     *
     * <pre>
     * translate(x, y, 50)     scale(-scale, scale, scale)
     * rotate(45, -1,0,0)      rotate(45, 0,-1,0)
     * rotate(180, 0,0,1)      rotate(180, 0,1,0)
     *   scale(-1, -1, 1)      translate(0, -1.501, 0)
     * </pre>
     *
     * <p>The mirrored scales go through {@code multiplyPositionMatrix} rather
     * than {@code MatrixStack.scale}, matching {@code GuiUtils.drawEntityOnScreen}:
     * a negative non-uniform {@code scale()} mangles the stack's normal matrix,
     * and the lighting on these previews is the one thing that would silently
     * differ from 1.12.2.</p>
     */
    private void drawModel(ModelCustom model, CustomMorph morph, PlayerEntity player, int x, int y, float scale, float alpha)
    {
        DrawContext dc = GuiDraw.getDrawContext();

        if (dc == null)
        {
            return;
        }

        Identifier texture = RenderCustomModel.lastTexture;

        if (texture == null)
        {
            return;
        }

        MatrixStack matrices = dc.getMatrices();

        matrices.push();

        try
        {
            matrices.translate(x, y, 50.0F);
            matrices.multiplyPositionMatrix(new Matrix4f().scaling(-scale, scale, scale));
            matrices.multiply(RotationAxis.NEGATIVE_X.rotationDegrees(45.0F));
            matrices.multiply(RotationAxis.NEGATIVE_Y.rotationDegrees(45.0F));
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180.0F));
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F));

            matrices.multiplyPositionMatrix(new Matrix4f().scaling(-1.0F, -1.0F, 1.0F));
            matrices.translate(0.0F, -1.501F, 0.0F);

            /* Legacy setLivingAnimations + setRotationAngles(0, 0, ticksExisted,
             * 0, 0) — a static, forward-facing, non-walking pose. */
            PoseContext context = new PoseContext();

            context.ageInTicks = player == null ? 0 : player.age;
            context.living = player != null;

            model.setRotationAngles(context);

            VertexConsumerProvider.Immediate consumers = dc.getVertexConsumers();

            /* The frame has to be installed around the model draw itself, not
             * just around the body parts: OBJ material textures and VOX palettes
             * are picked per material group out of the frame's provider
             * (MaterialTextures.ambient), and a group that cannot find a frame
             * falls back to the model's own skin. That is why a multi-material
             * OBJ previewed as a white silhouette here while the very same model
             * textured correctly in the world — the world path draws inside its
             * caller's frame. Same reasoning as GuiBBModelRenderer.renderPose.
             *
             * Legacy: LayerBodyPart.renderBodyParts(player, this, model, 0F,
             * factor) — same call, in the GUI frame. The parts draw into the
             * DrawContext's matrices, so they need the frame too. */
            MorphRenderContext.push(matrices, consumers, MorphRenderContext.FULL_BRIGHT, OverlayTexture.DEFAULT_UV, 0F);

            try
            {
                model.render(matrices, consumers.getBuffer(McLibRenderLayers.model(texture, morph.keying)),
                    1F, 1F, 1F, alpha, MorphRenderContext.FULL_BRIGHT, OverlayTexture.DEFAULT_UV);

                LayerBodyPart.renderBodyParts(player, morph, morph.parts, model, context, 0F, RenderCustomModel.BODY_PART_SCALE);
            }
            finally
            {
                MorphRenderContext.pop();
            }

            consumers.draw();
        }
        finally
        {
            matrices.pop();
        }
    }

    /**
     * Legacy's missing-model fallback: the localized error above the model key,
     * both centred on the preview cell.
     */
    private void drawMissingModel(CustomMorph morph, int x, int y)
    {
        DrawContext dc = GuiDraw.getDrawContext();
        MinecraftClient mc = MinecraftClient.getInstance();

        if (dc == null || mc == null || mc.textRenderer == null)
        {
            return;
        }

        String error = I18n.translate("blockbuster.morph_error");
        String name = morph.name == null ? "" : morph.name;
        int height = mc.textRenderer.fontHeight;

        dc.drawTextWithShadow(mc.textRenderer, error, x - mc.textRenderer.getWidth(error) / 2, y - (int) (height * 2.5F), 0xff2222);
        dc.drawTextWithShadow(mc.textRenderer, name, x - mc.textRenderer.getWidth(name) / 2, y - height, 0xffffff);

        /* Paint it here rather than at the end of the frame, or the next cell's
         * background covers it (GuiDraw.flush) */
        GuiDraw.flush();
    }

    /* --------------------------------------------------------------------- */
    /* Shared setup                                                          */
    /* --------------------------------------------------------------------- */

    /**
     * Point the shared renderer at this morph, resolve its model and run the
     * pose pass. Returns the resolved model, or null when the key has no model.
     */
    private ModelCustom setup(CustomMorph morph, LivingEntity entity, float partialTicks)
    {
        RENDERER.current = new MorphAdapter(morph);
        RENDERER.setupModel(entity, partialTicks);

        ModelCustom model = RENDERER.mainModel;

        if (model == null)
        {
            return null;
        }

        PoseContext context = PoseContexts.fromEntity(entity, partialTicks);

        if (hasCape(model))
        {
            PoseContexts.applyCape(context, entity, partialTicks,
                morph.prevCapeX, morph.capeX, morph.prevCapeY, morph.capeY, morph.prevCapeZ, morph.capeZ);
        }

        model.setRotationAngles(context);

        /* Hand the pose input to the renderer so LayerBodyPart can re-apply it
         * after each body part (the nested morph render clobbers shared model
         * state). */
        RENDERER.poseContext = context;

        return model;
    }

    /**
     * Legacy gated the cape branch on {@code limb.cape && living &&
     * current != null}; the first two conditions are per-limb/per-entity, so the
     * context flag only has to answer "does this model have a cape limb at all".
     */
    private static boolean hasCape(ModelCustom model)
    {
        if (model.limbs == null)
        {
            return false;
        }

        for (ModelCustomRenderer limb : model.limbs)
        {
            if (limb.limb != null && limb.limb.cape)
            {
                return true;
            }
        }

        return false;
    }

    /**
     * The skin in effect: the morph's override, else the model's default, else
     * the blank placeholder when the model carries its texture in its materials
     * (see {@link RenderCustomModel#skinOrBlank}) — an OBJ/MTL model has no skin
     * of its own and used to be skipped outright by every caller below.
     */
    private static Identifier resolveTexture(CustomMorph morph, Model data)
    {
        ResourceLocation skin = resolveSkin(morph, data);

        return RenderCustomModel.skinOrBlank(skin == null ? null : skin.toIdentifier(), data);
    }

    /**
     * The same skin in its McLib form, which is what
     * {@link mchorse.blockbuster.client.model.parsing.ModelExtrudedLayer} keys
     * on — a multiskin must not be flattened to a plain {@link Identifier} on
     * the way there. {@code null} when the model has no skin of its own.
     */
    private static ResourceLocation resolveSkin(CustomMorph morph, Model data)
    {
        return morph.skin != null ? morph.skin : (data == null ? null : data.defaultTexture);
    }

    /**
     * Bridges {@link CustomMorph} (common source set) onto the client-only
     * {@link IModelCustomMorph} seam the renderer talks to. The morph class
     * cannot implement the interface itself — it would not compile on a
     * dedicated server.
     */
    public static class MorphAdapter implements IModelCustomMorph
    {
        public final CustomMorph morph;

        public MorphAdapter(CustomMorph morph)
        {
            this.morph = morph;
        }

        @Override
        public String getKey()
        {
            return this.morph.getKey();
        }

        @Override
        public ModelPose getPose(LivingEntity entity, float partialTicks)
        {
            return this.morph.getPose(entity, partialTicks);
        }

        @Override
        public Map<String, ResourceLocation> getMaterials()
        {
            return this.morph.materials;
        }

        @Override
        public List<ShapeKey> getShapesForRendering(float partialTicks)
        {
            return this.morph.getShapesForRendering(partialTicks);
        }

        @Override
        public float getScale()
        {
            return this.morph.scale;
        }

        /**
         * The typed read {@code ModelCustom.render} used to do directly
         * ({@code this.current.keying}) — this is what makes the shipped
         * {@code Keying} switch select the subtractive layer for the model's
         * material groups.
         */
        @Override
        public boolean isKeying()
        {
            return this.morph.keying;
        }

        /**
         * Legacy {@code ModelCustom}/{@code ModelCustomRenderer} read
         * {@code current.orientedBBlimbs} directly; this is that read through
         * the seam. The map is filled lazily by {@link CustomMorph#fillObbs}
         * (already called on both render paths above), so it is non-null for
         * any morph that has drawn once.
         */
        @Override
        public Map<ModelLimb, List<OrientedBB>> getOrientedBBs()
        {
            return this.morph.orientedBBlimbs;
        }

        @Override
        public AbstractMorph getMorph()
        {
            return this.morph;
        }
    }
}
