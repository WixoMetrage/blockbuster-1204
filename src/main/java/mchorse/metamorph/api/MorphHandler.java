package mchorse.metamorph.api;

import java.util.ArrayList;
import java.util.List;

import mchorse.metamorph.Metamorph;
import mchorse.metamorph.MetamorphCommon;
import mchorse.metamorph.api.events.SpawnGhostEvent;
import mchorse.metamorph.api.morphs.AbstractMorph;
import mchorse.metamorph.capabilities.morphing.IMorphing;
import mchorse.metamorph.capabilities.morphing.Morphing;
import mchorse.metamorph.entity.EntityMorph;
import mchorse.metamorph.network.Dispatcher;
import mchorse.metamorph.network.common.PacketBlacklist;
import mchorse.metamorph.network.common.PacketSettings;
import mchorse.metamorph.network.common.creative.PacketMorph;
import mchorse.metamorph.network.common.survival.PacketAcquiredMorphs;
import mchorse.metamorph.network.common.survival.PacketMorphPlayer;
import mchorse.metamorph.network.common.survival.PacketMorphState;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;

/**
 * Server-side morphing event handler (roadmap P52.1) — behavioral port of
 * Metamorph 1.4's {@code MorphHandler} + the sync/clone half of
 * {@code CapabilityHandler}, mapped onto Fabric events (the per-player
 * capability attachment itself rides {@code PlayerEntityMorphingMixin}).
 *
 * <p>Responsibilities: (1) drive the per-player morph update loop, (2) grant
 * the morph's melee attack ability, (3) acquire morphs from killed entities,
 * (4) hostile-morph disguise targeting, (5) dimension-change morph sync, and
 * (6) clone/login state carry-over.</p>
 *
 * <p><b>Event-wiring table (legacy Forge event → Fabric hook/mixin):</b></p>
 * <table>
 * <tr><td>{@code PlayerTickEvent} Phase.END</td>
 *     <td>{@link ServerTickEvents#END_SERVER_TICK} + player-list iteration;
 *         the client half runs from {@code PlayerHandler.endTickClient}
 *         (S22 P247)</td></tr>
 * <tr><td>{@code LivingAttackEvent} (attack ability)</td>
 *     <td>{@link AttackEntityCallback}</td></tr>
 * <tr><td>{@code LivingDeathEvent} (kill→acquire)</td>
 *     <td>{@link ServerLivingEntityEvents#AFTER_DEATH} (ghost/strip body is a
 *         P53/P56 seam)</td></tr>
 * <tr><td>{@code LivingAttackEvent} (own-morph self-damage cancel)</td>
 *     <td>SEAM(P53): {@code LivingEntity.damage} mixin, needs
 *         {@code EntityMorph.isUpdatingEntity()}</td></tr>
 * <tr><td>{@code LivingSetAttackTargetEvent} (hostile disguise)</td>
 *     <td>{@code MobEntityMorphTargetMixin} via {@link #filterAttackTarget}</td></tr>
 * <tr><td>{@code PlayerChangedDimensionEvent}</td>
 *     <td>{@link ServerEntityWorldChangeEvents#AFTER_PLAYER_CHANGE_WORLD}</td></tr>
 * <tr><td>{@code PlayerEvent.Clone}</td>
 *     <td>{@link ServerPlayerEvents#COPY_FROM} (Mohist probe dropped —
 *         behavioral no-op on Fabric)</td></tr>
 * <tr><td>{@code EntityJoinWorldEvent} (respawn / dimension spawn resync)</td>
 *     <td>{@link ServerEntityEvents#ENTITY_LOAD} → {@link #onPlayerSpawn}</td></tr>
 * <tr><td>{@code PlayerLoggedInEvent} / {@code StartTracking} (state resend)</td>
 *     <td>{@link ServerPlayConnectionEvents#JOIN} re-applies abilities + full
 *         login payload; {@link EntityTrackingEvents#START_TRACKING} for peers</td></tr>
 * </table>
 *
 * Legacy sources:
 * .tools/legacy-src/metamorph/.../api/MorphHandler.java,
 * .tools/legacy-src/metamorph/.../capabilities/CapabilityHandler.java
 */
public class MorphHandler
{
    /* Next-tick tasks (used for the "knockback" attack). Public static and
     * one-drained-per-tick, names kept for diff-ability against legacy. */
    public static List<Runnable> FUTURE_TASKS_CLIENT = new ArrayList<Runnable>();
    public static List<Runnable> FUTURE_TASKS_SERVER = new ArrayList<Runnable>();

    /* On-track morph resends queued by START_TRACKING, flushed at
     * END_SERVER_TICK — see the registration comment in register(). */
    private static final List<PendingMorphSync> PENDING_MORPH_SYNCS = new ArrayList<PendingMorphSync>();

    /**
     * Register every server-side morphing hook. Called from the mod
     * initializer. The client-side hooks land in the client initializer: the
     * squid-air HUD mirror with P225 ({@code MetamorphHudWiring.install}) and
     * the client tick loop with P247 ({@code BlockbusterClient.registerPlayerTick}
     * → {@code PlayerHandler.morphTick} → {@link #onPlayerTick}).
     */
    public static void register()
    {
        /* P55: bundled Metamorph network channel (metamorph:*). Server-side
         * receivers wire during register(); client-side receivers wire in
         * BlockbusterClient.registerNetworking. Idempotent. */
        Dispatcher.register();

        /* Per-player morph update loop (legacy PlayerTickEvent Phase.END). */
        ServerTickEvents.END_SERVER_TICK.register(server ->
        {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList())
            {
                onPlayerTick(player);
            }
        });

        /* Grant the morph's melee attack ability (legacy onPlayerAttack;
         * AttackEntityCallback is inherently a direct melee hit, so the legacy
         * EntityDamageSourceIndirect skip is satisfied by construction). */
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
        {
            if (!world.isClient)
            {
                onPlayerAttack(player, entity);
            }

            return ActionResult.PASS;
        });

        /* Kill-to-acquire (legacy onPlayerKillEntity). */
        ServerLivingEntityEvents.AFTER_DEATH.register(MorphHandler::onPlayerKillEntity);

        /* Dimension change (legacy onPlayerChangeDimension). */
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
            onPlayerChangeDimension(player));

        /* Clone on respawn / return-from-end (legacy CapabilityHandler.onPlayerClone).
         * Copies the capability into the new player instance — does NOT sync the
         * client. Packet resync is onPlayerSpawn below. */
        ServerPlayerEvents.COPY_FROM.register(MorphHandler::onPlayerClone);

        /* Respawn / dimension-travel / world-join resync (legacy
         * CapabilityHandler.onPlayerSpawn via EntityJoinWorldEvent). Without this,
         * keep_morphs copies server-side on COPY_FROM but the new ClientPlayerEntity
         * stays empty — the player looks demorphed and the acquired list is gone
         * until a full relog. Login double-sends with playerLogsIn; that matches
         * 1.12.2 (both PlayerLoggedInEvent and EntityJoinWorldEvent fired). */
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) ->
        {
            if (entity instanceof ServerPlayerEntity)
            {
                onPlayerSpawn((ServerPlayerEntity) entity);
            }
        });

        /* Login: re-apply abilities + morph-state resend (P55). */
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> playerLogsIn(handler.getPlayer()));

        /* Start-tracking sync (legacy CapabilityHandler.playerStartsTracking):
         * when a client starts tracking another player, push that player's
         * current morph so it renders morphed on the tracker's screen. Replaces
         * the Forge PlayerEvent.StartTracking — with one load-bearing
         * difference. Forge fired StartTracking AFTER the tracker had sent the
         * entity's spawn packets; Fabric fires START_TRACKING at the HEAD of
         * EntityTrackerEntry.startTracking, BEFORE they are written. Sending
         * inline puts PacketMorphPlayer ahead of the player's spawn packet on
         * the wire, and ClientHandlerMorphPlayer (a total reader) drops packets
         * for entities that don't exist client-side yet — a scene fake player
         * logged in unmorphed (P241), and a morphed player walking into view
         * range rendered unmorphed. The resend is therefore queued and flushed
         * at END_SERVER_TICK, when the spawn packets are already ahead of it in
         * the connection's write order; the morph is read at flush time so
         * same-tick changes win. */
        EntityTrackingEvents.START_TRACKING.register((trackedEntity, player) ->
        {
            if (trackedEntity instanceof PlayerEntity)
            {
                PENDING_MORPH_SYNCS.add(new PendingMorphSync((PlayerEntity) trackedEntity, player));
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> flushMorphSyncs());
    }

    /** Deliver the on-track morph resends queued this tick. */
    private static void flushMorphSyncs()
    {
        if (PENDING_MORPH_SYNCS.isEmpty())
        {
            return;
        }

        for (PendingMorphSync sync : PENDING_MORPH_SYNCS)
        {
            if (sync.viewer.isDisconnected() || sync.tracked.isRemoved())
            {
                continue;
            }

            IMorphing cap = Morphing.get(sync.tracked);

            if (cap != null)
            {
                Dispatcher.sendTo(new PacketMorphPlayer(sync.tracked.getId(), cap.getCurrentMorph()), sync.viewer);
            }
        }

        PENDING_MORPH_SYNCS.clear();
    }

    /** One queued on-track resend: {@code viewer} began tracking {@code tracked}. */
    private static class PendingMorphSync
    {
        final PlayerEntity tracked;
        final ServerPlayerEntity viewer;

        PendingMorphSync(PlayerEntity tracked, ServerPlayerEntity viewer)
        {
            this.tracked = tracked;
            this.viewer = viewer;
        }
    }

    /**
     * When a player is morphed, its morphing abilities are executed here
     * (gliding, allergies, climbing, swimming…). Also records the finite health
     * fraction every tick (another mod could change max health at any time) and
     * force-demorphs (server side) if a broken morph throws — legacy crash
     * containment.
     */
    public static void onPlayerTick(PlayerEntity player)
    {
        IMorphing capability = Morphing.get(player);

        runFutureTasks(player);

        /* Store the current health ratio when it makes sense (heal-exploit
         * guard — see IMorphing.REASONABLE_HEALTH_VALUE). */
        if (capability != null)
        {
            float maxHealth = player.getMaxHealth();

            if (maxHealth > IMorphing.REASONABLE_HEALTH_VALUE)
            {
                capability.setLastHealthRatio(player.getHealth() / maxHealth);
            }
        }

        if (capability == null || !capability.isMorphed())
        {
            /* SEAM(P54): restore the default eye height while unmorphed (unless
             * disable_pov). On 1.20.4 eye height is pose-driven; the P54 size
             * mixins own the reset (legacy set player.eyeHeight directly). */
        }

        /* The client half of legacy's `if (player.world.isRemote)` squid-air
         * mirror landed in S22 P225 as
         * `MetamorphHudWiring.mirrorSquidAir(mc.player)`, driven from
         * ClientTickEvents.END_CLIENT_TICK (client source set — this class
         * cannot name GuiHud). S22 P247 completed the pair: this method is now
         * also driven client-side, for the local player, from
         * `PlayerHandler.endTickClient` (seam `PlayerHandler.morphTick`), so
         * `capability.update(player)` below runs on BOTH sides exactly as
         * legacy's PlayerTickEvent did — the squidAir countdown is simulated
         * client-side again rather than packet-driven, and
         * `IMorphing.getAnimation()` advances for the transition fade. */

        try
        {
            if (capability != null)
            {
                capability.update(player);
            }
        }
        catch (Exception e)
        {
            Metamorph.LOGGER.error("Morph update threw; force-demorphing to contain the crash", e);

            if (!player.getWorld().isClient && capability != null)
            {
                /* Legacy crash containment: MorphAPI.demorph also syncs the
                 * client (PacketMorph/PacketMorphPlayer/PacketMorphState). */
                MorphAPI.demorph(player);
            }
        }
    }

    /**
     * When a player is morphed, apply the morph's attack ability onto the melee
     * target (extra damage / potion effect / explosion, etc).
     */
    public static void onPlayerAttack(PlayerEntity player, Entity target)
    {
        IMorphing capability = Morphing.get(player);

        if (capability == null || !capability.isMorphed())
        {
            return;
        }

        AbstractMorph morph = capability.getCurrentMorph();

        if (morph != null)
        {
            morph.attack(target, player);
        }
    }

    /**
     * When a player kills an entity, they gain a morph based on that entity
     * (either directly with {@code acquire_immediately} or via a ghost the
     * player walks into).
     *
     * <p>Fired for the <b>dying</b> entity ({@link ServerLivingEntityEvents});
     * the killer is {@code damageSource.getAttacker()}.</p>
     *
     * <p>The three config gates, in legacy's order: {@code prevent_kill_acquire}
     * skips the whole thing; {@code acquire_immediately} hands the morph over
     * with no ghost at all (but <b>only</b> if the player does not already have
     * it — a re-kill of an owned morph falls through); {@code prevent_ghosts}
     * suppresses the ghost for morphs the player already owns. Note that
     * "already acquired" is an {@code AbstractMorph.equals} test (P47), so two
     * creepers with different NBT are two different morphs.</p>
     */
    public static void onPlayerKillEntity(LivingEntity entity, DamageSource damageSource)
    {
        Entity source = damageSource.getAttacker();
        Entity target = entity;

        /* AFTER_DEATH is server-only; the isRemote guard is implicit. */
        if (isFakePlayer(source))
        {
            return;
        }

        if (!(source instanceof PlayerEntity) || target instanceof PlayerEntity || Metamorph.preventKillAcquire.get())
        {
            return;
        }

        PlayerEntity player = (PlayerEntity) source;
        IMorphing capability = Morphing.get(player);

        if (capability == null)
        {
            return;
        }

        String name = MorphManager.INSTANCE.morphNameFromEntity(target);

        if (!MorphManager.INSTANCE.hasMorph(name))
        {
            Metamorph.log("Morph by key '" + name + "' doesn't exist!");

            return;
        }

        NbtCompound serialized = new NbtCompound();

        target.writeNbt(serialized);

        NbtCompound tag = new NbtCompound();

        tag.putString("Name", name);
        tag.put("EntityData", EntityUtils.stripEntityNBT(serialized));

        AbstractMorph morph = MorphManager.INSTANCE.morphFromNBT(tag);
        boolean acquired = capability.acquiredMorph(morph);

        KillReward reward = killReward(Metamorph.acquireImmediately.get(), Metamorph.preventGhosts.get(), acquired);

        if (reward == KillReward.GRANT)
        {
            MorphAPI.acquire(player, morph);

            return;
        }

        if (reward == KillReward.GHOST)
        {
            SpawnGhostEvent.Pre spawnGhostEvent = new SpawnGhostEvent.Pre(player, morph);

            if (MetamorphEvents.SPAWN_GHOST_PRE.invoker().onSpawnGhost(spawnGhostEvent) || spawnGhostEvent.morph == null)
            {
                return;
            }

            morph = spawnGhostEvent.morph;

            EntityMorph ghost =
                new EntityMorph(MetamorphCommon.MORPH, player.getWorld());

            ghost.initialize(player.getUuid(), morph);

            /* Legacy spawns it at the victim's mid-height, so the ghost floats
             * where the body was rather than at its feet. */
            ghost.refreshPositionAndAngles(target.getX(), target.getY() + target.getHeight() / 2F, target.getZ(),
                target.getYaw(), target.getPitch());

            player.getWorld().spawnEntity(ghost);

            MetamorphEvents.SPAWN_GHOST_POST.invoker().accept(new SpawnGhostEvent.Post(player, morph));
        }
    }

    /** What a kill is worth, once the config gates have had their say. */
    public enum KillReward
    {
        /** {@code acquire_immediately}: the morph goes straight into the list. */
        GRANT,
        /** The default: a {@code metamorph:morph} ghost spawns on the corpse. */
        GHOST,
        /** {@code prevent_ghosts} + a morph the player already owns. */
        NOTHING
    }

    /**
     * The kill-reward gate, pure. Legacy wrote it as two sequential {@code if}s
     * (P52.1/P56.1) and the interaction between them is easy to get subtly
     * wrong, so it is pinned here:
     *
     * <ul>
     *   <li>{@code acquire_immediately} only fires for a morph the player does
     *   <b>not</b> already own — re-killing something you have morphs for falls
     *   through to the ghost branch rather than short-circuiting.</li>
     *   <li>{@code prevent_ghosts} (on by default) is what stops the world
     *   filling with ghosts of mobs you already collected; with it off, every
     *   kill leaves a ghost.</li>
     *   <li>Both flags set + already acquired is the only path to
     *   {@link KillReward#NOTHING}.</li>
     * </ul>
     */
    public static KillReward killReward(boolean acquireImmediately, boolean preventGhosts, boolean acquired)
    {
        if (acquireImmediately && !acquired)
        {
            return KillReward.GRANT;
        }

        if (!preventGhosts || !acquired)
        {
            return KillReward.GHOST;
        }

        return KillReward.NOTHING;
    }

    /**
     * Make sure the player dimension and morph dimension stay synced (legacy
     * onPlayerChangeDimension → morph.onChangeDimension, where EntityMorph
     * reassigns its inner entity's world in P53).
     */
    public static void onPlayerChangeDimension(PlayerEntity player)
    {
        IMorphing capability = Morphing.get(player);

        if (capability != null && capability.getCurrentMorph() != null)
        {
            /* Legacy passed the from/to dimension ints; on 1.20.4 they are
             * registry keys and the base hook ignores them (SEAM P53:
             * EntityMorph.onChangeDimension reads the player's world directly). */
            capability.getCurrentMorph().onChangeDimension(player, 0, 0);
        }
    }

    /**
     * Copy morph data from the dead/returning player to the new player instance
     * (legacy PlayerEvent.Clone). Copies when {@code keep_morphs} is set or the
     * clone was not caused by death ({@code alive} — End return). The legacy
     * Mohist-server skip is dropped — dead code on Fabric (behavioral no-op).
     *
     * <p>This only mutates the server capability. Client resync is
     * {@link #onPlayerSpawn}, which runs when the new player is loaded into the
     * world (after this copy).</p>
     */
    public static void onPlayerClone(ServerPlayerEntity oldPlayer, ServerPlayerEntity newPlayer, boolean alive)
    {
        IMorphing morphing = Morphing.get(newPlayer);
        IMorphing oldMorphing = Morphing.get(oldPlayer);

        if (morphing == null || oldMorphing == null)
        {
            return;
        }

        if (Metamorph.keepMorphs.get() || alive)
        {
            morphing.copy(oldMorphing, newPlayer);
        }
    }

    /**
     * Push morph state to the owning client when a server player is loaded into
     * a world (legacy {@code CapabilityHandler.onPlayerSpawn} /
     * {@code EntityJoinWorldEvent}).
     *
     * <p>Fires on login, death-respawn, and dimension travel. On death-respawn
     * this is what makes {@link Metamorph#keepMorphs} visible: {@link #onPlayerClone}
     * has already copied the capability, and here the packets catch the new
     * {@code ClientPlayerEntity} up. Without this hook the server is morphed and
     * the client is not.</p>
     *
     * <p>Packet set matches 1.12.2 exactly: current morph + acquired list +
     * morph state. Ability re-apply stays on {@link #playerLogsIn} (login only)
     * and on {@link IMorphing#copy} during clone.</p>
     */
    public static void onPlayerSpawn(ServerPlayerEntity player)
    {
        IMorphing morphing = Morphing.get(player);

        if (morphing == null)
        {
            return;
        }

        sendAcquiredMorphs(morphing, player);
        Dispatcher.sendTo(new PacketMorphState(player, morphing), player);
    }

    /**
     * On login, re-apply the morph's abilities (legacy re-morphed on the
     * server before syncing) and resend the full morph state to the owner
     * (legacy {@code CapabilityHandler.playerLogsIn}).
     *
     * <p>Send order is preserved from legacy: acquired morphs first
     * ({@code sendAcquiredMorphs} = current morph then acquired list), then
     * blacklist, settings and morph state.</p>
     */
    public static void playerLogsIn(ServerPlayerEntity player)
    {
        IMorphing capability = Morphing.get(player);

        if (capability == null)
        {
            return;
        }

        sendAcquiredMorphs(capability, player);

        /* Ensure the player is (re)morphed so its abilities are active. */
        if (capability.isMorphed())
        {
            capability.getCurrentMorph().morph(player);
        }

        Dispatcher.sendTo(new PacketBlacklist(MorphManager.INSTANCE.activeBlacklist), player);
        Dispatcher.sendTo(new PacketSettings(MorphManager.INSTANCE.activeSettings), player);
        Dispatcher.sendTo(new PacketMorphState(player, capability), player);
    }

    /**
     * Send the player's current morph and acquired-morph list to the owning
     * client (legacy {@code CapabilityHandler.sendAcquiredMorphs}).
     */
    private static void sendAcquiredMorphs(IMorphing cap, ServerPlayerEntity player)
    {
        Dispatcher.sendTo(new PacketMorph(cap.getCurrentMorph()), player);
        Dispatcher.sendTo(new PacketAcquiredMorphs(cap.getAcquiredMorphs()), player);
    }

    /**
     * Hostile-morph disguise: cancel a mob acquiring a player as its attack
     * target when the player is disguised as a hostile morph and has not
     * provoked the mob. Called from {@code MobEntityMorphTargetMixin}'s
     * {@code setTarget} interception; returns the (possibly nulled) target.
     *
     * <p>SEAM(P53): the legacy handler also (a) excludes the case where the
     * player's morph <i>is</i> this mob's own {@code EntityMorph} inner entity,
     * and (b) retargets mobs that locked onto a player's {@code EntityMorph}
     * inner entity back onto the player. Both need {@code EntityMorph} (P53);
     * until then only the general hostile-disguise null-target case is
     * applied.</p>
     */
    public static LivingEntity filterAttackTarget(MobEntity source, LivingEntity target)
    {
        if (Metamorph.disableMorphDisguise.get())
        {
            return target;
        }

        if (target instanceof PlayerEntity player)
        {
            IMorphing morphing = Morphing.get(player);

            if (morphing == null || !morphing.isMorphed())
            {
                return target;
            }

            AbstractMorph currentMorph = morphing.getCurrentMorph();

            if (currentMorph != null && currentMorph.getSettings().hostile && source.getAttacker() != target)
            {
                return null;
            }
        }

        return target;
    }

    /**
     * Skip morph acquisition for non-real (fake) players.
     *
     * <p>Decision (stage open question 5): legacy skipped Forge {@code
     * FakePlayer} instances outright. Fabric has no shared {@code FakePlayer}
     * type; the faithful 1.12 default is "only real players acquire morphs". A
     * source that is not a {@link ServerPlayerEntity} (mobs, projectiles) is
     * skipped here; refining this to also exclude Carpet-style fakes (which
     * subclass {@code ServerPlayerEntity}) is deferred — noted because the
     * kill-acquire body itself is a P53/P56 seam, so no behavior depends on it
     * yet. */
    private static boolean isFakePlayer(Entity source)
    {
        /* Non-players are handled by the instanceof check in the caller; this
         * predicate exists as the documented seam for the Carpet-fake refinement. */
        return false;
    }

    /**
     * Run future tasks on the current side (one per tick — the knockback
     * attack's feel depends on the one-tick delay).
     */
    private static void runFutureTasks(PlayerEntity player)
    {
        drainFutureTasks(player.getWorld().isClient);
    }

    /**
     * Drain exactly one queued future task (FIFO) for the given side. Package
     * seam for headless queue tests.
     */
    public static void drainFutureTasks(boolean client)
    {
        if (client && !FUTURE_TASKS_CLIENT.isEmpty())
        {
            FUTURE_TASKS_CLIENT.remove(0).run();
        }

        if (!client && !FUTURE_TASKS_SERVER.isEmpty())
        {
            FUTURE_TASKS_SERVER.remove(0).run();
        }
    }
}
