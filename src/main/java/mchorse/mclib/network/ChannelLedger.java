package mchorse.mclib.network;

import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * P23.1 — the authoritative channel-ID ledger: every legacy discriminator
 * slot of the 1.12.2 Blockbuster channel (Dispatcher.register(), lines
 * 122–211 + CameraHandler.registerApertureMessages()) and the mclib channel
 * (mclib/Dispatcher.register()) mapped to a stable Fabric {@link Identifier}.
 *
 * <p>1.12's discriminator byte was the implicit registration counter
 * ({@code nextPacketID++}) — registration order was a wire contract, and
 * {@code CameraHandler.registerMessages()} being conditional on Aperture
 * shifted every id after slot 56 between installs. The rewrite bundles
 * Aperture, so the five camera packets get <b>unconditional</b> reserved
 * identifiers (slots marked {@code 57*–61*}: numbering as when Aperture was
 * installed). Registration is always total — never make it conditional
 * again.</p>
 *
 * <p>Both-side registrations consumed two legacy ids; on Fabric they collapse
 * to one Identifier registered with a receiver per side — the ledger keeps
 * both slots so later porters don't "find" a missing id. Names are
 * human-readable snake case ({@code blockbuster:modify_actor}) derived
 * mechanically from the class name by
 * {@link AbstractDispatcher#channelName(Class)} — enforced by the golden test
 * {@code ChannelLedgerTest} against
 * {@code fixtures/goldens/network/channel_ledger.golden.json}. The markdown
 * mirror lives at {@code plan/network-ledger.md}.</p>
 *
 * <p>The identifiers exist as constants NOW; the packet classes themselves
 * land in their owning phases (see each entry's phase tag).</p>
 *
 * <p><b>Batch U-N</b> extended the ledger from two channels to all
 * <b>four</b> the mod runs: the bundled Aperture (P182) and Metamorph (P55)
 * dispatchers were live but unledgered, so their wire ids were pinned only by
 * {@code AbstractDispatcher.channelName(packetClass)} — a mechanical
 * derivation that <i>follows</i> a packet-class rename instead of catching it.
 * Their registration order is recorded here slot by slot for the same reason
 * the blockbuster channel's is: order is what makes a discriminator byte
 * mean anything.</p>
 */
public final class ChannelLedger
{
    public static final String BLOCKBUSTER = "blockbuster";
    public static final String MCLIB = "mclib";
    public static final String APERTURE = "aperture";
    public static final String METAMORPH = "metamorph";

    /* Reserved non-packet channels (no 1.12 slot) */

    /** P25 chunked-transport carrier, one per channel. */
    public static final Identifier BLOCKBUSTER_CHUNK = bb("chunk");
    public static final Identifier MCLIB_CHUNK = ml("chunk");
    public static final Identifier APERTURE_CHUNK = ap("chunk");
    public static final Identifier METAMORPH_CHUNK = mm("chunk");

    /** P27 join handshake — 1.12.2 had none (presence rode Forge's mod-list handshake). */
    public static final Identifier HANDSHAKE = bb("handshake");

    /* Blockbuster channel (legacy slots 0..61) */

    public static final Identifier MODIFY_ACTOR = bb("modify_actor");
    public static final Identifier ACTOR_PAUSE = bb("actor_pause");
    /** P119.2 port addition — no 1.12 slot (replaces IEntityAdditionalSpawnData). */
    public static final Identifier ACTOR_SPAWN_DATA = bb("actor_spawn_data");
    public static final Identifier MODIFY_MODEL_BLOCK = bb("modify_model_block");
    public static final Identifier CAPTION = bb("caption");
    public static final Identifier PLAYER_RECORDING = bb("player_recording");
    public static final Identifier SYNC_TICK = bb("sync_tick");
    public static final Identifier PLAYBACK = bb("playback");
    public static final Identifier UNLOAD_FRAMES = bb("unload_frames");
    public static final Identifier UNLOAD_RECORDINGS = bb("unload_recordings");
    public static final Identifier FRAMES_LOAD = bb("frames_load");
    public static final Identifier FRAMES_CHUNK = bb("frames_chunk");
    public static final Identifier REQUESTED_FRAMES = bb("requested_frames");
    public static final Identifier REQUEST_FRAMES = bb("request_frames");
    public static final Identifier FRAMES_OVERWRITE = bb("frames_overwrite");
    public static final Identifier APPLY_FRAME = bb("apply_frame");
    public static final Identifier ACTIONS_CHANGE = bb("actions_change");
    public static final Identifier ACTIONS = bb("actions");
    public static final Identifier REQUEST_ACTION = bb("request_action");
    public static final Identifier REQUEST_ACTIONS = bb("request_actions");
    public static final Identifier REQUEST_RECORDING = bb("request_recording");
    public static final Identifier ACTION_LIST = bb("action_list");
    public static final Identifier SCENE_CAST = bb("scene_cast");
    public static final Identifier SCENE_REQUEST_CAST = bb("scene_request_cast");
    public static final Identifier SCENES = bb("scenes");
    public static final Identifier REQUEST_SCENES = bb("request_scenes");
    public static final Identifier SCENE_MANAGE = bb("scene_manage");
    public static final Identifier UPDATE_PLAYER_DATA = bb("update_player_data");
    public static final Identifier SCENE_GOTO = bb("scene_goto");
    public static final Identifier SCENE_PLAY = bb("scene_play");
    public static final Identifier SCENE_PLAYBACK = bb("scene_playback");
    public static final Identifier SCENE_RECORD = bb("scene_record");
    public static final Identifier SCENE_PAUSE = bb("scene_pause");
    public static final Identifier RELOAD_MODELS = bb("reload_models");
    public static final Identifier GUN_INFO = bb("gun_info");
    public static final Identifier ZOOM_COMMAND = bb("zoom_command");
    public static final Identifier GUN_SHOT = bb("gun_shot");
    public static final Identifier GUN_PROJECTILE = bb("gun_projectile");
    public static final Identifier GUN_INTERACT = bb("gun_interact");
    public static final Identifier GUN_RELOADING = bb("gun_reloading");
    public static final Identifier GUN_PROJECTILE_VANISH = bb("gun_projectile_vanish");
    public static final Identifier GUN_STUCK = bb("gun_stuck");
    /** P195 port addition — no 1.12 slot (replaces IEntityAdditionalSpawnData). */
    public static final Identifier GUN_PROJECTILE_SPAWN_DATA = bb("gun_projectile_spawn_data");
    public static final Identifier STRUCTURE = bb("structure");
    public static final Identifier STRUCTURE_REQUEST = bb("structure_request");
    public static final Identifier STRUCTURE_LIST = bb("structure_list");
    public static final Identifier STRUCTURE_LIST_REQUEST = bb("structure_list_request");
    public static final Identifier PLAYBACK_BUTTON = bb("playback_button");
    public static final Identifier AUDIO = bb("audio");
    public static final Identifier DAMAGE_CONTROL_CHECK = bb("damage_control_check");
    /** P100 port addition — no 1.12 slot (replaces Forge's IGuiHandler / openGui). */
    public static final Identifier OPEN_GUI = bb("open_gui");

    /* Aperture-conditional in 1.12 (CameraHandler.registerApertureMessages) — unconditional now */

    public static final Identifier REQUEST_PROFILES = bb("request_profiles");
    public static final Identifier CAMERA_PROFILE_LIST = bb("camera_profile_list");
    public static final Identifier REQUEST_LENGTH = bb("request_length");
    public static final Identifier SCENE_LENGTH = bb("scene_length");
    public static final Identifier AUDIO_SHIFT = bb("audio_shift");

    /* wixo additions (no 1.12 slot) */

    /** CDC §6 R4: the client closes each recorded tick. */
    public static final Identifier RECORDING_TICK = bb("recording_tick");

    /* mclib channel (legacy slots 0..8) */

    public static final Identifier MCLIB_DROP_ITEM = ml("drop_item");
    public static final Identifier MCLIB_REQUEST_CONFIGS = ml("request_configs");
    public static final Identifier MCLIB_CONFIG = ml("config");
    public static final Identifier MCLIB_CONFIRM = ml("confirm");
    public static final Identifier MCLIB_ANSWER = ml("answer");
    public static final Identifier MCLIB_BOOLEAN = ml("boolean");
    public static final Identifier MCLIB_REQUEST_PERMISSION = ml("request_permission");

    /* aperture channel (Aperture 1.8.2 Dispatcher.register(), slots 0..11).
     * Its OWN dispatcher — unrelated to the five blockbuster:*
     * Aperture-conditional identifiers above, which are the P182 bridge
     * packets on the blockbuster channel. */

    public static final Identifier APERTURE_APERTURE = ap("aperture");
    public static final Identifier APERTURE_CAMERA_PROFILE = ap("camera_profile");
    public static final Identifier APERTURE_CAMERA_RESET = ap("camera_reset");
    public static final Identifier APERTURE_CAMERA_STATE = ap("camera_state");
    public static final Identifier APERTURE_LOAD_CAMERA_PROFILE = ap("load_camera_profile");
    public static final Identifier APERTURE_REQUEST_CAMERA_PROFILES = ap("request_camera_profiles");
    public static final Identifier APERTURE_CAMERA_PROFILE_LIST = ap("camera_profile_list");
    public static final Identifier APERTURE_RENAME_CAMERA_PROFILE = ap("rename_camera_profile");
    public static final Identifier APERTURE_REMOVE_CAMERA_PROFILE = ap("remove_camera_profile");

    /* metamorph channel (Metamorph 1.4 Dispatcher.register(), slots 0..18) */

    public static final Identifier METAMORPH_ACTION = mm("action");
    public static final Identifier METAMORPH_MORPH = mm("morph");
    public static final Identifier METAMORPH_MORPH_PLAYER = mm("morph_player");
    public static final Identifier METAMORPH_ACQUIRE_MORPH = mm("acquire_morph");
    public static final Identifier METAMORPH_ACQUIRED_MORPHS = mm("acquired_morphs");
    public static final Identifier METAMORPH_SYNC_MORPH = mm("sync_morph");
    public static final Identifier METAMORPH_SELECT_MORPH = mm("select_morph");
    public static final Identifier METAMORPH_CLEAR_ACQUIRED = mm("clear_acquired");
    public static final Identifier METAMORPH_MORPH_STATE = mm("morph_state");
    public static final Identifier METAMORPH_FAVORITE = mm("favorite");
    public static final Identifier METAMORPH_KEYBIND = mm("keybind");
    public static final Identifier METAMORPH_REMOVE_MORPH = mm("remove_morph");
    public static final Identifier METAMORPH_BLACKLIST = mm("blacklist");
    public static final Identifier METAMORPH_SETTINGS = mm("settings");
    /** P56.1 port addition — no 1.12 slot (replaces IEntityAdditionalSpawnData). */
    public static final Identifier METAMORPH_MORPH_SPAWN_DATA = mm("morph_spawn_data");

    /** One legacy discriminator slot: id + receiving side ("C"/"S"). */
    public record Slot(int id, String side)
    {
        @Override
        public String toString()
        {
            return this.id + " " + this.side;
        }
    }

    /**
     * One ledger row: a packet class collapsed onto one Identifier, with all
     * legacy slots it consumed, the owning port phase, and quirk notes.
     */
    public record Entry(String packet, List<Slot> slots, Identifier channel, String phase, String note)
    {
    }

    private static final String APERTURE_NOTE =
        "Conditional in 1.12 (registered only when Aperture was installed, shifting these ids between installs); "
        + "the rewrite bundles Aperture, so this identifier is reserved unconditionally (registered in P182). "
        + "Slot number is as-with-Aperture.";

    public static final List<Entry> BLOCKBUSTER_CHANNEL;
    public static final List<Entry> MCLIB_CHANNEL;
    public static final List<Entry> APERTURE_CHANNEL;
    public static final List<Entry> METAMORPH_CHANNEL;

    static
    {
        List<Entry> blockbuster = new ArrayList<>();

        blockbuster.add(entry("PacketModifyActor", MODIFY_ACTOR, "S10", "", s(0, "C"), s(1, "S")));
        blockbuster.add(entry("PacketActorPause", ACTOR_PAUSE, "S10", "", s(2, "C")));
        blockbuster.add(entry("PacketActorSpawnData", ACTOR_SPAWN_DATA, "P119.2",
            "Port addition (no 1.12 slot): replaces Forge's IEntityAdditionalSpawnData — yarn EntitySpawnS2CPacket carries no NBT. "
            + "Server sends it to a player that starts tracking an actor; byte layout mirrors legacy EntityActor.writeSpawnData. Client-bound."));
        blockbuster.add(entry("PacketModifyModelBlock", MODIFY_MODEL_BLOCK, "P95", "", s(3, "C"), s(4, "S")));
        blockbuster.add(entry("PacketCaption", CAPTION, "P116", "", s(5, "C")));
        blockbuster.add(entry("PacketPlayerRecording", PLAYER_RECORDING, "P116", "", s(6, "C")));
        blockbuster.add(entry("PacketSyncTick", SYNC_TICK, "P116", "", s(7, "C")));
        blockbuster.add(entry("PacketPlayback", PLAYBACK, "P116", "", s(8, "C"), s(9, "S")));
        blockbuster.add(entry("PacketUnloadFrames", UNLOAD_FRAMES, "P115", "", s(10, "C")));
        blockbuster.add(entry("PacketUnloadRecordings", UNLOAD_RECORDINGS, "P116", "", s(11, "C")));
        blockbuster.add(entry("PacketFramesLoad", FRAMES_LOAD, "P115", "", s(12, "C")));
        blockbuster.add(entry("PacketFramesChunk", FRAMES_CHUNK, "P115", "", s(13, "S")));
        blockbuster.add(entry("PacketRequestedFrames", REQUESTED_FRAMES, "P115", "", s(14, "C")));
        blockbuster.add(entry("PacketRequestFrames", REQUEST_FRAMES, "P115", "", s(15, "S")));
        blockbuster.add(entry("PacketFramesOverwrite", FRAMES_OVERWRITE, "P115", "", s(16, "S")));
        blockbuster.add(entry("PacketApplyFrame", APPLY_FRAME, "P116", "", s(17, "C"), s(18, "S")));
        blockbuster.add(entry("PacketActionsChange", ACTIONS_CHANGE, "P117", "", s(19, "S")));
        blockbuster.add(entry("PacketActions", ACTIONS, "P117", "", s(20, "C")));
        blockbuster.add(entry("PacketRequestAction", REQUEST_ACTION, "P117", "", s(21, "S")));
        blockbuster.add(entry("PacketRequestActions", REQUEST_ACTIONS, "P117", "", s(22, "S")));
        blockbuster.add(entry("PacketRequestRecording", REQUEST_RECORDING, "P116", "", s(23, "S")));
        blockbuster.add(entry("PacketActionList", ACTION_LIST, "P117", "", s(24, "C")));
        blockbuster.add(entry("PacketSceneCast", SCENE_CAST, "P131", "", s(25, "C"), s(26, "S")));
        blockbuster.add(entry("PacketSceneRequestCast", SCENE_REQUEST_CAST, "P131", "", s(27, "S")));
        blockbuster.add(entry("PacketScenes", SCENES, "P131", "", s(28, "C")));
        blockbuster.add(entry("PacketRequestScenes", REQUEST_SCENES, "P131", "", s(29, "S")));
        blockbuster.add(entry("PacketSceneManage", SCENE_MANAGE, "P131",
            "MIS-SIDE FOOTNOTE: 1.12 slot 30 registered ClientHandlerSceneManage with Side.SERVER (Dispatcher.java line 167) — "
            + "a client handler on the server side, which consumed an id and made server->client PacketSceneManage unroutable. "
            + "The port registers the client handler on the CLIENT; the bug is recorded here, not reproduced (P131 parity note).",
            s(30, "S (mis-sided client handler)"), s(31, "S")));
        blockbuster.add(entry("PacketUpdatePlayerData", UPDATE_PLAYER_DATA, "P116", "", s(32, "S")));
        blockbuster.add(entry("PacketSceneGoto", SCENE_GOTO, "P131", "", s(33, "S")));
        blockbuster.add(entry("PacketScenePlay", SCENE_PLAY, "P131", "", s(34, "S")));
        blockbuster.add(entry("PacketScenePlayback", SCENE_PLAYBACK, "P131", "", s(35, "S")));
        blockbuster.add(entry("PacketSceneRecord", SCENE_RECORD, "P131", "", s(36, "S")));
        blockbuster.add(entry("PacketScenePause", SCENE_PAUSE, "P131", "", s(37, "S")));
        blockbuster.add(entry("PacketReloadModels", RELOAD_MODELS, "P69", "", s(38, "S")));
        blockbuster.add(entry("PacketGunInfo", GUN_INFO, "P196",
            "PacketZoomCommand (slot 40) sits BETWEEN the two GunInfo slots — exact interleaving preserved from 1.12 lines 183-192.",
            s(39, "S"), s(41, "C")));
        blockbuster.add(entry("PacketZoomCommand", ZOOM_COMMAND, "P196", "", s(40, "S")));
        blockbuster.add(entry("PacketGunShot", GUN_SHOT, "P196", "", s(42, "C")));
        blockbuster.add(entry("PacketGunProjectile", GUN_PROJECTILE, "P196", "", s(43, "C")));
        blockbuster.add(entry("PacketGunInteract", GUN_INTERACT, "P196",
            "PacketGunReloading (slot 45) sits BETWEEN the two GunInteract slots.",
            s(44, "S"), s(46, "C")));
        blockbuster.add(entry("PacketGunReloading", GUN_RELOADING, "P196", "", s(45, "S")));
        blockbuster.add(entry("PacketGunProjectileVanish", GUN_PROJECTILE_VANISH, "P196", "", s(47, "C")));
        blockbuster.add(entry("PacketGunStuck", GUN_STUCK, "P196", "", s(48, "C")));
        blockbuster.add(entry("PacketGunProjectileSpawnData", GUN_PROJECTILE_SPAWN_DATA, "P195",
            "Port addition (no 1.12 slot): replaces Forge's IEntityAdditionalSpawnData — yarn EntitySpawnS2CPacket carries no NBT. "
            + "Server sends it to tracking players on spawn + onStartedTrackingBy; byte layout mirrors legacy EntityGunProjectile.writeSpawnData. Client-bound."));
        blockbuster.add(entry("PacketStructure", STRUCTURE, "P162", "", s(49, "C")));
        blockbuster.add(entry("PacketStructureRequest", STRUCTURE_REQUEST, "P162", "", s(50, "S")));
        blockbuster.add(entry("PacketStructureList", STRUCTURE_LIST, "P162", "", s(51, "C")));
        blockbuster.add(entry("PacketStructureListRequest", STRUCTURE_LIST_REQUEST, "P162", "", s(52, "S")));
        blockbuster.add(entry("PacketPlaybackButton", PLAYBACK_BUTTON, "P129", "", s(53, "S"), s(54, "C")));
        blockbuster.add(entry("PacketAudio", AUDIO, "S16", "", s(55, "C")));
        blockbuster.add(entry("PacketDamageControlCheck", DAMAGE_CONTROL_CHECK, "P113", "", s(56, "S")));
        blockbuster.add(entry("PacketRequestProfiles", REQUEST_PROFILES, "P182", APERTURE_NOTE, s(57, "S")));
        blockbuster.add(entry("PacketCameraProfileList", CAMERA_PROFILE_LIST, "P182", APERTURE_NOTE, s(58, "C")));
        blockbuster.add(entry("PacketRequestLength", REQUEST_LENGTH, "P182", APERTURE_NOTE, s(59, "S")));
        blockbuster.add(entry("PacketSceneLength", SCENE_LENGTH, "P182", APERTURE_NOTE, s(60, "C")));
        blockbuster.add(entry("PacketAudioShift", AUDIO_SHIFT, "P182", APERTURE_NOTE, s(61, "S")));
        blockbuster.add(entry("PacketOpenGui", OPEN_GUI, "P100",
            "Port addition (no 1.12 slot): replaces Forge's IGuiHandler / EntityPlayer.openGui, which rode Forge's built-in GUI-open packet. "
            + "Carries {int id, x, y, z} — the exact openGui argument shape; for GUI id 1 (ACTOR) the entity id rides in x. "
            + "Server-bound senders only in this phase (S2C). GUI ids: PLAYBACK 0, ACTOR 1, MODEL_BLOCK 3 (id 2 historically removed)."));
        blockbuster.add(entry("(handshake)", HANDSHAKE, "P27",
            "No 1.12 slot: presence rode Forge's mod-list handshake; Fabric needs an explicit JOIN-event packet. Optional on both sides."));
        blockbuster.add(entry("(chunked transport)", BLOCKBUSTER_CHUNK, "P25",
            "No 1.12 slot: replaces McLib's PayloadASM coremod patch of the 32767-byte payload cap."));

        List<Entry> mclib = new ArrayList<>();

        mclib.add(entry("PacketDropItem", MCLIB_DROP_ITEM, "P26", "", s(0, "S")));
        mclib.add(entry("PacketRequestConfigs", MCLIB_REQUEST_CONFIGS, "P26", "", s(1, "S")));
        mclib.add(entry("PacketConfig", MCLIB_CONFIG, "P26", "", s(2, "S"), s(3, "C")));
        mclib.add(entry("PacketConfirm", MCLIB_CONFIRM, "P26", "", s(4, "C"), s(5, "S")));
        mclib.add(entry("PacketAnswer", MCLIB_ANSWER, "P26", "", s(6, "C")));
        mclib.add(entry("PacketBoolean", MCLIB_BOOLEAN, "P26", "", s(7, "C")));
        mclib.add(entry("PacketRequestPermission", MCLIB_REQUEST_PERMISSION, "P26", "", s(8, "S")));
        mclib.add(entry("(chunked transport)", MCLIB_CHUNK, "P25",
            "No 1.12 slot: replaces McLib's PayloadASM coremod patch of the 32767-byte payload cap."));

        /* Aperture 1.8.2's own dispatcher (P182). Order below IS the legacy
         * discriminator sequence, 1:1 with
         * .tools/legacy-src/aperture/.../network/Dispatcher.register(). */

        List<Entry> aperture = new ArrayList<>();

        aperture.add(entry("PacketAperture", APERTURE_APERTURE, "P182",
            "Server-mode handshake sent on JOIN; flips ClientProxy.server. CLIENT receiver only in 1.12 and here.", s(0, "C")));
        aperture.add(entry("PacketCameraProfile", APERTURE_CAMERA_PROFILE, "P182",
            "OP-gated save on the server half; the full profile rides the P25 chunked transport.", s(1, "C"), s(2, "S")));
        aperture.add(entry("PacketCameraReset", APERTURE_CAMERA_RESET, "P182",
            "S22 P244 decision: KEEP, no sender. Aperture 1.8.2 has no send site either (only the class, the registration and the handler) — "
            + "a receive-only API surface for third-party mods that clear a player's camera capability. Deleting it would move every id after slot 3.",
            s(3, "S")));
        aperture.add(entry("PacketCameraState", APERTURE_CAMERA_STATE, "P182", "", s(4, "C")));
        aperture.add(entry("PacketLoadCameraProfile", APERTURE_LOAD_CAMERA_PROFILE, "P182", "", s(5, "S")));
        aperture.add(entry("PacketRequestCameraProfiles", APERTURE_REQUEST_CAMERA_PROFILES, "P182", "", s(6, "S")));
        aperture.add(entry("PacketCameraProfileList", APERTURE_CAMERA_PROFILE_LIST, "P182",
            "OP-gated list reply. A class of the SAME simple name is also registered on the blockbuster channel (blockbuster:camera_profile_list, "
            + "slot 58) — different class, different dispatcher, different identifier; do not collapse them.", s(7, "C")));
        aperture.add(entry("PacketRenameCameraProfile", APERTURE_RENAME_CAMERA_PROFILE, "P182",
            "Legacy old-name capability quirk preserved.", s(8, "C"), s(9, "S")));
        aperture.add(entry("PacketRemoveCameraProfile", APERTURE_REMOVE_CAMERA_PROFILE, "P182",
            "Server echoes only on success.", s(10, "C"), s(11, "S")));
        aperture.add(entry("(chunked transport)", APERTURE_CHUNK, "P25",
            "No 1.12 slot: replaces McLib's PayloadASM coremod patch of the 32767-byte payload cap. Reserved carrier for large profiles."));

        /* Metamorph 1.4's own dispatcher (P55). Order below IS the legacy
         * discriminator sequence, 1:1 with
         * .tools/legacy-src/metamorph/.../network/Dispatcher.register(). */

        List<Entry> metamorph = new ArrayList<>();

        metamorph.add(entry("PacketAction", METAMORPH_ACTION, "P55", "", s(0, "S")));
        metamorph.add(entry("PacketMorph", METAMORPH_MORPH, "P55", "", s(1, "C"), s(2, "S")));
        metamorph.add(entry("PacketMorphPlayer", METAMORPH_MORPH_PLAYER, "P55", "", s(3, "C")));
        metamorph.add(entry("PacketAcquireMorph", METAMORPH_ACQUIRE_MORPH, "P55", "", s(4, "C"), s(5, "S")));
        metamorph.add(entry("PacketAcquiredMorphs", METAMORPH_ACQUIRED_MORPHS, "P55", "", s(6, "C")));
        metamorph.add(entry("PacketSyncMorph", METAMORPH_SYNC_MORPH, "P55", "", s(7, "S")));
        metamorph.add(entry("PacketSelectMorph", METAMORPH_SELECT_MORPH, "P55", "", s(8, "S")));
        metamorph.add(entry("PacketClearAcquired", METAMORPH_CLEAR_ACQUIRED, "P55", "", s(9, "S")));
        metamorph.add(entry("PacketMorphState", METAMORPH_MORPH_STATE, "P55", "", s(10, "C")));
        metamorph.add(entry("PacketFavorite", METAMORPH_FAVORITE, "P55", "", s(11, "C"), s(12, "S")));
        metamorph.add(entry("PacketKeybind", METAMORPH_KEYBIND, "P55", "", s(13, "C"), s(14, "S")));
        metamorph.add(entry("PacketRemoveMorph", METAMORPH_REMOVE_MORPH, "P55", "", s(15, "C"), s(16, "S")));
        metamorph.add(entry("PacketBlacklist", METAMORPH_BLACKLIST, "P55", "", s(17, "C")));
        metamorph.add(entry("PacketSettings", METAMORPH_SETTINGS, "P55", "", s(18, "C")));
        metamorph.add(entry("PacketMorphSpawnData", METAMORPH_MORPH_SPAWN_DATA, "P56.1",
            "Port addition (no 1.12 slot): the morph ghost's spawn payload, which Forge carried on the vanilla spawn packet through "
            + "IEntityAdditionalSpawnData. Registered AFTER the whole legacy sequence so the frozen order above is untouched. Client-bound."));
        metamorph.add(entry("(chunked transport)", METAMORPH_CHUNK, "P25",
            "No 1.12 slot: replaces McLib's PayloadASM coremod patch of the 32767-byte payload cap."));

        BLOCKBUSTER_CHANNEL = Collections.unmodifiableList(blockbuster);
        MCLIB_CHANNEL = Collections.unmodifiableList(mclib);
        APERTURE_CHANNEL = Collections.unmodifiableList(aperture);
        METAMORPH_CHANNEL = Collections.unmodifiableList(metamorph);
    }

    private ChannelLedger()
    {}

    private static Identifier bb(String name)
    {
        return new Identifier(BLOCKBUSTER, name);
    }

    private static Identifier ml(String name)
    {
        return new Identifier(MCLIB, name);
    }

    private static Identifier ap(String name)
    {
        return new Identifier(APERTURE, name);
    }

    private static Identifier mm(String name)
    {
        return new Identifier(METAMORPH, name);
    }

    private static Slot s(int id, String side)
    {
        return new Slot(id, side);
    }

    private static Entry entry(String packet, Identifier channel, String phase, String note, Slot... slots)
    {
        return new Entry(packet, Arrays.asList(slots), channel, phase, note);
    }
}
