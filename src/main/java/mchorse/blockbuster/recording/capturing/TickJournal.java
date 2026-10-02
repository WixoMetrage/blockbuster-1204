package mchorse.blockbuster.recording.capturing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * wixo (CDC §6, R1): an ordered log of world changes, each stamped with the
 * scene / recording tick it happened at.
 *
 * <p>Undoing walks the log newest first, so a position changed several times
 * ends up in the state it had before the first change, and a rewind to tick
 * {@code T} leaves the world exactly as it was when tick {@code T} began.
 * Holds no Minecraft type: {@link DamageControl} journals its own change
 * records here.</p>
 */
public class TickJournal<T>
{
    private final List<Entry<T>> entries = new ArrayList<>();

    private record Entry<T>(int tick, T change)
    {}

    public void record(int tick, T change)
    {
        this.entries.add(new Entry<>(tick, change));
    }

    public int size()
    {
        return this.entries.size();
    }

    public boolean isEmpty()
    {
        return this.entries.isEmpty();
    }

    /** Every change still in the journal, oldest first. */
    public void forEach(Consumer<T> consumer)
    {
        for (Entry<T> entry : this.entries)
        {
            consumer.accept(entry.change);
        }
    }

    /**
     * Undo every change made at or after {@code tick}, newest first, and drop
     * them from the journal. Changes from earlier ticks stay journaled.
     *
     * <p>After a rewind to {@code T} every entry left is below {@code T} and
     * playback journals from {@code T} on, so the log stays sorted by tick.</p>
     */
    public void rewindTo(int tick, Consumer<T> undo)
    {
        for (int i = this.entries.size() - 1; i >= 0; i--)
        {
            Entry<T> entry = this.entries.get(i);

            if (entry.tick >= tick)
            {
                this.entries.remove(i);
                undo.accept(entry.change);
            }
        }
    }

    /** Undo everything, newest first. */
    public void rewindAll(Consumer<T> undo)
    {
        for (int i = this.entries.size() - 1; i >= 0; i--)
        {
            undo.accept(this.entries.get(i).change);
        }

        this.entries.clear();
    }
}
