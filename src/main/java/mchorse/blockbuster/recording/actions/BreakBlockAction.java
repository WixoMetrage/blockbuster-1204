package mchorse.blockbuster.recording.actions;

import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import mchorse.blockbuster.recording.capturing.DamageControl;
import mchorse.blockbuster.recording.scene.SceneSeek;

/**
 * Breaking block action
 *
 * Actor breaks the block
 */
public class BreakBlockAction extends InteractBlockAction
{
    public boolean drop = false;

    public BreakBlockAction()
    {}

    public BreakBlockAction(BlockPos pos, boolean drop)
    {
        super(pos);
        this.drop = drop;
    }

    /** wixo (R2): replayed by a fast-forward through the timeline. */
    @Override
    public boolean modifiesWorld()
    {
        return true;
    }

    @Override
    public void apply(LivingEntity actor)
    {
        World world = actor.getWorld();

        /* wixo (CDC §6): never a drop during a replay, not even the content of
         * a container (it scatters whatever the flags). The recorded `drop`
         * stays in the file, it is just not honoured. A seek breaks silently. */
        DamageControl.clearInventory(world, this.pos);

        if (SceneSeek.seeking)
        {
            world.removeBlock(this.pos, false);
        }
        else
        {
            world.breakBlock(this.pos, false);
        }

        actor.getWorld().setBlockBreakingInfo(actor.getId(), this.pos, -1);
    }

    @Override
    public void fromBuf(PacketByteBuf buf)
    {
        super.fromBuf(buf);
        this.drop = buf.readBoolean();
    }

    @Override
    public void toBuf(PacketByteBuf buf)
    {
        super.toBuf(buf);
        buf.writeBoolean(this.drop);
    }

    @Override
    public void fromNBT(NbtCompound tag)
    {
        super.fromNBT(tag);

        this.drop = tag.getBoolean("Drop");
    }

    @Override
    public void toNBT(NbtCompound tag)
    {
        super.toNBT(tag);

        tag.putBoolean("Drop", this.drop);
    }
}
