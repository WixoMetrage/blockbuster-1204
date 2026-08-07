package mchorse.blockbuster.client.model;

import mchorse.blockbuster.api.ModelLimb;
import mchorse.blockbuster.api.ModelTransform;
import mchorse.blockbuster.client.model.parsing.ModelExtrudedLayer;
import mchorse.blockbuster.client.render.RenderCustomModel;
import mchorse.blockbuster.common.OrientedBB;
import mchorse.blockbuster_pack.morphs.CustomMorph.LimbProperties;
import mchorse.mclib.client.render.RenderingUtilsClient;
import mchorse.mclib.utils.MatrixUtils;
import mchorse.mclib.utils.resources.ResourceLocation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4d;
import javax.vecmath.Vector3f;

/**
 * Custom model limb renderer (roadmap P75).
 *
 * <p>Port of Blockbuster 2.7.2's {@code client/model/ModelCustomRenderer}. The
 * 1.12.2 class extended vanilla {@code ModelRenderer}/{@code ModelBox} and drew
 * through GL display lists. On 1.20.4 (core profile, no display lists) it
 * becomes a standalone limb node that:</p>
 *
 * <ul>
 *   <li>bakes its box geometry via {@link ModelBoxBaker} (GL-free, headless
 *       testable) — the legacy {@code compileDisplayList} point;</li>
 *   <li>applies the exact legacy per-limb transform ({@link #applyTransform})
 *       — the {@code +24} root bias and negated Y/Z rotations / negated Z
 *       translation are the coordinate convention every legacy model.json was
 *       authored against;</li>
 *   <li>walks the child tree pushing/popping a {@link MatrixStack} instead of
 *       GL matrix ops, emitting quads into a {@link VertexConsumer}.</li>
 * </ul>
 *
 * <p>The transform math is deliberately separable from the emit path so the
 * P85 geometry probes can drive {@link #applyTransform},
 * {@link #bake()} and {@link #pushTransform(MatrixStack, float)} with no GL
 * context.</p>
 *
 * <p><b>Field name note:</b> the typo'd {@code trasnform} is legacy-public API
 * for subclasses ({@link ModelOBJRenderer}/{@link ModelVoxRenderer}) — kept for
 * diff-ability against the legacy sources.</p>
 */
public class ModelCustomRenderer
{
    public ModelLimb limb;
    /** Legacy typo kept intentionally (public subclass API). */
    public ModelTransform trasnform;
    public ModelCustomRenderer parent;
    public ModelCustom model;

    public Vector3f cachedTranslation = new Vector3f();
    public Vector3f angularVelocity = new Vector3f();

    /**
     * Legacy {@code ModelCustomRenderer.setup} for a limb with
     * {@code lighting = false}:
     * {@code setLightmapTextureCoords(unit, 240, lastBrightnessY)} — the
     * <b>block</b> coordinate pinned to full, the <b>sky</b> coordinate passed
     * straight through from the frame.
     *
     * <p>P286: 1.12.2's two lightmap coordinates are the two halves of yarn's
     * single packed int, and the halves survived the version gap in place —
     * {@code LightmapTextureManager.pack(block, sky)} is
     * {@code block << 4 | sky << 20}, so the low 16 bits are the block half and
     * the high 16 the sky half (javap). The whole-word constant
     * {@code MAX_LIGHT_COORDINATE} (0x00F000F0) pins <i>both</i>, which is
     * legacy's {@code (240, 240)} — what {@code ImageMorph} and {@code ItemMorph}
     * asked for, and what this limb flag never did.</p>
     */
    private static int fullBrightBlockKeepSky(int light)
    {
        return (light & 0xFFFF0000) | LightmapTextureManager.MAX_BLOCK_LIGHT_COORDINATE;
    }

    /* Transform state, mirrors vanilla ModelRenderer fields the legacy class
     * inherited and wrote into applyTransform. */
    public float rotationPointX;
    public float rotationPointY;
    public float rotationPointZ;
    public float rotateAngleX;
    public float rotateAngleY;
    public float rotateAngleZ;
    public float offsetX;
    public float offsetY;
    public float offsetZ;

    public float scaleX = 1;
    public float scaleY = 1;
    public float scaleZ = 1;

    public boolean mirror;
    public boolean showModel = true;
    public boolean isHidden = false;

    /* Baked geometry (lazy, legacy compile point). */
    protected boolean compiled;
    protected final List<float[]> boxes = new ArrayList<float[]>();
    protected float[][][] baked;

    /* Stencil magic (consumed by P84). */
    public int stencilIndex = -1;
    public boolean stencilRendering = false;

    public Vector3f min;
    public Vector3f max;
    private final Matrix4d worldTransformation = new Matrix4d();
    private Matrix4d modelView = new Matrix4d();

    public final List<ModelCustomRenderer> childModels = new ArrayList<ModelCustomRenderer>();

    public ModelCustomRenderer(ModelCustom model, int texOffX, int texOffY)
    {
        this.model = model;
    }

    public ModelCustomRenderer(ModelCustom model, ModelLimb limb, ModelTransform transform)
    {
        this(model, limb.texture[0], limb.texture[1]);

        this.limb = limb;
        this.trasnform = transform;
    }

    public Matrix4d getWorldTransformation()
    {
        return new Matrix4d(this.worldTransformation);
    }

    public Matrix4d getModelView()
    {
        return new Matrix4d(this.modelView);
    }

    public void setupStencilRendering(int stencilIndex)
    {
        this.stencilIndex = stencilIndex;
        this.stencilRendering = true;
    }

    /**
     * Register a box for baking. Mirrors vanilla
     * {@code ModelRenderer.addBox(x, y, z, w, h, d, delta)} — geometry is baked
     * lazily on first render (or via {@link #bake()}).
     */
    public void addBox(float x, float y, float z, int width, int height, int depth, float delta)
    {
        this.boxes.add(new float[] {x, y, z, width, height, depth, delta});
        this.compiled = false;
    }

    /**
     * Apply per-limb transform. <b>Ported verbatim from legacy</b>: the root
     * {@code -y + 24} bias and the Y/Z rotation sign flips + negated Z
     * translation are load-bearing.
     */
    public void applyTransform(ModelTransform transform)
    {
        this.trasnform = transform;

        float x = transform.translate[0];
        float y = transform.translate[1];
        float z = transform.translate[2];

        this.rotationPointX = x;
        this.rotationPointY = this.limb.parent.isEmpty() ? (-y + 24) : -y;
        this.rotationPointZ = -z;

        this.rotateAngleX = transform.rotate[0] * (float) Math.PI / 180;
        this.rotateAngleY = -transform.rotate[1] * (float) Math.PI / 180;
        this.rotateAngleZ = -transform.rotate[2] * (float) Math.PI / 180;

        this.scaleX = transform.scale[0];
        this.scaleY = transform.scale[1];
        this.scaleZ = transform.scale[2];
    }

    public void addChild(ModelCustomRenderer renderer)
    {
        renderer.parent = this;
        this.childModels.add(renderer);
    }

    /**
     * Bake box geometry into quads. Called lazily; safe to call directly in
     * headless tests. Subclasses ({@link ModelOBJRenderer}/
     * {@link ModelVoxRenderer}) override to bake mesh data instead.
     */
    public void bake()
    {
        float texW = this.model != null ? this.model.model.texture[0] : 64;
        float texH = this.model != null ? this.model.model.texture[1] : 32;
        int texU = this.limb != null ? this.limb.texture[0] : 0;
        int texV = this.limb != null ? this.limb.texture[1] : 0;

        this.baked = new float[this.boxes.size()][][];

        for (int i = 0; i < this.boxes.size(); i++)
        {
            float[] b = this.boxes.get(i);

            this.baked[i] = ModelBoxBaker.bake(texU, texV, b[0], b[1], b[2], (int) b[3], (int) b[4], (int) b[5], b[6], this.mirror, texW, texH);
        }

        this.compiled = true;
    }

    /**
     * Push this limb's transform onto the matrix stack (translate rotationPoint,
     * rotate Z→Y→X, scale), following the exact legacy fast-path structure.
     * GL-free — the P85 probes read {@code matrices.peek()} to validate.
     */
    public void pushTransform(MatrixStack matrices, float scale)
    {
        matrices.translate(this.offsetX, this.offsetY, this.offsetZ);

        if (this.rotateAngleX == 0.0F && this.rotateAngleY == 0.0F && this.rotateAngleZ == 0.0F)
        {
            if (!(this.rotationPointX == 0.0F && this.rotationPointY == 0.0F && this.rotationPointZ == 0.0F))
            {
                matrices.translate(this.rotationPointX * scale, this.rotationPointY * scale, this.rotationPointZ * scale);
            }
        }
        else
        {
            matrices.translate(this.rotationPointX * scale, this.rotationPointY * scale, this.rotationPointZ * scale);

            if (this.rotateAngleZ != 0.0F)
            {
                matrices.multiply(new Quaternionf().rotationZ(this.rotateAngleZ));
            }

            if (this.rotateAngleY != 0.0F)
            {
                matrices.multiply(new Quaternionf().rotationY(this.rotateAngleY));
            }

            if (this.rotateAngleX != 0.0F)
            {
                matrices.multiply(new Quaternionf().rotationX(this.rotateAngleX));
            }
        }

        matrices.scale(this.scaleX, this.scaleY, this.scaleZ);
    }

    /**
     * Compute {@link #cachedTranslation} exactly as legacy did, given the
     * extracted model-view transformation. Kept separable & headless-testable
     * (Snowstorm / body-part anchoring reads this even for invisible limbs).
     */
    public void computeCachedTranslation(MatrixUtils.Transformation modelView)
    {
        this.cachedTranslation.set(this.rotationPointX / 16,
            (this.limb.parent.isEmpty() ? this.rotationPointY - 24 : this.rotationPointY) / 16,
            this.rotationPointZ / 16);

        if (modelView != null)
        {
            Matrix3f transformation = new Matrix3f(modelView.getRotation3f());

            transformation.mul(modelView.getScale3f());
            transformation.transform(this.cachedTranslation);
        }

        if (this.parent != null)
        {
            this.cachedTranslation.add(this.parent.cachedTranslation);
        }

        /* SEAM(morphs): legacy also folded model.current.cachedTranslation in
         * here and zeroed it (consume-once). CustomMorph lands in the morph
         * phase; wired then. */
    }

    /**
     * Render this limb and its children into a {@link VertexConsumer}. This is
     * the GL-boundary method — the transform math it drives
     * ({@link #pushTransform}, {@link #bake}) is independently testable.
     */
    public void render(MatrixStack matrices, VertexConsumer consumer, float scale, float r, float g, float b, float a, int light, int overlay)
    {
        if (this.isHidden || !this.showModel)
        {
            return;
        }

        if (!this.compiled)
        {
            this.bake();
        }

        if (MatrixUtils.matrix != null)
        {
            this.computeCachedTranslation(null);
        }

        matrices.push();
        this.pushTransform(matrices, scale);

        this.renderRenderer(matrices, consumer, scale, r, g, b, a, light, overlay);

        for (ModelCustomRenderer child : this.childModels)
        {
            child.render(matrices, consumer, scale, r, g, b, a, light, overlay);
        }

        this.updateObbs(matrices);

        matrices.pop();
    }

    /**
     * Legacy {@code ModelCustomRenderer.updateObbs()} (P75.2) — refresh this
     * limb's oriented bounding boxes from the limb's current model view, then
     * rebuild their corners (which is also what registers them for the F3
     * debug wireframe).
     *
     * <p><b>Where it runs.</b> Legacy called it from all three branches of
     * {@code render}, after the limb's own geometry and its children and
     * <i>inside</i> the limb's pushed transform — before the
     * {@code rotationPoint} translate was undone in the branch that had one.
     * {@link #pushTransform} folds those three branches into one, so this is
     * that same matrix state: offset + rotationPoint + rotation + scale.</p>
     *
     * <p><b>The decomposition.</b> {@code MatrixUtils.matrix} is captured at
     * the entity root ({@code RenderCustomModel}, at legacy's
     * {@code renderLivingAt} tail — after the entity translate, before any
     * rotation), so {@code extractTransformations(matrix, limbMatrix)} inverts
     * it out and yields the limb's transform <b>relative to the entity</b>.
     * That is what {@code offset} means, and it is why
     * {@link mchorse.blockbuster.common.OrientedBB#center} carries the
     * entity's absolute world position: their sum is world space. Legacy read
     * the same two matrices out of GL; here the limb matrix is the
     * {@link MatrixStack} we are standing in and the capture is a
     * {@code matrices.peek()} snapshot.</p>
     *
     * <p>The {@code matrix == null} branch is legacy's too: no capture (no
     * enclosing morph render — the model-editor preview, a model block) means
     * rotation/offset/scale keep their previous values and only
     * {@code buildCorners()} runs.</p>
     */
    public void updateObbs(MatrixStack matrices)
    {
        if (this.model == null || this.limb == null)
        {
            return;
        }

        Map<ModelLimb, List<OrientedBB>> limbs = this.model.getOrientedBBs();

        if (limbs == null)
        {
            return;
        }

        List<OrientedBB> obbs = limbs.get(this.limb);

        if (obbs == null)
        {
            return;
        }

        for (OrientedBB obb : obbs)
        {
            if (MatrixUtils.matrix != null && matrices != null)
            {
                MatrixUtils.Transformation modelView = MatrixUtils.extractTransformations(MatrixUtils.matrix,
                    RenderingUtilsClient.toVecmath(matrices.peek().getPositionMatrix()));

                obb.rotation.set(modelView.getRotation3f());
                obb.offset.set(modelView.getTranslation3f());
                obb.scale.set(modelView.getScale3f());
            }

            obb.buildCorners();
        }
    }

    /**
     * Stencil-pass variant of {@link #render} (roadmap P84/P158).
     *
     * <p>{@code glStencilFunc} is a <b>draw-time</b> GL call while
     * {@link VertexConsumer} emission is deferred until the provider is
     * flushed — so batching every limb into one buffer and flushing once would
     * rasterise all of them under the <em>last</em> limb's stencil index.
     * Legacy did not have this problem because each limb was an immediate-mode
     * display-list draw issued right after its own {@code glStencilFunc}.</p>
     *
     * <p>This path therefore takes a {@link StencilConsumers} seam and flushes
     * the previous limb's geometry <b>before</b> emitting this limb's
     * {@code glStencilFunc}, so every limb's quads are rasterised under its own
     * stencil index. The caller flushes once more after the walk to land the
     * last limb.</p>
     */
    public void renderForStencil(MatrixStack matrices, StencilConsumers buffers, float scale, int light, int overlay)
    {
        if (this.isHidden || !this.showModel)
        {
            return;
        }

        if (!this.compiled)
        {
            this.bake();
        }

        if (MatrixUtils.matrix != null)
        {
            this.computeCachedTranslation(null);
        }

        matrices.push();
        this.pushTransform(matrices, scale);

        float[] tint = this.resolveLimbTint(1F, 1F, 1F, 1F);

        if (tint != null)
        {
            /* Flush whatever the previous limb emitted, THEN set this limb's
             * stencil index, THEN emit this limb's geometry. */
            VertexConsumer consumer = buffers.flushAndGet();

            if (this.stencilRendering)
            {
                this.emitStencilFunc();
                this.stencilRendering = false;
            }

            int effectiveLight = this.resolveLimbLight(light);

            this.emit(matrices, consumer, scale, tint[0], tint[1], tint[2], tint[3], effectiveLight, overlay);
        }

        for (ModelCustomRenderer child : this.childModels)
        {
            child.renderForStencil(matrices, buffers, scale, light, overlay);
        }

        matrices.pop();
    }

    /**
     * Per-limb vertex-consumer seam for the stencil pass — see
     * {@link #renderForStencil}. Implementations flush every vertex emitted so
     * far to the framebuffer and hand back a fresh consumer for the next limb.
     */
    public interface StencilConsumers
    {
        VertexConsumer flushAndGet();
    }

    protected void renderRenderer(MatrixStack matrices, VertexConsumer consumer, float scale, float r, float g, float b, float a, int light, int overlay)
    {
        /* Zero-opacity early-out still updated cached matrices in legacy;
         * cachedTranslation is already computed above, so simply skip emission. */
        float[] tint = this.resolveLimbTint(r, g, b, a);

        if (tint == null)
        {
            return;
        }

        /* Stencil limb-picking (roadmap P84): tag this limb's geometry with its
         * stencil index. Legacy emitted GL11.glStencilFunc(GL_ALWAYS, index, -1)
         * right before the limb's display list; the caller
         * (GuiModelRenderer.renderForStencil) flushes the vertex consumer per
         * limb so this func lands before the limb's quads. GL-free headless. */
        if (this.stencilRendering)
        {
            this.emitStencilFunc();
            this.stencilRendering = false;
        }

        this.emit(matrices, consumer, scale, tint[0], tint[1], tint[2], tint[3], this.resolveLimbLight(light), overlay);
    }

    /**
     * Legacy {@code ModelCustomRenderer.setup} colour half: default to the
     * model limb's colour/opacity, but when the active pose transform is a
     * {@link LimbProperties} <b>replace</b> it with the pose colour (not
     * multiply). Returns {@code null} when the limb should not emit
     * ({@code opacity}/{@code color.a <= 0}).
     *
     * <p>The parent {@code r,g,b,a} is the modern vertex-tint stand-in for the
     * ambient GL colour stack; pose/model limb colour is multiplied on top so
     * a white pass-through matches legacy's {@code GlStateManager.color}.</p>
     */
    protected float[] resolveLimbTint(float r, float g, float b, float a)
    {
        /* Legacy early-out: limb.opacity <= 0 always skips; a pose with
         * color.a <= 0 also skips. */
        if (this.limb.opacity <= 0)
        {
            return null;
        }

        float lr = this.limb.color[0];
        float lg = this.limb.color[1];
        float lb = this.limb.color[2];
        float la = this.limb.opacity;

        if (this.trasnform instanceof LimbProperties)
        {
            LimbProperties prop = (LimbProperties) this.trasnform;

            if (prop.color.a <= 0)
            {
                return null;
            }

            /* Pose colour replaces the model limb colour (legacy setup()). */
            lr = prop.color.r;
            lg = prop.color.g;
            lb = prop.color.b;
            la = prop.color.a;
        }

        return new float[] {r * lr, g * lg, b * lb, a * la};
    }

    /**
     * Limb lighting: {@code lighting=false} pins the block lightmap half to
     * fullbright (legacy {@code setup}), then a pose {@link LimbProperties}
     * may further massage the lightmap via {@link LimbProperties#applyGlow}.
     */
    protected int resolveLimbLight(int light)
    {
        int effective = this.limb.lighting ? light : fullBrightBlockKeepSky(light);

        if (this.trasnform instanceof LimbProperties)
        {
            effective = ((LimbProperties) this.trasnform).applyGlow(effective);
        }

        return effective;
    }

    /**
     * Emit the per-limb {@code glStencilFunc} for P84 limb-picking. Guarded so
     * it is a headless no-op (no GL context): the P84 index-assignment /
     * name-mapping logic stays testable without a window.
     */
    private void emitStencilFunc()
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null || mc.getWindow() == null)
        {
            return;
        }

        try
        {
            org.lwjgl.opengl.GL11.glStencilFunc(org.lwjgl.opengl.GL11.GL_ALWAYS, this.stencilIndex, -1);
        }
        catch (Throwable t)
        {
            /* No GL context — picking simply yields nothing this frame. */
        }
    }

    /**
     * Emit baked box quads. Subclasses override for mesh geometry.
     */
    protected void emit(MatrixStack matrices, VertexConsumer consumer, float scale, float r, float g, float b, float a, int light, int overlay)
    {
        /* An is3D limb draws its extruded skin layer *instead of* its box —
         * legacy's renderDisplayList branched the same way, and drawing both
         * would z-fight the voxel shell against the flat quad it was built
         * from. */
        if (this.limb != null && this.limb.is3D)
        {
            this.emitExtruded(matrices, consumer, r, g, b, a, light, overlay);

            return;
        }

        /* Optional static-limb geometry cache (P86, off by default). When
         * enabled for a cacheable box limb, replay the cached local geometry —
         * byte-identical to the loop below (same pose, scale, colour, order). */
        if (LimbGeometryCache.isEnabled() && LimbGeometryCache.isCacheable(this))
        {
            LimbGeometryCache.emit(this, matrices, consumer, scale, r, g, b, a, light, overlay);

            return;
        }

        MatrixStack.Entry entry = matrices.peek();
        Matrix4f pose = entry.getPositionMatrix();

        for (float[][] box : this.baked)
        {
            for (float[] v : box)
            {
                consumer.vertex(pose, v[0] * scale, v[1] * scale, v[2] * scale)
                    .color(r, g, b, a)
                    .texture(v[3], v[4])
                    .overlay(overlay)
                    .light(light)
                    .normal(entry.getNormalMatrix(), v[5], v[6], v[7])
                    .next();
            }
        }
    }

    /**
     * Draw an {@code is3D} limb's extruded skin layer (legacy
     * {@code renderDisplayList}'s {@code limb.is3D} branch — the {@code _3d}
     * models' second layer, and the P79 seam that had no caller until now).
     *
     * <p>{@link ModelExtrudedLayer} voxelises the limb's region of the
     * <b>currently bound skin</b> by texel alpha and caches the mesh per
     * (renderer, skin), which is why the texture comes from
     * {@link RenderCustomModel#lastSkin} rather than from anything on the limb:
     * the same limb re-extrudes whenever the wearer's skin changes. No skin
     * means no pixels to extrude, so nothing is drawn — legacy's behaviour with
     * nothing bound.</p>
     *
     * <p>Deliberately <b>not</b> scaled by {@code scale}: the mesh is generated
     * with {@code 1/16} already folded into its vertices
     * ({@code generateGeometry}'s {@code f}), where the box path multiplies raw
     * pixel coordinates by the caller's {@code scale}. The two agree at the
     * 0.0625 every call site passes, and the constant is legacy's.</p>
     */
    private void emitExtruded(MatrixStack matrices, VertexConsumer consumer, float r, float g, float b, float a, int light, int overlay)
    {
        ResourceLocation skin = RenderCustomModel.lastSkin;

        if (skin == null || this.model == null || this.model.model == null)
        {
            return;
        }

        ModelExtrudedLayer.render3DLayer(this, skin,
            new ModelExtrudedLayer.ExtrudeParams(this.limb, this.model.model),
            consumer, matrices.peek(), light, overlay, r, g, b, a);
    }

    /**
     * Recursive parent-first matrix application used by feature layers
     * (P76/P80). GL-free.
     */
    public void postRender(MatrixStack matrices, float scale)
    {
        if (this.parent != null)
        {
            this.parent.postRender(matrices, scale);
        }

        if (this.isHidden || !this.showModel)
        {
            return;
        }

        this.pushTransform(matrices, scale);
    }

    /**
     * Free baked GPU resources. No GPU state is held until the P86 VBO cache;
     * the method is kept as the lifecycle contract.
     */
    public void delete()
    {
        /* P86: drop any cached static geometry (frees GPU residency on the
         * render thread). No-op when the cache holds nothing for this limb. */
        LimbGeometryCache.invalidate(this);

        this.baked = null;
        this.compiled = false;
    }
}
