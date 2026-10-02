package mchorse.blockbuster.recording.scene;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Non-regression (CDC §6, R2): a fast-forward follows the order of the take,
 * and stops before the target tick.
 */
class ChronologicalSeekTest
{
    private static ChronologicalSeek.Track<String> track(Map<Integer, List<String>> actions)
    {
        return actions::get;
    }

    @Test
    void ticksBeforeActors()
    {
        /* Actor B places a block at tick 2, actor A breaks it at tick 5. Legacy
         * went actor by actor: A broke nothing, then B placed it for good. */
        ChronologicalSeek.Track<String> a = track(Map.of(5, List.of("A breaks")));
        ChronologicalSeek.Track<String> b = track(Map.of(2, List.of("B places")));
        List<String> order = new ArrayList<>();

        ChronologicalSeek.forward(0, 10, List.of(a, b), (action) -> true, (tick) -> {}, (tick, index, action) -> order.add(action));

        assertEquals(List.of("B places", "A breaks"), order);
    }

    @Test
    void theTargetTickIsLeftToThePlayback()
    {
        ChronologicalSeek.Track<String> a = track(Map.of(0, List.of("t0"), 3, List.of("t3"), 4, List.of("t4")));
        List<String> order = new ArrayList<>();

        ChronologicalSeek.forward(0, 4, List.of(a), (action) -> true, (tick) -> {}, (tick, index, action) -> order.add(action));

        assertEquals(List.of("t0", "t3"), order, "the action of tick 4 runs once, when the playback reaches it");
    }

    @Test
    void sameTickKeepsRecordedOrderAndActorOrder()
    {
        ChronologicalSeek.Track<String> a = track(Map.of(1, List.of("a1", "a2")));
        ChronologicalSeek.Track<String> b = track(Map.of(1, List.of("b1")));
        List<String> order = new ArrayList<>();

        ChronologicalSeek.forward(0, 2, List.of(a, b), (action) -> true, (tick) -> {}, (tick, index, action) -> order.add(index + ":" + action));

        assertEquals(List.of("0:a1", "0:a2", "1:b1"), order);
    }

    @Test
    void filterAndClock()
    {
        ChronologicalSeek.Track<String> a = track(Map.of(1, List.of("place", "chat"), 2, List.of("break")));
        List<String> order = new ArrayList<>();
        List<Integer> clock = new ArrayList<>();

        ChronologicalSeek.forward(1, 3, List.of(a), (action) -> !action.equals("chat"), clock::add, (tick, index, action) -> order.add(tick + ":" + action));

        assertEquals(List.of("1:place", "2:break"), order);
        assertEquals(List.of(1, 2), clock, "the clock is on each tick before its actions run");
    }
}
