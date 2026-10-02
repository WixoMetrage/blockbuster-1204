package mchorse.blockbuster.recording.scene;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * wixo (CDC §6, R2): the order in which a fast-forward replays actions.
 *
 * <p>Legacy fast-forwarded actor by actor — all of the first actor's actions,
 * then all of the second's — so an actor could break a block another one had
 * not placed yet. Here every tick is replayed in turn, all actors within it,
 * each actor's actions in recorded order: the order the take happened in.</p>
 *
 * <p>The range is {@code [from, to)}: the target tick itself is left to the
 * playback, which applies it once (legacy applied it here and again on resume).
 * Holds no Minecraft type, so it is unit-tested directly.</p>
 */
public final class ChronologicalSeek
{
    private ChronologicalSeek()
    {}

    /** One actor's actions, by scene tick (null or empty when there are none). */
    public interface Track<A>
    {
        List<A> actionsAt(int tick);
    }

    public interface Visitor<A>
    {
        void visit(int tick, int track, A action);
    }

    /**
     * Visit the actions of every tick in {@code [from, to)} that pass
     * {@code filter}, tick by tick. {@code onTick} runs before each tick (it
     * moves the clocks the world changes are stamped with).
     */
    public static <A> void forward(int from, int to, List<? extends Track<A>> tracks, Predicate<A> filter, IntConsumer onTick, Visitor<A> visitor)
    {
        for (int tick = Math.max(from, 0); tick < to; tick++)
        {
            onTick.accept(tick);

            for (int i = 0; i < tracks.size(); i++)
            {
                List<A> actions = tracks.get(i).actionsAt(tick);

                if (actions == null)
                {
                    continue;
                }

                for (A action : actions)
                {
                    if (filter.test(action))
                    {
                        visitor.visit(tick, i, action);
                    }
                }
            }
        }
    }
}
