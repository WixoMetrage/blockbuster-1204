package mchorse.blockbuster.recording.actions;

import com.google.common.collect.ImmutableSet;

import mchorse.blockbuster.common.block.BlockDirector;
import mchorse.blockbuster.recording.LTHelper;
import mchorse.blockbuster.recording.RecordPlayer;
import mchorse.blockbuster.recording.data.Frame;
import mchorse.blockbuster.utils.EntityUtils;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Set;

/**
 * Interact block action
 *
 * Makes actor interact with a block (press button, switch lever, open the door,
 * etc.)
 *
 * If there was CL4P-TP actor in this mod, this action would be called
 * IntergradeBlockAction :D
 */
public class InteractBlockAction extends Action
{
    public static final Set<Identifier> BLACKLIST = ImmutableSet.of(
        new Identifier("littletiles", "blocklittletiles")
    );

    public BlockPos pos = BlockPos.ORIGIN;

    public InteractBlockAction()
    {}

    public InteractBlockAction(BlockPos pos)
    {
        this.pos = pos;
    }

    /** wixo (R2): replayed by a fast-forward through the timeline. */
    @Override
    public boolean modifiesWorld()
    {
        return true;
    }

    /**
     * Legacy {@code state.getBlock().onBlockActivated(world, pos, state,
     * player, MAIN_HAND, null, pos.getX(), pos.getY(), pos.getZ())} → yarn
     * {@code BlockState#onUse(World, PlayerEntity, Hand, BlockHitResult)}, run
     * against the interaction player (the actor's fake player, or a recorded
     * player replaying onto itself).
     *
     * <p>Legacy order, preserved: director-block skip → frame guard →
     * {@code copyActor} (only when the player is not the actor) → the
     * LittleTiles bridge ({@link LTHelper}, S21 P221.1 — it gets <b>first
     * refusal</b> on every recorded interaction; on Fabric its reflective probe
     * never finds the mod, so it silently returns false) → blacklist →
     * activation. The blacklist is deliberately checked <b>after</b> the
     * bridge, not before, exactly as 1.12.2 did.</p>
     *
     * <p>Two forced translations of arguments 1.20.4 has no room for:</p>
     * <ul>
     * <li>Legacy passed {@code null} for the clicked <b>side</b>.
     * {@link BlockHitResult} cannot carry a null side, so this uses
     * {@link Direction#UP} — the same value vanilla falls back to for
     * non-directional activation.</li>
     * <li>Legacy passed the block's <b>absolute</b> coordinates into 1.12.2's
     * relative {@code hitX/hitY/hitZ} parameters (a legacy quirk: they should
     * have been 0…1). The very same numbers are handed to the hit vector here,
     * where 1.20.4's absolute-coordinate semantics make them land exactly on
     * the block's minimum corner.</li>
     * </ul>
     */
    @Override
    public void apply(LivingEntity actor)
    {
        /* Legacy read the block state first and dereferenced the record player
         * unguarded right after; the guard is hoisted above the (side-effect
         * free) state read so a recordless actor is a no-op instead of an NPE,
         * the same totality shape every other ported action uses. */
        RecordPlayer record = EntityUtils.getRecordPlayer(actor);

        if (record == null)
        {
            return;
        }

        /* wixo (CDC §6, R3): the use_item_block action recorded with this one
         * already replayed the click, block first like the server did. Legacy
         * applied both: the block reacted, and the item was used on it too. */
        if (RightClickReplay.consume(actor, this.pos, record.tick))
        {
            return;
        }

        Frame frame = record.getCurrentFrame();
        PlayerEntity player = Action.resolvePlayer(actor);

        if (frame == null || player == null)
        {
            return;
        }

        if (player != actor)
        {
            this.copyActor(actor, player, frame);
        }

        useBlock(actor.getWorld(), player, frame, this.pos, Hand.MAIN_HAND, this.hitResult());
    }

    /**
     * The block half of a right click: director-block skip, the LittleTiles
     * bridge, the blacklist, then {@code onUse}. Returns whether the block
     * took the click (then the held item is not used).
     */
    public static boolean useBlock(World world, PlayerEntity player, Frame frame, BlockPos pos, Hand hand, BlockHitResult hit)
    {
        BlockState state = world.getBlockState(pos);

        /* Black listed block */
        if (state.getBlock() instanceof BlockDirector)
        {
            return false;
        }

        if (LTHelper.playerRightClickServer(player, frame))
        {
            return true;
        }

        if (BLACKLIST.contains(Registries.BLOCK.getId(state.getBlock())))
        {
            return false;
        }

        return state.onUse(world, player, hand, hit).isAccepted();
    }

    /**
     * The activation hit legacy described with {@code (null facing, pos.getX(),
     * pos.getY(), pos.getZ())} — see {@link #apply(LivingEntity)} for why the
     * side becomes {@link Direction#UP} and why the absolute coordinates are
     * carried over verbatim.
     */
    public BlockHitResult hitResult()
    {
        return new BlockHitResult(new Vec3d(this.pos.getX(), this.pos.getY(), this.pos.getZ()), Direction.UP, this.pos, false);
    }

    @Override
    public void changeOrigin(double rotation, double newX, double newY, double newZ, double firstX, double firstY, double firstZ)
    {
        /* I don't like wasting variables */
        firstX = this.pos.getX() - firstX;
        firstY = this.pos.getY() - firstY;
        firstZ = this.pos.getZ() - firstZ;

        if (rotation != 0)
        {
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

            this.pos = BlockPos.ofFloored(coordinate + diff, this.pos.getY(), this.pos.getZ());
        }
        else
        {
            double diff = coordinate - this.pos.getZ();

            this.pos = BlockPos.ofFloored(this.pos.getX(), this.pos.getY(), coordinate + diff);
        }
    }

    @Override
    public void fromBuf(PacketByteBuf buf)
    {
        super.fromBuf(buf);
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
    }

    @Override
    public void toBuf(PacketByteBuf buf)
    {
        super.toBuf(buf);
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
    }

    @Override
    public void fromNBT(NbtCompound tag)
    {
        this.pos = new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
    }

    @Override
    public void toNBT(NbtCompound tag)
    {
        tag.putInt("X", this.pos.getX());
        tag.putInt("Y", this.pos.getY());
        tag.putInt("Z", this.pos.getZ());
    }
}
