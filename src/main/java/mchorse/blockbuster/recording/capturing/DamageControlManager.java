package mchorse.blockbuster.recording.capturing;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;

import mchorse.blockbuster.Blockbuster;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Damage control manager — port of legacy
 * {@code recording.capturing.DamageControlManager} (roadmap P113).
 *
 * <p>This person is responsible for managing damage control. Owner keys are
 * arbitrary {@link Object}s — {@link mchorse.blockbuster.recording.RecordRecorder}
 * instances (P109) and scene/playback objects (S11) share the same map, so
 * {@link #restoreDamageControl(Object, World)} with an unknown key is a silent
 * no-op.</p>
 *
 * <p>wixo (R1): every change is handed to every active session, and nothing
 * is journaled while a session puts the world back ({@link #isCapturing}):
 * the restore must not record itself.</p>
 */
public class DamageControlManager
{
    /**
     * Damage control objects
     */
    public Map<Object, DamageControl> damage = new HashMap<Object, DamageControl>();

    /** Depth of the restores in progress (nothing is journaled meanwhile). */
    private int restoring;

    public void reset()
    {
        this.damage.clear();
    }

    /** Whether a change happening now must be journaled. */
    public boolean isCapturing()
    {
        return !this.damage.isEmpty() && this.restoring == 0;
    }

    /**
     * Start observing the world, stamping each change with {@code clock}
     * (the scene or recording tick).
     */
    public void addDamageControl(Object object, LivingEntity player, IntSupplier clock)
    {
        if (Blockbuster.damageControl.get())
        {
            int dist = Blockbuster.damageControlLimit.get() ? Blockbuster.damageControlDistance.get() : 0;

            this.damage.put(object, new DamageControl(player, dist, clock));
        }
    }

    /**
     * Restore made damage
     */
    public void restoreDamageControl(Object object, World world)
    {
        DamageControl control = this.damage.remove(object);

        if (control != null)
        {
            this.restoring++;

            try
            {
                control.apply(world);
            }
            finally
            {
                this.restoring--;
            }
        }
    }

    /**
     * wixo (R2): put the world back as it was when {@code tick} began, keeping
     * the session open.
     */
    public void rewindDamageControl(Object object, World world, int tick)
    {
        DamageControl control = this.damage.get(object);

        if (control != null)
        {
            this.restoring++;

            try
            {
                control.rewindTo(world, tick);
            }
            finally
            {
                this.restoring--;
            }
        }
    }

    /**
     * Add an entity to track
     */
    public void addEntity(Entity entity)
    {
        for (DamageControl damage : this.damage.values())
        {
            damage.addEntity(entity);
        }
    }

    /** An entity is about to be destroyed: journal it as it is now. */
    public void removeEntity(Entity entity)
    {
        NbtCompound nbt = DamageControl.snapshot(entity);

        for (DamageControl damage : this.damage.values())
        {
            damage.removeEntity(entity, nbt);
        }
    }

    /**
     * Add a block to track, as it is before the change
     */
    public void addBlock(World world, BlockPos pos, BlockState oldState, NbtCompound nbt)
    {
        BlockPos immutable = pos.toImmutable();

        for (DamageControl damage : this.damage.values())
        {
            damage.addBlock(world, immutable, oldState, nbt);
        }
    }

    /** A container's content, as it is before a player changes it. */
    public void addContent(World world, BlockPos pos, NbtCompound nbt)
    {
        BlockPos immutable = pos.toImmutable();

        for (DamageControl damage : this.damage.values())
        {
            damage.addContent(world, immutable, nbt);
        }
    }
}
