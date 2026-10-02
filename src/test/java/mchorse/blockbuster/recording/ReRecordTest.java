package mchorse.blockbuster.recording;

import mchorse.blockbuster.recording.data.Frame;
import mchorse.blockbuster.recording.data.FrameChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Non-regression (CDC §6, R4): a take re-recorded from an offset keeps the old
 * ticks before it and nothing after the new portion.
 */
class ReRecordTest
{
    @Test
    void actionsBeforeTheOffsetStayTheRestIsReplaced()
    {
        /* Old take: 200 ticks. Re-recorded from 50, stopped at 120. */
        List<String> old = new ArrayList<>();

        for (int i = 0; i < 200; i++)
        {
            old.add("old" + i);
        }

        List<String> taken = new ArrayList<>();

        for (int i = 0; i < 70; i++)
        {
            taken.add("new" + i);
        }

        List<String> spliced = RecordRecorder.splice(old, taken, 50);

        assertEquals(120, spliced.size(), "the take ends where the new recording stopped");
        assertEquals("old49", spliced.get(49));
        assertEquals("new0", spliced.get(50), "no old action is merged into the new tick");
        assertEquals("new69", spliced.get(119));
    }

    @Test
    void offsetPastTheOldEndIsPadded()
    {
        List<String> spliced = RecordRecorder.splice(List.of("a", "b"), List.of("c"), 4);

        assertEquals(Arrays.asList("a", "b", null, null, "c"), spliced);
    }

    @Test
    void framesAreCutTheSameWay()
    {
        List<Frame> old = frames(200);
        List<Frame> taken = frames(70);
        FrameChunk chunk = new FrameChunk(1, 50);

        chunk.add(0, taken);

        List<Frame> compiled = chunk.compile(old);

        assertEquals(120, compiled.size());
        assertSame(old.get(49), compiled.get(49));
        assertSame(taken.get(0), compiled.get(50));
        assertSame(taken.get(69), compiled.get(119));
    }

    private static List<Frame> frames(int count)
    {
        List<Frame> frames = new ArrayList<>();

        for (int i = 0; i < count; i++)
        {
            Frame frame = new Frame();

            frame.x = i;
            frames.add(frame);
        }

        return frames;
    }
}
