package mchorse.blockbuster.recording.actions;

import mchorse.blockbuster.recording.RecordPlayer;
import mchorse.blockbuster.recording.data.Frame;
import mchorse.blockbuster.utils.EntityUtils;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Hand;

/**
 * Item use action
 *
 * This action is responsible for using the item in actor's hand. This action
 * will right click the item, not will use it on the block.
 */
public class ItemUseAction extends Action
{
    public Hand hand = Hand.MAIN_HAND;

    public ItemUseAction()
    {}

    public ItemUseAction(Hand hand)
    {
        this.hand = hand;
    }

    /**
     * Legacy {@code item.getItem().onItemRightClick(actor.world, player,
     * this.hand)} → yarn {@code Item#use(World, PlayerEntity, Hand)}, run
     * against the interaction player (the actor's fake player, or a recorded
     * player replaying onto itself).
     *
     * <p>Legacy's {@code item != null} guard is an empty-stack guard here:
     * 1.12.2's {@code getHeldItem} could return {@code null}, 1.20.4's returns
     * {@link ItemStack#EMPTY}, and using an empty hand is not what was
     * recorded.</p>
     *
     * <p>The state sync is legacy's inline copy — the same statements
     * {@link Action#copyActor} performs, so it routes through that instead of
     * repeating them (legacy inlined it in this class and called the shared
     * helper in {@link InteractBlockAction}; the two bodies were identical).
     * Unlike {@code InteractBlockAction} legacy did <b>not</b> guard the copy
     * with {@code player != actor}, so a player replaying onto itself
     * re-applies its own frame rotation — kept.</p>
     */
    @Override
    public void apply(LivingEntity actor)
    {
        ItemStack item = actor.getStackInHand(this.hand);

        /* wixo (CDC §6, R3): a full bucket's fluid is a place_block of its own
         * (BucketItemMixin); using the bucket again poured a second one
         * wherever the actor happened to look. */
        if (item == null || item.isEmpty() || item.isOf(Items.WATER_BUCKET) || item.isOf(Items.LAVA_BUCKET))
        {
            return;
        }

        RecordPlayer record = EntityUtils.getRecordPlayer(actor);

        if (record == null)
        {
            return;
        }

        Frame frame = record.getCurrentFrame();
        PlayerEntity player = Action.resolvePlayer(actor);

        if (frame == null || player == null)
        {
            return;
        }

        this.copyActor(actor, player, frame);

        item.getItem().use(actor.getWorld(), player, this.hand);
    }

    @Override
    public void fromBuf(PacketByteBuf buf)
    {
        super.fromBuf(buf);
        this.hand = buf.readByte() == 0 ? Hand.MAIN_HAND : Hand.OFF_HAND;
    }

    @Override
    public void toBuf(PacketByteBuf buf)
    {
        super.toBuf(buf);
        buf.writeByte((byte) (this.hand.equals(Hand.MAIN_HAND) ? 0 : 1));
    }

    @Override
    public void fromNBT(NbtCompound tag)
    {
        this.hand = tag.getByte("Hand") == 0 ? Hand.MAIN_HAND : Hand.OFF_HAND;
    }

    @Override
    public void toNBT(NbtCompound tag)
    {
        tag.putByte("Hand", (byte) (this.hand.equals(Hand.MAIN_HAND) ? 0 : 1));
    }
}
