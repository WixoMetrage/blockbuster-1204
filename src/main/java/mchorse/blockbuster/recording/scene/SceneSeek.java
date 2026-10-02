package mchorse.blockbuster.recording.scene;

import java.util.ArrayList;
import java.util.List;

import mchorse.blockbuster.Blockbuster;
import mchorse.blockbuster.CommonProxy;
import mchorse.blockbuster.recording.RecordPlayer;
import mchorse.blockbuster.recording.actions.Action;
import mchorse.blockbuster.recording.actions.EquipAction;
import mchorse.blockbuster.recording.actions.HotbarChangeAction;
import mchorse.blockbuster.recording.actions.ItemUseBlockAction;
import mchorse.blockbuster.recording.data.Record;
import mchorse.blockbuster.utils.EntityUtils;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;

/**
 * wixo (CDC §6, R2): moves a scene's world to another tick of its timeline.
 *
 * <p>The world state of a scene at tick {@code T} is "every action before
 * {@code T} applied" — the action of tick {@code T} itself is applied by the
 * playback when it runs that tick ({@code Scene.worldTick}).</p>
 *
 * <ul>
 * <li><b>Backwards:</b> the scene's damage-control journal undoes every change
 * stamped {@code >= T}. Nothing is replayed: legacy replayed the actions of
 * the interval instead, which placed again what it should have removed.</li>
 * <li><b>Forwards:</b> the actions that change the world are replayed tick by
 * tick, all actors within each tick ({@link ChronologicalSeek}), with the
 * scene clock on that tick so the journal can undo them later. No sound, no
 * drop, no attack, no chat or command.</li>
 * </ul>
 *
 * <p>The actors' equipment follows: equip and hotbar actions are replayed
 * forwards, and rebuilt from the start of the record after a rewind.</p>
 */
public final class SceneSeek
{
    /** True while a seek replays actions (actions skip their sounds). */
    public static boolean seeking;

    private SceneSeek()
    {}

    public static void seek(Scene scene, int from, int to)
    {
        seek(scene, from, to, null);
    }

    /**
     * @param self the take being re-recorded from {@code to} (its record on the
     *             recording player), or null. Its actions before {@code to} are
     *             part of the world too, but only their effect on blocks is
     *             replayed — never the player's items, position or rotation.
     */
    public static void seek(Scene scene, int from, int to, RecordPlayer self)
    {
        if (to == from)
        {
            return;
        }

        List<RecordPlayer> players = new ArrayList<>(scene.actors.values());

        if (to < from)
        {
            CommonProxy.damage.rewindDamageControl(scene, scene.getWorld(), to);
            rebuildEquipment(players, to);

            return;
        }

        if (self != null)
        {
            players.add(self);
        }

        List<ChronologicalSeek.Track<Action>> tracks = new ArrayList<>();

        for (RecordPlayer player : players)
        {
            Record record = player.record;

            tracks.add((tick) -> record == null ? null : record.getActions(tick - record.preDelay));
        }

        LivingEntity selfActor = self == null ? null : self.actor;
        RecordPlayer selfPrevious = selfActor == null ? null : EntityUtils.getRecordPlayer(selfActor);
        Vec3d selfPos = selfActor == null ? null : selfActor.getPos();
        float selfYaw = selfActor == null ? 0 : selfActor.getYaw();
        float selfPitch = selfActor == null ? 0 : selfActor.getPitch();

        if (selfActor != null)
        {
            EntityUtils.setRecordPlayer(selfActor, self);
        }

        seeking = true;

        try
        {
            ChronologicalSeek.forward(from, to, tracks, SceneSeek::replayedForward, (tick) ->
            {
                scene.setTick(tick);

                for (RecordPlayer player : players)
                {
                    player.tick = tick;
                }
            }, (tick, index, action) ->
            {
                RecordPlayer player = players.get(index);

                if (player == self)
                {
                    /* Blocks only: the item in the player's hand now is not
                     * the one of the old take, so no item use */
                    if (!action.modifiesWorld() || action instanceof ItemUseBlockAction)
                    {
                        return;
                    }
                }
                else if (action.modifiesWorld())
                {
                    /* Block actions aim from the actor's recorded position */
                    player.applyFrame(tick, player.actor, true);
                }

                apply(player, action);
            });
        }
        finally
        {
            seeking = false;

            if (selfActor != null)
            {
                EntityUtils.setRecordPlayer(selfActor, selfPrevious);
                selfActor.refreshPositionAndAngles(selfPos.x, selfPos.y, selfPos.z, selfYaw, selfPitch);
            }
        }
    }

    private static boolean replayedForward(Action action)
    {
        return action.modifiesWorld() || isEquipment(action);
    }

    private static boolean isEquipment(Action action)
    {
        return action instanceof EquipAction || action instanceof HotbarChangeAction;
    }

    /** Equipment as it was when {@code tick} began: cleared, then rebuilt from the record. */
    private static void rebuildEquipment(List<RecordPlayer> players, int tick)
    {
        for (RecordPlayer player : players)
        {
            Record record = player.record;

            if (record == null || player.actor == null)
            {
                continue;
            }

            for (EquipmentSlot slot : EquipmentSlot.values())
            {
                player.actor.equipStack(slot, ItemStack.EMPTY);
            }

            for (int i = 0; i < tick - record.preDelay; i++)
            {
                List<Action> actions = record.getActions(i);

                if (actions == null)
                {
                    continue;
                }

                for (Action action : actions)
                {
                    if (isEquipment(action))
                    {
                        apply(player, action);
                    }
                }
            }
        }
    }

    private static void apply(RecordPlayer player, Action action)
    {
        try
        {
            action.apply(player.actor);
        }
        catch (Exception e)
        {
            Blockbuster.LOGGER.warn("Seek: action {} failed", action.getClass().getSimpleName(), e);
        }
    }
}
