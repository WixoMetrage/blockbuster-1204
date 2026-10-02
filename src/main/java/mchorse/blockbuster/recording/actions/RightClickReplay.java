package mchorse.blockbuster.recording.actions;

import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;

/**
 * wixo (CDC §6, R3): pairs the two actions a right click on a block records.
 *
 * <p>Every right click on a block is captured as {@code use_item_block}
 * followed by {@code interact_block} (two listeners, legacy and 1.20.4 alike).
 * The server only ran one of them: the block reacted (door, lever, chest, TNT)
 * or, if it did not, the held item was used. Legacy replayed both, which
 * opened the door <i>and</i> placed the held block against it.
 * {@link ItemUseBlockAction} now replays the click the server's way and marks
 * it here; the {@link InteractBlockAction} that follows it skips itself.</p>
 */
final class RightClickReplay
{
    private static LivingEntity actor;
    private static BlockPos pos;
    private static int tick;

    private RightClickReplay()
    {}

    static void mark(LivingEntity actor, BlockPos pos, int tick)
    {
        RightClickReplay.actor = actor;
        RightClickReplay.pos = pos;
        RightClickReplay.tick = tick;
    }

    /** Whether this click was already replayed by its use_item_block (the mark is used up). */
    static boolean consume(LivingEntity actor, BlockPos pos, int tick)
    {
        boolean handled = RightClickReplay.actor == actor && tick == RightClickReplay.tick && pos.equals(RightClickReplay.pos);

        if (handled)
        {
            RightClickReplay.actor = null;
            RightClickReplay.pos = null;
        }

        return handled;
    }
}
