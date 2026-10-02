package mchorse.blockbuster.recording.actions;

import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;

/**
 * Breaking block animation
 *
 * This action is responsible for animating blocks which are about to be
 * broken. Recorded by the world event listener (P114).
 *
 * <p>Quirk: breaker id is <b>-1</b> (no entity) so the crack overlay never
 * cancels the actor's own mining animation.</p>
 */
public class BreakBlockAnimation extends InteractBlockAction
{
    public int progress;

    public BreakBlockAnimation()
    {}

    public BreakBlockAnimation(BlockPos pos, int progress)
    {
        super(pos);
        this.progress = progress;
    }

    @Override
    public void apply(LivingEntity actor)
    {
        /* wixo (CDC §6, R3): the actor is the breaker. Legacy used -1 for every
         * actor: the client keeps one crack per breaker id, so two actors mining
         * at once stole each other's crack, and BreakBlockAction (which clears
         * the actor's own id) never cleared it. */
        actor.getWorld().setBlockBreakingInfo(actor.getId(), this.pos, this.progress);
    }

    @Override
    public void fromBuf(PacketByteBuf buf)
    {
        super.fromBuf(buf);
        this.progress = buf.readInt();
    }

    @Override
    public void toBuf(PacketByteBuf buf)
    {
        super.toBuf(buf);
        buf.writeInt(this.progress);
    }

    @Override
    public void fromNBT(NbtCompound tag)
    {
        super.fromNBT(tag);

        this.progress = tag.getInt("Progress");
    }

    @Override
    public void toNBT(NbtCompound tag)
    {
        super.toNBT(tag);

        tag.putInt("Progress", this.progress);
    }
}
