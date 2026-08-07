package mchorse.blockbuster_pack.morphs;

import com.google.common.base.Objects;
import mchorse.blockbuster.CommonProxy;
import mchorse.blockbuster.api.Model;
import mchorse.blockbuster.api.ModelHandler;
import mchorse.blockbuster.api.ModelLimb;
import mchorse.blockbuster.api.ModelPose;
import mchorse.blockbuster.api.ModelTransform;
import mchorse.blockbuster.api.formats.obj.ShapeKey;
import mchorse.blockbuster.common.OrientedBB;
import mchorse.blockbuster.common.entity.EntityActor;
import mchorse.mclib.utils.Color;
import mchorse.mclib.utils.Interpolation;
import mchorse.mclib.utils.NBTUtils;
import mchorse.mclib.utils.resources.RLUtils;
import mchorse.mclib.utils.resources.ResourceLocation;
import mchorse.metamorph.api.EntityUtils;
import mchorse.metamorph.api.models.IMorphProvider;
import mchorse.metamorph.api.morphs.AbstractMorph;
import mchorse.metamorph.api.morphs.utils.Animation;
import mchorse.metamorph.api.morphs.utils.IAnimationProvider;
import mchorse.metamorph.api.morphs.utils.IMorphGenerator;
import mchorse.metamorph.api.morphs.utils.ISyncableMorph;
import mchorse.metamorph.bodypart.BodyPart;
import mchorse.metamorph.bodypart.BodyPartManager;
import mchorse.metamorph.bodypart.IBodyPartProvider;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom morph (the flagship model morph, roadmap P158).
 *
 * <p>This is a morph which allows players to use Blockbuster's custom models as
 * morphs. It carries a {@link Model} blueprint, a skin/material override set, a
 * custom pose (or a named model pose, optionally applied only while sneaking),
 * an animated pose-transition ({@link PoseAnimation}), body parts
 * ({@link BodyPartManager}), oriented bounding boxes per limb (gun hit-detection,
 * S17) and a cape spring simulation.</p>
 *
 * <p><b>NBT is a wire/disk contract</b> (embedded in records, scenes, player
 * data, model blocks, {@code /morph}); {@code toNBT} writes only non-defaults
 * and {@code fromNBT} re-resolves the model only when the {@code Name} tag
 * changed, keeping the old model when the new lookup fails (load order:
 * morph parses before models load). See {@code plan/S14-morph-pack.md} §P158.</p>
 *
 * <p><b>Render seam.</b> Legacy {@code renderOnScreen}/{@code render}/
 * {@code renderHand} + {@code drawModel} were {@code @SideOnly(CLIENT)} GL
 * bodies delegating to {@code ClientProxy.actorRenderer} ({@code
 * RenderCustomModel}). Per the S14 stage overview the actor renderer itself is
 * an S6 deliverable (P76/P80, {@code RenderCustomActor}); the GL bodies land
 * with that pipeline. Here we keep only the data path (model hot-reload, OBB
 * fill, body-part update, cape spring) plus the legacy
 * {@link #renderingOnScreen} static — the client render path toggles it around
 * its on-screen draw via {@link #setRenderingOnScreen}. The
 * per-limb {@link LimbProperties#applyGlow(int)} lightmap massaging (glow=1 →
 * 240 fullbright) and pose colour are applied by {@code ModelCustomRenderer}
 * when the active transform is a {@link LimbProperties}.</p>
 *
 * Legacy source: blockbuster-1.12/.../blockbuster_pack/morphs/CustomMorph.java
 */
public class CustomMorph extends AbstractMorph implements IBodyPartProvider, IAnimationProvider, ISyncableMorph, IMorphGenerator
{
    private static boolean renderingOnScreen;

    /**
     * OrientedBoundingBoxes List by limbs
     */
    public Map<ModelLimb, List<OrientedBB>> orientedBBlimbs;

    /**
     * Morph's model
     */
    public Model model;

    /**
     * Current pose
     */
    protected ModelPose pose;

    /**
     * Current custom pose
     */
    public String currentPose = "";

    /**
     * Apply current pose on sneaking
     */
    public boolean currentPoseOnSneak = false;

    /**
     * Skin for custom morph
     */
    public ResourceLocation skin;

    /**
     * Custom pose
     */
    public ModelProperties customPose = null;

    /**
     * Map of textures designated to specific OBJ materials
     */
    public Map<String, ResourceLocation> materials = new HashMap<String, ResourceLocation>();

    /**
     * Scale of this model
     */
    public float scale = 1F;

    /**
     * Scale of this model in morph GUIs
     */
    public float scaleGui = 1F;

    /**
     * Whether this image morph should cut out background color
     */
    public boolean keying;

    /**
     * Animation details
     */
    public PoseAnimation animation = new PoseAnimation();

    /**
     * Body part manager
     */
    public BodyPartManager parts = new BodyPartManager();

    /**
     * Cached key value
     */
    private String key;

    private long lastUpdate;

    /* Cape variables */
    public double prevCapeX;
    public double prevCapeY;
    public double prevCapeZ;
    public double capeX;
    public double capeY;
    public double capeZ;

    /**
     * Make hands true!
     */
    public CustomMorph()
    {
        super();

        this.getSettings().hands = true;
    }

    public static boolean isRenderingOnScreen()
    {
        return renderingOnScreen;
    }

    /**
     * SEAM(S6): the client on-screen render path (P76/P80) toggles this around
     * {@code renderOnScreen}, so a morph can tell it is being drawn in a GUI
     * rather than in the world.
     */
    public static void setRenderingOnScreen(boolean value)
    {
        renderingOnScreen = value;
    }

    /**
     * This method fills the obbsLimb Map with data from the model blueprint.
     * @param force if true it ignores that orientedBBlimbs might already be filled
     */
    public void fillObbs(boolean force)
    {
        if (this.orientedBBlimbs == null || force)
        {
            this.orientedBBlimbs = new HashMap<>();

            if (this.model != null)
            {
                for (ModelLimb limb : this.model.limbs.values())
                {
                    List<OrientedBB> newObbs = new ArrayList<>();

                    for (OrientedBB obb : limb.obbs)
                    {
                        newObbs.add(obb.clone());
                    }

                    this.orientedBBlimbs.put(limb, newObbs);
                }
            }
        }
    }

    @Override
    public void pause(AbstractMorph previous, int offset)
    {
        this.animation.pause(offset);

        while (previous instanceof IMorphProvider)
        {
            previous = ((IMorphProvider) previous).getMorph();
        }

        if (previous instanceof CustomMorph)
        {
            CustomMorph custom = (CustomMorph) previous;
            ModelPose pose = custom.getCurrentPose();

            if (!this.animation.ignored)
            {
                if (custom.animation.isInProgress() && pose != null)
                {
                    this.animation.last = this.convertProp(custom.animation.calculatePose(pose, 1).copy());
                }
                else
                {
                    this.animation.last = this.convertProp(pose);
                }
            }
            else if (custom.customPose != null)
            {
                this.customPose = custom.customPose;
            }
            else if (!custom.currentPose.isEmpty())
            {
                this.customPose = null;
                this.currentPose = custom.currentPose;
            }

            if (pose != null)
            {
                this.animation.mergeShape(pose.shapes);
            }
        }

        this.parts.pause(previous, offset);
    }

    @Override
    public boolean isPaused()
    {
        return this.animation.paused;
    }

    @Override
    public Animation getAnimation()
    {
        return this.animation;
    }

    @Override
    public boolean canGenerate()
    {
        return this.animation.isInProgress();
    }

    @Override
    public AbstractMorph genCurrentMorph(float partialTicks)
    {
        CustomMorph morph = (CustomMorph) this.copy();

        if (this.getCurrentPose() != null)
        {
            morph.customPose = this.convertProp(this.animation.calculatePose(this.getCurrentPose(), partialTicks));
            morph.customPose.shapes.clear();
            morph.customPose.shapes.addAll(this.getShapesForRendering(partialTicks));
        }

        morph.animation.duration = this.animation.progress;

        morph.parts.parts.clear();

        for (BodyPart part : this.parts.parts)
        {
            morph.parts.parts.add(part.genCurrentBodyPart(this, partialTicks));
        }

        return morph;
    }

    public List<ShapeKey> getShapesForRendering(float partialTick)
    {
        if (this.model.shapes.isEmpty())
        {
            return this.getCurrentPose().shapes;
        }

        if (this.animation.isInProgress())
        {
            return this.animation.calculateShapes(this, partialTick);
        }

        return this.getCurrentPose().shapes;
    }

    @Override
    protected String getSubclassDisplayName()
    {
        if (this.model != null)
        {
            return this.model.name;
        }

        return super.getSubclassDisplayName();
    }

    public void changeModel(String model)
    {
        if (models() == null || models().models.get(model) == null)
        {
            return;
        }

        this.name = "blockbuster." + model;
        this.key = null;
        this.model = models().models.get(model);

        fillObbs(true);

        if (this.customPose != null)
        {
            this.customPose.updateLimbs(this.model, false);
        }
    }

    @Override
    public BodyPartManager getBodyPart()
    {
        return this.parts;
    }

    /**
     * Get a pose for rendering
     */
    public ModelPose getPose(LivingEntity target, float partialTicks)
    {
        return this.getPose(target, false, partialTicks);
    }

    /**
     * Get a pose for rendering
     */
    public ModelPose getPose(LivingEntity target, boolean ignoreCustom, float partialTicks)
    {
        ModelPose pose = this.getCurrentPose(target, ignoreCustom);

        if (this.animation.isInProgress() && pose != null)
        {
            return this.animation.calculatePose(pose, partialTicks);
        }

        return pose;
    }

    private ModelPose getCurrentPose(LivingEntity target, boolean ignoreCustom)
    {
        if (this.customPose != null && !ignoreCustom)
        {
            if (this.currentPoseOnSneak && target.isSneaking() || !this.currentPoseOnSneak)
            {
                return this.customPose;
            }
        }

        String poseName = EntityUtils.getPose(target, this.currentPose, this.currentPoseOnSneak);

        if (target instanceof EntityActor)
        {
            poseName = ((EntityActor) target).isMounted ? "riding" : poseName;
        }

        return this.model == null ? null : this.model.getPose(poseName);
    }

    public ModelPose getCurrentPose()
    {
        return this.customPose != null ? this.customPose : (this.model == null ? null : this.model.getPose(this.currentPose));
    }

    public String getKey()
    {
        if (this.key == null)
        {
            this.key = this.name.replaceAll("^blockbuster\\.", "");
        }

        return this.key;
    }

    public void updateModel()
    {
        this.updateModel(false);
    }

    public void updateModel(boolean force)
    {
        if (this.lastUpdate < ModelHandler.lastUpdate || force)
        {
            this.lastUpdate = ModelHandler.lastUpdate;
            this.model = models() == null ? this.model : models().models.get(this.getKey());

            fillObbs(true);

            if (this.customPose != null)
            {
                this.customPose.updateLimbs(this.model, false);
            }
        }
    }

    /**
     * Update the player based on its morph abilities and properties. This
     * method also responsible for updating AABB size.
     */
    @Override
    public void update(LivingEntity target)
    {
        this.updateModel();
        this.animation.update();
        this.parts.updateBodyLimbs(this, target);

        super.update(target);

        if (target.getWorld().isClient)
        {
            this.updateCapeVariables(target);
        }
    }

    private void updateCapeVariables(LivingEntity target)
    {
        this.stepCape(target.getX(), target.getY(), target.getZ());
    }

    /**
     * Cape spring step (chase factor {@code 0.25}, per-axis snap when the delta
     * exceeds 10 blocks). Extracted from {@code updateCapeVariables} so the
     * teleport-snap behavior is unit-testable without a live entity; the math is
     * a verbatim port of 1.12.2.
     */
    void stepCape(double x, double y, double z)
    {
        this.prevCapeX = this.capeX;
        this.prevCapeY = this.capeY;
        this.prevCapeZ = this.capeZ;

        double dX = x - this.capeX;
        double dY = y - this.capeY;
        double dZ = z - this.capeZ;
        double multiplier = 0.25D;

        if (Math.abs(dX) > 10)
        {
            this.capeX = x;
            this.prevCapeX = this.capeX;
        }

        if (Math.abs(dY) > 10)
        {
            this.capeY = y;
            this.prevCapeY = this.capeY;
        }

        if (Math.abs(dZ) > 10)
        {
            this.capeZ = z;
            this.prevCapeZ = this.capeZ;
        }

        this.capeX += dX * multiplier;
        this.capeY += dY * multiplier;
        this.capeZ += dZ * multiplier;
    }

    @Override
    protected void updateUserHitbox(LivingEntity target)
    {
        this.pose = this.getPose(target, 0);

        if (this.pose != null)
        {
            float[] pose = this.pose.size;

            this.updateSize(target, pose[0] * this.scale, pose[1] * this.scale);
        }
    }

    @Override
    public float getWidth(LivingEntity target)
    {
        return (this.pose != null ? this.pose.size[0] : 0.6F) * this.scale;
    }

    @Override
    public float getHeight(LivingEntity target)
    {
        return (this.pose != null ? this.pose.size[1] : 1.8F) * this.scale;
    }

    /**
     * Check whether given object equals to this object
     *
     * This method is responsible for checking whether other {@link CustomMorph}
     * has the same skin as this morph. This method plays very big role in
     * morphing and morph acquiring.
     */
    @Override
    public boolean equals(Object object)
    {
        boolean result = super.equals(object);

        if (object instanceof CustomMorph)
        {
            CustomMorph morph = (CustomMorph) object;

            result = result && Objects.equal(this.currentPose, morph.currentPose);
            result = result && Objects.equal(this.skin, morph.skin);
            result = result && Objects.equal(this.customPose, morph.customPose);
            result = result && this.currentPoseOnSneak == morph.currentPoseOnSneak;
            result = result && this.scale == morph.scale;
            result = result && this.scaleGui == morph.scaleGui;
            result = result && this.materials.equals(morph.materials);
            result = result && this.parts.equals(morph.parts);
            result = result && this.keying == morph.keying;
            result = result && this.animation.equals(morph.animation);

            return result;
        }

        return result;
    }

    @Override
    public boolean canMerge(AbstractMorph morph)
    {
        if (morph instanceof CustomMorph)
        {
            CustomMorph custom = (CustomMorph) morph;

            this.mergeBasic(morph);

            /* Don't suddenly end the animation in progress, interpolate */
            if (!custom.animation.ignored)
            {
                /* If the last pose is null, it might case a first cycle freeze.
                 * this should fix it. */
                ModelPose pose = this.getCurrentPose();

                if (this.animation.isInProgress() && pose != null)
                {
                    this.animation.last = this.convertProp(this.animation.calculatePose(pose, 0).copy());
                }
                else
                {
                    this.animation.last = this.convertProp(pose);
                }

                this.currentPose = custom.currentPose;
                this.customPose = custom.customPose == null ? null : custom.customPose.copy();
                this.animation.merge(custom.animation);

                if (pose != null)
                {
                    this.animation.mergeShape(pose.shapes);
                }
            }
            else
            {
                this.animation.ignored = true;
            }

            this.key = null;
            this.name = custom.name;
            this.skin = RLUtils.clone(custom.skin);
            this.currentPoseOnSneak = custom.currentPoseOnSneak;
            this.scale = custom.scale;
            this.scaleGui = custom.scaleGui;
            this.materials.clear();

            for (Map.Entry<String, ResourceLocation> entry : custom.materials.entrySet())
            {
                this.materials.put(entry.getKey(), RLUtils.clone(entry.getValue()));
            }

            this.parts.merge(custom.parts);
            this.model = custom.model;

            return true;
        }

        return super.canMerge(morph);
    }

    @Override
    public void afterMerge(AbstractMorph morph)
    {
        super.afterMerge(morph);

        while (morph instanceof IMorphProvider)
        {
            morph = ((IMorphProvider) morph).getMorph();
        }

        if (morph instanceof IBodyPartProvider)
        {
            this.recursiveAfterMerge(this, (IBodyPartProvider) morph);
        }

        if (morph instanceof CustomMorph)
        {
            this.copyPoseForAnimation(this, (CustomMorph) morph);
        }
    }

    private void recursiveAfterMerge(IBodyPartProvider target, IBodyPartProvider destination)
    {
        for (int i = 0, c = target.getBodyPart().parts.size(); i < c; i++)
        {
            if (i >= destination.getBodyPart().parts.size())
            {
                break;
            }

            AbstractMorph a = target.getBodyPart().parts.get(i).morph.get();
            AbstractMorph b = destination.getBodyPart().parts.get(i).morph.get();

            if (a != null)
            {
                a.afterMerge(b);
            }
        }
    }

    private void copyPoseForAnimation(CustomMorph target, CustomMorph destination)
    {
        /* If the last pose is null, it might case a first cycle freeze.
         * this should fix it. */
        ModelPose pose = destination.getCurrentPose();

        target.animation.progress = 0;

        if (destination.animation.isInProgress() && pose != null)
        {
            target.animation.last = this.convertProp(destination.animation.calculatePose(pose, 0).copy());
        }
        else
        {
            target.animation.last = this.convertProp(pose);
        }
    }

    @Override
    public void reset()
    {
        super.reset();

        this.key = null;
        this.parts.reset();
        this.animation.reset();
        this.scale = this.scaleGui = 1F;
    }

    @Override
    public AbstractMorph create()
    {
        return new CustomMorph();
    }

    @Override
    public void copy(AbstractMorph from)
    {
        super.copy(from);

        if (from instanceof CustomMorph)
        {
            CustomMorph morph = (CustomMorph) from;

            this.skin = RLUtils.clone(morph.skin);

            this.currentPose = morph.currentPose;
            this.currentPoseOnSneak = morph.currentPoseOnSneak;
            this.scale = morph.scale;
            this.scaleGui = morph.scaleGui;
            this.keying = morph.keying;

            if (morph.customPose != null)
            {
                this.customPose = morph.customPose.copy();
            }

            if (!morph.materials.isEmpty())
            {
                this.materials.clear();

                for (Map.Entry<String, ResourceLocation> entry : morph.materials.entrySet())
                {
                    this.materials.put(entry.getKey(), RLUtils.clone(entry.getValue()));
                }
            }

            this.model = morph.model;
            this.parts.copy(morph.parts);
            this.animation.copy(morph.animation);
        }
    }

    @Override
    public void toNBT(NbtCompound tag)
    {
        super.toNBT(tag);

        if (this.skin != null)
        {
            tag.put("Skin", RLUtils.writeNbt(this.skin));
        }

        if (!this.currentPose.isEmpty()) tag.putString("Pose", this.currentPose);
        if (this.currentPoseOnSneak) tag.putBoolean("Sneak", this.currentPoseOnSneak);
        if (this.scale != 1F) tag.putFloat("Scale", this.scale);
        if (this.scaleGui != 1F) tag.putFloat("ScaleGUI", this.scaleGui);
        if (this.keying) tag.putBoolean("Keying", this.keying);

        if (this.customPose != null)
        {
            tag.put("CustomPose", this.customPose.toNBT(new NbtCompound()));
        }

        if (!this.materials.isEmpty())
        {
            NbtCompound materials = new NbtCompound();

            for (Map.Entry<String, ResourceLocation> entry : this.materials.entrySet())
            {
                materials.put(entry.getKey(), RLUtils.writeNbt(entry.getValue()));
            }

            tag.put("Materials", materials);
        }

        NbtList bodyParts = this.parts.toNBT();

        if (bodyParts != null)
        {
            tag.put("BodyParts", bodyParts);
        }

        NbtCompound animation = this.animation.toNBT();

        if (!animation.isEmpty())
        {
            tag.put("Animation", animation);
        }
    }

    @Override
    public void fromNBT(NbtCompound tag)
    {
        String name = this.name;

        super.fromNBT(tag);

        /* Replace the current model */
        if (!name.equals(this.name))
        {
            Model model = models() == null ? null : models().models.get(this.getKey());

            this.model = model == null ? this.model : model;
        }

        if (tag.contains("Skin"))
        {
            this.skin = RLUtils.create(tag.get("Skin"));
        }

        this.currentPose = tag.getString("Pose");
        this.currentPoseOnSneak = tag.getBoolean("Sneak");
        if (tag.contains("Scale", NbtElement.NUMBER_TYPE)) this.scale = tag.getFloat("Scale");
        if (tag.contains("ScaleGUI", NbtElement.NUMBER_TYPE)) this.scaleGui = tag.getFloat("ScaleGUI");
        if (tag.contains("Keying")) this.keying = tag.getBoolean("Keying");

        if (tag.contains("CustomPose", NbtElement.COMPOUND_TYPE))
        {
            this.customPose = new ModelProperties();
            this.customPose.fromNBT(tag.getCompound("CustomPose"));
        }

        if (tag.contains("Materials", NbtElement.COMPOUND_TYPE))
        {
            NbtCompound materials = tag.getCompound("Materials");

            this.materials.clear();

            for (String key : materials.getKeys())
            {
                this.materials.put(key, RLUtils.create(materials.get(key)));
            }
        }

        if (tag.contains("BodyParts", NbtElement.LIST_TYPE))
        {
            this.parts.fromNBT(tag.getList("BodyParts", NbtElement.COMPOUND_TYPE));
        }

        if (tag.contains("Animation"))
        {
            this.animation.fromNBT(tag.getCompound("Animation"));
        }
    }

    public ModelProperties convertProp(ModelPose pose)
    {
        if (pose == null || pose instanceof ModelProperties)
        {
            return (ModelProperties) pose;
        }

        NbtCompound tag = pose.toNBT(new NbtCompound());

        ModelProperties props = new ModelProperties();

        props.fromNBT(tag);

        if (this.model != null)
        {
            props.updateLimbs(this.model, true);
        }

        return props;
    }

    /**
     * Convenience accessor for the domain {@link ModelHandler} (legacy
     * {@code Blockbuster.proxy.models}). May be {@code null} in headless / early
     * load — callers guard so the reader stays total (model lookup failure keeps
     * the existing model rather than crashing).
     */
    private static ModelHandler models()
    {
        return CommonProxy.models;
    }

    /**
     * Animation details
     */
    public static class PoseAnimation extends Animation
    {
        public Map<String, LimbProperties> lastProps;
        public List<ShapeKey> lastShapes = new ArrayList<ShapeKey>();
        public ModelProperties last;
        public ModelProperties pose = new ModelProperties();

        private List<ShapeKey> temporaryShapes = new ArrayList<ShapeKey>();

        @Override
        public void merge(Animation animation)
        {
            super.merge(animation);
            this.pose.limbs.clear();
        }

        public void mergeShape(List<ShapeKey> shapes)
        {
            this.lastShapes.clear();
            this.lastShapes.addAll(shapes);
        }

        public List<ShapeKey> calculateShapes(CustomMorph morph, float partialTicks)
        {
            float factor = this.getFactor(partialTicks);

            this.temporaryShapes.clear();

            for (ShapeKey key : morph.getCurrentPose().shapes)
            {
                ShapeKey last = null;

                for (ShapeKey previous : this.lastShapes)
                {
                    if (previous.name.equals(key.name))
                    {
                        last = previous;

                        break;
                    }
                }

                this.temporaryShapes.add(new ShapeKey(key.name, this.interp.interpolate(last == null ? 0 : last.value, key.value, factor), key.relative));
            }

            for (ShapeKey key : this.lastShapes)
            {
                ShapeKey last = null;

                for (ShapeKey previous : this.temporaryShapes)
                {
                    if (previous.name.equals(key.name))
                    {
                        last = previous;

                        break;
                    }
                }

                if (last == null)
                {
                    this.temporaryShapes.add(new ShapeKey(key.name, this.interp.interpolate(key.value, 0, factor), key.relative));
                }
            }

            return this.temporaryShapes;
        }

        @Override
        public boolean isInProgress()
        {
            return super.isInProgress() && this.last != null;
        }

        public ModelPose calculatePose(ModelPose current, float partialTicks)
        {
            float factor = this.getFactor(partialTicks);

            for (Map.Entry<String, ModelTransform> entry : current.limbs.entrySet())
            {
                String key = entry.getKey();
                ModelTransform trans = this.pose.limbs.get(key);
                ModelTransform last = this.last.limbs.get(key);

                if (last == null)
                {
                    continue;
                }

                if (trans == null)
                {
                    trans = new LimbProperties();
                    this.pose.limbs.put(key, trans);
                }

                trans.interpolate(last, entry.getValue(), factor, this.interp);
            }

            for (int i = 0; i < this.pose.size.length; i++)
            {
                this.pose.size[i] = this.interp.interpolate(this.last.size[i], current.size[i], factor);
            }

            return this.pose;
        }
    }

    public static class LimbProperties extends ModelTransform
    {
        public float fixed = 0F;
        public float glow = 0F;
        public Color color = new Color(1F, 1F, 1F, 1F);

        public boolean absoluteBrightness = false;

        @Override
        public boolean isDefault()
        {
            return false;
        }

        /**
         * Pose-freeze blend factor consumed by the P82 pose runtime
         * ({@code anim = 1 - fixed}). {@link ModelTransform#getFixed()} returns 0
         * for plain transforms; a {@link LimbProperties} contributes its
         * {@code fixed} field.
         */
        @Override
        public float getFixed()
        {
            return this.fixed;
        }

        /**
         * Legacy {@code LimbProperties.applyGlow}: blend the lightmap's block
         * half toward fullbright ({@code 240}) by {@link #glow}. When
         * {@link #absoluteBrightness} is set the current block value is forced
         * to 0 first so glow is measured from darkness rather than ambient.
         *
         * <p>1.20.4 packs the two 1.12 lightmap coordinates into one int
         * ({@code block | sky << 16}); the low half is the block coordinate
         * (same 0–240 scale as {@code OpenGlHelper.lastBrightnessX}).</p>
         */
        public int applyGlow(int light)
        {
            float lastX = (float) (light & 0xFFFF);
            int sky = light & 0xFFFF0000;

            if (this.absoluteBrightness)
            {
                lastX = 0F;
            }

            /* LINEAR.interpolate(lastX, 240, glow) */
            int block = Math.round(lastX + (240F - lastX) * this.glow);

            return sky | (block & 0xFFFF);
        }

        @Override
        public void copy(ModelTransform transform)
        {
            super.copy(transform);

            if (transform instanceof LimbProperties)
            {
                LimbProperties prop = (LimbProperties) transform;

                this.fixed = prop.fixed;
                this.glow = prop.glow;
                this.color.copy(prop.color);

                this.absoluteBrightness = prop.absoluteBrightness;
            }
        }

        @Override
        public boolean equals(Object obj)
        {
            if (obj instanceof LimbProperties)
            {
                LimbProperties prop = (LimbProperties) obj;

                return super.equals(obj) &&
                        this.fixed == prop.fixed &&
                        Math.abs(this.glow - prop.glow) < 0.0001f &&
                        this.color.equals(prop.color) &&
                        this.absoluteBrightness == prop.absoluteBrightness;
            }

            return false;
        }

        @Override
        public LimbProperties clone()
        {
            LimbProperties b = new LimbProperties();

            b.copy(this);

            return b;
        }

        @Override
        public void fromNBT(NbtCompound tag)
        {
            super.fromNBT(tag);

            if (tag.contains("F", NbtElement.BYTE_TYPE)) this.fixed = tag.getBoolean("F") ? 1F : 0F;
            if (tag.contains("G", NbtElement.FLOAT_TYPE)) this.glow = tag.getFloat("G");
            if (tag.contains("C", NbtElement.INT_TYPE)) this.color.set(tag.getInt("C"));
            if (tag.contains("AB", NbtElement.BYTE_TYPE)) this.absoluteBrightness = tag.getBoolean("AB");
        }

        @Override
        public NbtCompound toNBT()
        {
            NbtCompound tag = new NbtCompound();

            if (!this.isDefault())
            {
                if (!equalFloatArray(DEFAULT.translate, this.translate)) tag.put("P", NBTUtils.writeFloatList(new NbtList(), this.translate));
                if (!equalFloatArray(DEFAULT.scale, this.scale)) tag.put("S", NBTUtils.writeFloatList(new NbtList(), this.scale));
                if (!equalFloatArray(DEFAULT.rotate, this.rotate)) tag.put("R", NBTUtils.writeFloatList(new NbtList(), this.rotate));
                if (this.fixed != 0F) tag.putBoolean("F", true);
                if (this.glow > 0.0001F) tag.putFloat("G", this.glow);
                if (this.color.getRGBAColor() != 0xFFFFFFFF) tag.putInt("C", this.color.getRGBAColor());
                if (this.absoluteBrightness) tag.putBoolean("AB", this.absoluteBrightness);
            }

            return tag;
        }

        @Override
        public void interpolate(ModelTransform a, ModelTransform b, float x, Interpolation interp)
        {
            super.interpolate(a, b, x, interp);

            float fixed = 0F;
            float glow = 0F;
            float cr, cg, cb, ca;

            cr = cg = cb = ca = 1F;

            if (a instanceof LimbProperties)
            {
                LimbProperties l = (LimbProperties) a;

                fixed = l.fixed;
                glow = l.glow;
                cr = l.color.r;
                cg = l.color.g;
                cb = l.color.b;
                ca = l.color.a;
            }

            if (b instanceof LimbProperties)
            {
                LimbProperties l = (LimbProperties) b;

                fixed = interp.interpolate(fixed, l.fixed, x);
                glow = interp.interpolate(glow, l.glow, x);
                cr = interp.interpolate(cr, l.color.r, x);
                cg = interp.interpolate(cg, l.color.g, x);
                cb = interp.interpolate(cb, l.color.b, x);
                ca = interp.interpolate(ca, l.color.a, x);

                this.absoluteBrightness = l.absoluteBrightness;
            }
            else
            {
                fixed = interp.interpolate(fixed, 0F, x);
                glow = interp.interpolate(glow, 0F, x);
                cr = interp.interpolate(cr, 1F, x);
                cg = interp.interpolate(cg, 1F, x);
                cb = interp.interpolate(cb, 1F, x);
                ca = interp.interpolate(ca, 1F, x);

                this.absoluteBrightness = false;
            }

            this.fixed = fixed;
            this.glow = glow;
            this.color.set(cr, cg, cb, ca);
        }
    }

    public static class ModelProperties extends ModelPose
    {
        @Override
        public ModelProperties copy()
        {
            ModelProperties b = new ModelProperties();

            b.size = new float[] {this.size[0], this.size[1], this.size[2]};

            for (Map.Entry<String, ModelTransform> entry : this.limbs.entrySet())
            {
                b.limbs.put(entry.getKey(), entry.getValue().clone());
            }

            for (ShapeKey key : this.shapes)
            {
                b.shapes.add(key.copy());
            }

            return b;
        }

        public void updateLimbs(Model model, boolean override)
        {
            if (model == null)
            {
                return;
            }

            for (Map.Entry<String, ModelLimb> entry : model.limbs.entrySet())
            {
                ModelLimb limb = entry.getValue();
                LimbProperties prop = (LimbProperties) this.limbs.get(entry.getKey());
                boolean newProp = false;

                if (prop == null)
                {
                    prop = new LimbProperties();
                    newProp = true;

                    this.limbs.put(entry.getKey(), prop);
                }

                if (newProp || override)
                {
                    prop.color.set(limb.color[0], limb.color[1], limb.color[2], limb.opacity);
                    prop.glow = limb.lighting ? 0.0f : 1.0f;
                }
            }
        }

        @Override
        public void fillInMissing(ModelPose pose)
        {
            for (Map.Entry<String, ModelTransform> entry : pose.limbs.entrySet())
            {
                String key = entry.getKey();

                if (!this.limbs.containsKey(key))
                {
                    LimbProperties limb = new LimbProperties();
                    limb.copy(entry.getValue());
                    this.limbs.put(key, limb);
                }
            }
        }

        @Override
        public void fromNBT(NbtCompound tag)
        {
            if (tag.contains("Size", NbtElement.LIST_TYPE))
            {
                NbtList list = tag.getList("Size", 5);

                if (list.size() >= 3)
                {
                    NBTUtils.readFloatList(list, this.size);
                }
            }

            if (tag.contains("Poses", NbtElement.COMPOUND_TYPE))
            {
                this.limbs.clear();

                NbtCompound poses = tag.getCompound("Poses");

                for (String key : poses.getKeys())
                {
                    ModelTransform trans = new LimbProperties();

                    trans.fromNBT(poses.getCompound(key));
                    this.limbs.put(key, trans);
                }
            }

            if (tag.contains("Shapes"))
            {
                NbtList shapes = tag.getList("Shapes", NbtElement.COMPOUND_TYPE);

                this.shapes.clear();

                for (int i = 0; i < shapes.size(); i++)
                {
                    NbtCompound key = shapes.getCompound(i);

                    if (key.contains("Name") && key.contains("Value"))
                    {
                        ShapeKey shapeKey = new ShapeKey();

                        shapeKey.fromNBT(key);
                        this.shapes.add(shapeKey);
                    }
                }
            }
        }
    }
}
