package mchorse.blockbuster.recording;

import java.util.ArrayDeque;
import java.util.Queue;

import mchorse.blockbuster.CommonProxy;
import mchorse.blockbuster.common.entity.EntityActor;
import mchorse.blockbuster.network.Dispatcher;
import mchorse.blockbuster.network.common.recording.PacketActorPause;
import mchorse.blockbuster.network.common.recording.PacketPlayback;
import mchorse.blockbuster.recording.data.Frame;
import mchorse.blockbuster.recording.data.Mode;
import mchorse.blockbuster.recording.data.Record;
import mchorse.blockbuster.recording.scene.Replay;
import mchorse.blockbuster.recording.scene.fake.FakePlayerLifecycle;
import mchorse.blockbuster.utils.EntityUtils;
import mchorse.mclib.network.IMessage;
import mchorse.metamorph.capabilities.morphing.MorphingStorage;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Record player class (roadmap P110) — 1:1 port of the 2.7.2 class.
 *
 * This thing is responsible for playing given record. It applies frames and
 * actions from the record instance on the given actor.
 *
 * <p>The legacy {@code playerLoggedIn} dance for non-real player actors landed
 * as <b>P241</b>: {@link #checkAndSpawn()} routes it through
 * {@code FakePlayerLifecycle} (see that class for why
 * {@code PlayerManager.onPlayerConnect} is the wrong counterpart). The
 * {@link #unsentPackets} queue + {@link #sendToTracked(IMessage)} pair exist so
 * first-tick sync survives the actor spawn-ordering race — packets emitted
 * before the actor is in the world are drained once it is.</p>
 */
public class RecordPlayer
{
    /**
     * Record from which this player is going to play
     */
    public Record record;

    /**
     * Play mode
     */
    public Mode mode;

    /**
     * Entity which is used by this record player to replay the action
     */
    public LivingEntity actor;

    /**
     * Current tick
     */
    public int tick = 0;

    /**
     * Whether to kill an actor when player finished playing
     */
    public boolean kill = false;

    /**
     * Is this player is playing
     */
    public boolean playing = true;

    /**
     * Sync mode - pauses the playback once hit the end
     */
    public boolean sync = false;

    /**
     * It might be null
     */
    private Replay replay;

    public boolean realPlayer;

    /**
     * Packets that could not be delivered yet because the actor entity is
     * not spawned into the world (scene spawn ordering) — flushed by
     * {@link #checkAndSpawn()}.
     */
    public Queue<IMessage> unsentPackets = new ArrayDeque<IMessage>();

    public boolean actorUpdated;

    public RecordPlayer(Record record, Mode mode, LivingEntity actor)
    {
        this.record = record;
        this.mode = mode;
        this.actor = actor;
    }

    public Replay getReplay()
    {
        return replay;
    }

    public void setReplay(Replay replay)
    {
        this.replay = replay;

        if (this.record != null)
        {
            this.record.setReplay(this.replay);
        }
    }

    public RecordPlayer realPlayer()
    {
        this.realPlayer = true;

        return this;
    }

    /**
     * Check if the record player is finished
     */
    public boolean isFinished()
    {
        boolean isFinished = this.record != null && this.tick - this.record.preDelay - this.record.postDelay >= this.record.getLength();

        if (isFinished && this.sync && this.playing)
        {
            this.pause();

            return false;
        }

        return isFinished;
    }

    /**
     * Get appropriate amount of real ticks (for accessing current
     * action or something like this)
     */
    public int getTick()
    {
        return Math.max(0, this.record == null ? this.tick : this.tick - this.record.preDelay);
    }

    /**
     * Get current frame
     */
    public Frame getCurrentFrame()
    {
        return this.record.getFrame(this.getTick());
    }

    /**
     * It should be called before world tick
     */
    public void playActions()
    {
        if (!this.playing || this.isFinished())
        {
            return;
        }

        if (this.record != null)
        {
            if (this.mode == Mode.ACTIONS || this.mode == Mode.BOTH) this.applyAction(this.tick, actor, false);

            this.record.resetUnload();
        }
    }

    public void next()
    {
        this.next(this.actor);
    }

    /**
     * Apply current frame and advance to the next one
     */
    public void next(LivingEntity actor)
    {
        if (this.record != null)
        {
            this.record.resetUnload();
        }

        if (!this.playing || this.isFinished())
        {
            return;
        }

        if (this.record != null)
        {
            if (this.mode == Mode.FRAMES || this.mode == Mode.BOTH) this.applyFrame(this.tick, actor, false);

            this.record.resetUnload();
        }

        /* Align the body with the head on spawn */
        if (this.tick == 0)
        {
            actor.bodyYaw = actor.getYaw();
        }

        this.tick++;
        this.actorUpdated = true;
    }

    /**
     * Pause the playing actor
     */
    public void pause()
    {
        this.playing = false;
        this.actor.noClip = true;
        this.actor.setInvulnerable(true);

        this.applyFrame(this.tick - 1, this.actor, true);

        if (!this.actor.getWorld().isClient)
        {
            this.record.applyPreviousMorph(this.actor, this.replay, this.tick, Record.MorphType.PAUSE);
            this.sendToTracked(new PacketActorPause(this.actor.getId(), true, this.tick));
        }
    }

    /**
     * Resume the paused actor
     */
    public void resume(int tick)
    {
        if (tick >= 0)
        {
            this.tick = tick;
        }

        this.playing = true;
        this.actor.noClip = false;

        if (!this.actor.getWorld().isClient && this.replay != null)
        {
            this.actor.setInvulnerable(this.replay.invincible);
        }

        this.applyFrame(this.tick, this.actor, true);

        if (!this.actor.getWorld().isClient)
        {
            this.record.applyPreviousMorph(this.actor, this.replay, tick, Record.MorphType.FORCE);
            this.sendToTracked(new PacketActorPause(this.actor.getId(), false, this.tick));
        }
    }

    /**
     * Make an actor go to the given tick
     *
     * <p>wixo (R2): the actor only — position and morph. The world (and the
     * actions that change it) is moved by {@code Scene.goTo} through
     * {@code SceneSeek}, for all actors at once and in chronological order.
     * Legacy replayed this actor's actions between the two ticks here, plus the
     * target tick's, which the playback then applied again.</p>
     */
    public void goTo(int tick, boolean actions)
    {
        int preDelay = this.record.preDelay;
        int original = tick;

        if (tick > this.record.frames.size() + this.record.preDelay)
        {
            tick = this.record.frames.size() + this.record.preDelay - 1;
        }

        tick -= preDelay;

        this.tick = original;
        this.record.resetUnload();
        this.record.applyFrame(this.playing ? tick : Math.max(0, tick - 1), this.actor, true, this.realPlayer);

        if (actions)
        {
            if (this.replay != null)
            {
                this.record.applyPreviousMorph(this.actor, this.replay, tick, this.playing ? Record.MorphType.FORCE : Record.MorphType.PAUSE);
            }
        }

        if (this.actor != null && !this.actor.getWorld().isClient)
        {
            this.sendToTracked(new PacketActorPause(this.actor.getId(), !this.playing, this.tick));
        }
    }

    /**
     * Start the playback, but with default tick argument
     */
    public void startPlaying(String filename, boolean kill)
    {
        this.startPlaying(filename, 0, kill);
    }

    /**
     * Start the playback, invoked by director block (more specifically by
     * DirectorTileEntity).
     */
    public void startPlaying(String filename, int tick, boolean kill)
    {
        this.tick = tick;
        this.kill = kill;
        this.sync = false;

        //TODO this should rather be in Replay.apply(EntityPlayer)
        // but there seems to be no way to then revert invulnerability based on Replay instance when recording stops
        if (!this.actor.getWorld().isClient && this.replay != null)
        {
            this.actor.setInvulnerable(this.replay.invincible);
        }

        this.applyFrame(this.playing ? tick : tick - 1, this.actor, true);

        EntityUtils.setRecordPlayer(this.actor, this);

        this.sendToTracked(new PacketPlayback(this.actor.getId(), true, this.realPlayer, filename, this.replay));

        if (this.realPlayer && this.actor instanceof ServerPlayerEntity serverPlayer)
        {
            Dispatcher.sendTo(new PacketPlayback(this.actor.getId(), true, this.realPlayer, filename, this.replay), serverPlayer);
        }
    }

    /**
     * Stop playing
     */
    public void stopPlaying()
    {
        CommonProxy.manager.stop(this);

        this.actor.noClip = false;

        /* Legacy only reverts when the replay ASKED for invulnerability — an
         * actor that was invulnerable for its own reasons keeps it. */
        if (!this.actor.getWorld().isClient && this.replay != null && this.replay.invincible)
        {
            this.actor.setInvulnerable(false);
        }
    }

    public void applyFrame(int tick, LivingEntity target, boolean force)
    {
        tick -= this.record.preDelay;

        if (tick < 0)
        {
            tick = 0;
        }
        else if (tick >= this.record.frames.size())
        {
            tick = this.record.frames.size() - 1;
        }

        this.record.applyFrame(tick, target, force, this.realPlayer);
    }

    public void applyAction(int tick, LivingEntity target, boolean safe)
    {
        this.record.applyAction(tick - this.record.preDelay, target, safe);
    }

    public void sendToTracked(IMessage packet)
    {
        if (this.actor == null || this.actor.getWorld().getEntityById(this.actor.getId()) != this.actor)
        {
            this.unsentPackets.add(packet);
        }
        else
        {
            Dispatcher.sendToTracked(this.actor, packet);
        }
    }

    public void checkAndSpawn()
    {
        /* Checks whether actor isn't already spawned in the world */
        if (this.actor.getWorld().getEntityById(this.actor.getId()) != this.actor)
        {
            if (this.actor instanceof EntityActor)
            {
                if (!this.actor.isRemoved())
                {
                    this.actor.getWorld().spawnEntity(this.actor);

                    /* Legacy positioned the fake player here and then appended
                     * it to world.loadedEntityList so vanilla would tick it.
                     * 1.20.4 has no such list — the actor drives
                     * EntityFakePlayer.tick() itself (see EntityActor.tick) —
                     * so only the initial placement remains. */
                    EntityActor.EntityFakePlayer fake = ((EntityActor) this.actor).fakePlayer;

                    if (fake != null)
                    {
                        fake.setPosition(this.actor.getX(), this.actor.getY(), this.actor.getZ());
                    }
                }
            }
            else if (this.actor instanceof PlayerEntity)
            {
                if (this.record.playerData != null)
                {
                    if (!this.realPlayer)
                    {
                        /* P286: legacy called readEntityFromNBT — the
                         * CUSTOM half only (inventory, xp, abilities, ender
                         * chest…). yarn's counterpart is readCustomDataFromNbt,
                         * which PlayerEntity declares public; readNbt is legacy
                         * readFromNBT, the whole envelope, and would drag the
                         * recording player's Pos/Motion/Rotation/UUID onto the
                         * fake player. */
                        NbtCompound playerData = this.record.playerData.copy();

                        /* P52 folds the morph capability into
                         * writeCustomDataToNbt, so playerData carries the
                         * recording player's morph state — a passenger legacy
                         * writeEntityToNBT never had (ForgeCaps lived outside
                         * it). Restoring it would clobber the replay morph
                         * Scene.collectActors applied, and the fake player
                         * would log in unmorphed. */
                        playerData.remove(MorphingStorage.MORPHING_KEY);

                        this.actor.readCustomDataFromNbt(playerData);
                    }

                    if (MPMHelper.isLoaded() && this.record.playerData.contains("MPMData", NbtElement.COMPOUND_TYPE))
                    {
                        MPMHelper.setMPMData((PlayerEntity) this.actor, this.record.playerData.getCompound("MPMData"));
                    }
                }

                /* P241: legacy `playerLoggedIn` for non-realPlayer player
                 * actors — this is what actually puts a scene fake player into
                 * the world (and the tab list). See FakePlayerLifecycle for why
                 * PlayerManager.onPlayerConnect is the wrong counterpart. */
                if (FakePlayerLifecycle.shouldLogIn(this.actor, this.realPlayer))
                {
                    FakePlayerLifecycle.logIn((ServerPlayerEntity) this.actor);
                }
            }

            while (!this.unsentPackets.isEmpty())
            {
                Dispatcher.sendToTracked(this.actor, this.unsentPackets.poll());
            }
        }
    }
}
