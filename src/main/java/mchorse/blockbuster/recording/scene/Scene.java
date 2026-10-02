package mchorse.blockbuster.recording.scene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;

import io.netty.buffer.ByteBuf;
import mchorse.blockbuster.Blockbuster;
import mchorse.blockbuster.CommonProxy;
import mchorse.blockbuster.audio.AudioHandler;
import mchorse.blockbuster.common.entity.EntityActor;
import mchorse.blockbuster.recording.RecordPlayer;
import mchorse.blockbuster.recording.RecordUtils;
import mchorse.blockbuster.recording.data.Mode;
import mchorse.blockbuster.recording.data.Record;
import mchorse.blockbuster.recording.scene.fake.FakePlayerFactory;
import mchorse.mclib.network.ForgeByteBufUtils;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.world.World;

/**
 * Scene domain object + server-side playback engine.
 *
 * <p>A scene groups {@link Replay}s (one per actor) plus playback metadata and
 * persists as gzip NBT under {@code <world>/blockbuster/scenes/*.dat}.</p>
 *
 * <p>Legacy source:
 * {@code blockbuster-1.12/src/main/java/mchorse/blockbuster/recording/scene/Scene.java}.</p>
 *
 * <p><b>P127</b> ported the pure data-transfer surface (fields, id/suffix/rename
 * machinery, {@code copy}, byte-exact NBT/ByteBuf). <b>P128</b> adds the
 * server-side playback engine driven by {@link SceneManager}: actor collection,
 * {@code startPlayback}/{@code stopPlayback}/{@code spawn}/{@code pause}/
 * {@code resume}/{@code goTo}/{@code reload}, per-tick actor driving
 * ({@link #tick()}/{@link #worldTick(World)}), loop restarts, and the
 * start/stop command execution.</p>
 *
 * <p><b>P130</b> completed the actor <i>spawn orchestration</i> that P128 left
 * open: {@link #collectActors(Replay)} now resolves target players (with the
 * first-person {@link PlayerState} snapshot, {@link #getTargetPlayer(String)})
 * and builds fake players before falling back to a plain {@link EntityActor},
 * {@code Replay.apply} runs on spawn and reload, and {@link #applySpawnMorph}
 * scrubs the paused-preview morph through {@code Record.applyPreviousMorph}. <b>S16</b>
 * completed the {@link AudioHandler} engine, which this class now drives on
 * every playback transition (start / stop / pause / resume / goto) as well as
 * through NBT/ByteBuf/copy. No seams remain open in this class.</p>
 */
public class Scene
{
    /**
     * Timestamp bumped by {@link #collectActors(Replay)} when
     * {@code reset_on_playback} is on; model blocks (S8) watch it.
     */
    public static long lastUpdate;

    /**
     * Pattern for finding numbered suffix
     */
    public static final Pattern NUMBERED_SUFFIX = Pattern.compile("_(\\d+)$");

    /**
     * Pattern for finding prefix
     */
    public static final Pattern PREFIX = Pattern.compile("^(.+)_([^_]+)$");

    /**
     * Pattern for finding indexes
     */
    public static final Pattern INDEXES = Pattern.compile("[^_]+");

    /**
     * Scene's id/filename. <b>Never</b> serialized to NBT — only to the
     * ByteBuf. Loaders must {@code setId(filename)} after {@code fromNBT}.
     */
    private String id = "";

    /**
     * List of replays
     */
    public List<Replay> replays = new ArrayList<Replay>();

    /**
     * Display title, used for client organization purposes
     */
    public String title = "";

    /**
     * Command which should be executed when scene starts playing
     */
    public String startCommand = "";

    /**
     * Command which should be executed when scene stops playing
     */
    public String stopCommand = "";

    /**
     * Whether scene's playback is looping
     */
    public boolean loops;

    private AudioHandler audioHandler = new AudioHandler();

    /* Runtime properties */

    /**
     * Whether this scene is active
     */
    public boolean playing;

    /**
     * Map of currently playing actors
     */
    public Map<Replay, RecordPlayer> actors = new HashMap<Replay, RecordPlayer>();

    /**
     * Count of actors which were spawned (used to check whether actors
     * are still playing)
     */
    public int actorsCount = 0;

    /**
     * Scene command sender
     */
    private SceneSender sender;

    /**
     * This tick used for checking if actors still playing
     */
    private int tick = 0;

    /**
     * Whether this scene gets recorded
     */
    private boolean wasRecording;

    /**
     * Whether it's paused
     */
    private boolean paused;

    /**
     * World instance
     */
    private World world;

    /**
     * List that contains the players that have been chosen for first person
     * playback in this scene with their inventory before playback has started.
     *
     * <p>Populated by the target-player branch of {@link #collectActors(Replay)}
     * (P130) — one entry per replay whose {@code target} resolves to a live
     * player. {@link #stopPlayback(boolean)} restores + clears it.</p>
     */
    private List<PlayerState> targetPlayers = new ArrayList<>();

    /**
     * @return true if the given player reference has been chosen for first
     *         person playback in one actor
     */
    public boolean isPlayerTargetPlayback(PlayerEntity player)
    {
        for (PlayerState state : this.targetPlayers)
        {
            if (state.getPlayer() == player)
            {
                return true;
            }
        }

        return false;
    }

    public List<PlayerEntity> getTargetPlaybackPlayers()
    {
        List<PlayerEntity> players = new ArrayList<>();

        for (PlayerState state : this.targetPlayers)
        {
            players.add(state.getPlayer());
        }

        return players;
    }

    public String getAudio()
    {
        return this.audioHandler.getAudioName();
    }

    public void setAudio(String audio)
    {
        this.audioHandler.setAudioName(audio);
    }

    public int getAudioShift()
    {
        return this.audioHandler.getAudioShift();
    }

    public void setAudioShift(int audioShift)
    {
        this.audioHandler.setAudioShift(audioShift);

        /* Legacy nudged the running audio to the new shift so an edit is
         * audible mid-playback — server-side scenes only (client copies just
         * store the value). yarn: World.isClient replaces isRemote. */
        if (this.world != null && !this.world.isClient)
        {
            this.audioHandler.goTo(this.tick);
        }
    }

    public AudioHandler getAudioHandler()
    {
        return this.audioHandler;
    }

    /**
     * Send this scene's current audio state to a freshly-joined player (S16
     * P189) — called from {@code CapabilityHandler} on login so a player who
     * joins mid-playback hears the audio at the right position.
     */
    public void syncAudio(ServerPlayerEntity player)
    {
        this.audioHandler.syncPlayer(player);
    }

    public String getId()
    {
        return this.id;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public void setWorld(World world)
    {
        this.world = world;
    }

    public World getWorld()
    {
        return this.world;
    }

    public int getTick()
    {
        return this.tick;
    }

    public int getCurrentTick()
    {
        for (RecordPlayer player : this.actors.values())
        {
            /* P277: legacy `!player.actor.isDead` — the 1.12.2 FIELD, i.e.
             * "still in the world". NOT yarn's isDead(), which is health <= 0.
             * See areActorsFinished below for why the difference bites. */
            if (!player.isFinished() && !player.actor.isRemoved())
            {
                return player.tick;
            }
        }

        return 0;
    }

    /**
     * Set scene command sender
     */
    public void setSender(SceneSender sender)
    {
        this.sender = sender;
    }

    /**
     * Get a replay by its record filename.
     */
    public Replay getByFile(String filename)
    {
        for (Replay replay : this.replays)
        {
            if (replay.id.equals(filename))
            {
                return replay;
            }
        }

        return null;
    }

    /**
     * Get maximum length of this scene (the longest replay record).
     */
    public int getMaxLength()
    {
        int max = 0;

        for (Replay replay : this.replays)
        {
            Record record = null;

            try
            {
                record = CommonProxy.manager.get(replay.id);
            }
            catch (Exception e)
            {}

            if (record != null)
            {
                max = Math.max(max, record.getFullLength());
            }
        }

        return max;
    }

    public void tick()
    {
        if (Blockbuster.debugPlaybackTicks.get())
        {
            this.logTicks();
        }

        for (RecordPlayer player : this.actors.values())
        {
            if (!player.realPlayer && player.actor instanceof ServerPlayerEntity)
            {
                /* Fake players advance via ServerPlayerEntity.playerTick()
                 * (legacy onUpdateEntity()) rather than RecordPlayer.next() —
                 * the record still drives them through worldTick/playActions. */
                ((ServerPlayerEntity) player.actor).playerTick();
            }
            else if (!player.actorUpdated)
            {
                player.next();
            }

            player.actorUpdated = false;
        }

        if (this.playing && !this.paused)
        {
            if (this.tick % 4 == 0 && !this.checkActors()) return;

            this.audioHandler.update();
            this.tick++;
        }
    }

    public void worldTick(World world)
    {
        for (RecordPlayer player : this.actors.values())
        {
            if (player.actor.getWorld() == world)
            {
                if (this.playing)
                {
                    player.checkAndSpawn();
                }

                player.playActions();
            }
        }
    }

    /**
     * Check whether collected actors are still playing
     */
    public boolean areActorsFinished()
    {
        int count = 0;

        for (Map.Entry<Replay, RecordPlayer> entry : this.actors.entrySet())
        {
            Replay replay = entry.getKey();
            RecordPlayer actor = entry.getValue();

            if (this.loops && actor.isFinished())
            {
                actor.record.reset(actor.actor);

                actor.startPlaying(replay.id, actor.kill);
                actor.record.applyAction(0, actor.actor);

                CommonProxy.manager.players.put(actor.actor, actor);
            }

            /* P277 — the mistranslation that made a scene stop noticing its own
             * cast. Legacy read `actor.actor.isDead`, the 1.12.2 Entity FIELD,
             * which means "removed from the world" (set by setDead()). Yarn
             * 1.20.4 happens to HAVE a LivingEntity.isDead(), and it means
             * something else entirely: `getHealth() <= 0`, ignoring removal.
             * Porting the name instead of the meaning compiles, reads right and
             * is wrong.
             *
             * It bites hardest here. RecordManager.stop removes an actor with
             * discard(), which does not touch health — so a discarded actor
             * reports isDead() == false forever, is never counted, the count
             * never reaches actorsCount, areActorsFinished() never returns true
             * and the scene never auto-stops. isRemoved() is the exact
             * equivalent of the legacy field. */
            if ((actor.isFinished() && actor.playing) || actor.actor.isRemoved())
            {
                count++;
            }
        }

        return count == this.actorsCount;
    }

    /* Playback and editing */

    /**
     * Check whether actors are still playing, if they're stop the whole thing.
     *
     * @return false if every actor is finished and the scene can be stopped.
     */
    public boolean checkActors()
    {
        /*
         * Don't stop the entire scene when one actor is left and if that is the
         * recording actor. If it would stop, delayed audio might not start.
         */
        if (this.areActorsFinished() && !this.loops && !this.wasRecording)
        {
            this.stopPlayback(false);

            return false;
        }

        return true;
    }

    /**
     * Play the scene from the given tick.
     */
    public void startPlayback(int tick)
    {
        if (this.getWorld().isClient || this.playing || this.replays.isEmpty())
        {
            return;
        }

        for (Replay replay : this.replays)
        {
            if (replay.id.isEmpty())
            {
                RecordUtils.broadcastError("director.empty_filename");

                return;
            }
        }

        this.collectActors(null);

        LivingEntity firstActor = null;

        for (Map.Entry<Replay, RecordPlayer> entry : this.actors.entrySet())
        {
            Replay replay = entry.getKey();
            RecordPlayer actor = entry.getValue();

            if (firstActor == null)
            {
                firstActor = actor.actor;
            }

            actor.startPlaying(replay.id, tick, !this.loops);
        }

        this.playing = true;
        this.sendCommand(this.startCommand);

        if (firstActor != null)
        {
            CommonProxy.damage.addDamageControl(this, firstActor, this::getTick);
        }

        this.audioHandler.startAudio(tick);

        this.wasRecording = false;
        this.paused = false;
        this.tick = tick;
    }

    /**
     * The same thing as play, but don't play the replay that is passed in the
     * arguments (because he might be recorded by the player).
     *
     * Used by recording code.
     */
    public void startPlayback(String exception, int tick)
    {
        if (this.getWorld().isClient || this.playing)
        {
            return;
        }

        this.collectActors(this.getByFile(exception));

        for (Map.Entry<Replay, RecordPlayer> entry : this.actors.entrySet())
        {
            Replay replay = entry.getKey();
            RecordPlayer actor = entry.getValue();

            actor.startPlaying(replay.id, tick, true);
        }

        this.playing = true;
        this.sendCommand(this.startCommand);

        this.audioHandler.startAudio(tick);

        this.wasRecording = true;
        this.paused = false;
        this.tick = tick;
    }

    /**
     * Spawns actors at given tick in idle mode and pauses the scene. This is
     * pretty useful for positioning cameras for exact positions.
     */
    public boolean spawn(int tick)
    {
        if (this.replays.isEmpty())
        {
            return false;
        }

        if (!this.actors.isEmpty())
        {
            this.stopPlayback(true);
        }

        for (Replay replay : this.replays)
        {
            if (replay.id.isEmpty())
            {
                RecordUtils.broadcastError("director.empty_filename");

                return false;
            }
        }

        this.collectActors(null);
        this.playing = true;
        this.paused = true;

        int j = 0;

        for (Map.Entry<Replay, RecordPlayer> entry : this.actors.entrySet())
        {
            Replay replay = entry.getKey();
            RecordPlayer actor = entry.getValue();

            if (j == 0 && actor.actor != null)
            {
                CommonProxy.damage.addDamageControl(this, actor.actor, this::getTick);
            }

            actor.playing = false;
            actor.startPlaying(replay.id, tick, true);
            actor.sync = true;
            actor.pause();

            for (int i = 0; i <= tick; i++)
            {
                actor.record.applyAction(i - actor.record.preDelay, actor.actor);
            }

            this.applySpawnMorph(actor, replay, tick);

            j++;
        }

        this.audioHandler.pauseAudio(tick);
        this.tick = tick;

        return true;
    }

    /**
     * The paused-preview morph scrub {@link #spawn(int)} runs for every actor
     * it seeds (S22 P235 — legacy {@code Scene.java:547}, verbatim). Without it
     * a scene spawned at a tick showed each actor's <i>initial</i> morph rather
     * than the one the record's morph actions had reached by that tick.
     *
     * <p>Extracted from {@code spawn} so the delegation is exercisable
     * headlessly: {@code spawn} itself needs a live world, a player manager and
     * spawned actors (see {@code SceneManagerTickDrivingTest} for that
     * boundary), while this is the only line of it that is pure dispatch.</p>
     */
    void applySpawnMorph(RecordPlayer actor, Replay replay, int tick)
    {
        actor.record.applyPreviousMorph(actor.actor, replay, tick, Record.MorphType.PAUSE);
    }

    /**
     * Force stop playback.
     *
     * @param triggered - true if it was caused by something, and false if it
     *                    just ended playing
     */
    public void stopPlayback(boolean triggered)
    {
        if (!triggered && !this.wasRecording || triggered)
        {
            this.wasRecording = false;
        }

        if (this.getWorld().isClient || !this.playing)
        {
            return;
        }

        this.tick = 0;

        for (Map.Entry<Replay, RecordPlayer> entry : this.actors.entrySet())
        {
            RecordPlayer actor = entry.getValue();

            actor.kill = true;
            actor.stopPlaying();
        }

        CommonProxy.damage.restoreDamageControl(this, this.getWorld());

        this.targetPlayers.forEach((playerState) ->
        {
            playerState.resetPlayerState();
        });

        this.targetPlayers.clear();

        this.audioHandler.stopAudio();

        this.actors.clear();
        this.playing = false;
        this.sendCommand(this.stopCommand);
    }

    /**
     * Toggle scene's playback
     */
    public boolean togglePlayback()
    {
        if (this.playing)
        {
            this.stopPlayback(true);
        }
        else
        {
            this.startPlayback(0);
        }

        return this.playing;
    }

    /**
     * Collect actors.
     *
     * <p>This method is responsible for collecting actors — the ones already in
     * the world and also the ones that don't exist (they will be created and
     * spawned later on).</p>
     *
     * <p><b>Selection order (P130, load-bearing):</b> target player (with the
     * first-person {@link PlayerState} snapshot) → fake player, when
     * {@code replay.fake} and the world can host one → plain
     * {@link EntityActor}. Every step is total: an unresolvable target or a
     * failed fake construction falls through to the plain actor rather than
     * skipping the replay ({@code E.dat}'s all-plain-actor case is simply the
     * bottom of that ladder).</p>
     */
    private void collectActors(Replay exception)
    {
        this.actors.clear();
        this.actorsCount = 0;

        for (Replay replay : this.replays)
        {
            if (replay == exception || !replay.enabled)
            {
                continue;
            }

            World world = this.getWorld();
            LivingEntity actor = null;
            boolean real = false;

            /* Locate the target player (first-person playback). */
            if (!replay.target.isEmpty())
            {
                ServerPlayerEntity player = this.getTargetPlayer(replay.target);

                if (player != null)
                {
                    if (!this.isPlayerTargetPlayback(player))
                    {
                        this.targetPlayers.add(new PlayerState(player));
                    }

                    actor = player;
                    real = true;
                }
            }

            /* Otherwise a fake player, if requested (and the world can host
             * one) — total: a failed fake construction falls back to a plain
             * actor rather than crashing. */
            if (actor == null && replay.fake && world instanceof ServerWorld)
            {
                actor = FakePlayerFactory.create((ServerWorld) world, replay, this.actorsCount);
            }

            if (actor == null)
            {
                EntityActor entity = new EntityActor(world);

                entity.wasAttached = true;
                actor = entity;
            }

            RecordPlayer player = CommonProxy.manager.play(replay.id, actor, Mode.BOTH, 0, true);

            if (real)
            {
                player.realPlayer();
            }

            if (player != null)
            {
                player.setReplay(replay);

                this.actorsCount++;
                replay.apply(actor);
                this.actors.put(replay, player);
            }
        }

        if (Blockbuster.modelBlockResetOnPlayback.get())
        {
            lastUpdate = System.currentTimeMillis();
        }
    }

    /**
     * The 1.12.2 grammar of a replay {@code target} string — <b>not</b> the
     * vanilla command-selector grammar (P273).
     *
     * <p>The director's Target field has always had its own, much smaller
     * grammar (see {@code blockbuster.gui.director.target_tooltip}, which
     * documents exactly these two forms): {@code "@r"} is a random online
     * player, any other {@code "@…"} names a <b>scoreboard team</b>, and
     * anything else is a username. {@code @p}/{@code @a}/{@code @e}/{@code @s}
     * were never selectors here — legacy read {@code "@p"} as the team
     * {@code "p"} and, failing that, as the username {@code "@p"}. Widening
     * this to the command-selector set would break a team literally called
     * {@code p}, so the port keeps the legacy grammar verbatim; the full
     * selector set lives on the commands, via
     * {@link mchorse.mclib.commands.utils.EntitySelectorUtils}.</p>
     */
    public enum TargetKind
    {
        /** Exactly {@code "@r"} — a random online player. */
        RANDOM,
        /** Any other {@code "@…"} — a scoreboard team name (minus the {@code @}). */
        TEAM,
        /** Anything else — an exact username. */
        USERNAME
    }

    /**
     * Classify a replay {@code target} string per {@link TargetKind}. Total:
     * {@code null} and {@code ""} classify as {@link TargetKind#USERNAME}, the
     * same bucket legacy's final {@code getPlayerByUsername} fallthrough put
     * them in.
     */
    public static TargetKind classifyTarget(String target)
    {
        if (target == null)
        {
            return TargetKind.USERNAME;
        }

        if (target.equals("@r"))
        {
            return TargetKind.RANDOM;
        }

        if (target.startsWith("@"))
        {
            return TargetKind.TEAM;
        }

        return TargetKind.USERNAME;
    }

    /**
     * Resolve a replay {@code target} string to an online player:
     * {@code "@r"} → a random online player; {@code "@<team>"} → the first
     * member of a scoreboard team (by username lookup); otherwise an exact
     * username match. Returns {@code null} when nothing resolves (total —
     * legacy would NPE on an absent server).
     *
     * <p>Legacy fallthrough preserved: an {@code "@<team>"} that names no team
     * (or an empty one) drops to the username lookup with the {@code @} still
     * attached, which never matches — so an unknown selector-looking target
     * yields no target player and the replay falls to a fake/plain actor,
     * exactly as in 1.12.2.</p>
     */
    private ServerPlayerEntity getTargetPlayer(String target)
    {
        World world = this.getWorld();

        if (target == null || world == null || world.getServer() == null)
        {
            return null;
        }

        PlayerManager list = world.getServer().getPlayerManager();
        TargetKind kind = classifyTarget(target);

        if (kind == TargetKind.RANDOM)
        {
            /* Pick a random player */
            List<ServerPlayerEntity> players = list.getPlayerList();

            if (players.isEmpty())
            {
                return null;
            }

            return players.get((int) (players.size() * Math.random()));
        }
        else if (kind == TargetKind.TEAM)
        {
            /* Pick the first player from the given team */
            Team team = world.getScoreboard().getTeam(target.substring(1));

            if (team != null && !team.getPlayerList().isEmpty())
            {
                return list.getPlayer(team.getPlayerList().iterator().next());
            }
        }

        /* Get the player by username */
        return list.getPlayer(target);
    }

    public boolean isPlaying()
    {
        for (RecordPlayer player : this.actors.values())
        {
            if (player.playing)
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Pause the scene's playback (basically, pause all actors).
     */
    public void pause()
    {
        for (RecordPlayer actor : this.actors.values())
        {
            actor.pause();
        }

        this.audioHandler.pauseAudio();
        this.paused = true;
    }

    /**
     * Resume paused scene playback (basically, resume all actors).
     *
     * @param tick the tick at which to resume playing. If -1 the scene will
     *             just play at the tick it was paused at.
     */
    public void resume(int tick)
    {
        if (tick >= 0)
        {
            this.tick = tick;
        }

        for (RecordPlayer player : this.actors.values())
        {
            player.resume(tick);
        }

        this.audioHandler.resume(this.tick);
        this.paused = false;
    }

    /**
     * Make actors go to the given tick.
     */
    public void goTo(int tick, boolean actions)
    {
        this.tick = tick;

        for (Map.Entry<Replay, RecordPlayer> entry : this.actors.entrySet())
        {
            Replay replay = entry.getKey();

            if (tick == 0)
            {
                replay.apply(entry.getValue().actor);
            }

            entry.getValue().goTo(tick, actions);
        }

        this.audioHandler.goTo(tick);
    }

    /**
     * Reload actors.
     */
    public void reload(int tick)
    {
        this.stopPlayback(true);
        this.spawn(tick);
    }

    /**
     * Duplicate a replay by index, giving the copy the next free {@code _N}
     * suffix.
     */
    public boolean dupe(int index)
    {
        if (index < 0 || index >= this.replays.size())
        {
            return false;
        }

        Replay replay = this.replays.get(index).copy();

        replay.id = this.getNextSuffix(replay.id);
        this.replays.add(replay);

        return true;
    }

    /**
     * Return next base suffix, this fixes issue with getNextSuffix() when the
     * scene's name is "tia_6", and it returns "tia_1" instead of "tia_6_1"
     */
    public String getNextBaseSuffix(String filename)
    {
        if (filename.isEmpty())
        {
            return filename;
        }

        return this.getNextSuffix(filename + "_0");
    }

    public String getNextSuffix(String filename)
    {
        if (filename.isEmpty())
        {
            return filename;
        }

        Matcher matcher = NUMBERED_SUFFIX.matcher(filename);

        String prefix = filename;
        boolean found = matcher.find();
        int max = 0;

        if (found)
        {
            prefix = filename.substring(0, matcher.start());
        }

        for (Replay other : this.replays)
        {
            if (other.id.startsWith(prefix))
            {
                matcher = NUMBERED_SUFFIX.matcher(other.id);

                if (matcher.find() && other.id.substring(0, matcher.start()).equals(prefix))
                {
                    max = Math.max(max, Integer.parseInt(matcher.group(1)));
                }
            }
        }

        return prefix + "_" + (max + 1);
    }

    public void setupIds()
    {
        for (Replay replay : this.replays)
        {
            if (replay.id.isEmpty())
            {
                replay.id = this.getNextBaseSuffix(this.getId());
            }
        }
    }

    public void renamePrefix(String newPrefix)
    {
        this.renamePrefix(null, newPrefix, null);
    }

    public void renamePrefix(@Nullable String oldPrefix, String newPrefix, Function<String, String> process)
    {
        //default format <scene name>_<id>
        for (Replay replay : this.replays)
        {
            Matcher matcher = PREFIX.matcher(replay.id);

            /* test whether <scene name> is at the beginning
            *  and whether there are multiple indexes*/
            if (oldPrefix != null && replay.id.startsWith(oldPrefix + "_"))
            {
                String indexes = replay.id.substring(oldPrefix.length() + 1); //length+1 to exclude "_"
                Matcher matcherIndexes = INDEXES.matcher(indexes);

                int counter = 0;

                while (matcherIndexes.find())
                {
                    counter++;
                }

                /* there are multiple indexes separated by _ */
                if (counter > 1)
                {
                    replay.id = newPrefix + "_" + indexes;

                    continue;
                }
            }

            if (matcher.find())
            {
                replay.id = newPrefix + "_" + matcher.group(2);
            }
            else if (process != null)
            {
                replay.id = process.apply(replay.id);
            }
        }
    }

    /**
     * Send a command through the scene sender (permission-free, silent).
     */
    public void sendCommand(String command)
    {
        if (this.sender != null && !command.isEmpty())
        {
            ServerCommandSource source = this.sender.create();
            World world = this.getWorld();

            if (source != null && world != null && world.getServer() != null)
            {
                world.getServer().getCommandManager().executeWithPrefix(source, command);
            }
        }
    }

    /**
     * Log first actor's ticks (for debug purposes).
     */
    public void logTicks()
    {
        if (this.actors.isEmpty())
        {
            return;
        }

        RecordPlayer actor = this.actors.values().iterator().next();

        if (actor != null)
        {
            Blockbuster.LOGGER.info("Scene tick: " + actor.getTick());
        }
    }

    public void copy(Scene scene)
    {
        /* There is no need to copy itself, copying itself will lead to
         * lost of replay data as it clears its replays and then will have
         * nothing to copy over... */
        if (this == scene)
        {
            return;
        }

        this.replays.clear();

        scene.replays.forEach((element) ->
        {
            this.replays.add(element.copy());
        });

        this.loops = scene.loops;
        this.title = scene.title;
        this.startCommand = scene.startCommand;
        this.stopCommand = scene.stopCommand;

        this.audioHandler.copy(scene.audioHandler);
    }

    /* NBT methods */

    public void fromNBT(NbtCompound compound)
    {
        this.replays.clear();

        NbtList tagList = compound.getList("Actors", NbtElement.COMPOUND_TYPE);

        for (int i = 0; i < tagList.size(); i++)
        {
            Replay replay = new Replay();

            replay.fromNBT(tagList.getCompound(i));
            this.replays.add(replay);
        }

        this.loops = compound.getBoolean("Loops");
        this.title = compound.getString("Title");
        this.startCommand = compound.getString("StartCommand");
        this.stopCommand = compound.getString("StopCommand");

        this.audioHandler.fromNBT(compound);
    }

    public void toNBT(NbtCompound compound)
    {
        NbtList tagList = new NbtList();

        for (int i = 0; i < this.replays.size(); i++)
        {
            NbtCompound tag = new NbtCompound();

            this.replays.get(i).toNBT(tag);
            tagList.add(tag);
        }

        compound.put("Actors", tagList);
        compound.putBoolean("Loops", this.loops);
        compound.putString("Title", this.title);
        compound.putString("StartCommand", this.startCommand);
        compound.putString("StopCommand", this.stopCommand);

        this.audioHandler.toNBT(compound);
    }

    /* ByteBuf methods */

    public void fromBuf(ByteBuf buffer)
    {
        this.id = ForgeByteBufUtils.readUTF8String(buffer);
        this.replays.clear();

        for (int i = 0, c = buffer.readInt(); i < c; i++)
        {
            Replay replay = new Replay();

            this.replays.add(replay);
            replay.fromBuf(buffer);
        }

        this.loops = buffer.readBoolean();
        this.title = ForgeByteBufUtils.readUTF8String(buffer);
        this.startCommand = ForgeByteBufUtils.readUTF8String(buffer);
        this.stopCommand = ForgeByteBufUtils.readUTF8String(buffer);

        this.audioHandler.fromBytes(buffer);
    }

    public void toBuf(ByteBuf buffer)
    {
        ForgeByteBufUtils.writeUTF8String(buffer, this.id);
        buffer.writeInt(this.replays.size());

        for (Replay replay : this.replays)
        {
            replay.toBuf(buffer);
        }

        buffer.writeBoolean(this.loops);
        ForgeByteBufUtils.writeUTF8String(buffer, this.title);
        ForgeByteBufUtils.writeUTF8String(buffer, this.startCommand);
        ForgeByteBufUtils.writeUTF8String(buffer, this.stopCommand);

        this.audioHandler.toBytes(buffer);
    }

    /**
     * Stores an {@link PlayerEntity} and its state (inventory, XP, food) so a
     * first-person target actor can be reset to its original state at the end
     * of scene playback.
     *
     * <p>Constructed by the target-player branch of {@link Scene#collectActors}
     * (P130) and consumed by {@link Scene#stopPlayback(boolean)}, which restores
     * and clears state exactly like 1.12.2.</p>
     */
    public class PlayerState
    {
        private PlayerEntity player;
        private DefaultedList<ItemStack> mainInventory;
        private int experienceLevel;
        /**
         * The total amount of experience the player has (includes the amount
         * within their experience bar).
         */
        private int experienceTotal;
        /** The current amount of experience within the player's experience bar. */
        private float experience;
        private int foodLevel;

        public PlayerState(PlayerEntity player)
        {
            this.player = player;

            /* data structure used for the main inventory list (36 slots) */
            this.mainInventory = DefaultedList.ofSize(36, ItemStack.EMPTY);

            for (int i = 0; i < this.mainInventory.size(); i++)
            {
                this.mainInventory.set(i, player.getInventory().main.get(i).copy());
            }

            this.experience = player.experienceProgress;
            this.experienceLevel = player.experienceLevel;
            this.experienceTotal = player.totalExperience;
            this.foodLevel = player.getHungerManager().getFoodLevel();
        }

        public PlayerEntity getPlayer()
        {
            return this.player;
        }

        /**
         * Resets the player's attributes stored by this state.
         */
        public void resetPlayerState()
        {
            for (int i = 0; i < this.player.getInventory().main.size(); i++)
            {
                this.player.getInventory().main.set(i, this.mainInventory.get(i).copy());
            }

            this.player.experienceProgress = this.experience;
            this.player.experienceLevel = this.experienceLevel;
            this.player.totalExperience = this.experienceTotal;
            this.player.getHungerManager().setFoodLevel(this.foodLevel);
        }
    }
}
