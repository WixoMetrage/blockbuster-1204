package mchorse.blockbuster.recording.capturing;

import java.util.UUID;
import java.util.function.IntSupplier;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Damage control — port of legacy
 * {@code recording.capturing.DamageControl} (roadmap P113), rebuilt in wixo
 * (CDC §6, R1) around a {@link TickJournal}.
 *
 * <p>It records every change the world goes through while a scene plays or a
 * player records — blocks with their block entity, container contents,
 * entities that appear and entities that are destroyed — each stamped with the
 * owner's tick ({@link #clock}). {@link #apply} puts the world back as it was
 * when the session began; {@link #rewindTo} as it was when a given tick began.</p>
 *
 * <p>What legacy got wrong, and why the rewrite:</p>
 * <ul>
 * <li>A chest's contents were read <i>after</i> Minecraft had scattered them on
 * the ground ({@code onStateReplaced} runs before {@code onBlockChanged}): a
 * restored chest came back empty. Snapshots are now taken before the change
 * ({@link WorldEventListener#setBlockState}).</li>
 * <li>Items taken out of a container without breaking it were never restored:
 * opening a container now snapshots the containers around it.</li>
 * <li>Destroyed entities never came back; they are now saved and respawned.</li>
 * <li>Only blocks within 64 blocks of one actor were tracked. The radius is
 * now optional ({@code damage_control_limit}), off by default.</li>
 * <li>Restoring with neighbour updates let blocks react to each other (sand,
 * redstone, chests dropping their content); blocks are now put back silently,
 * without drops.</li>
 * </ul>
 */
public class DamageControl
{
    /** Restore flags: tell clients, no neighbour reactions, no drops. */
    private static final int RESTORE_FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;

    public final TickJournal<Change> journal = new TickJournal<>();
    public LivingEntity target;
    public World world;

    /** Per-axis radius around {@link #target}; 0 means no limit. */
    public int maxDistance;

    /** The owner's tick (scene or recording tick) each change is stamped with. */
    public IntSupplier clock;

    public DamageControl(LivingEntity target, int maxDistance, IntSupplier clock)
    {
        this.target = target;
        this.world = target.getWorld();
        this.maxDistance = maxDistance;
        this.clock = clock;
    }

    /* Change records */

    public sealed interface Change permits BlockChange, ContentChange, EntitySpawn, EntityRemoval
    {}

    /** A block (and its block entity) as it was before a change. */
    public record BlockChange(BlockPos pos, BlockState state, NbtCompound nbt) implements Change
    {}

    /** A container's block entity as it was when a player opened a screen next to it. */
    public record ContentChange(BlockPos pos, NbtCompound nbt) implements Change
    {}

    /** An entity that appeared during the session. */
    public record EntitySpawn(UUID uuid) implements Change
    {}

    /** An entity destroyed during the session, as it was before. */
    public record EntityRemoval(UUID uuid, NbtCompound nbt) implements Change
    {}

    /* Recording */

    public boolean isInRange(BlockPos pos)
    {
        if (this.maxDistance <= 0)
        {
            return true;
        }

        double x = Math.abs(this.target.getX() - pos.getX());
        double y = Math.abs(this.target.getY() - pos.getY());
        double z = Math.abs(this.target.getZ() - pos.getZ());

        return x <= this.maxDistance && y <= this.maxDistance && z <= this.maxDistance;
    }

    private boolean accepts(World world, BlockPos pos)
    {
        return world == this.world && this.isInRange(pos);
    }

    public void addBlock(World world, BlockPos pos, BlockState state, NbtCompound nbt)
    {
        if (this.accepts(world, pos))
        {
            this.journal.record(this.clock.getAsInt(), new BlockChange(pos, state, nbt));
        }
    }

    public void addContent(World world, BlockPos pos, NbtCompound nbt)
    {
        if (this.accepts(world, pos))
        {
            this.journal.record(this.clock.getAsInt(), new ContentChange(pos, nbt));
        }
    }

    public void addEntity(Entity entity)
    {
        if (this.accepts(entity.getWorld(), entity.getBlockPos()))
        {
            this.journal.record(this.clock.getAsInt(), new EntitySpawn(entity.getUuid()));
        }
    }

    public void removeEntity(Entity entity, NbtCompound nbt)
    {
        if (this.accepts(entity.getWorld(), entity.getBlockPos()))
        {
            this.journal.record(this.clock.getAsInt(), new EntityRemoval(entity.getUuid(), nbt));
        }
    }

    /** Whether a block change at this position is journaled. */
    public boolean touches(BlockPos pos)
    {
        boolean[] found = {false};

        this.journal.forEach((change) ->
        {
            if (change instanceof BlockChange block && block.pos.equals(pos))
            {
                found[0] = true;
            }
        });

        return found[0];
    }

    /* Restoring */

    /** Put the world back as it was when the session began. */
    public void apply(World world)
    {
        this.journal.rewindAll((change) -> undo(world, change));
    }

    /** Put the world back as it was when {@code tick} began; earlier changes stay. */
    public void rewindTo(World world, int tick)
    {
        this.journal.rewindTo(tick, (change) -> undo(world, change));
    }

    static void undo(World world, Change change)
    {
        if (change instanceof BlockChange block)
        {
            clearInventory(world, block.pos);
            world.setBlockState(block.pos, block.state, RESTORE_FLAGS);

            if (block.nbt != null)
            {
                readBlockEntity(world, block.pos, block.nbt);
            }
        }
        else if (change instanceof ContentChange content)
        {
            readBlockEntity(world, content.pos, content.nbt);
        }
        else if (change instanceof EntitySpawn spawn)
        {
            Entity entity = find(world, spawn.uuid);

            if (entity != null)
            {
                entity.discard();
            }
        }
        else if (change instanceof EntityRemoval removal)
        {
            respawn(world, removal);
        }
    }

    /**
     * Empty the container at {@code pos} before it is replaced: a chest
     * replaced by anything else scatters its content
     * ({@code ItemScatterer.onStateReplaced}), whatever the flags.
     */
    public static void clearInventory(World world, BlockPos pos)
    {
        if (world.getBlockEntity(pos) instanceof Inventory inventory)
        {
            inventory.clear();
        }
    }

    private static void readBlockEntity(World world, BlockPos pos, NbtCompound nbt)
    {
        BlockEntity be = world.getBlockEntity(pos);

        if (be != null)
        {
            BlockState state = world.getBlockState(pos);

            be.readNbt(nbt);
            be.markDirty();
            world.updateListeners(pos, state, state, Block.NOTIFY_LISTENERS);
        }
    }

    private static Entity find(World world, UUID uuid)
    {
        return world instanceof ServerWorld server ? server.getEntity(uuid) : null;
    }

    private static void respawn(World world, EntityRemoval removal)
    {
        Entity existing = find(world, removal.uuid);

        if (existing != null)
        {
            if (existing.isAlive())
            {
                return;
            }

            /* Still playing its death animation */
            existing.discard();
        }

        EntityType.getEntityFromNbt(removal.nbt, world).ifPresent((entity) ->
        {
            if (entity instanceof LivingEntity living)
            {
                living.setHealth(living.getMaxHealth());
                living.deathTime = 0;
            }

            world.spawnEntity(entity);
        });
    }

    /** Snapshot of an entity, readable by {@link EntityType#getEntityFromNbt}. */
    public static NbtCompound snapshot(Entity entity)
    {
        NbtCompound nbt = entity.writeNbt(new NbtCompound());

        nbt.putString("id", EntityType.getId(entity.getType()).toString());

        return nbt;
    }
}
