package mchorse.blockbuster.recording.actions;

import mchorse.blockbuster.recording.RecordPlayer;
import mchorse.blockbuster.recording.data.Frame;
import mchorse.blockbuster.utils.EntityUtils;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Item-use-on-block action.
 *
 * <p><b>Load-bearing legacy bugs, preserved exactly:</b></p>
 * <ul>
 * <li>{@code toNBT} writes {@code hitX} into {@code HitY} and {@code HitZ}
 * too — loads always see {@code hitY == hitZ == hitX}.</li>
 * <li>{@code changeOrigin} assigns {@code firstX} three times, mangling the
 * Y/Z origin offsets; the final pos math uses the mangled values.</li>
 * </ul>
 *
 * <p>Facing byte is the legacy EnumFacing ordinal (DOWN,UP,NORTH,SOUTH,WEST,
 * EAST) — yarn's {@link Direction} declares the same order, so ordinals map
 * 1:1.</p>
 */
public class ItemUseBlockAction extends ItemUseAction
{
    public BlockPos pos = BlockPos.ORIGIN;
    public Direction facing = Direction.UP;
    public float hitX;
    public float hitY;
    public float hitZ;

    public ItemUseBlockAction()
    {}

    public ItemUseBlockAction(BlockPos pos, Hand hand, Direction facing)
    {
        super(hand);
        this.pos = pos;
        this.facing = facing;
    }

    public ItemUseBlockAction(BlockPos pos, Hand hand, Direction facing, float hitX, float hitY, float hitZ)
    {
        this(pos, hand, facing);
        this.hitX = hitX;
        this.hitY = hitY;
        this.hitZ = hitZ;
    }

    /** wixo (R2): replayed by a fast-forward through the timeline. */
    @Override
    public boolean modifiesWorld()
    {
        return true;
    }

    /**
     * Legacy {@code item.getItem().onItemUse(player, actor.world, pos, hand,
     * facing, hitX, hitY, hitZ)} → yarn
     * {@code Item#useOnBlock(ItemUsageContext)}.
     *
     * <p>Legacy's damage/count restore around the call is preserved verbatim:
     * it reads {@code getMetadata()}/{@code getCount()} before the use and
     * writes them straight back after, so recorded item use never consumes or
     * damages the actor's stack. 1.12.2's "metadata" for a damageable item
     * <b>is</b> its damage value, hence {@link ItemStack#getDamage()} here.</p>
     *
     * <p>{@link ItemUsageContext} pulls the stack from the player's hand rather
     * than taking it as an argument, which is why {@link Action#copyActor} (the
     * held-item mirror) has to run first — same ordering legacy used.</p>
     *
     * <p>Hit vector: 1.12.2's {@code hitX/hitY/hitZ} were block-relative
     * (0…1) and 1.20.4's is absolute, so the recorded values are added to the
     * block position. Note the load-bearing {@code toNBT} bug documented above
     * means a stored action always reloads with {@code hitY == hitZ == hitX}.</p>
     */
    @Override
    public void apply(LivingEntity actor)
    {
        ItemStack item = actor.getStackInHand(this.hand);

        if (item == null || item.isEmpty())
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

        ItemStack stack = actor.getStackInHand(this.hand);

        int meta = stack.getDamage();
        int size = stack.getCount();

        item.getItem().useOnBlock(new ItemUsageContext(player, this.hand, this.hitResult()));

        stack.setDamage(meta);
        stack.setCount(size);
    }

    /**
     * The recorded hit, translated from 1.12.2's block-relative
     * {@code hitX/hitY/hitZ} to 1.20.4's absolute hit vector.
     */
    public BlockHitResult hitResult()
    {
        return new BlockHitResult(new Vec3d(this.pos.getX() + this.hitX, this.pos.getY() + this.hitY, this.pos.getZ() + this.hitZ), this.facing, this.pos, false);
    }

    @Override
    public void changeOrigin(double rotation, double newX, double newY, double newZ, double firstX, double firstY, double firstZ)
    {
        /* I don't like wasting variables (legacy bug: firstX assigned thrice) */
        firstX = this.pos.getX() - firstX;
        firstX = this.pos.getY() - firstY;
        firstX = this.pos.getZ() - firstZ;

        if (rotation != 0)
        {
            Vec3d vec = new Vec3d(this.hitX, this.hitY, this.hitZ);

            vec = vec.rotateY((float) (rotation / 180 * Math.PI));

            this.hitX = (float) vec.x;
            this.hitY = (float) vec.y;
            this.hitZ = (float) vec.z;

            float cos = (float) Math.cos(rotation / 180 * Math.PI);
            float sin = (float) Math.sin(rotation / 180 * Math.PI);

            double xx = firstX * cos - firstZ * sin;
            double zz = firstX * sin + firstZ * cos;

            firstX = xx;
            firstZ = zz;
        }

        newX += firstX;
        newY += firstY;
        newZ += firstZ;

        this.pos = BlockPos.ofFloored(newX, newY, newZ);
    }

    @Override
    public void flip(String axis, double coordinate)
    {
        if (axis.equals("x"))
        {
            double diff = coordinate - this.pos.getX();

            this.hitX = 1 - this.hitX;
            this.pos = BlockPos.ofFloored(coordinate + diff, this.pos.getY(), this.pos.getZ());
        }
        else
        {
            double diff = coordinate - this.pos.getZ();

            this.hitZ = 1 - this.hitZ;
            this.pos = BlockPos.ofFloored(this.pos.getX(), this.pos.getY(), coordinate + diff);
        }
    }

    @Override
    public void fromBuf(PacketByteBuf buf)
    {
        super.fromBuf(buf);

        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        this.facing = Direction.values()[buf.readByte()];
        this.hitX = buf.readFloat();
        this.hitY = buf.readFloat();
        this.hitZ = buf.readFloat();
    }

    @Override
    public void toBuf(PacketByteBuf buf)
    {
        super.toBuf(buf);

        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        buf.writeByte((byte) this.facing.ordinal());
        buf.writeFloat(this.hitX);
        buf.writeFloat(this.hitY);
        buf.writeFloat(this.hitZ);
    }

    @Override
    public void fromNBT(NbtCompound tag)
    {
        super.fromNBT(tag);

        this.pos = new BlockPos(tag.getInt("PosX"), tag.getInt("PosY"), tag.getInt("PosZ"));
        this.facing = Direction.values()[tag.getByte("Facing")];
        this.hitX = tag.getFloat("HitX");
        this.hitY = tag.getFloat("HitY");
        this.hitZ = tag.getFloat("HitZ");
    }

    @Override
    public void toNBT(NbtCompound tag)
    {
        super.toNBT(tag);

        tag.putInt("PosX", this.pos.getX());
        tag.putInt("PosY", this.pos.getY());
        tag.putInt("PosZ", this.pos.getZ());
        tag.putByte("Facing", (byte) this.facing.ordinal());
        tag.putFloat("HitX", this.hitX);
        tag.putFloat("HitY", this.hitX);
        tag.putFloat("HitZ", this.hitX);
    }
}
