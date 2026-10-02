package mchorse.blockbuster.recording.capturing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-regression (CDC §6, R1): undoing the journal puts a toy "world" back
 * exactly as it was, whatever happened to a position in between.
 */
class TickJournalTest
{
    /** A position and the value it had before a change. */
    private record Change(String pos, String before)
    {}

    /** A toy world that journals its own changes. */
    private static class World
    {
        final Map<String, String> blocks = new HashMap<>();
        final TickJournal<Change> journal = new TickJournal<>();
        int tick;

        void set(String pos, String value)
        {
            this.journal.record(this.tick, new Change(pos, this.blocks.get(pos)));
            this.blocks.put(pos, value);
        }

        void undo(Change change)
        {
            if (change.before == null)
            {
                this.blocks.remove(change.pos);
            }
            else
            {
                this.blocks.put(change.pos, change.before);
            }
        }
    }

    @Test
    void rewindAllRestoresTheStateBeforeTheFirstChange()
    {
        World world = new World();

        world.blocks.put("a", "stone");
        world.tick = 3;
        world.set("a", "air");
        world.tick = 7;
        world.set("a", "chest");
        world.set("b", "dirt");

        world.journal.rewindAll(world::undo);

        assertEquals(Map.of("a", "stone"), world.blocks);
        assertTrue(world.journal.isEmpty());
    }

    @Test
    void rewindToATickKeepsWhatHappenedBefore()
    {
        World world = new World();

        world.blocks.put("a", "stone");

        for (int tick = 0; tick < 10; tick++)
        {
            world.tick = tick;
            world.set("a", "state" + tick);
        }

        /* The world as it was when tick 5 began: tick 4's change is the last one. */
        world.journal.rewindTo(5, world::undo);

        assertEquals("state4", world.blocks.get("a"));
        assertEquals(5, world.journal.size());

        /* Then back to the very beginning. */
        world.journal.rewindAll(world::undo);

        assertEquals("stone", world.blocks.get("a"));
    }

    @Test
    void playbackAfterARewindIsJournaledAgain()
    {
        World world = new World();

        world.tick = 2;
        world.set("a", "x");
        world.tick = 8;
        world.set("a", "y");

        world.journal.rewindTo(5, world::undo);
        assertEquals("x", world.blocks.get("a"));

        world.tick = 5;
        world.set("a", "z");
        world.journal.rewindAll(world::undo);

        assertEquals(null, world.blocks.get("a"));
    }

    @Test
    void undoRunsNewestFirst()
    {
        TickJournal<Integer> journal = new TickJournal<>();
        List<Integer> order = new ArrayList<>();

        for (int i = 0; i < 5; i++)
        {
            journal.record(i, i);
        }

        journal.rewindTo(2, order::add);

        assertEquals(List.of(4, 3, 2), order);
    }
}
