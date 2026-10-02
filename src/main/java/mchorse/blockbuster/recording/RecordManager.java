package mchorse.blockbuster.recording;

import mchorse.blockbuster.Blockbuster;
import mchorse.blockbuster.CommonProxy;
import mchorse.blockbuster.network.Dispatcher;
import mchorse.blockbuster.network.common.PacketCaption;
import mchorse.blockbuster.network.common.recording.PacketPlayback;
import mchorse.blockbuster.network.common.recording.PacketPlayerRecording;
import mchorse.blockbuster.recording.actions.Action;
import mchorse.blockbuster.recording.actions.DamageAction;
import mchorse.blockbuster.recording.data.FrameChunk;
import mchorse.blockbuster.recording.data.Mode;
import mchorse.blockbuster.recording.data.Record;
import mchorse.blockbuster.recording.scene.fake.FakePlayerLifecycle;
import mchorse.blockbuster.utils.EntityUtils;
import mchorse.metamorph.api.MorphAPI;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtTagSizeTracker;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Record manager (roadmap P109 record/halt/schedule halves + P111 cache
 * halves) — 1:1 port of the 2.7.2 class.
 *
 * This class responsible is responsible for managing record recorders and
 * players for entity players and actors.
 *
 * <p>Deferred with seams: damage control snapshots (P113), morph demorph on
 * halt (S4), {@code PacketCaption}/{@code PacketPlayerRecording}/
 * {@code PacketPlayback} sends (P116 — countdown captions go to the vanilla
 * action bar until then so singleplayer recording is testable), the client
 * mirror manager + {@code getClient} (P116), and fake-player logout on
 * kill-stop (S10).</p>
 *
 * <p>Recorder/player maps are keyed by entity identity like legacy —
 * death+respawn replaces the {@code ServerPlayerEntity}, but the death halt
 * fires first via {@code ActionHandler.onPlayerTick}, mirroring legacy's
 * lifecycle (S9 open question 6: resolved as "mirror legacy").</p>
 */
public class RecordManager
{
    /**
     * Loaded records
     */
    public Map<String, Record> records = new HashMap<String, Record>();

    /**
     * Incomplete chunk frame only records (for recording big records)
     */
    public Map<String, FrameChunk> chunks = new HashMap<String, FrameChunk>();

    /**
     * Currently running record recorders (I have something to do about the
     * name)
     */
    public Map<PlayerEntity, RecordRecorder> recorders = new HashMap<PlayerEntity, RecordRecorder>();

    /**
     * Me: No, not {@link PlayerEntity}s, say record pla-yers, pla-yers...
     * Also me in 2020: What a cringe...
     */
    public Map<LivingEntity, RecordPlayer> players = new HashMap<LivingEntity, RecordPlayer>();

    /**
     * Scheduled recordings
     */
    public Map<PlayerEntity, ScheduledRecording> scheduled = new HashMap<PlayerEntity, ScheduledRecording>();

    /**
     * Get action list for given player
     */
    public List<Action> getActions(PlayerEntity player)
    {
        RecordRecorder recorder = this.recorders.get(player);

        return recorder == null ? null : recorder.actions;
    }

    public boolean record(String filename, PlayerEntity player, Mode mode, boolean teleportBack, boolean notify, Runnable runnable)
    {
        return this.record(filename, player, mode, teleportBack, notify, 0, runnable);
    }

    /**
     * Start recording given player to record with given filename
     */
    public boolean record(String filename, PlayerEntity player, Mode mode, boolean teleportBack, boolean notify, int offset, Runnable runnable)
    {
        /* wixo (CDC §6, R4): no more replay of the old take's actions on the
         * player from the offset on. That portion is being replaced, and its
         * replayed actions were captured again into the new take (a replayed
         * block placement recorded as a new one). The world is brought to the
         * offset by the scene instead (Scene.startPlayback). */
        Runnable proxy = () ->
        {
            if (runnable != null)
            {
                runnable.run();
            }
        };

        if (this.recorders.containsKey(player))
        {
            proxy.run();
        }

        if (filename.isEmpty() || this.halt(player, false, notify))
        {
            if (filename.isEmpty())
            {
                RecordUtils.broadcastError("recording.empty_filename");
            }

            return false;
        }

        for (RecordRecorder recorder : this.recorders.values())
        {
            if (recorder.record.filename.equals(filename))
            {
                RecordUtils.broadcastInfo("recording.recording", filename);

                return false;
            }
        }

        RecordRecorder recorder = new RecordRecorder(new Record(filename), mode, player, teleportBack);

        recorder.offset = offset;

        if (player.getWorld().isClient)
        {
            this.recorders.put(player, recorder);
        }
        else
        {
            this.setupPlayerData(recorder, player);
            CommonProxy.damage.addDamageControl(recorder, player, () -> recorder.tick);

            this.scheduled.put(player, new ScheduledRecording(recorder, (ServerPlayerEntity) player, proxy, (int) (Blockbuster.recordingCountdown.get() * 20), offset));
        }

        return true;
    }

    private void setupPlayerData(RecordRecorder recorder, PlayerEntity player)
    {
        NbtCompound tag = new NbtCompound();

        /* Legacy wrote writeEntityToNBT — the CUSTOM half only, no id/pos/
         * rotation/UUID wrapper. P286: yarn's exact counterpart is
         * writeCustomDataToNbt, which PlayerEntity declares *public*, so the
         * earlier "writeNbt is the closest public analog" reading was wrong on
         * both counts. `PlayerData` is part of the record .dat format, so this
         * is a parity fix as well as a behaviour one; RecordPlayer reads it
         * back with readCustomDataFromNbt, and extra keys left in records
         * written by the interim build are simply ignored. */
        player.writeCustomDataToNbt(tag);
        recorder.record.playerData = tag;

        if (MPMHelper.isLoaded())
        {
            tag = MPMHelper.getMPMData(player);

            if (tag != null)
            {
                recorder.record.playerData.put("MPMData", tag);
            }
        }
    }

    /**
     * Stop recording given player
     */
    public boolean halt(PlayerEntity player, boolean hasDied, boolean notify)
    {
        return this.halt(player, hasDied, notify, false);
    }

    /**
     * Stop recording given player
     */
    public boolean halt(PlayerEntity player, boolean hasDied, boolean notify, boolean canceled)
    {
        /* Stop countdown */
        ScheduledRecording scheduled = this.scheduled.get(player);

        if (scheduled != null)
        {
            this.scheduled.remove(player);
            this.sendCaption(scheduled.player, null);

            return true;
        }

        /* Stop the recording via command or whatever the source is */
        RecordRecorder recorder = this.recorders.get(player);

        if (recorder != null)
        {
            Record record = recorder.record;
            String filename = record.filename;

            if (!canceled && hasDied && !record.actions.isEmpty())
            {
                record.addAction(record.actions.size() - 1, new DamageAction(200.0F));
            }
            else
            {
                recorder.stop(player);
            }

            /* Remove action preview for previously recorded actions */
            RecordPlayer recordPlayer = this.players.get(player);

            if (recordPlayer != null && recordPlayer.realPlayer)
            {
                this.players.remove(player);

                EntityUtils.setRecordPlayer(player, null);
            }

            if (!canceled)
            {
                /* Apply old player recording */
                try
                {
                    Record oldRecord = this.get(filename);

                    recorder.applyOld(oldRecord);
                }
                catch (Exception e)
                {}

                this.records.put(filename, record);
            }

            this.recorders.remove(player);
            MorphAPI.demorph(player);

            if (notify)
            {
                CommonProxy.damage.restoreDamageControl(recorder, player.getWorld());

                if (player instanceof ServerPlayerEntity serverPlayer)
                {
                    Dispatcher.sendTo(new PacketPlayerRecording(false, "", 0, canceled), serverPlayer);
                }
            }

            return true;
        }

        return false;
    }

    /**
     * Version with default tick parameter
     */
    public RecordPlayer play(String filename, LivingEntity actor, Mode mode, boolean kill)
    {
        return this.play(filename, actor, mode, 0, kill);
    }

    /**
     * Start playback from given filename and given actor. You also have to
     * specify the mode of playback.
     */
    public RecordPlayer play(String filename, LivingEntity actor, Mode mode, int tick, boolean kill)
    {
        if (this.players.containsKey(actor))
        {
            return null;
        }

        try
        {
            Record record = this.get(filename);

            if (record.frames.size() == 0)
            {
                RecordUtils.broadcastError("recording.empty_record", filename);

                return null;
            }

            RecordPlayer playback = new RecordPlayer(record, mode, actor);

            playback.tick = tick;
            playback.kill = kill;
            playback.applyFrame(tick, actor, true);

            EntityUtils.setRecordPlayer(actor, playback);

            this.players.put(actor, playback);

            return playback;
        }
        catch (FileNotFoundException e)
        {
            RecordUtils.broadcastError("recording.not_recorded", filename);
        }
        catch (Exception e)
        {
            RecordUtils.broadcastError("recording.read", filename);
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Stop playback for the given record player
     */
    public void stop(RecordPlayer actor)
    {
        if (!this.players.containsKey(actor.actor))
        {
            return;
        }

        if (actor.actor.getHealth() > 0.0F)
        {
            if (actor.kill)
            {
                actor.actor.stopRiding();

                if (actor.realPlayer)
                {
                    if (actor.actor instanceof ServerPlayerEntity serverPlayer)
                    {
                        Dispatcher.sendTo(new PacketPlayback(actor.actor.getId(), false, actor.realPlayer, ""), serverPlayer);
                    }
                }
                else
                {
                    actor.actor.discard();

                    /* P241: legacy `playerLoggedOut` when the actor is a (fake)
                     * player entity — unregisters it from the player list, the
                     * tab list and the world, which `discard()` alone does not
                     * do. Order matches legacy: setDead first, then logout. */
                    if (FakePlayerLifecycle.shouldLogOut(actor.actor, actor.realPlayer, actor.kill))
                    {
                        FakePlayerLifecycle.logOut((ServerPlayerEntity) actor.actor);
                    }
                }
            }
            else
            {
                Dispatcher.sendToTracked(actor.actor, new PacketPlayback(actor.actor.getId(), false, actor.realPlayer, ""));
            }
        }

        this.players.remove(actor.actor);
        EntityUtils.setRecordPlayer(actor.actor, null);
    }

    public boolean cancel(PlayerEntity player)
    {
        return this.halt(player, false, true, true);
    }

    /**
     * Reset the tracking manager data
     */
    public void reset()
    {
        for (Record record : this.records.values())
        {
            if (record.dirty)
            {
                try
                {
                    record.save(RecordUtils.replayFile(record.filename));
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }

        /* Legacy clears exactly these four maps — scheduled countdowns were
         * (probably accidentally) left alone; preserved for parity */
        this.records.clear();
        this.chunks.clear();
        this.recorders.clear();
        this.players.clear();
    }

    /**
     * Abort the recording of action for given player
     */
    public void abort(PlayerEntity player)
    {
        if (this.recorders.containsKey(player))
        {
            RecordRecorder recorder = this.recorders.remove(player);

            RecordUtils.broadcastError("recording.logout", recorder.record.filename);
        }
    }

    /**
     * Get record by the filename
     *
     * If a record by the filename doesn't exist, then record manager tries to
     * load this record.
     */
    public Record get(String filename) throws Exception
    {
        Record record = this.records.get(filename);

        if (record == null)
        {
            File file = RecordUtils.replayFile(filename);

            record = new Record(filename);
            record.load(file);

            this.records.put(filename, record);
        }

        return record;
    }

    /**
     * Get a record on the client mirror manager (roadmap P116). Unlike
     * {@link #get(String)} (server, world-folder), this loads jar-bundled
     * default records from {@code /assets/blockbuster/records/*.dat} when the
     * record is not already cached. Total reader: any failure yields
     * {@code null} (the caller then requests the record from the server).
     */
    public Record getClient(String filename)
    {
        Record record = this.records.get(filename);

        if (record == null)
        {
            try (InputStream stream = RecordUtils.getLocalReplay(filename))
            {
                if (stream == null)
                {
                    return null;
                }

                NbtCompound tag = NbtIo.readCompressed(stream, NbtTagSizeTracker.ofUnlimitedBytes());

                record = new Record(filename);
                record.load(tag);

                this.records.put(filename, record);
            }
            catch (Exception e)
            {}
        }

        return record;
    }

    /**
     * Unload old records and check scheduled actions
     */
    public void tick()
    {
        if (Blockbuster.recordUnload.get() && !this.records.isEmpty())
        {
            this.checkAndUnloadRecords();
        }

        if (!this.scheduled.isEmpty())
        {
            this.checkScheduled();
        }
    }

    /**
     * Check for any unloaded record and unload it if needed requirements are
     * met.
     */
    private void checkAndUnloadRecords()
    {
        Iterator<Map.Entry<String, Record>> iterator = this.records.entrySet().iterator();

        while (iterator.hasNext())
        {
            Record record = iterator.next().getValue();

            record.unload--;

            if (record.unload <= 0)
            {
                iterator.remove();
                RecordUtils.unloadRecord(record);

                try
                {
                    if (record.dirty)
                    {
                        record.save(RecordUtils.replayFile(record.filename));
                        record.dirty = false;
                    }
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Check for scheduled records and countdown them.
     */
    private void checkScheduled()
    {
        Iterator<ScheduledRecording> it = this.scheduled.values().iterator();

        while (it.hasNext())
        {
            ScheduledRecording record = it.next();

            if (record.countdown % 2 == 0)
            {
                this.sendCaption(record.player, Text.translatable("blockbuster.start_recording", record.recorder.record.filename, String.valueOf(record.countdown / 20F)));
            }

            if (record.countdown <= 0)
            {
                record.run();
                this.recorders.put(record.player, record.recorder);
                this.sendCaption(record.player, null);

                if (record.player != null)
                {
                    Dispatcher.sendTo(new PacketPlayerRecording(true, record.recorder.record.filename, record.offset, false), record.player);
                }

                it.remove();

                continue;
            }

            record.countdown--;
        }
    }

    /**
     * Countdown caption seam (roadmap P116). Sends {@code PacketCaption} to the
     * player; the client {@code RecordingOverlayState} / P124 HUD renders it.
     * A null text hides the overlay. Null-guarded for headless tests.
     */
    private void sendCaption(ServerPlayerEntity player, Text text)
    {
        if (player != null)
        {
            Dispatcher.sendTo(new PacketCaption(text), player);
        }
    }

    public void rename(String old, Record record)
    {
        RecordUtils.unloadRecord(record);

        this.records.remove(old);
        this.records.put(record.filename, record);

        for (String iter : RecordUtils.getReplayIterations(old))
        {
            File oldIter = new File(RecordUtils.replayFile(old).getAbsolutePath() + "~" + iter);

            oldIter.renameTo(new File(RecordUtils.replayFile(record.filename).getAbsolutePath() + "~" + iter));
        }

        RecordUtils.replayFile(old).delete();
    }
}
