package mchorse.blockbuster.recording.actions;

import mchorse.blockbuster.common.entity.EntityActor;
import mchorse.blockbuster.recording.data.Frame;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;

/**
 * Parent of all recording actions
 *
 * This class holds additional information about player's actions performed during
 * recording. Supports abstraction and stuffz.
 *
 * <p>Port note: wire codecs take {@link PacketByteBuf} (the P23/P24 bundled
 * networking style) instead of raw netty ByteBuf; the byte layout of every
 * subclass follows legacy statement order.</p>
 */
public abstract class Action
{
    /**
     * Apply action on an actor (shoot arrow, mount entity, break block, etc.)
     *
     * Some action doesn't necessarily should have apply method (that's why this
     * method is empty)
     */
    public void apply(LivingEntity actor)
    {}

    public void applyWithForce(LivingEntity actor)
    {
        this.apply(actor);
    }

    public void changeOrigin(double rotation, double newX, double newY, double newZ, double firstX, double firstY, double firstZ)
    {}

    public void flip(String axis, double coordinate)
    {}

    /**
     * Resolve the player that performs a recorded interaction on behalf of
     * {@code actor} — legacy {@code actor instanceof EntityActor ?
     * ((EntityActor) actor).fakePlayer : (EntityPlayer) actor}.
     *
     * <p>A recorded <b>player</b> replays directly onto itself; an
     * {@link EntityActor} replays onto its {@link EntityActor.EntityFakePlayer}.
     * One deliberate departure from legacy: a non-actor, non-player
     * {@link LivingEntity} (and an actor whose fake player failed to build)
     * yields {@code null} instead of legacy's {@code ClassCastException} —
     * callers skip the interaction.</p>
     */
    public static PlayerEntity resolvePlayer(LivingEntity actor)
    {
        if (actor instanceof PlayerEntity player)
        {
            return player;
        }

        if (actor instanceof EntityActor entityActor)
        {
            return entityActor.fakePlayer;
        }

        return null;
    }

    /**
     * Sync actor state onto the fake player used for interaction playback.
     *
     * <p>Legacy wrote {@code width}/{@code height}/{@code eyeHeight} onto the
     * player and then its bounding box. On 1.20.4 those three are derived, so
     * the equivalent is {@link net.minecraft.entity.Entity#calculateDimensions()},
     * which republishes {@link EntityActor.EntityFakePlayer}'s pose overrides
     * (they read the actor's own dimensions and eye height).</p>
     *
     * <p>Statement order is load-bearing: legacy assigned {@code posX/Y/Z} as
     * raw fields, which did <b>not</b> rebuild the bounding box, so the box it
     * ended up with was the actor's verbatim. {@code setPosition} does rebuild
     * it here, so the box assignment has to come after the position to land on
     * the same end state.</p>
     */
    public void copyActor(LivingEntity actor, PlayerEntity player, Frame frame)
    {
        player.calculateDimensions();
        player.setPosition(actor.getX(), actor.getY(), actor.getZ());
        player.setBoundingBox(actor.getBoundingBox());
        player.setYaw(frame.yaw);
        player.setPitch(frame.pitch);
        player.equipStack(EquipmentSlot.MAINHAND, actor.getMainHandStack());
        player.equipStack(EquipmentSlot.OFFHAND, actor.getOffHandStack());
    }

    /**
     * Persist action from byte buffer. Used for sending the action
     * over the network.
     */
    public void fromBuf(PacketByteBuf buf)
    {}

    /**
     * Persist action to byte buffer. Used for sending the action over
     * the network.
     */
    public void toBuf(PacketByteBuf buf)
    {}

    /**
     * Persist action from NBT tag. Used for loading from the disk.
     */
    public void fromNBT(NbtCompound tag)
    {}

    /**
     * Persist action to NBT tag. Used for saving to the disk.
     */
    public void toNBT(NbtCompound tag)
    {}

    /**
     * Whether this action is safe. Safe action means that it doesn't
     * modify the world, at max, only its user.
     */
    public boolean isSafe()
    {
        return false;
    }

    /**
     * wixo (CDC §6, R2): whether this action changes the world (blocks), so a
     * fast-forward through the timeline must replay it. Attacks, drops, chat,
     * commands and other one-off effects are not replayed by a seek.
     */
    public boolean modifiesWorld()
    {
        return false;
    }
}
