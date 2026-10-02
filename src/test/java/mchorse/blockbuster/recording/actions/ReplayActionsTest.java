package mchorse.blockbuster.recording.actions;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-regression (CDC §6, R3).
 */
class ReplayActionsTest
{
    @Test
    void explosionKeepsItsResultThroughASave()
    {
        ExplosionAction action = new ExplosionAction(new Vec3d(10.5, 64, -3.5), List.of(new BlockPos(10, 64, -4), new BlockPos(-200, -60, 3000)), false);
        NbtCompound tag = new NbtCompound();

        action.toNBT(tag);

        ExplosionAction loaded = new ExplosionAction();

        loaded.fromNBT(tag);

        assertEquals(action.center, loaded.center);
        assertEquals(action.blocks, loaded.blocks);
        assertFalse(loaded.effects);
        assertTrue(loaded.modifiesWorld(), "a seek replays explosions");
    }

    @Test
    void explosionWithoutEffectsTagPlaysThem()
    {
        ExplosionAction loaded = new ExplosionAction();

        loaded.fromNBT(new NbtCompound());

        assertTrue(loaded.effects);
    }

    @Test
    void explosionFlipsLikeABlockAction()
    {
        ExplosionAction action = new ExplosionAction(new Vec3d(2.5, 0, 0), List.of(new BlockPos(2, 0, 0)), true);
        InteractBlockAction block = new InteractBlockAction(new BlockPos(2, 0, 0));

        action.flip("x", 5);
        block.flip("x", 5);

        assertEquals(block.pos, action.blocks.get(0));
        assertEquals(7.5, action.center.x, 1e-9);
    }

    /**
     * A right click is recorded as use_item_block + interact_block; the server
     * ran only one. Legacy replayed both (door opened and a block placed on it).
     */
    @Test
    void rightClickIsReplayedOnce()
    {
        BlockPos door = new BlockPos(1, 2, 3);

        RightClickReplay.mark(null, door, 40);

        assertFalse(RightClickReplay.consume(null, door.up(), 40), "another block");
        assertFalse(RightClickReplay.consume(null, door, 41), "another tick");
        assertTrue(RightClickReplay.consume(null, door, 40));
        assertFalse(RightClickReplay.consume(null, door, 40), "the mark is used up");
    }

    @Test
    void onlyWorldActionsAreReplayedBySeek()
    {
        assertTrue(new PlaceBlockAction().modifiesWorld());
        assertTrue(new BreakBlockAction().modifiesWorld());
        assertTrue(new InteractBlockAction().modifiesWorld());
        assertTrue(new ItemUseBlockAction().modifiesWorld());
        assertFalse(new AttackAction().modifiesWorld());
        assertFalse(new ChatAction().modifiesWorld());
        assertFalse(new CommandAction().modifiesWorld());
    }
}
