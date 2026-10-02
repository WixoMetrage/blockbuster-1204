package mchorse.blockbuster.recording.scene;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import mchorse.blockbuster.Blockbuster;
import mchorse.blockbuster.CommonProxy;
import mchorse.blockbuster.recording.data.Mode;
import mchorse.blockbuster.utils.BlockbusterPaths;
import mchorse.mclib.utils.PastCopies;
import mchorse.mclib.utils.AtomicWrite;
import mchorse.mclib.utils.Patterns;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtTagSizeTracker;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;

/**
 * Scene manager (roadmap P128) — 1:1 port of the 2.7.2 class.
 *
 * <p>This manager owns the lazily-loaded, per-world cache of live {@link Scene}s
 * (something like remote director blocks) and their disk I/O under
 * {@code <world>/blockbuster/scenes/*.dat}.</p>
 *
 * <p>Legacy source:
 * {@code blockbuster-1.12/src/main/java/mchorse/blockbuster/recording/scene/SceneManager.java}.</p>
 *
 * <p><b>Eviction semantics (load-bearing):</b> {@link #tick()} removes every
 * scene with {@code !playing} <i>every tick</i> — the cache only ever holds live
 * scenes (plus same-tick additions deferred through {@link #toPut}). Downstream
 * audio-sync / damage-control queries rely on {@link #getScenes()} exposing only
 * playing scenes.</p>
 *
 * <p>Disk I/O uses yarn {@link NbtIo} compressed read/write against
 * {@link BlockbusterPaths#scenes} (legacy {@code Utils.serverFile("blockbuster/
 * scenes", …)}). Every reader is total: a corrupt/missing {@code .dat} logs and
 * yields {@code null}/skip, never a crash.</p>
 */
public class SceneManager
{
    /**
     * Currently loaded scenes
     */
    private Map<String, Scene> scenes = new ConcurrentHashMap<String, Scene>();

    private List<String> toRemove = new ArrayList<String>();
    private Map<String, Scene> toPut = new HashMap<String, Scene>();
    private boolean ticking;

    public Map<String, Scene> getScenes()
    {
        return new HashMap<>(this.scenes);
    }

    public static boolean isValidFilename(String filename)
    {
        return !filename.isEmpty() && Patterns.FILENAME.matcher(filename).matches();
    }

    /**
     * Reset scene manager
     *
     * <p><b>Drops</b> the cache — it does not <i>stop</i> anything. Legacy's
     * {@code reset()} is the same bare {@code clear()}, and that is deliberate:
     * it is the "forget this world's scenes" half. The "tear the scene down"
     * half is {@link #stopAll()}, and it must run <i>before</i> this. Calling
     * {@code reset()} on a still-{@code playing} scene abandons its actors in
     * the world — the P277 bug.</p>
     */
    public void reset()
    {
        this.ticking = false;
        this.toRemove.clear();
        this.toPut.clear();

        this.scenes.clear();
    }

    /* ------------------------------------------------------------------ *
     * P277 — teardown
     * ------------------------------------------------------------------ */

    /**
     * Stop <b>every</b> live scene exactly as an explicit scene stop would,
     * then drop the cache (roadmap P277).
     *
     * <p><b>The bug this closes.</b> Server shutdown used to call
     * {@link #reset()} alone. {@code reset()} is a bare {@code Map.clear()}: it
     * forgets the {@link Scene} objects without ever calling
     * {@link Scene#stopPlayback(boolean)} — and {@code stopPlayback} is the
     * <i>only</i> path that</p>
     * <ul>
     * <li>flips {@code RecordPlayer.kill} and calls {@code RecordManager.stop},
     *     whose {@code kill} branch is the single place an actor is ever
     *     {@code discard()}ed (and a fake player logged out of the player
     *     list);</li>
     * <li>runs {@code CommonProxy.damage.restoreDamageControl}, i.e. puts the
     *     blocks the scene destroyed back and removes the entities it
     *     spawned;</li>
     * <li>fires the scene's {@code stopCommand} — the user-facing "restore the
     *     environment" hook, since a scene changes weather/time by running a
     *     command from {@code startCommand} and undoing it from
     *     {@code stopCommand};</li>
     * <li>restores every first-person target player's inventory/XP/food from
     *     the {@code PlayerState} snapshot taken when the scene started;</li>
     * <li>stops the scene's audio.</li>
     * </ul>
     * <p>So "Save and Quit to Title" left the actors standing in the world
     * (where the imminent world save then persisted them, so they came back on
     * rejoin), the terrain broken, whatever the scene's {@code startCommand}
     * had set still applied, and the target player still wearing the scene's
     * inventory.</p>
     *
     * <p><b>Ordering is load-bearing, twice over.</b></p>
     * <ol>
     * <li><i>Against the world save.</i> This must run on
     *     {@code ServerLifecycleEvents.SERVER_STOPPING}, which Fabric injects at
     *     the <b>HEAD of {@code MinecraftServer.shutdown}</b> — ahead of that
     *     method's own {@code save(…)} call. {@code SERVER_STOPPED} (injected at
     *     the TAIL) and {@code ServerWorldEvents.UNLOAD} (fired from
     *     {@code ServerWorld.close}) both run <i>after</i> the save, so an actor
     *     discarded there is already on disk and returns on the next join.</li>
     * <li><i>Against {@code RecordManager.reset()}.</i> {@code RecordManager
     *     .stop} opens with {@code if (!this.players.containsKey(actor.actor))
     *     return;}, and {@code reset()} clears {@code players}. Reset the record
     *     manager first and every {@code stopPlayback} below silently no-ops —
     *     nothing is discarded at all.
     *     {@link mchorse.blockbuster.Blockbuster#onServerStopping()} therefore
     *     calls this <i>before</i> {@code CommonProxy.manager.reset()},
     *     inverting legacy's order (legacy was free to use either, because
     *     legacy never stopped playback on shutdown at all).</li>
     * </ol>
     * <p>The damage repository's own {@code reset()} rides a separate
     * {@code SERVER_STOPPING} listener registered later (in
     * {@code ActionHandler.register()}), and Fabric fires listeners in
     * registration order, so the repository is still populated when
     * {@code restoreDamageControl} runs from here.</p>
     *
     * <p><b>Total and idempotent</b> by construction: an already-stopped scene
     * short-circuits inside {@code stopPlayback}, a world-less scene is skipped,
     * and a scene that throws is logged and stepped over so one bad scene cannot
     * abort the teardown of the rest.</p>
     */
    public void stopAll()
    {
        this.stopEach(this.snapshot());
        this.reset();
    }

    /**
     * Stop every live scene bound to {@code world}, leaving scenes that belong
     * to another dimension alone (roadmap P277).
     *
     * <p>Wired to {@code ServerWorldEvents.UNLOAD} as the dimension-unload leg.
     * At a full shutdown {@link #stopAll()} has already run from
     * {@code SERVER_STOPPING}, so this finds an empty cache — which is exactly
     * why the teardown has to be idempotent.</p>
     *
     * <p>Scenes are only <i>stopped</i> here, not evicted from the cache
     * directly: {@link #tick()} already evicts every {@code !playing} scene, and
     * a world unload that is not a shutdown leaves the manager alive.</p>
     */
    public void stopAllIn(World world)
    {
        if (world == null)
        {
            return;
        }

        List<Scene> matching = new ArrayList<Scene>();

        for (Scene scene : this.snapshot())
        {
            if (scene != null && scene.getWorld() == world)
            {
                matching.add(scene);
            }
        }

        this.stopEach(matching);
    }

    /**
     * Stop every live scene that had {@code player} cast for first-person
     * playback (roadmap P277).
     *
     * <p>The dedicated-server leg, wired to
     * {@code ServerPlayConnectionEvents.DISCONNECT}. A scene whose replay
     * targets a real player drives that player's entity directly and holds a
     * {@code PlayerState} snapshot of their inventory/XP/food taken when the
     * scene started; if they log out mid-scene nothing else ever puts that
     * snapshot back, and the scene goes on ticking a departed entity.
     * Deliberately <b>narrow</b>: an unrelated player leaving a multiplayer
     * server must not stop everyone else's scene, which is why this filters on
     * the target-playback set rather than stopping the world.</p>
     */
    public void stopScenesTargeting(PlayerEntity player)
    {
        if (player == null)
        {
            return;
        }

        List<Scene> matching = new ArrayList<Scene>();

        for (Scene scene : this.snapshot())
        {
            if (scene != null && scene.isPlayerTargetPlayback(player))
            {
                matching.add(scene);
            }
        }

        this.stopEach(matching);
    }

    /** A stable copy of the live scenes — teardown mutates the map it walks. */
    private List<Scene> snapshot()
    {
        return new ArrayList<Scene>(this.scenes.values());
    }

    /**
     * Run the explicit-stop path over each scene, totally: a world-less scene
     * (reachable from tests and from a half-initialised load) has nothing in a
     * world to tear down, and any scene that throws is logged and skipped.
     */
    private void stopEach(List<Scene> targets)
    {
        for (Scene scene : targets)
        {
            try
            {
                if (scene != null && scene.getWorld() != null)
                {
                    scene.stopPlayback(true);
                }
            }
            catch (Exception e)
            {
                Blockbuster.LOGGER.error("Failed to stop scene '" + (scene == null ? "?" : scene.getId()) + "' during teardown", e);
            }
        }
    }

    /**
     * Spawn actors and execute unsafe actions
     */
    public void worldTick(World world)
    {
        for (Map.Entry<String, Scene> entry : this.scenes.entrySet())
        {
            Scene scene = entry.getValue();

            scene.worldTick(world);
        }
    }

    /**
     * Tick scenes. Non-playing scenes are evicted every tick; deferred
     * {@link #toPut} additions are applied after the loop. The loop body is
     * wrapped in try/catch so one broken scene can't kill the server tick.
     */
    public void tick()
    {
        this.ticking = true;

        try
        {
            for (Map.Entry<String, Scene> entry : this.scenes.entrySet())
            {
                Scene scene = entry.getValue();

                scene.tick();

                if (!scene.playing)
                {
                    this.toRemove.add(entry.getKey());
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        this.ticking = false;

        for (String scene : this.toRemove)
        {
            this.scenes.remove(scene);
        }

        this.scenes.putAll(this.toPut);

        this.toRemove.clear();
        this.toPut.clear();
    }

    /**
     * Play a scene
     */
    public boolean play(String filename, World world)
    {
        Scene scene = this.get(filename, world);

        if (scene == null)
        {
            return false;
        }

        scene.startPlayback(0);

        return true;
    }

    public void record(String filename, String record, ServerPlayerEntity player)
    {
        this.record(filename, record, 0, player);
    }

    /**
     * Record the player into one of the scene's replays, playing all other
     * replays alongside the recording.
     */
    public void record(String filename, String record, int offset, ServerPlayerEntity player)
    {
        final Scene scene = this.get(filename, player.getWorld());

        if (scene != null)
        {
            scene.setWorld(player.getWorld());

            final Replay replay = scene.getByFile(record);

            if (replay != null)
            {
                CommonProxy.manager.record(replay.id, player, Mode.ACTIONS, replay.teleportBack, true, offset, () ->
                {
                    if (!CommonProxy.manager.recorders.containsKey(player))
                    {
                        this.put(filename, scene);
                        scene.startPlayback(record, offset, player);
                    }
                    else
                    {
                        scene.stopPlayback(true);
                    }

                    replay.apply(player);
                });
            }
        }
    }

    /**
     * Toggle playback of a scene by given filename.
     */
    public boolean toggle(String filename, World world)
    {
        Scene scene = this.scenes.get(filename);

        if (scene != null)
        {
            scene.stopPlayback(true);

            return false;
        }

        return this.play(filename, world);
    }

    /**
     * Get currently running or load a scene.
     */
    public Scene get(String filename, World world)
    {
        Scene scene = this.scenes.get(filename);

        if (scene != null)
        {
            return scene;
        }

        try
        {
            scene = this.load(filename);

            if (scene != null)
            {
                scene.setWorld(world);
                scene.setSender(new SceneSender(scene));
                this.put(filename, scene);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return scene;
    }

    /**
     * Load a scene by given filename (bypasses the cache — reads from disk).
     * Returns null for a missing file; totally tolerant of read failures.
     */
    public Scene load(String filename) throws IOException
    {
        File file = sceneFile(filename);

        if (!file.isFile())
        {
            return null;
        }

        NbtCompound compound = NbtIo.readCompressed(new FileInputStream(file), NbtTagSizeTracker.ofUnlimitedBytes());
        Scene scene = new Scene();

        scene.setId(filename);
        scene.fromNBT(compound);

        return scene;
    }

    public void save(String filename, Scene scene) throws IOException
    {
        this.save(filename, scene, Blockbuster.sceneSaveUpdate.get());
    }

    /**
     * Save a scene by given filename. When {@code reload} (config
     * {@code scenes.save_update}) is on and a live instance exists, that
     * instance receives the edited state ({@code copy}) and re-spawns actors at
     * its current tick.
     */
    public void save(String filename, Scene scene, boolean reload) throws IOException
    {
        Scene present = this.scenes.get(scene.getId());

        if (reload && present != null)
        {
            present.copy(scene);
            present.reload(present.getCurrentTick());
        }

        File file = sceneFile(filename);
        NbtCompound compound = new NbtCompound();

        scene.toNBT(compound);

        /* P284, both halves. See warnOnReplayLoss for why a scene that loses
         * every actor is warned about rather than refused. */
        warnOnReplayLoss(file, filename, compound);
        PastCopies.rotate(file, ".dat");

        AtomicWrite.write(file, stream -> NbtIo.writeCompressed(compound, stream));
    }

    /* ------------------------------------------------------------------ *
     * P284 — scene write safety
     * ------------------------------------------------------------------ */

    /**
     * Log loudly when a scene save is about to drop every actor off a scene that
     * had some (roadmap <b>P284</b>).
     *
     * <p><b>The exposure.</b> A scene is written by exactly one interactive
     * path: {@code GuiScenePanel.close()} fires {@code PacketSceneCast} with the
     * client's whole in-memory {@link Scene} every single time the dashboard
     * panel closes, and {@code ServerHandlerSceneCast} writes it straight to
     * {@code <world>/blockbuster/scenes/<name>.dat}. That is structurally the
     * same client-uploads-authoritative-state shape as the frame upload that
     * emptied three of the reporter's recordings — except that before P284
     * scenes had <b>no backup chain and no atomic write</b>, so the upload was
     * the only copy. {@link Scene#fromNBT} is a total reader: an {@code Actors}
     * tag that is absent or the wrong type yields an empty replay list with no
     * exception, so a scene that half-read on the way to the client would have
     * been written back actor-less and permanently.</p>
     *
     * <p><b>Why a warning and not a refusal.</b> {@link mchorse.blockbuster.recording.data.Record#acceptSave} does
     * refuse, because a zero-frame recording is never something a user asks
     * for — you delete a recording, you do not empty it. Emptying a scene
     * <i>is</i> a real edit: removing the actors one at a time in the director
     * panel and saving is a legitimate thing to do, and refusing it would leave
     * the user unable to save a change the GUI let them make. The protection
     * scenes get instead is the {@code .dat~N} rotation that recordings have had
     * since 2.7.2 — it covers this case <i>and</i> the partial-loss case a
     * zero-actor check cannot see (a scene that comes back with 8 replays
     * instead of 9).</p>
     *
     * <p>Total: any failure to read the previous file is swallowed — this is
     * diagnostics, and it must never be the reason a save does not happen.</p>
     */
    static void warnOnReplayLoss(File file, String filename, NbtCompound saving)
    {
        if (file == null || !file.isFile() || !saving.getList("Actors", NbtElement.COMPOUND_TYPE).isEmpty())
        {
            return;
        }

        try (FileInputStream stream = new FileInputStream(file))
        {
            NbtCompound previous = NbtIo.readCompressed(stream, NbtTagSizeTracker.ofUnlimitedBytes());
            int had = previous.getList("Actors", NbtElement.COMPOUND_TYPE).size();

            if (had > 0)
            {
                Blockbuster.LOGGER.warn(
                    "Saving scene '{}' with no actors over a scene file that had {} — "
                    + "the previous version was rotated to '{}.dat~1'.",
                    filename, had, filename);
            }
        }
        catch (Exception e)
        {
            /* diagnostics only */
        }
    }

    /**
     * The legacy director-block rescue write (S22 P252). Writes an already-built
     * scene compound <b>verbatim</b>, and <b>only</b> when no scene file exists
     * under that name.
     *
     * <p>Deliberately not {@link #save(String, Scene)}: that re-serializes a
     * parsed {@link Scene}, which would run the rescued bytes through the port's
     * reimplemented reader and silently drop anything it does not model. The
     * rescue moves bytes instead — see
     * {@link mchorse.blockbuster.common.tileentity.TileEntityDirector}.</p>
     *
     * <p>Refusing to overwrite is what makes the rescue idempotent: a legacy tile
     * entity is re-read on every chunk load, and its payload is frozen data that
     * can never be newer than the scene file it would replace.</p>
     *
     * @return {@code true} when the payload was written, {@code false} when an
     *         existing scene file was kept.
     */
    public boolean rescue(String filename, NbtCompound payload) throws IOException
    {
        File file = sceneFile(filename);

        if (file.exists())
        {
            return false;
        }

        /* P284: atomic like every other user-content write. The rescue already
         * refuses to overwrite, so there is no rotation to do here. */
        AtomicWrite.write(file, stream -> NbtIo.writeCompressed(payload, stream));

        return true;
    }

    /**
     * Rename a scene on the disk. Refuses (returns false) when the destination
     * already exists — the GUI depends on that being non-destructive.
     */
    public boolean rename(String from, String to)
    {
        File fromFile = sceneFile(from);
        File toFile = sceneFile(to);

        if (fromFile.isFile() && !toFile.exists())
        {
            boolean renamed = fromFile.renameTo(toFile);

            if (renamed)
            {
                /* P284: carry the backup chain across, the way
                 * RecordManager.rename already does for recordings. Leaving it
                 * behind orphans every past version under a name nothing will
                 * ever look for again. */
                for (int i = 1; i <= PastCopies.COPIES; i++)
                {
                    File iteration = new File(fromFile.getParentFile(), from + ".dat~" + i);

                    if (iteration.isFile())
                    {
                        iteration.renameTo(new File(toFile.getParentFile(), to + ".dat~" + i));
                    }
                }
            }

            return renamed;
        }

        return false;
    }

    /**
     * Remove a scene from the disk.
     */
    public boolean remove(String filename)
    {
        File file = sceneFile(filename);

        if (file.exists())
        {
            return file.delete();
        }

        return false;
    }

    /**
     * Returns a file instance to the scene by given filename
     * ({@code <world>/blockbuster/scenes/<filename>.dat}).
     */
    private File sceneFile(String filename)
    {
        return BlockbusterPaths.scenes(CommonProxy.saveRoot(), filename);
    }

    private void put(String filename, Scene scene)
    {
        (this.ticking ? this.toPut : this.scenes).put(filename, scene);
    }

    /**
     * Get all the NBT files in the scenes folder.
     */
    public List<String> sceneFiles()
    {
        return BlockbusterPaths.scenes(CommonProxy.saveRoot());
    }
}
