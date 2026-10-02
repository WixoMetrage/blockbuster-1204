package mchorse.blockbuster.network;

import mchorse.aperture.network.common.PacketCameraProfileList;
import mchorse.blockbuster.aperture.network.common.PacketAudioShift;
import mchorse.blockbuster.aperture.network.common.PacketRequestLength;
import mchorse.blockbuster.aperture.network.common.PacketRequestProfiles;
import mchorse.blockbuster.aperture.network.common.PacketSceneLength;
import mchorse.blockbuster.aperture.network.server.ServerHandlerAudioShift;
import mchorse.blockbuster.aperture.network.server.ServerHandlerRequestLength;
import mchorse.blockbuster.aperture.network.server.ServerHandlerRequestProfiles;
import mchorse.blockbuster.network.common.PacketActorSpawnData;
import mchorse.blockbuster.network.common.PacketCaption;
import mchorse.blockbuster.network.common.PacketDamageControlCheck;
import mchorse.blockbuster.network.common.recording.PacketRecordingTick;
import mchorse.blockbuster.network.common.PacketModifyActor;
import mchorse.blockbuster.network.common.PacketModifyModelBlock;
import mchorse.blockbuster.network.common.PacketOpenGui;
import mchorse.blockbuster.network.common.PacketPlaybackButton;
import mchorse.blockbuster.network.common.PacketReloadModels;
import mchorse.blockbuster.network.common.audio.PacketAudio;
import mchorse.blockbuster.network.common.guns.PacketGunInfo;
import mchorse.blockbuster.network.common.guns.PacketGunInteract;
import mchorse.blockbuster.network.common.guns.PacketGunProjectile;
import mchorse.blockbuster.network.common.guns.PacketGunProjectileSpawnData;
import mchorse.blockbuster.network.common.guns.PacketGunProjectileVanish;
import mchorse.blockbuster.network.common.guns.PacketGunReloading;
import mchorse.blockbuster.network.common.guns.PacketGunShot;
import mchorse.blockbuster.network.common.guns.PacketGunStuck;
import mchorse.blockbuster.network.common.guns.PacketZoomCommand;
import mchorse.blockbuster.network.common.recording.PacketActorPause;
import mchorse.blockbuster.network.common.recording.PacketApplyFrame;
import mchorse.blockbuster.network.common.recording.PacketFramesChunk;
import mchorse.blockbuster.network.common.recording.PacketFramesLoad;
import mchorse.blockbuster.network.common.recording.PacketFramesOverwrite;
import mchorse.blockbuster.network.common.recording.PacketPlayback;
import mchorse.blockbuster.network.common.recording.PacketPlayerRecording;
import mchorse.blockbuster.network.common.recording.PacketRequestFrames;
import mchorse.blockbuster.network.common.recording.PacketRequestRecording;
import mchorse.blockbuster.network.common.recording.PacketRequestedFrames;
import mchorse.blockbuster.network.common.recording.PacketSyncTick;
import mchorse.blockbuster.network.common.recording.PacketUnloadFrames;
import mchorse.blockbuster.network.common.recording.PacketUnloadRecordings;
import mchorse.blockbuster.network.common.recording.PacketUpdatePlayerData;
import mchorse.blockbuster.network.common.recording.actions.PacketActionList;
import mchorse.blockbuster.network.common.recording.actions.PacketActions;
import mchorse.blockbuster.network.common.recording.actions.PacketActionsChange;
import mchorse.blockbuster.network.common.recording.actions.PacketRequestAction;
import mchorse.blockbuster.network.common.recording.actions.PacketRequestActions;
import mchorse.blockbuster.network.common.scene.PacketRequestScenes;
import mchorse.blockbuster.network.common.scene.PacketSceneCast;
import mchorse.blockbuster.network.common.scene.PacketSceneManage;
import mchorse.blockbuster.network.common.scene.PacketScenePause;
import mchorse.blockbuster.network.common.scene.PacketScenePlayback;
import mchorse.blockbuster.network.common.scene.PacketSceneRecord;
import mchorse.blockbuster.network.common.scene.PacketSceneRequestCast;
import mchorse.blockbuster.network.common.scene.PacketScenes;
import mchorse.blockbuster.network.common.scene.sync.PacketSceneGoto;
import mchorse.blockbuster.network.common.scene.sync.PacketScenePlay;
import mchorse.blockbuster.network.common.structure.PacketStructure;
import mchorse.blockbuster.network.common.structure.PacketStructureList;
import mchorse.blockbuster.network.common.structure.PacketStructureListRequest;
import mchorse.blockbuster.network.common.structure.PacketStructureRequest;
import mchorse.blockbuster.network.server.ServerHandlerApplyFrame;
import mchorse.blockbuster.network.server.ServerHandlerDamageControlCheck;
import mchorse.blockbuster.network.server.recording.ServerHandlerRecordingTick;
import mchorse.blockbuster.network.server.ServerHandlerModifyActor;
import mchorse.blockbuster.network.server.ServerHandlerModifyModelBlock;
import mchorse.blockbuster.network.server.ServerHandlerPlaybackButton;
import mchorse.blockbuster.network.server.ServerHandlerReloadModels;
import mchorse.blockbuster.network.server.ServerHandlerStructureListRequest;
import mchorse.blockbuster.network.server.ServerHandlerStructureRequest;
import mchorse.blockbuster.network.server.gun.ServerHandlerGunInfo;
import mchorse.blockbuster.network.server.gun.ServerHandlerGunInteract;
import mchorse.blockbuster.network.server.gun.ServerHandlerGunReloading;
import mchorse.blockbuster.network.server.gun.ServerHandlerZoomCommand;
import mchorse.blockbuster.network.server.recording.ServerHandlerFramesChunk;
import mchorse.blockbuster.network.server.recording.ServerHandlerFramesOverwrite;
import mchorse.blockbuster.network.server.recording.ServerHandlerRequestFrames;
import mchorse.blockbuster.network.server.recording.ServerHandlerRequestRecording;
import mchorse.blockbuster.network.server.recording.ServerHandlerUpdatePlayerData;
import mchorse.blockbuster.network.server.recording.actions.ServerHandlerActionsChange;
import mchorse.blockbuster.network.server.recording.actions.ServerHandlerRequestAction;
import mchorse.blockbuster.network.server.recording.actions.ServerHandlerRequestActions;
import mchorse.blockbuster.network.server.scene.ServerHandlerRequestScenes;
import mchorse.blockbuster.network.server.scene.ServerHandlerSceneCast;
import mchorse.blockbuster.network.server.scene.ServerHandlerSceneManage;
import mchorse.blockbuster.network.server.scene.ServerHandlerScenePause;
import mchorse.blockbuster.network.server.scene.ServerHandlerScenePlayback;
import mchorse.blockbuster.network.server.scene.ServerHandlerSceneRecord;
import mchorse.blockbuster.network.server.scene.ServerHandlerSceneRequestCast;
import mchorse.blockbuster.network.server.scene.sync.ServerHandlerSceneGoto;
import mchorse.blockbuster.network.server.scene.sync.ServerHandlerScenePlay;
import mchorse.mclib.network.AbstractDispatcher;
import mchorse.mclib.network.ChannelLedger;
import mchorse.mclib.network.IMessage;
import mchorse.mclib.network.Side;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * Blockbuster-channel network dispatcher (roadmap S9 networking) over the P23
 * {@link AbstractDispatcher}. One stable {@code Identifier} per packet class;
 * the reserved identifiers live in {@link ChannelLedger} — registration order
 * is therefore NOT a wire contract (the identifier is derived from the class
 * name), so packet families can be registered in any order.
 *
 * <p>This class is populated by every stage that owns Blockbuster packets — the
 * control-plane batch (P116) and the frame (P115) + action (P117) families land
 * here; the scene/gun/structure/camera families (S10/S11/S15/S16) each add their
 * {@code register(...)} lines. CLIENT-side handlers are referenced by name
 * because they live in the split client source set (see
 * {@link AbstractDispatcher}). Registration below is ordered by ledger slot.</p>
 */
public class Dispatcher
{
    private static final String CLIENT = "mchorse.blockbuster.network.client.";
    private static final String RECORDING = "mchorse.blockbuster.network.client.recording.";
    private static final String ACTIONS = "mchorse.blockbuster.network.client.recording.actions.";
    private static final String GUNS = "mchorse.blockbuster.network.client.guns.";
    private static final String SCENE = "mchorse.blockbuster.network.client.scene.";

    public static final AbstractDispatcher DISPATCHER = new AbstractDispatcher(ChannelLedger.BLOCKBUSTER)
    {
        @Override
        public void register()
        {
            /* slots 0-1 — P123 actor GUI close-sync (C→S) + the P119.2
             * client-bound tracker broadcast, whose handler only enqueues (the
             * apply runs on the actor's tick). */
            register(PacketModifyActor.class, CLIENT + "ClientHandlerModifyActor", Side.CLIENT);
            register(PacketModifyActor.class, ServerHandlerModifyActor.class, Side.SERVER);

            /* P119.2 — actor spawn data (port addition, no 1.12 slot): replaces
             * Forge's IEntityAdditionalSpawnData. S2C on onStartedTrackingBy. */
            register(PacketActorSpawnData.class, CLIENT + "ClientHandlerActorSpawnData", Side.CLIENT);

            /* slots 3-4 — P95 model block edit/sync (bidirectional) */
            register(PacketModifyModelBlock.class, CLIENT + "ClientHandlerModifyModelBlock", Side.CLIENT);
            register(PacketModifyModelBlock.class, ServerHandlerModifyModelBlock.class, Side.SERVER);

            /* P100 — screen-open plumbing (Forge GuiHandler replacement); no
             * 1.12 slot (port addition, identifier blockbuster:open_gui). S2C. */
            register(PacketOpenGui.class, CLIENT + "ClientHandlerOpenGui", Side.CLIENT);

            /* slots 5-9 — P116 control plane */
            register(PacketCaption.class, CLIENT + "ClientHandlerCaption", Side.CLIENT);
            register(PacketPlayerRecording.class, RECORDING + "ClientHandlerPlayerRecording", Side.CLIENT);
            register(PacketSyncTick.class, RECORDING + "ClientHandlerSyncTick", Side.CLIENT);

            /* slot 2 — P116 actor pause/resume broadcast */
            register(PacketActorPause.class, RECORDING + "ClientHandlerActorPause", Side.CLIENT);
            register(PacketPlayback.class, RECORDING + "ClientHandlerPlayback", Side.CLIENT);

            /* slot 10 — P115 */
            register(PacketUnloadFrames.class, RECORDING + "ClientHandlerUnloadFrames", Side.CLIENT);

            /* slot 11 — P116 */
            register(PacketUnloadRecordings.class, RECORDING + "ClientHandlerUnloadRecordings", Side.CLIENT);

            /* slots 12-16 — P115 frames */
            register(PacketFramesLoad.class, RECORDING + "ClientHandlerFramesLoad", Side.CLIENT);
            register(PacketFramesChunk.class, ServerHandlerFramesChunk.class, Side.SERVER);
            register(PacketRequestedFrames.class, RECORDING + "ClientHandlerRequestedFrames", Side.CLIENT);
            register(PacketRequestFrames.class, ServerHandlerRequestFrames.class, Side.SERVER);
            register(PacketFramesOverwrite.class, ServerHandlerFramesOverwrite.class, Side.SERVER);

            /* slots 17-18 — P116 */
            register(PacketApplyFrame.class, RECORDING + "ClientHandlerApplyFrame", Side.CLIENT);
            register(PacketApplyFrame.class, ServerHandlerApplyFrame.class, Side.SERVER);

            /* slots 19-24 — P117 actions */
            register(PacketActionsChange.class, ServerHandlerActionsChange.class, Side.SERVER);
            register(PacketActions.class, ACTIONS + "ClientHandlerActions", Side.CLIENT);
            register(PacketRequestAction.class, ServerHandlerRequestAction.class, Side.SERVER);
            register(PacketRequestActions.class, ServerHandlerRequestActions.class, Side.SERVER);

            /* slot 23 — P116 */
            register(PacketRequestRecording.class, ServerHandlerRequestRecording.class, Side.SERVER);

            /* slot 24 — P117 */
            register(PacketActionList.class, ACTIONS + "ClientHandlerActionList", Side.CLIENT);

            /* slot 25+ — P116 */
            register(PacketUpdatePlayerData.class, ServerHandlerUpdatePlayerData.class, Side.SERVER);

            /* slots 25-37 — P131 scene family (identifiers are per-class, so
             * this ordering is cosmetic — see ChannelLedger slots 25-37). */
            register(PacketSceneCast.class, SCENE + "ClientHandlerSceneCast", Side.CLIENT);
            register(PacketSceneCast.class, ServerHandlerSceneCast.class, Side.SERVER);
            register(PacketSceneRequestCast.class, ServerHandlerSceneRequestCast.class, Side.SERVER);
            register(PacketScenes.class, SCENE + "ClientHandlerScenes", Side.CLIENT);
            register(PacketRequestScenes.class, ServerHandlerRequestScenes.class, Side.SERVER);
            /* PacketSceneManage: 1.12 mis-registered the client handler on the
             * SERVER (dead — see the ChannelLedger P131 parity note). The port
             * puts the no-op client handler on the CLIENT so the server's echo
             * is received and dropped cleanly; the real handler is SERVER. */
            register(PacketSceneManage.class, SCENE + "ClientHandlerSceneManage", Side.CLIENT);
            register(PacketSceneManage.class, ServerHandlerSceneManage.class, Side.SERVER);
            register(PacketSceneGoto.class, ServerHandlerSceneGoto.class, Side.SERVER);
            register(PacketScenePlay.class, ServerHandlerScenePlay.class, Side.SERVER);
            register(PacketScenePlayback.class, ServerHandlerScenePlayback.class, Side.SERVER);
            register(PacketSceneRecord.class, ServerHandlerSceneRecord.class, Side.SERVER);
            register(PacketScenePause.class, ServerHandlerScenePause.class, Side.SERVER);

            /* slot 56 — P131.2 damage-control ownership query (miscellaneous
             * section in 1.12 Dispatcher line 208, not the scene block). */
            register(PacketDamageControlCheck.class, ServerHandlerDamageControlCheck.class, Side.SERVER);

            /* slot 38 — P69 model reload (op-gated); identifier blockbuster:reload_models */
            register(PacketReloadModels.class,
                ServerHandlerReloadModels.class, Side.SERVER);

            /* P129 — playback button: SERVER rewrites the item's camera/scene
             * NBT (op-gated), CLIENT opens the playback config GUI (S15 seam). */
            register(PacketPlaybackButton.class,
                ServerHandlerPlaybackButton.class, Side.SERVER);
            register(PacketPlaybackButton.class,
                CLIENT + "ClientHandlerPlaybackButton", Side.CLIENT);

            /* slots 39-48 — P196 gun family (order mirrors 1.12 Dispatcher
             * lines 183-192; identifiers are per-class so order is cosmetic). */
            register(PacketGunInfo.class, ServerHandlerGunInfo.class, Side.SERVER);
            register(PacketZoomCommand.class, ServerHandlerZoomCommand.class, Side.SERVER);
            register(PacketGunInfo.class, GUNS + "ClientHandlerGunInfo", Side.CLIENT);
            register(PacketGunShot.class, GUNS + "ClientHandlerGunShot", Side.CLIENT);
            register(PacketGunProjectile.class, GUNS + "ClientHandlerGunProjectile", Side.CLIENT);
            register(PacketGunInteract.class, ServerHandlerGunInteract.class, Side.SERVER);
            register(PacketGunReloading.class, ServerHandlerGunReloading.class, Side.SERVER);
            register(PacketGunInteract.class, GUNS + "ClientHandlerGunInteract", Side.CLIENT);
            register(PacketGunProjectileVanish.class, GUNS + "ClientHandlerGunProjectileVanish", Side.CLIENT);
            register(PacketGunStuck.class, GUNS + "ClientHandlerGunStuck", Side.CLIENT);

            /* P195 port addition — projectile spawn data (client-bound). */
            register(PacketGunProjectileSpawnData.class, GUNS + "ClientHandlerGunProjectileSpawnData", Side.CLIENT);

            /* slots 49-52 — P162 structure morph sync (identifiers per-class, so
             * this ordering is cosmetic — see ChannelLedger slots 49-52). */
            register(PacketStructure.class, CLIENT + "ClientHandlerStructure", Side.CLIENT);
            register(PacketStructureRequest.class, ServerHandlerStructureRequest.class, Side.SERVER);
            register(PacketStructureList.class, CLIENT + "ClientHandlerStructureList", Side.CLIENT);
            register(PacketStructureListRequest.class, ServerHandlerStructureListRequest.class, Side.SERVER);

            /* slot 55 — S16 P189 scene audio (S→C). Client handler lives in the
             * split client source set. */
            register(PacketAudio.class,
                CLIENT + "audio.ClientHandlerAudio", Side.CLIENT);

            /* slots 57-58 — P185.1 playback-button profile list (C→S request,
             * S→C reply). PacketCameraProfileList is deliberately registered on
             * BOTH channels: Aperture's copy feeds GuiProfilesManager, this one
             * feeds GuiPlayback, exactly as 1.12.2 split them. */
            register(PacketRequestProfiles.class,
                ServerHandlerRequestProfiles.class, Side.SERVER);
            register(PacketCameraProfileList.class,
                "mchorse.blockbuster.aperture.network.client.ClientHandlerCameraProfileList", Side.CLIENT);

            /* slots 59-60 — P185.1 camera-editor scene length + audio shift
             * (C→S request, S→C reply) */
            register(PacketRequestLength.class,
                ServerHandlerRequestLength.class, Side.SERVER);
            register(PacketSceneLength.class,
                "mchorse.blockbuster.aperture.network.client.ClientHandlerSceneLength", Side.CLIENT);

            /* slot 61 — S16 P189 camera-editor audio-shift edit (C→S). 1.12
             * registered this via CameraHandler.registerApertureMessages on the
             * Blockbuster channel; the port bundles Aperture so it is
             * unconditional here. Identifier blockbuster:audio_shift. */
            register(PacketAudioShift.class,
                ServerHandlerAudioShift.class, Side.SERVER);

            /* wixo (CDC §6, R4) — client recording tick (C→S), no 1.12 slot.
             * Identifier blockbuster:recording_tick. */
            register(PacketRecordingTick.class, ServerHandlerRecordingTick.class, Side.SERVER);
        }
    };

    private static boolean registered;

    /**
     * Send message to players who are tracking given entity
     */
    public static void sendToTracked(Entity entity, IMessage message)
    {
        DISPATCHER.sendToTracked(entity, message);
    }

    /**
     * Send message to given player
     */
    public static void sendTo(IMessage message, ServerPlayerEntity player)
    {
        DISPATCHER.sendTo(message, player);
    }

    /**
     * Send message to all players
     */
    public static void sendToAll(IMessage message)
    {
        DISPATCHER.sendToAll(message);
    }

    /**
     * Send message to the server
     */
    public static void sendToServer(IMessage message)
    {
        DISPATCHER.sendToServer(message);
    }

    /**
     * Send message to every player in the given dimension (legacy
     * {@code Dispatcher.sendToDimension}). 1.12 keyed on the int dimension id;
     * the port keys on the {@link ServerWorld} instance directly.
     */
    public static void sendToDimension(IMessage message, ServerWorld world)
    {
        for (ServerPlayerEntity player : PlayerLookup.world(world))
        {
            DISPATCHER.sendTo(message, player);
        }
    }

    /**
     * Register all the networking messages and message handlers (idempotent —
     * mod init and headless tests may both call it).
     */
    public static synchronized void register()
    {
        if (registered)
        {
            return;
        }

        registered = true;

        DISPATCHER.register();
    }
}
