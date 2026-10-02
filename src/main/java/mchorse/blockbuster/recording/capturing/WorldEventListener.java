package mchorse.blockbuster.recording.capturing;

import java.util.List;

import mchorse.blockbuster.Blockbuster;
import mchorse.blockbuster.CommonProxy;
import mchorse.blockbuster.common.block.BlockDirector;
import mchorse.blockbuster.common.entity.EntityActor;
import mchorse.blockbuster.recording.actions.Action;
import mchorse.blockbuster.recording.actions.BreakBlockAnimation;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Blockbuster's world event listener (roadmap P108 seam + P114 parity).
 *
 * <p>This dude is responsible only for adding breaking block animation during
 * player recording, plus feeding damage control the block/entity changes that
 * happen while a recording is running.</p>
 *
 * <p>Legacy was an {@code IWorldEventListener} attached per server world; that
 * interface no longer exists on 1.20.4, so this is now a plain class invoked
 * from mixins ({@code WorldMixin.setBlockState} HEAD for the static
 * {@link #setBlockState} coremod target, {@code ServerWorldMixin
 * .setBlockBreakingInfo} for {@link #sendBlockBreakProgress},
 * {@code ServerWorldMixin.addEntity} / {@code EntityMixin.setRemoved} /
 * {@code LivingEntityMixin.onDeath} for the entity feed,
 * {@code ServerPlayerEntityMixin.openHandledScreen} for containers).</p>
 */
public class WorldEventListener
{
    /**
     * Damage control feed (legacy {@code notifyBlockUpdate} + the
     * {@code WorldTransformer} coremod), called from {@code WorldMixin} at the
     * HEAD of {@code World.setBlockState}.
     *
     * <p>wixo (CDC §6, R1): the block and its block entity are journaled
     * <b>here</b>, before anything changes. Legacy kept a reference to the
     * block entity and serialized it after the change, by which time a chest
     * had scattered its content on the ground: restored chests came back
     * empty. Director blocks are skipped (actors must never toggle them) and a
     * moving piston is journaled as air.</p>
     */
    public static void setBlockState(World world, BlockPos pos, BlockState newState, int flags)
    {
        if (world.isClient() || !Blockbuster.damageControl.get() || !CommonProxy.damage.isCapturing())
        {
            return;
        }

        BlockState oldState = world.getBlockState(pos);

        if (oldState == newState)
        {
            return;
        }

        BlockState resolved = resolveDamageOldState(oldState);

        if (resolved == null)
        {
            return;
        }

        BlockEntity be = world.getBlockEntity(pos);

        CommonProxy.damage.addBlock(world, pos, resolved, be == null ? null : be.createNbtWithIdentifyingData());
    }

    /**
     * wixo (R1): a player opens a screen. Containers nearby are journaled as
     * they are now, so items taken out without breaking anything come back.
     * Called from {@code ServerPlayerEntityMixin.openHandledScreen} HEAD.
     */
    public static void onScreenOpened(PlayerEntity player)
    {
        World world = player.getWorld();

        if (world.isClient() || !Blockbuster.damageControl.get() || !CommonProxy.damage.isCapturing())
        {
            return;
        }

        BlockPos center = player.getBlockPos();
        int radius = CONTAINER_RADIUS;

        for (int cx = (center.getX() - radius) >> 4; cx <= (center.getX() + radius) >> 4; cx++)
        {
            for (int cz = (center.getZ() - radius) >> 4; cz <= (center.getZ() + radius) >> 4; cz++)
            {
                if (!world.isChunkLoaded(cx, cz))
                {
                    continue;
                }

                for (BlockEntity be : world.getChunk(cx, cz).getBlockEntities().values())
                {
                    BlockPos pos = be.getPos();

                    if (be instanceof Inventory && pos.isWithinDistance(center, radius))
                    {
                        CommonProxy.damage.addContent(world, pos, be.createNbtWithIdentifyingData());
                    }
                }
            }
        }
    }

    /** Containers within this distance of a player opening a screen are journaled. */
    private static final int CONTAINER_RADIUS = 8;

    /**
     * Legacy decision half of {@link #setBlockState}: returns the old
     * state to hand to damage control, {@code Blocks.AIR} substituted for a
     * moving piston, or {@code null} when the change must be skipped (director
     * blocks). Extracted so the branching can be exercised headlessly before
     * the P113 sink exists.
     *
     * <p>P241: the {@link BlockDirector} skip is restored (legacy
     * {@code WorldEventListener.notifyBlockUpdate} lines 52-55). It must stay
     * <b>first</b>, before the moving-piston substitution and before the
     * {@code addBlock} call — a director block that changed state during a take
     * is never handed to damage control, so playback cannot resurrect or
     * rewrite it. The guard is on the <i>old</i> state only, exactly like
     * legacy: breaking a director is skipped, but placing one over a plain
     * block still records the plain block as the restore target.</p>
     */
    static BlockState resolveDamageOldState(BlockState oldState)
    {
        if (oldState.getBlock() instanceof BlockDirector)
        {
            return null;
        }

        if (oldState.getBlock() == Blocks.MOVING_PISTON)
        {
            return Blocks.AIR.getDefaultState();
        }

        return oldState;
    }

    /**
     * Adds a breaking-block animation action to the recorder of the player
     * doing the breaking (legacy {@code sendBlockBreakProgress}). Keyed off
     * the breaker's own recorder — other players mining near a recording is
     * not captured. Called from {@code ServerWorldMixin.setBlockBreakingInfo}.
     */
    public static void sendBlockBreakProgress(World world, int breakerId, BlockPos pos, int progress)
    {
        Entity breaker = world.getEntityById(breakerId);

        if (breaker instanceof PlayerEntity player)
        {
            List<Action> events = CommonProxy.manager.getActions(player);

            if (!player.getWorld().isClient() && events != null)
            {
                events.add(new BreakBlockAnimation(pos, progress));
            }
        }
    }

    /**
     * Damage-control entity tracking (legacy {@code onEntityAdded}): actors and
     * players are never tracked; everything else spawned during a recording is
     * handed to damage control so it can be removed on restore.
     *
     * <p>No {@code damage_control} config gate here, exactly like legacy: the
     * manager's map is empty whenever the feature is off (only
     * {@code addDamageControl} is gated), so {@code addEntity} is a no-op.</p>
     *
     * <p>wixo (R1): called from {@code ServerWorldMixin.addEntity} when an
     * entity is really spawned. It used to be Fabric's {@code ENTITY_LOAD},
     * which also fires when a chunk loads its entities, so walking into a
     * chunk during a take discarded its animals on restore.</p>
     */
    public static void onEntityAdded(Entity entity)
    {
        if (!shouldTrackEntity(entity) || !CommonProxy.damage.isCapturing())
        {
            return;
        }

        CommonProxy.damage.addEntity(entity);
    }

    /**
     * wixo (R1): an entity is about to be destroyed (killed or discarded, not
     * unloaded): journal it so the restore brings it back. A living entity is
     * journaled when it dies ({@link #onEntityDeath}), not when its body is
     * removed a second later. Called from {@code EntityMixin.setRemoved} HEAD.
     */
    public static void onEntityRemoved(Entity entity, Entity.RemovalReason reason)
    {
        if (entity.getWorld().isClient() || !reason.shouldDestroy() || !shouldTrackEntity(entity) || !CommonProxy.damage.isCapturing())
        {
            return;
        }

        if (entity instanceof LivingEntity living && living.isDead())
        {
            return;
        }

        CommonProxy.damage.removeEntity(entity);
    }

    /** wixo (R1): a living entity dies. Called from {@code LivingEntityMixin.onDeath} HEAD. */
    public static void onEntityDeath(LivingEntity entity)
    {
        if (entity.getWorld().isClient() || !shouldTrackEntity(entity) || !CommonProxy.damage.isCapturing())
        {
            return;
        }

        CommonProxy.damage.removeEntity(entity);
    }

    /**
     * Legacy filter for {@link #onEntityAdded}: {@code EntityActor} and player
     * entities are excluded. Package-visible for headless parity tests.
     */
    static boolean shouldTrackEntity(Entity entity)
    {
        return !(entity instanceof EntityActor || entity instanceof PlayerEntity);
    }
}
