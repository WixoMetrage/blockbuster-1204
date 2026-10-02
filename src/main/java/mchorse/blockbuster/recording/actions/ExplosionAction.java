package mchorse.blockbuster.recording.actions;

import java.util.ArrayList;
import java.util.List;

import mchorse.blockbuster.recording.capturing.DamageControl;
import mchorse.blockbuster.recording.scene.SceneSeek;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * wixo (CDC §6, R3): an explosion, recorded as its result.
 *
 * <p>Explosions were not recorded at all: a TNT lit again by an actor exploded
 * differently on every playback (an explosion's rays are random), and a creeper
 * or a redstone-lit TNT did not come back. The blocks the explosion destroyed
 * during the take are stored here and removed at the same tick on every
 * playback, without drops and without setting off the TNT around it (each
 * chained explosion is recorded on its own).</p>
 *
 * <p>{@link #effects}: whether this action plays the sound and the particles.
 * Off when the explosion was set off by the recording player — the actor sets
 * it off again on playback, and that real explosion (which keeps its blast on
 * entities but no longer breaks blocks, see {@code ExplosionMixin}) already
 * plays them.</p>
 */
public class ExplosionAction extends Action
{
    public Vec3d center = Vec3d.ZERO;
    public List<BlockPos> blocks = new ArrayList<>();
    public boolean effects = true;

    public ExplosionAction()
    {}

    public ExplosionAction(Vec3d center, List<BlockPos> blocks, boolean effects)
    {
        this.center = center;
        this.blocks = new ArrayList<>(blocks);
        this.effects = effects;
    }

    @Override
    public boolean modifiesWorld()
    {
        return true;
    }

    @Override
    public void apply(LivingEntity actor)
    {
        World world = actor.getWorld();

        for (BlockPos pos : this.blocks)
        {
            DamageControl.clearInventory(world, pos);
            world.removeBlock(pos, false);
        }

        if (this.effects && !SceneSeek.seeking && world instanceof ServerWorld server)
        {
            server.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, this.center.x, this.center.y, this.center.z, 1, 0, 0, 0, 0);
            world.playSound(null, this.center.x, this.center.y, this.center.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 4.0F, (1.0F + (world.random.nextFloat() - world.random.nextFloat()) * 0.2F) * 0.7F);
        }
    }

    @Override
    public void changeOrigin(double rotation, double newX, double newY, double newZ, double firstX, double firstY, double firstZ)
    {
        double cos = Math.cos(rotation / 180 * Math.PI);
        double sin = Math.sin(rotation / 180 * Math.PI);
        List<BlockPos> moved = new ArrayList<>(this.blocks.size());

        for (BlockPos pos : this.blocks)
        {
            Vec3d out = move(pos.getX(), pos.getY(), pos.getZ(), cos, sin, newX, newY, newZ, firstX, firstY, firstZ);

            moved.add(BlockPos.ofFloored(out));
        }

        this.blocks = moved;
        this.center = move(this.center.x, this.center.y, this.center.z, cos, sin, newX, newY, newZ, firstX, firstY, firstZ);
    }

    /** Same transform as {@link InteractBlockAction#changeOrigin}. */
    private static Vec3d move(double x, double y, double z, double cos, double sin, double newX, double newY, double newZ, double firstX, double firstY, double firstZ)
    {
        double dx = x - firstX;
        double dz = z - firstZ;

        return new Vec3d(newX + dx * cos - dz * sin, newY + y - firstY, newZ + dx * sin + dz * cos);
    }

    @Override
    public void flip(String axis, double coordinate)
    {
        List<BlockPos> flipped = new ArrayList<>(this.blocks.size());
        boolean x = axis.equals("x");

        for (BlockPos pos : this.blocks)
        {
            flipped.add(x
                ? BlockPos.ofFloored(2 * coordinate - pos.getX(), pos.getY(), pos.getZ())
                : BlockPos.ofFloored(pos.getX(), pos.getY(), 2 * coordinate - pos.getZ()));
        }

        this.blocks = flipped;
        this.center = x
            ? new Vec3d(2 * coordinate - this.center.x, this.center.y, this.center.z)
            : new Vec3d(this.center.x, this.center.y, 2 * coordinate - this.center.z);
    }

    @Override
    public void fromBuf(PacketByteBuf buf)
    {
        super.fromBuf(buf);

        this.center = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
        this.effects = buf.readBoolean();
        this.blocks.clear();

        for (int i = 0, c = buf.readVarInt(); i < c; i++)
        {
            this.blocks.add(BlockPos.fromLong(buf.readLong()));
        }
    }

    @Override
    public void toBuf(PacketByteBuf buf)
    {
        super.toBuf(buf);

        buf.writeDouble(this.center.x);
        buf.writeDouble(this.center.y);
        buf.writeDouble(this.center.z);
        buf.writeBoolean(this.effects);
        buf.writeVarInt(this.blocks.size());

        for (BlockPos pos : this.blocks)
        {
            buf.writeLong(pos.asLong());
        }
    }

    @Override
    public void fromNBT(NbtCompound tag)
    {
        this.center = new Vec3d(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
        this.effects = !tag.contains("Effects") || tag.getBoolean("Effects");
        this.blocks.clear();

        for (long pos : tag.getLongArray("Blocks"))
        {
            this.blocks.add(BlockPos.fromLong(pos));
        }
    }

    @Override
    public void toNBT(NbtCompound tag)
    {
        tag.putDouble("X", this.center.x);
        tag.putDouble("Y", this.center.y);
        tag.putDouble("Z", this.center.z);
        tag.putBoolean("Effects", this.effects);
        tag.putLongArray("Blocks", this.blocks.stream().mapToLong(BlockPos::asLong).toArray());
    }
}
