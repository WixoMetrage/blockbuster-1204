package mchorse.blockbuster.recording.capturing;

import mchorse.blockbuster.Blockbuster;
import mchorse.blockbuster.CommonProxy;
import mchorse.blockbuster.recording.RecordManager;
import mchorse.blockbuster.recording.RecordPlayer;
import mchorse.blockbuster.recording.RecordRecorder;
import mchorse.blockbuster.recording.RecordUtils;
import mchorse.blockbuster.recording.actions.Action;
import mchorse.blockbuster.recording.actions.AttackAction;
import mchorse.blockbuster.recording.actions.BreakBlockAction;
import mchorse.blockbuster.recording.actions.ChatAction;
import mchorse.blockbuster.recording.actions.CommandAction;
import mchorse.blockbuster.recording.actions.DropAction;
import mchorse.blockbuster.recording.actions.ExplosionAction;
import mchorse.blockbuster.recording.actions.InteractBlockAction;
import mchorse.blockbuster.recording.actions.InteractEntityAction;
import mchorse.blockbuster.recording.actions.ItemUseAction;
import mchorse.blockbuster.recording.actions.ItemUseBlockAction;
import mchorse.blockbuster.recording.actions.MorphAction;
import mchorse.blockbuster.recording.actions.MorphActionAction;
import mchorse.blockbuster.recording.actions.MountingAction;
import mchorse.blockbuster.recording.actions.PlaceBlockAction;
import mchorse.blockbuster.recording.actions.ShootArrowAction;
import mchorse.blockbuster.recording.scene.Scene;
import mchorse.blockbuster.utils.EntityUtils;
import mchorse.metamorph.api.MetamorphEvents;
import mchorse.metamorph.api.events.MorphActionEvent;
import mchorse.metamorph.api.events.MorphEvent;
import mchorse.metamorph.api.morphs.AbstractMorph;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.explosion.Explosion;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Event handler for recording purposes (legacy {@code capturing/ActionHandler},
 * roadmap P108/P109).
 *
 * <p>This event handler listens to different events and then writes them to
 * the recording event list. The Forge event matrix maps to Fabric API events
 * where one exists and to the mixins in {@code mchorse.blockbuster.mixin}
 * otherwise. All capture is
 * server-side and guarded by {@code CommonProxy.manager.getActions(player)}
 * being non-null, exactly like 1.12.2.</p>
 *
 * <p>Tick seam (S9 open question 5, resolved): the per-player recorder tick
 * runs from the tail of {@code ServerPlayerEntity.playerTick()} via
 * {@code ServerPlayerEntityMixin} — the same seam Forge's server-side
 * {@code PlayerTickEvent} END phase fired from ({@code onUpdateEntity} in
 * 1.12.2, invoked by the network handler's tick, dead or alive).</p>
 *
 * <p><b>S22 P294 — and the client half of that seam.</b> Forge posts
 * {@code PlayerTickEvent} from {@code EntityPlayer.onUpdate()}, so legacy's
 * {@code onPlayerTick} ran <b>on both sides</b>, once per player entity per
 * tick. Only the {@code record.next()} line is side-agnostic there (legacy
 * guards the recorder and {@code stopPlaying()} with {@code server}), and it is
 * the line that makes a player-driven replay smooth: on the client
 * {@code Record.applyFrame} is what applies the recorded rotations every tick
 * and, for a {@code realPlayer} playback, the recorded position plus
 * {@code prevX}/{@code lastRenderX} pair the renderer lerps between (and
 * {@code applyClientMovement}'s sneak input and {@code applyFrameClient}'s
 * camera roll). Without it a replayed player was never driven client-side at
 * all — it moved only when a server correction packet landed, which is exactly
 * "the replay is not fluid". {@link #onClientPlayerTick(PlayerEntity)} restores
 * it from the tail of {@code PlayerEntity.tick()}
 * ({@code PlayerEntityPlaybackTickMixin}), the yarn counterpart of
 * {@code EntityPlayer.onUpdate()}.</p>
 */
public class ActionHandler
{
    /**
     * Registers the Fabric-event half of the capture matrix (the mixin half
     * registers itself through {@code blockbuster.mixins.json}).
     */
    public static void register()
    {
        UseItemCallback.EVENT.register((player, world, hand) ->
        {
            onItemUse(player, hand);

            return TypedActionResult.pass(player.getStackInHand(hand));
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) ->
        {
            onItemUseBlock(player, hand, hitResult);

            return ActionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
        {
            onRightClickEntity(player, hand);

            return ActionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
        {
            onPlayerAttack(player);

            return ActionResult.PASS;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> onPlayerBreaksBlock(player, pos));

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> onServerChatEvent(sender, message.getSignedContent()));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> CommonProxy.manager.abort(handler.getPlayer()));

        /* Metamorph event layer (S4 / P22 / P56) — legacy onPlayerMorph +
         * onPlayerMorphAction. The firing owner (MorphAPI, P48) posts these;
         * registering the capture listeners here is P108's responsibility. */
        MetamorphEvents.MORPH_POST.register(ActionHandler::onPlayerMorph);
        MetamorphEvents.MORPH_ACTION.register(ActionHandler::onPlayerMorphAction);

        ServerWorldEvents.LOAD.register((server, world) ->
        {
            /* Legacy onWorldLoad reloaded server models once dimension 0 (the
             * overworld) loaded. The legacy per-world IWorldEventListener
             * attachment is replaced by the global WorldMixin /
             * ServerWorldMixin → WorldEventListener seams, so only the reload
             * survives here. */
            if (world.getRegistryKey() == World.OVERWORLD)
            {
                Blockbuster.reloadServerModels(true);
            }
        });

        /* P113 damage-control feeds are all mixins since wixo R1 (see
         * WorldEventListener): ENTITY_LOAD also fired on chunk loads. */

        /* Legacy FMLServerStoppingEvent dropped the damage repository alongside
         * the record manager and scenes (Blockbuster.serverStopping). Kept next
         * to the feed that fills it. */
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> CommonProxy.damage.reset());
    }

    /* Fabric-event / mixin entry points — resolve the player's pending
     * action list, then defer to the package-visible capture seams (which
     * headless tests drive directly, since real players can't exist there) */

    /**
     * wixo (CDC §6, R3): the player's pending actions, with the held items
     * recorded first. The tracker only noticed a hotbar switch at the end of
     * the tick, after the use it led to: switching to a sword and hitting in
     * the same tick replayed the hit with the previous item.
     */
    private static List<Action> actionsWithHeldItem(PlayerEntity player)
    {
        RecordRecorder recorder = player.getWorld().isClient() ? null : CommonProxy.manager.recorders.get(player);

        if (recorder != null && recorder.tracker != null)
        {
            recorder.tracker.trackHeldItem(player.getMainHandStack(), player.getOffHandStack(), player.getInventory().selectedSlot);
        }

        return CommonProxy.manager.getActions(player);
    }

    public static void onItemUse(PlayerEntity player, Hand hand)
    {
        List<Action> events = actionsWithHeldItem(player);

        if (!player.getWorld().isClient() && events != null)
        {
            events.add(new ItemUseAction(hand));
        }
    }

    public static void onItemUseBlock(PlayerEntity player, Hand hand, BlockHitResult hit)
    {
        List<Action> events = actionsWithHeldItem(player);

        if (!player.getWorld().isClient() && events != null)
        {
            captureUseBlock(events, hit.getBlockPos(), hand, hit.getSide(), hit.getPos());
        }
    }

    public static void onRightClickEntity(PlayerEntity player, Hand hand)
    {
        List<Action> events = actionsWithHeldItem(player);

        if (!player.getWorld().isClient() && events != null)
        {
            events.add(new InteractEntityAction(hand));
        }
    }

    public static void onPlayerBreaksBlock(PlayerEntity player, BlockPos pos)
    {
        List<Action> events = actionsWithHeldItem(player);

        if (!player.getWorld().isClient() && events != null)
        {
            events.add(new BreakBlockAction(pos, !player.isCreative()));
        }
    }

    public static void onPlayerAttack(PlayerEntity player)
    {
        List<Action> events = actionsWithHeldItem(player);

        if (!player.getWorld().isClient() && events != null)
        {
            captureAttack(events);
        }
    }

    /**
     * Called by {@code BlockItemMixin} after a successful
     * {@code BlockItem.place} (Fabric has no place event).
     */
    public static void onBlockPlaced(ItemPlacementContext context)
    {
        PlayerEntity player = context.getPlayer();

        if (player == null || context.getWorld().isClient())
        {
            return;
        }

        List<Action> events = CommonProxy.manager.getActions(player);

        if (events != null)
        {
            BlockPos pos = context.getBlockPos();
            BlockState state = context.getWorld().getBlockState(pos);

            capturePlaceBlock(events, pos, state);
        }
    }

    /**
     * Called by {@code BucketItemMixin} once a bucket has really put its fluid
     * at {@code pos}: recorded as the resulting block state (a water source,
     * or a waterlogged block), wixo R3.
     */
    public static void onPlayerPlacedFluid(PlayerEntity player, World world, BlockPos pos)
    {
        List<Action> events = CommonProxy.manager.getActions(player);

        if (events != null)
        {
            capturePlaceBlock(events, pos.toImmutable(), world.getBlockState(pos));
        }
    }

    /**
     * Called by {@code EntityMixin} on start/stop riding (players only) —
     * Fabric has no mount event.
     */
    public static void onPlayerMountsSomething(PlayerEntity player, Entity mounted, boolean isMounting)
    {
        List<Action> events = CommonProxy.manager.getActions(player);

        if (!player.getWorld().isClient() && events != null)
        {
            events.add(new MountingAction(mounted.getUuid(), isMounting));
        }
    }

    /**
     * Called by {@code BowItemMixin} — legacy ArrowLooseEvent, charge is
     * {@code getMaxUseTime - remainingUseTicks}.
     */
    public static void onArrowLoose(PlayerEntity player, int charge)
    {
        List<Action> events = actionsWithHeldItem(player);

        if (!player.getWorld().isClient() && events != null)
        {
            events.add(new ShootArrowAction(charge));
        }
    }

    /**
     * Called by {@code ServerPlayerEntityMixin} on item toss (legacy
     * ItemTossEvent).
     */
    public static void onItemToss(ServerPlayerEntity player, ItemStack stack)
    {
        List<Action> events = CommonProxy.manager.getActions(player);

        if (events != null)
        {
            events.add(new DropAction(stack));
        }
    }

    public static void onServerChatEvent(ServerPlayerEntity player, String message)
    {
        List<Action> events = CommonProxy.manager.getActions(player);

        if (events != null)
        {
            events.add(new ChatAction(message));
        }
    }

    /**
     * Called by {@code CommandManagerMixin} for every executed command
     * (legacy CommandEvent). The command string arrives without the leading
     * slash, exactly like legacy's name+args reconstruction.
     */
    public static void onPlayerCommand(ServerCommandSource source, String command)
    {
        ServerPlayerEntity player = source.getPlayer();

        if (player == null)
        {
            return;
        }

        List<Action> events = CommonProxy.manager.getActions(player);

        if (events != null)
        {
            captureCommand(events, command);
        }
    }

    /**
     * Event listener for MORPH (legacy {@code onPlayerMorph}). Submits a
     * {@link MorphAction} carrying the post-morph morph. Fired through the
     * bundled Metamorph event bus ({@link MetamorphEvents#MORPH_POST}).
     *
     * <p>The morph is carried as a real {@link AbstractMorph} (P167). A null
     * (demorph) yields a null-morph action, exactly like legacy.</p>
     */
    public static void onPlayerMorph(MorphEvent.Post event)
    {
        PlayerEntity player = event.player;
        List<Action> events = CommonProxy.manager.getActions(player);

        if (!player.getWorld().isClient() && events != null)
        {
            captureMorph(events, event.morph);
        }
    }

    /**
     * Event listener for MORPH_ACTION (legacy {@code onPlayerMorphAction}).
     * Submits a {@link MorphActionAction} only when the action is valid (a
     * null action means nothing happened). Fired through the bundled
     * Metamorph event bus ({@link MetamorphEvents#MORPH_ACTION}).
     */
    public static void onPlayerMorphAction(MorphActionEvent event)
    {
        PlayerEntity player = event.player;
        List<Action> events = CommonProxy.manager.getActions(player);

        if (!player.getWorld().isClient() && events != null)
        {
            captureMorphAction(events, event.isValid());
        }
    }

    /* Capture seams (payload logic, headless-testable) */

    /**
     * Legacy RightClickBlock had two listeners — both fire per click, in
     * declaration order: {@code use_item_block} then {@code interact_block}.
     * Do not deduplicate.
     */
    static void captureUseBlock(List<Action> events, BlockPos pos, Hand hand, Direction facing, Vec3d hit)
    {
        if (hit == null)
        {
            events.add(new ItemUseBlockAction(pos, hand, facing));
        }
        else
        {
            events.add(new ItemUseBlockAction(pos, hand, facing, (float) hit.x - pos.getX(), (float) hit.y - pos.getY(), (float) hit.z - pos.getZ()));
        }

        events.add(new InteractBlockAction(pos));
    }

    /**
     * Legacy gate: AttackEntityEvent only records when the PlayerTracker
     * swing path is off. wixo R3: that is now the default
     * ({@code record_swipe_attacks} false), so only real hits are attacks.
     */
    static void captureAttack(List<Action> events)
    {
        if (!Blockbuster.recordSwipeAttacks.get())
        {
            events.add(new AttackAction());
        }
    }

    /**
     * S22 P296 — the real block-placement capture seam: it stores the whole
     * placed {@link BlockState}, not just its block.
     *
     * <p>{@code (Block, Meta)} stays exactly what it was — the flattened
     * 1.20.4 id with {@code meta = 0}. P71 is forward-only, deliberately: the
     * shim has no reverse table and needs none, because that pair is a 1.12
     * <i>input</i> format and {@code LegacyIdMap.blockState()} passes a modern
     * id straight back through ({@code blockRenames.getOrDefault(path, path)} →
     * registry lookup) at apply time. A reverse map would have to invent a
     * pre-flattening {@code (name, meta)} pair that does not exist for
     * post-1.12 blocks, only for the same shim to flatten it again on the way
     * out.</p>
     *
     * <p>What that pair can <b>never</b> carry is the state's properties, and
     * 1.12.2's 4-bit metadata did carry them ({@code getMetaFromState}). Hence
     * the additional {@code State} string — see {@link PlaceBlockAction} for
     * the serialization decision. Round-trip pinned by
     * {@code PlaceBlockCaptureRoundTripTest}.</p>
     */
    static void capturePlaceBlock(List<Action> events, BlockPos pos, BlockState state)
    {
        capturePlaceBlock(events, pos, (byte) 0, Registries.BLOCK.getId(state.getBlock()).toString(), PlaceBlockAction.stringify(state));
    }

    /** Stateless capture (the legacy {@code (block, meta)} pair only). */
    static void capturePlaceBlock(List<Action> events, BlockPos pos, byte metadata, String block)
    {
        capturePlaceBlock(events, pos, metadata, block, "");
    }

    static void capturePlaceBlock(List<Action> events, BlockPos pos, byte metadata, String block, String state)
    {
        events.add(new PlaceBlockAction(pos, metadata, block, state));
    }

    /**
     * wixo (CDC §6, R3): an explosion is about to destroy its blocks
     * ({@code ExplosionMixin}, HEAD of {@code Explosion.affectWorld}).
     *
     * <ul>
     * <li>While a scene plays in this world, an explosion not set off by a
     * recording player is a replayed one: it destroys nothing, the
     * {@link ExplosionAction} recorded in the take removes the same blocks at
     * the same tick. Its blast on entities, sound and particles stay.</li>
     * <li>While someone records, the blocks it destroys are recorded as an
     * {@link ExplosionAction}: on the recording player who set it off, or else
     * on the first recorder in this world.</li>
     * </ul>
     */
    public static void onExplosion(World world, Explosion explosion)
    {
        if (world.isClient())
        {
            return;
        }

        LivingEntity cause = explosion.getCausingEntity();
        RecordManager manager = CommonProxy.manager;
        boolean byRecorder = cause instanceof PlayerEntity player && manager.recorders.containsKey(player);

        if (!byRecorder && isScenePlaying(world))
        {
            explosion.clearAffectedBlocks();

            return;
        }

        RecordRecorder target = byRecorder ? manager.recorders.get((PlayerEntity) cause) : null;

        if (target == null)
        {
            for (Map.Entry<PlayerEntity, RecordRecorder> entry : manager.recorders.entrySet())
            {
                if (entry.getKey().getWorld() == world)
                {
                    target = entry.getValue();

                    break;
                }
            }
        }

        if (target == null || target.tracker == null)
        {
            return;
        }

        List<BlockPos> blocks = new ArrayList<>();

        for (BlockPos pos : explosion.getAffectedBlocks())
        {
            if (!world.getBlockState(pos).isAir())
            {
                blocks.add(pos.toImmutable());
            }
        }

        /* Set off by the recording player: the actor sets it off again on
         * playback, and that real explosion plays the sound and particles. */
        target.actions.add(new ExplosionAction(explosion.getPosition(), blocks, !byRecorder));
    }

    private static boolean isScenePlaying(World world)
    {
        for (Scene scene : CommonProxy.scenes.getScenes().values())
        {
            if (scene.playing && scene.getWorld() == world)
            {
                return true;
            }
        }

        return false;
    }

    static void captureCommand(List<Action> events, String command)
    {
        if (!Blockbuster.recordCommands.get())
        {
            return;
        }

        events.add(new CommandAction("/" + command));
    }

    /**
     * Legacy {@code onPlayerMorph}: submit a {@link MorphAction} carrying the
     * post-morph {@link AbstractMorph} (P167 — the carrier now holds a real
     * morph, not raw NBT). A null (demorph) yields a null-morph action, exactly
     * like legacy.
     */
    static void captureMorph(List<Action> events, AbstractMorph morph)
    {
        events.add(new MorphAction(morph));
    }

    /**
     * Legacy {@code onPlayerMorphAction}: only records when the action is
     * valid (a null action means nothing happened).
     */
    static void captureMorphAction(List<Action> events, boolean valid)
    {
        if (valid)
        {
            events.add(new MorphActionAction());
        }
    }

    /* Tick drivers */

    /**
     * Driven from {@code ServerTickEvents.END_SERVER_TICK} — Forge's
     * ServerTickEvent END equivalent. Per-player recording moved to the
     * {@code playerTick} tail mixin (P108); this drives the manager + scenes
     * (legacy {@code onServerTick}: manager.tick() then scenes.tick(), END
     * phase).
     */
    public static void onServerTick(MinecraftServer server)
    {
        CommonProxy.manager.tick();
        CommonProxy.scenes.tick();
    }

    /**
     * Driven from {@code ServerTickEvents.START_WORLD_TICK} — Forge's
     * {@code WorldTickEvent} Phase.START (server worlds). Legacy
     * {@code onWorldServerTick}: spawns scene actors + runs unsafe (world
     * modifying) actions before the world ticks, so clients don't see block
     * changes one tick late.
     */
    public static void onWorldTick(ServerWorld world)
    {
        CommonProxy.scenes.worldTick(world);
    }

    /**
     * This is going to record the player actions. Called from the tail of
     * {@code ServerPlayerEntity.playerTick()} (see class javadoc).
     */
    public static void onPlayerTick(ServerPlayerEntity player)
    {
        /* Advance a player-attached playback (real-player playback) */
        RecordPlayer record = EntityUtils.getRecordPlayer(player);

        if (record != null)
        {
            record.next();

            if (record.isFinished())
            {
                record.stopPlaying();
            }
        }
    }

    /**
     * wixo (CDC §6, R4): the recording player's client finished a tick
     * ({@code PacketRecordingTick}): close that tick's action list.
     *
     * <p>This used to run on every <b>server</b> tick while the frames were
     * counted on the <b>client</b>: whenever the two drifted (the server lagging
     * then catching up, or skipping ticks) every later action landed on the
     * wrong frame. The client's packet arrives after the clicks of the same
     * tick on the same ordered connection, so the lists now follow the frames
     * one for one.</p>
     */
    public static void onRecordingTick(ServerPlayerEntity player)
    {
        RecordManager manager = CommonProxy.manager;

        if (manager.recorders.containsKey(player))
        {
            RecordRecorder recorder = manager.recorders.get(player);

            /* P277 — the same isDead mistranslation as Scene.areActorsFinished,
             * and here the TIMING is the behaviour. Legacy read `player.isDead`,
             * the 1.12.2 field, which for a player goes true in
             * EntityLivingBase.onDeathUpdate at `deathTime == 20` — one second
             * AFTER health hits zero. Those 20 ticks are recorded: legacy takes
             * capture the death itself.
             *
             * Verified with javap that 1.20.4 kept that timing exactly —
             * LivingEntity.updatePostDeath does `if (deathTime >= 20 &&
             * !isRemoved()) remove(RemovalReason.KILLED)` and ServerPlayerEntity
             * does NOT override it — so isRemoved() is a faithful port, tick for
             * tick. Yarn's isDead() (health <= 0) halts a second early and drops
             * the death from every take; a player who dies on the first recorded
             * tick yields a zero-frame record (found independently by batch
             * X-G while chasing a RecordMorph crash). */
            if (player.isRemoved())
            {
                manager.halt(player, true, true);
                RecordUtils.broadcastInfo("recording.dead", recorder.record.filename);
            }
            else
            {
                recorder.record(player);
            }
        }
    }

    /**
     * S22 P294 — the client half of legacy's {@code PlayerTickEvent} END body,
     * called from the tail of {@code PlayerEntity.tick()} (see class javadoc).
     *
     * <p>Legacy's body, with the {@code server} branches taken out, is exactly
     * one statement:</p>
     *
     * <pre>
     *   RecordPlayer record = Recording.get(player).getRecordPlayer();
     *
     *   if (record != null)
     *   {
     *       record.next();
     *       if (record.isFinished() &amp;&amp; server) record.stopPlaying();
     *   }
     * </pre>
     *
     * <p>The {@code && server} is load-bearing and is why this is not just a
     * side-agnostic call to {@link #onPlayerTick}: {@code stopPlaying()} routes
     * through {@code CommonProxy.manager.stop(...)}, the <b>server's</b> record
     * manager, and the client tears its own playback down when
     * {@code PacketPlayback(state = false)} arrives instead. The recorder half is
     * server-only for the same reason. Total by construction — an entity with no
     * attached playback is a no-op, which is every player in the ordinary
     * case.</p>
     *
     * @param player any client-side {@code PlayerEntity} — the local player or a
     *               remote one; legacy fired for both.
     */
    public static void onClientPlayerTick(PlayerEntity player)
    {
        if (player == null || !player.getWorld().isClient)
        {
            return;
        }

        RecordPlayer record = EntityUtils.getRecordPlayer(player);

        if (record != null)
        {
            record.next();
        }
    }
}
