package mchorse.blockbuster;

import mchorse.aperture.Aperture;
import mchorse.aperture.commands.CommandAperture;
import mchorse.blockbuster.aperture.CameraHandler;
import mchorse.blockbuster.aperture.TrackerModifierWiring;
import mchorse.blockbuster.api.ModelHandler;
import mchorse.blockbuster.capabilities.CapabilityHandler;
import mchorse.blockbuster.client.video.ScreenshotConfig;
import mchorse.blockbuster.client.video.VideoConfig;
import mchorse.blockbuster.commands.CommandAction;
import mchorse.blockbuster.commands.CommandDamage;
import mchorse.blockbuster.commands.CommandModelBlock;
import mchorse.blockbuster.commands.CommandMount;
import mchorse.blockbuster.commands.CommandOnHead;
import mchorse.blockbuster.commands.CommandRecord;
import mchorse.blockbuster.commands.CommandScene;
import mchorse.blockbuster.commands.CommandSpectate;
import mchorse.blockbuster.common.BlockbusterPermissions;
import mchorse.blockbuster.common.BlockbusterTab;
import mchorse.blockbuster.common.block.BlockDimGreen;
import mchorse.blockbuster.common.block.BlockDirector;
import mchorse.blockbuster.common.block.BlockGreen;
import mchorse.blockbuster.common.block.BlockModel;
import mchorse.blockbuster.common.entity.EntityActor;
import mchorse.blockbuster.common.entity.EntityGunProjectile;
import mchorse.blockbuster.common.item.BlockbusterItems;
import mchorse.blockbuster.common.item.ItemBlockGreen;
import mchorse.blockbuster.common.item.ItemBlockModel;
import mchorse.blockbuster.common.item.ItemGun;
import mchorse.blockbuster.common.tileentity.TileEntityDirector;
import mchorse.blockbuster.common.tileentity.TileEntityModel;
import mchorse.blockbuster.events.PlayerHandler;
import mchorse.blockbuster.events.TickHandler;
import mchorse.blockbuster.legacy.LegacyBlockEntityIds;
import mchorse.blockbuster.network.server.ServerHandlerStructureRequest;
import mchorse.blockbuster.recording.capturing.ActionHandler;
import mchorse.blockbuster.utils.mclib.BlockbusterResourceTransformer;
import mchorse.blockbuster.utils.mclib.ValueAudioButtons;
import mchorse.blockbuster.utils.mclib.ValueMainButtons;
import mchorse.blockbuster_pack.BlockbusterFactory;
import mchorse.blockbuster_pack.MetamorphHandler;
import mchorse.blockbuster_pack.morphs.StructureMorph;
import mchorse.blockbuster_pack.trackers.TrackerRegistry;
import mchorse.chameleon.Chameleon;
import mchorse.chameleon.metamorph.ChameleonFactory;
import mchorse.mclib.McLib;
import mchorse.mclib.client.gui.utils.keys.LangKey;
import mchorse.mclib.commands.CommandCheats;
import mchorse.mclib.commands.CommandMcLib;
import mchorse.mclib.commands.utils.L10n;
import mchorse.mclib.config.Config;
import mchorse.mclib.config.ConfigBuilder;
import mchorse.mclib.config.ConfigManager;
import mchorse.mclib.config.values.ValueBoolean;
import mchorse.mclib.config.values.ValueFloat;
import mchorse.mclib.config.values.ValueInt;
import mchorse.mclib.config.values.ValueString;
import mchorse.mclib.events.McLibEvents;
import mchorse.mclib.events.RegisterConfigEvent;
import mchorse.mclib.events.RegisterPermissionsEvent;
import mchorse.mclib.network.AbstractDispatcher;
import mchorse.mclib.network.ChannelLedger;
import mchorse.mclib.network.Side;
import mchorse.mclib.network.mclib.Dispatcher;
import mchorse.mclib.network.mclib.common.PacketConfig;
import mchorse.mclib.permissions.DefaultPermissionLevel;
import mchorse.mclib.permissions.PermissionCategory;
import mchorse.mclib.utils.NextTickQueue;
import mchorse.mclib.utils.resources.RLUtils;
import mchorse.metamorph.Metamorph;
import mchorse.metamorph.MetamorphCommon;
import mchorse.metamorph.MetamorphSettingsWiring;
import mchorse.metamorph.api.MorphHandler;
import mchorse.metamorph.api.MorphManager;
import mchorse.metamorph.commands.CommandAcquireMorph;
import mchorse.metamorph.commands.CommandMetamorph;
import mchorse.metamorph.commands.CommandMorph;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.MathHelper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Blockbuster mod (Fabric 1.20.4 full port of Blockbuster 2.7.2)
 */
public class Blockbuster implements ModInitializer
{
    public static final String MOD_ID = "blockbuster";

    /**
     * Legacy behavior/format version string. In 1.12.2 this was the
     * {@code %VERSION%} build template resolved to {@code 2.7.2}; the full port
     * pins that same value <b>deliberately</b> so version-embedding outputs like
     * the OBJ/MTL export headers (P74) stay byte-identical to files produced by
     * Blockbuster 2.7.2. This is <b>not</b> the mod's distribution version — the
     * P216 release freeze ships as {@code 3.0.0-port} (see
     * {@code gradle.properties mod_version} + {@code fabric.mod.json}); the
     * runtime handshake reports that distribution version via the Fabric mod
     * container, while this constant is the format-parity marker only.
     */
    public static final String VERSION = "2.7.2";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /* P22: real config values (previously Supplier placeholders). Ids,
     * defaults and ranges are legacy-exact (blockbuster-1.12 Blockbuster.java
     * onConfigRegister); instances are pre-built so pre-registration reads
     * are safe, then registered into config/blockbuster/blockbuster.json by
     * {@link #onConfigRegister}. The remaining ~55 legacy options land with
     * their owning subsystems (completion pass: P208). */
    public static ValueFloat recordingCountdown = new ValueFloat("recording_countdown", 1.5F, 0, 10);
    public static ValueInt recordUnloadTime = new ValueInt("record_unload_time", 2400, 600, 72000);
    public static ValueBoolean recordUnload = new ValueBoolean("record_unload", true);
    public static ValueInt recordSyncRate = new ValueInt("record_sync_rate", 6, 1, 30);
    public static ValueBoolean recordAttackOnSwipe = new ValueBoolean("record_attack_on_swipe", true);
    public static ValueBoolean recordCommands = new ValueBoolean("record_commands", true);
    public static ValueString recordChatPrefix = new ValueString("record_chat_prefix", "");
    public static ValueBoolean recordPausePreview = new ValueBoolean("record_pause_preview", true);
    public static ValueBoolean recordRenderDebugPaths = new ValueBoolean("record_render_debug_paths", true);
    public static ValueInt actorTrackingRange = new ValueInt("actor_tracking_range", 256, 64, 1024);
    /* P80 client render gates. Legacy Blockbuster.java: actor_rendering_range
     * (256/64/1024), actor_always_render (false), actor_always_render_names
     * (false) — all clientSide(). Consumed by RenderActor.shouldRender and the
     * RenderCustomActor nametag rule. */
    public static ValueInt actorRenderingRange = new ValueInt("actor_rendering_range", 256, 64, 1024);
    public static ValueBoolean actorAlwaysRender = new ValueBoolean("actor_always_render", false);
    public static ValueBoolean actorAlwaysRenderNames = new ValueBoolean("actor_always_render_names", false);
    public static ValueBoolean actorSwishSwipe = new ValueBoolean("actor_swish_swipe", false);
    public static ValueBoolean actorFixY = new ValueBoolean("actor_y", false);
    public static ValueBoolean actorFallDamage = new ValueBoolean("actor_fall_damage", true);
    public static ValueBoolean actorPlaybackBodyYaw = new ValueBoolean("actor_playback_body_yaw", true);
    public static ValueBoolean damageControl = new ValueBoolean("damage_control", true);
    public static ValueInt damageControlDistance = new ValueInt("damage_control_distance", 64, 1, 1024);
    public static ValueBoolean damageControlMessage = new ValueBoolean("damage_control_message", false);

    /* Scene / director options (S11 P128). Legacy categories/defaults
     * (blockbuster-1.12 Blockbuster.java): general.debug_playback_ticks
     * (false), model_block.reset_on_playback (false), scenes.save_update
     * (true). Names/categories are legacy-exact for config migration. */
    public static ValueBoolean debugPlaybackTicks = new ValueBoolean("debug_playback_ticks", false);
    public static ValueBoolean modelBlockResetOnPlayback = new ValueBoolean("reset_on_playback", false);
    /* P96: model-block rendering config (legacy Blockbuster.java lines 260-268,
     * category model_block, client-side). model_block_disable_rendering /
     * model_block_disable_item_rendering are the render/inventory kill-switches;
     * model_block_missing_name_rendering is consumed by the S6 custom-model
     * renderer for missing skins; model_block_debug_rendering_f1 forces the F3
     * status cubes even with the GUI hidden; restore (full name model_block.restore)
     * is consumed by the S12 dashboard model-block panel (recorded here so S12/S19
     * don't miss it). */
    public static ValueBoolean modelBlockDisableRendering = new ValueBoolean("model_block_disable_rendering", false);
    public static ValueBoolean modelBlockRenderMissingName = new ValueBoolean("model_block_missing_name_rendering", true);
    public static ValueBoolean modelBlockRenderDebuginf1 = new ValueBoolean("model_block_debug_rendering_f1", false);
    public static ValueBoolean sceneSaveUpdate = new ValueBoolean("save_update", true);

    /* Model-block dashboard panel options (P136). Legacy
     * (blockbuster-1.12 Blockbuster.java): model_block.restore (default false,
     * client-side/invisible) — when on, the model-block panel caches the last
     * saved TileEntityModel per BlockPos and restores its data on reopen;
     * immersive_editor.model_block (default true, client-side) — gates whether
     * picking a morph opens the in-world immersive editor vs. the inline
     * creative morph picker. Keys/categories are legacy-exact for migration. */
    public static ValueBoolean modelBlockRestore = new ValueBoolean("restore", false);
    public static ValueBoolean immersiveModelBlock = new ValueBoolean("model_block", true);

    /**
     * P83: model-block-as-item render switch (client-side). When true, the 16
     * model-block items render their baked {@code model_static} sprite in the
     * inventory/hand/ground instead of the live morph — legacy
     * {@code Blockbuster.modelBlockDisableItemRendering}
     * ({@code Blockbuster.java} line 261). The key is <b>read live per render</b>
     * by the item renderer (toggling it changes inventory icons immediately),
     * so it must never be cached at registration. The leaf key keeps the legacy
     * {@code model_block_}-prefixed spelling verbatim for config-file parity
     * (unlike {@code reset_on_playback}, the legacy config used the long key
     * here). Default false, client-side.
     */
    public static ValueBoolean modelBlockDisableItemRendering = new ValueBoolean("model_block_disable_item_rendering", false);

    /**
     * Onion-skin category (P167). Legacy {@code Blockbuster.java} lines 209-214
     * (declarations) / 337-344 (registration): six {@code ValueInt} values under
     * {@code builder.category("onion_skin")}, whose category is
     * {@code invisible().markClientSide()} (pure render state). {@code
     * morph_action_color} (default {@code 0x7FFFFF00}, {@code colorAlpha}) is
     * bound by the recording editor's {@code GuiMorphActionPanel}; the five
     * {@code seq_*} keys are consumed by the sequencer editor's onion-skin
     * renderer (P160). {@code seq_prev}/{@code seq_next} are frame counts
     * (default 0); the {@code *_color} keys are RGBA ({@code colorAlpha}) pickers
     * whose defaults carry alpha bytes ({@code 0xCC…}/{@code 0xC0…}). Keys and
     * defaults are legacy-exact for config-file migration.
     */
    public static ValueInt morphActionOnionSkinColor = new ValueInt("morph_action_color", 0x7FFFFF00).colorAlpha();
    public static ValueInt seqOnionSkinPrev = new ValueInt("seq_prev", 0);
    public static ValueInt seqOnionSkinPrevColor = new ValueInt("seq_prev_color", 0xCCFF0000).colorAlpha();
    public static ValueInt seqOnionSkinNext = new ValueInt("seq_next", 0);
    public static ValueInt seqOnionSkinNextColor = new ValueInt("seq_next_color", 0xCC00FF00).colorAlpha();
    public static ValueInt seqOnionSkinLoopColor = new ValueInt("seq_loop_color", 0xC07F7FFF).colorAlpha();

    /**
     * Gun category (S17). {@code bb_gun_sync_distance} (default 0, range 0–100,
     * client-side) gates {@code EntityGunProjectile}'s client desync correction
     * — at 0 (the default) server position corrections are ignored entirely.
     * Legacy {@code Blockbuster.java} line 256. S19 wires it into the config GUI.
     */
    public static ValueFloat bbGunSyncDistance = new ValueFloat("bb_gun_sync_distance", 0, 0, 100);

    /* Audio category (P188). The full waveform_* / audio_sync tree lands with
     * P190/P208; this phase registers only the option the AudioLibrary load path
     * reads — waveform density (client-side, legacy default 20, range 10-100). */

    /* Audio category. Density landed with P188 (the AudioLibrary load path
     * reads it); P190 completes the waveform_* HUD tree + audio_sync. All are
     * client-side except audio_sync (a plain server-side toggle, legacy-exact).
     * The "buttons" (ValueAudioButtons) widget lands with S19 P210. */
    public static ValueBoolean audioWaveformVisible = new ValueBoolean("waveform_visible", true);
    public static ValueInt audioWaveformDensity = new ValueInt("waveform_density", 20, 10, 100);
    public static ValueFloat audioWaveformWidth = new ValueFloat("waveform_width", 0.5F, 0F, 1F);
    public static ValueInt audioWaveformHeight = new ValueInt("waveform_height", 24, 10, 40);
    public static ValueBoolean audioWaveformFilename = new ValueBoolean("waveform_filename", true);
    public static ValueBoolean audioWaveformTime = new ValueBoolean("waveform_time", true);
    public static ValueBoolean audioSync = new ValueBoolean("audio_sync", true);

    /* General category (P90). url_skins_sync_download (client-side, default
     * true): when true, URL skins download synchronously inside the resource
     * resolve (works with 3D outer layers at the cost of a hitch); when false,
     * the download is deferred to the render executor. The rest of the general
     * tree lands with P208. Read by
     * {@link mchorse.blockbuster.client.textures.URLDownloadThread#handleURLSkins}. */
    public static ValueBoolean syncedURLTextureDownload = new ValueBoolean("url_skins_sync_download", true);

    /* General category (P92). {@code watch_files} is a NEW, purely additive
     * client-only option with NO 1.12.2 equivalent — it opts into the optional
     * WatchService-based live reload ({@link mchorse.blockbuster.utils.watchdog.SkinWatcher}).
     * Default OFF: parity first (legacy has only the polling loops). Documented
     * in the parity notes as an intentional additive modernization.
     *
     * S22/P233 registered it with the config builder (see onConfigRegister) —
     * P208 had deliberately left it out to keep config.json byte-identical to
     * 2.7.2, but an option that reaches neither the file nor the GUI can never
     * be turned on, which made the whole watchdog unreachable. */
    public static ValueBoolean watchFiles = new ValueBoolean("watch_files", false);

    /* General category (P99). The creative tab reads add_utility_blocks (client
     * side, legacy default false — Blockbuster.java lines 254-255) to decide
     * whether the command/structure/barrier blocks join the Blockbuster tab. The
     * rest of the general category lands with P208. */
    public static ValueBoolean addUtilityBlocks = new ValueBoolean("add_utility_blocks", false);

    /* General category (P142). Legacy {@code Blockbuster.java} line 245:
     * {@code generalFirstTime = builder.getBoolean("show_first_time_modal", true)}
     * then {@code .clientSide()} — gates the one-time GuiFirstTime welcome
     * modal; the Done button self-unsets it. The rest of the general category
     * (buttons/chroma_sky/etc.) lands with P208. */
    public static ValueBoolean generalFirstTime = new ValueBoolean("show_first_time_modal", true);

    /* General category (S18 P203). Legacy {@code Blockbuster.java} lines 248-251:
     * {@code chromaSky = builder.getBoolean("green_screen_sky", false)} and
     * {@code chromaSkyColor = builder.getInt("green_screen_sky_color", 0xff00ff00).colorAlpha()},
     * both {@code .clientSide()}. The color is ARGB with a MEANINGFUL alpha
     * ({@code colorAlpha()} subtype): on 1.12.2 the alpha only fed
     * {@code clearColor}, but in the port's alpha-capture mode it is the actual
     * transparency source (P203) — do not strip it. Read by
     * {@link mchorse.blockbuster.client.RenderingHandler#isGreenSky()} /
     * {@code renderGreenSky()}. The rest of the general tree lands with P208. */
    public static ValueBoolean chromaSky = new ValueBoolean("green_screen_sky", false);
    public static ValueInt chromaSkyColor = new ValueInt("green_screen_sky_color", 0xff00ff00).colorAlpha();

    /* Snowstorm category. Legacy {@code Blockbuster.java} line 199/309:
     * {@code snowstormDepthSorting = builder.category("snowstorm").getBoolean("depth_sorting", false)}.
     * P153 (the snowstorm renderer) consumes this at two render levels — emitter
     * ordering in {@link mchorse.blockbuster.client.RenderingHandler} and particle
     * ordering in {@code BedrockEmitter.depthSorting()} — both farthest-first.
     * This is the temporary default-false value object the S13 plan allows until
     * S19 (P207-P210) wires the "snowstorm" category into {@link #onConfigRegister}
     * and the config file/GUI; the default matches legacy so behaviour is
     * unchanged until then. */
    public static ValueBoolean snowstormDepthSorting = new ValueBoolean("depth_sorting", false);

    /* Config-completion pass (S19 P208). The following options complete the
     * legacy blockbuster module catalog; ids/defaults/categories/subtypes are
     * legacy-exact (blockbuster-1.12 Blockbuster.onConfigRegister). Consumers
     * live in other stages (chroma sky = S18, onion-skin GUI = S12,
     * model-folder scan = S5, better-lights = S13 renderer, actor riding =
     * P93) and reference these statics; registering them here makes
     * config/blockbuster/config.json byte-identical to 1.12.2. */

    /* general.green_screen_sky / green_screen_sky_color are declared above with
     * the P203 chroma renderer docs (single declaration). */

    /** actor.actor_disable_riding (server): when true the empty-hand click on
     * an actor never mounts it (see {@code EntityActor.interactMob}). */
    public static ValueBoolean actorDisableRiding = new ValueBoolean("actor_disable_riding", false);

    /** model_folders.path (server): one extra model search root scanned by the
     * S5 model loader in addition to {@code config/blockbuster/models} and the
     * world folder; empty by default. Legacy {@code ModelPack.setupFolders}
     * added it as a single {@code new File(path)} — not a path list. */
    public static ValueString modelFolderPath = new ValueString("path", "");

    /* onion_skin category (morph_action_color + seq_* set) is declared above
     * with the P167 onion-skin docs (single declaration). */

    /** immersive_editor.record_editor (client-side): open the in-world
     * immersive record editor vs. the legacy dashboard flow. */
    public static ValueBoolean immersiveRecordEditor = new ValueBoolean("record_editor", true);

    /* ------------------------------------------------------------------ */
    /* video category — S18's built-in recorder (S22/P250).               */
    /* ------------------------------------------------------------------ */

    /**
     * The {@code video} category: the whole thing is a <b>port addition</b>
     * with no 1.12.2 equivalent (on 1.12.2 every one of these settings lived in
     * the external Minema mod's own config, which Blockbuster only talked to
     * through {@code MinemaAPI}). {@code plan/S18-video-capture.md:334-341}
     * specified the category name, keys, types and defaults and handed the
     * registration to S19 P208 — and P208 shipped without it, which left the
     * recorder hard-wired to the {@code VideoConfig.DEFAULT_*} constants: no
     * frame rate, no ffmpeg path, no output folder, and — because the
     * constants are {@code static final} and javac folds them — the P203 alpha
     * arm and the P199/P200 motion-blur arm were not merely off but absent
     * from the compiled jar. Registering the category is what makes them
     * reachable; see {@link mchorse.blockbuster.client.video.VideoConfig} for
     * the live accessors the recorder now reads.
     *
     * <p>Key order follows the S18 sketch verbatim ({@code ffmpeg_path},
     * {@code arguments}, {@code arguments_audio}, then the geometry/timing
     * block, then {@code export_path}, {@code encoder_log}); the two alpha
     * templates sit next to their opaque siblings and the two feature toggles
     * ({@code alpha}, {@code audio}) next to the timing block they gate.
     * Defaults are the {@code VideoConfig.DEFAULT_*} constants themselves — one
     * source of truth, so the file's defaults and the headless fallbacks can
     * never drift.</p>
     *
     * <p>Ranges: {@code motion_blur}'s 0..6 is from the S18 spec. The others
     * ({@code frame_rate} 1..240, {@code held_frames} 1..60, {@code width}/
     * {@code height} 0..7680) are port-chosen sanity clamps — S18 specified no
     * bounds for them, and an unbounded trackpad in the config GUI is how you
     * get a 0-fps recording.</p>
     *
     * <p>Whole category is {@code markClientSide()}: recording is a client-only
     * activity, exactly like {@code snowstorm}.</p>
     */
    public static ValueString videoFfmpegPath = new ValueString("ffmpeg_path", VideoConfig.DEFAULT_FFMPEG_PATH);
    public static ValueString videoArguments = new ValueString("arguments", VideoConfig.DEFAULT_ARGUMENTS);
    public static ValueString videoArgumentsAudio = new ValueString("arguments_audio", VideoConfig.DEFAULT_ARGUMENTS_AUDIO);
    public static ValueString videoArgumentsAlpha = new ValueString("arguments_alpha", VideoConfig.DEFAULT_ARGUMENTS_ALPHA);
    public static ValueString videoArgumentsAlphaAudio = new ValueString("arguments_alpha_audio", VideoConfig.DEFAULT_ARGUMENTS_ALPHA_AUDIO);
    /** {@code 0} = record at the window's framebuffer size. See
     * {@code MinemaBackend.resolveSize} for the still-deferred custom-resolution seam. */
    public static ValueInt videoWidth = new ValueInt("width", 0, 0, 7680);
    public static ValueInt videoHeight = new ValueInt("height", 0, 0, 7680);
    public static ValueInt videoFrameRate = new ValueInt("frame_rate", VideoConfig.DEFAULT_FRAME_RATE, 1, 240);
    public static ValueInt videoMotionBlur = new ValueInt("motion_blur", VideoConfig.DEFAULT_MOTION_BLUR, 0, 6);
    public static ValueInt videoHeldFrames = new ValueInt("held_frames", VideoConfig.DEFAULT_HELD_FRAMES, 1, 60);
    public static ValueBoolean videoAlpha = new ValueBoolean("alpha", VideoConfig.DEFAULT_ALPHA);
    public static ValueBoolean videoAudio = new ValueBoolean("audio", VideoConfig.DEFAULT_AUDIO);
    /** Empty = {@code config/blockbuster/movies} (S18 open question 2, resolved
     * config-side so the folder survives world switches). */
    public static ValueString videoExportPath = new ValueString("export_path", "");
    public static ValueBoolean videoEncoderLog = new ValueBoolean("encoder_log", VideoConfig.DEFAULT_ENCODER_LOG);
    /** wixo.1: render at the chosen resolution even with an Iris shader pack (CDC R2). */
    public static ValueBoolean videoCustomResolutionShaders = new ValueBoolean("custom_resolution_shaders", VideoConfig.DEFAULT_CUSTOM_RESOLUTION_SHADERS);
    /** wixo.1: encoding preset id ({@link mchorse.blockbuster.client.video.EncoderPresets}); empty = not chosen yet (migrated on read). */
    public static ValueString videoEncoder = new ValueString("encoder", "");
    /** wixo.1 (CDC §2.6): verbose recorder diagnostics in latest.log. */
    public static ValueBoolean videoDebug = new ValueBoolean("debug", false);

    /* ------------------------------------------------------------------ */
    /* screenshot category — S18's transparent still (S22/P298).          */
    /* ------------------------------------------------------------------ */

    /**
     * The {@code screenshot} category (S22 <b>P298</b>): a second <b>port-only</b>
     * category, appended after {@code video} for exactly the same reason and
     * under exactly the same contract — 1.12.2 Blockbuster/McLib/Metamorph/
     * Aperture contain no screenshot code at all, so there is no legacy key order
     * to preserve and nothing in a 2.7.2 document to collide with. A real 2.7.2
     * {@code config.json} that lacks both trailing objects still loads unchanged
     * ({@code ConfigParser.fromJson} is total on missing categories), and the
     * single documented delta is that a config we <i>write</i> gains the two
     * trailing objects — pinned by {@code ConfigRoundTripTest}, which drops
     * exactly these categories before asserting byte-identity, so any
     * <b>other</b> new category still fails.
     *
     * <p><b>Why it was needed.</b> S18 P204 shipped the transparent still with
     * two triggers and <i>no</i> config: the world keybind
     * ({@code key.blockbuster.screenshot_transparent}) defaults to
     * {@code GLFW_KEY_UNKNOWN}, so on a fresh install nothing outside the model
     * editor could take one and the settings screen never mentioned the feature.
     * {@code screenshot.replace_vanilla} is what makes it usable without editing
     * a keybind: vanilla F2 takes the transparent shot instead (in-world, no
     * screen open — see
     * {@link mchorse.blockbuster.client.video.ScreenshotConfig#interceptsVanillaScreenshot}).</p>
     *
     * <p>Defaults are the {@code ScreenshotConfig.DEFAULT_*} constants — one
     * source of truth for the file default and the headless fallback, read
     * <b>only</b> here (a capture-path read would be constant-folded; see
     * S22/P250). Whole category is {@code markClientSide()}: taking a screenshot
     * is a client-only activity, like {@code video}/{@code snowstorm}.</p>
     */
    public static ValueBoolean screenshotTransparent = new ValueBoolean("transparent", ScreenshotConfig.DEFAULT_TRANSPARENT);
    public static ValueBoolean screenshotReplaceVanilla = new ValueBoolean("replace_vanilla", ScreenshotConfig.DEFAULT_REPLACE_VANILLA);
    /** Empty = {@code <run dir>/screenshots/blockbuster} (P204's default). */
    public static ValueString screenshotExportPath = new ValueString("export_path", ScreenshotConfig.DEFAULT_EXPORT_PATH);

    /**
     * Legacy {@code Blockbuster.onConfigRegister} — the complete blockbuster
     * config module (S19 P208), registered in exact legacy category/key order
     * so {@code config/blockbuster/config.json} round-trips 1:1 against real
     * 2.7.2 files. Client/server ({@code clientSide()}/{@code markClientSide()})
     * and {@code colorAlpha()}/invisible markings mirror the legacy source.
     */
    public static void onConfigRegister(RegisterConfigEvent event)
    {
        ConfigBuilder builder = event.createBuilder(MOD_ID);

        /* General category. Legacy key order: buttons, show_first_time_modal,
         * debug_playback_ticks, green_screen_sky, green_screen_sky_color,
         * url_skins_sync_download, add_utility_blocks, bb_gun_sync_distance.
         * Every key is clientSide() individually EXCEPT debug_playback_ticks
         * (a server value), so the general category itself is never
         * markClientSide()'d. The "buttons" ValueMainButtons row serializes as
         * {} (its widget lands with P210).
         *
         * P208 decision, REVERSED by S22/P233: the port-only additive
         * `watch_files` (P92 SkinWatcher opt-in) used to be deliberately left
         * unregistered so config.json stayed byte-identical to 2.7.2. But an
         * option absent from both the file and the config GUI can never be
         * switched on, which left the whole P92/P233 live-reload feature
         * unreachable — a worse parity outcome than one extra key, since
         * 1.12.2 *did* hot-reload edited skins (through its polling loops).
         * So it is registered, LAST in the category, after every legacy key:
         * the eight 1.12.2 keys keep their exact order and payloads, and a
         * 2.7.2 file that lacks `watch_files` still loads unchanged (the
         * parser is total on missing keys). The single documented delta is
         * that a config we WRITE gains a trailing `"watch_files": false` —
         * pinned by ConfigRoundTripTest, which strips exactly this key before
         * asserting byte-identity so any *other* new key still fails. It is
         * clientSide() like every other general key except debug_playback_ticks. */
        builder.category("general").register(new ValueMainButtons("buttons").clientSide());
        builder.register(generalFirstTime);
        generalFirstTime.clientSide();
        builder.register(debugPlaybackTicks);
        builder.register(chromaSky);
        chromaSky.clientSide();
        builder.register(chromaSkyColor);
        chromaSkyColor.clientSide();
        builder.register(syncedURLTextureDownload);
        syncedURLTextureDownload.clientSide();
        builder.register(addUtilityBlocks);
        addUtilityBlocks.clientSide();
        builder.register(bbGunSyncDistance);
        bbGunSyncDistance.clientSide();
        builder.register(watchFiles);
        watchFiles.clientSide();

        /* Model block category. Five render options, then the whole category is
         * markClientSide()'d, then reset_on_playback is registered AFTER — it
         * carries no individual clientSide() flag but inherits the category's
         * (legacy Value.isClientSide walks up parents), exactly as 1.12.2. */
        builder.category("model_block").register(modelBlockDisableRendering);
        builder.register(modelBlockDisableItemRendering);
        builder.register(modelBlockRestore);
        builder.register(modelBlockRenderMissingName);
        builder.register(modelBlockRenderDebuginf1);
        builder.getCategory().markClientSide();
        builder.register(modelBlockResetOnPlayback);

        /* Recording category (all server-side). */
        builder.category("recording").register(recordingCountdown);
        builder.register(recordUnloadTime);
        builder.register(recordUnload);
        builder.register(recordSyncRate);
        builder.register(recordAttackOnSwipe);
        builder.register(recordCommands);
        builder.register(recordChatPrefix);
        builder.register(recordPausePreview);
        builder.register(recordRenderDebugPaths);

        /* Scenes category. */
        builder.category("scenes").register(sceneSaveUpdate);

        /* Actor category. Legacy order: fall_damage, tracking_range,
         * rendering_range, always_render, always_render_names, swish_swipe,
         * actor_y, disable_riding, playback_body_yaw. rendering_range /
         * always_render / always_render_names / actor_y / playback_body_yaw
         * are individually clientSide(). */
        builder.category("actor").register(actorFallDamage);
        builder.register(actorTrackingRange);
        builder.register(actorRenderingRange);
        actorRenderingRange.clientSide();
        builder.register(actorAlwaysRender);
        actorAlwaysRender.clientSide();
        builder.register(actorAlwaysRenderNames);
        actorAlwaysRenderNames.clientSide();
        builder.register(actorSwishSwipe);
        builder.register(actorFixY);
        actorFixY.clientSide();
        builder.register(actorDisableRiding);
        builder.register(actorPlaybackBodyYaw);
        actorPlaybackBodyYaw.clientSide();

        /* Damage control category (all server-side). */
        builder.category("damage_control").register(damageControl);
        builder.register(damageControlDistance);
        builder.register(damageControlMessage);

        /* Model folder category. */
        builder.category("model_folders").register(modelFolderPath);

        /* Snowstorm category (whole category markClientSide()'d). */
        builder.category("snowstorm").register(snowstormDepthSorting);
        builder.getCategory().markClientSide();

        /* Audio category. The ValueAudioButtons "buttons" row is NOT
         * clientSide() in legacy; each waveform_* key IS clientSide();
         * audio_sync is server-side. */
        builder.category("audio").register(new ValueAudioButtons("buttons"));
        builder.register(audioWaveformVisible);
        audioWaveformVisible.clientSide();
        builder.register(audioWaveformDensity);
        audioWaveformDensity.clientSide();
        builder.register(audioWaveformWidth);
        audioWaveformWidth.clientSide();
        builder.register(audioWaveformHeight);
        audioWaveformHeight.clientSide();
        builder.register(audioWaveformFilename);
        audioWaveformFilename.clientSide();
        builder.register(audioWaveformTime);
        audioWaveformTime.clientSide();
        builder.register(audioSync);

        /* Onion skin category (invisible + markClientSide()). Colors use
         * colorAlpha() (baked into the field initializers); seq_prev/seq_next
         * are plain ints. */
        builder.category("onion_skin").register(morphActionOnionSkinColor);
        builder.register(seqOnionSkinPrev);
        builder.register(seqOnionSkinPrevColor);
        builder.register(seqOnionSkinNext);
        builder.register(seqOnionSkinNextColor);
        builder.register(seqOnionSkinLoopColor);
        builder.getCategory().invisible().markClientSide();

        /* Immersive editor category (whole category markClientSide()). */
        builder.category("immersive_editor").register(immersiveModelBlock);
        builder.register(immersiveRecordEditor);
        builder.getCategory().markClientSide();

        /* Bundled Aperture (S15 P186): Blockbuster's own "aperture" category
         * (reload / actions / stop_scene), merged into the blockbuster config
         * module exactly as legacy CameraHandler.registerConfig did. */
        CameraHandler.registerConfig(builder);

        /* Video category (S18, registered by S22/P250 — the S18 -> S19 P208
         * handoff was dropped). PORT-ONLY IN FULL: 1.12.2 had no video config
         * of its own, so this whole category is additive and is registered
         * LAST, after every legacy category including "aperture". A 2.7.2
         * config.json that lacks it still loads unchanged (the parser is total
         * on missing categories/keys); the single documented delta is that a
         * config we WRITE gains a trailing "video" object — pinned by
         * ConfigRoundTripTest, which drops exactly this category before
         * asserting byte-identity, so any OTHER new category still fails.
         * Whole category markClientSide(): recording is client-only. */
        builder.category("video").register(videoFfmpegPath);
        builder.register(videoArguments);
        builder.register(videoArgumentsAudio);
        builder.register(videoArgumentsAlpha);
        builder.register(videoArgumentsAlphaAudio);
        builder.register(videoWidth);
        builder.register(videoHeight);
        builder.register(videoFrameRate);
        builder.register(videoMotionBlur);
        builder.register(videoHeldFrames);
        builder.register(videoAlpha);
        builder.register(videoAudio);
        builder.register(videoExportPath);
        builder.register(videoEncoderLog);
        builder.register(videoCustomResolutionShaders);
        builder.register(videoEncoder);
        builder.register(videoDebug);
        builder.getCategory().markClientSide();

        /* Screenshot category (S18 P204's transparent still, registered by
         * S22/P298 — P204 shipped with no config at all and an unbound
         * keybind). PORT-ONLY IN FULL, same contract as "video" above: 1.12.2
         * had no screenshot code, so this is additive and is registered LAST,
         * after "video". Key order: the feature switch, the vanilla-F2 takeover
         * it gates, then the output folder (mirroring video's trailing
         * export_path). Whole category markClientSide(): screenshots are
         * client-only. */
        builder.category("screenshot").register(screenshotTransparent);
        builder.register(screenshotReplaceVanilla);
        builder.register(screenshotExportPath);
        builder.getCategory().markClientSide();
    }

    /* First-time-modal / social link getters (P142). Legacy
     * {@code Blockbuster.java} lines 87-119 (@SideOnly CLIENT). The URL source
     * is the LANGUAGE file, not config: {@link #langOrDefault} returns the
     * translated value when a resource pack overrides the key, else the
     * hardcoded legacy default. Config-free by design. */

    public static String WIKI_URL()
    {
        return langOrDefault("blockbuster.gui.links.wiki", "https://github.com/mchorse/blockbuster/wiki");
    }

    public static String DISCORD_URL()
    {
        return langOrDefault("blockbuster.gui.links.discord", "https://discord.gg/qfxrqUF");
    }

    public static String CHANNEL_URL()
    {
        return langOrDefault("blockbuster.gui.links.channel", "https://www.youtube.com/c/McHorsesMods");
    }

    public static String TWITTER_URL()
    {
        return langOrDefault("blockbuster.gui.links.twitter", "https://twitter.com/McHorsy");
    }

    public static String TUTORIAL_URL()
    {
        return langOrDefault("blockbuster.gui.links.tutorial", "https://www.youtube.com/watch?v=qDPEjf2TxAc&list=PLLnllO8nnzE-xmqdymsLpxnXTaAbyIVjM&index=2");
    }

    /**
     * Legacy {@code Blockbuster.langOrDefault} (line 117): return the
     * translated string for {@code lang} when a translation exists, otherwise
     * {@code orDefault}. 1.12.2 {@code I18n.format} echoed the key back when
     * untranslated; the bundled {@code LangKey.translator} (yarn
     * {@code I18n.translate} on the client, key-echo headless/server) behaves
     * identically, so the {@code result.equals(lang)} test is preserved 1:1.
     * This lets resource packs override URLs via the language file — no config
     * option involved.
     */
    public static String langOrDefault(String lang, String orDefault)
    {
        String result = LangKey.translator.apply(lang, new Object[0]);

        return result.equals(lang) ? orDefault : result;
    }

    /**
     * Legacy {@code Blockbuster.onPermissionRegister} (P21) — ported 1:1.
     * Registers Blockbuster's mod node at {@link
     * mchorse.mclib.permissions.DefaultPermissionLevel#OP} then two leaves:
     * {@code blockbuster.model_block.edit} and {@code blockbuster.scenes.open}.
     * Fired through {@link mchorse.mclib.events.McLibEvents#REGISTER_PERMISSIONS}.
     */
    public static void onPermissionRegister(RegisterPermissionsEvent event)
    {
        event.registerMod(MOD_ID, DefaultPermissionLevel.OP);

        event.registerCategory(new PermissionCategory("model_block"));
        event.registerPermission(BlockbusterPermissions.editModelBlock = new PermissionCategory("edit"));

        event.endCategory();

        event.registerCategory(new PermissionCategory("scenes"));
        event.registerPermission(BlockbusterPermissions.openScene = new PermissionCategory("open"));

        event.endMod();
    }

    /**
     * Localization shortcuts (legacy {@code Blockbuster.l10n}, McLib L10n).
     */
    public static final L10n l10n = new L10n(MOD_ID);

    /**
     * Reload server-side domain models (roadmap P69). Legacy
     * {@code Blockbuster.reloadServerModels(force)} → {@code proxy.loadModels}.
     * Called by {@code ServerHandlerReloadModels} (op-gated) and the
     * {@code /model reload} command family (S10).
     */
    public static void reloadServerModels(boolean force)
    {
        CommonProxy.loadModels(force);
    }

    /**
     * P22.1: the generic tick scheduler (legacy {@code Blockbuster.proxy}
     * carried it via event subscription; the port keeps one static instance
     * dispatched from Fabric tick events / the client initializer).
     */
    public static final TickHandler tickHandler = new TickHandler();

    /**
     * The actor entity type (P93). Legacy registration:
     * {@code CommonProxy.registerEntityWithEgg(EntityActor.class,
     * "blockbuster:actor", …, trackingRange = 256 blocks, updateFrequency = 3)}.
     * Populated by {@link #registerContent()}.
     */
    public static EntityType<EntityActor> ACTOR;

    /**
     * The model block + its block-entity type (P95). Legacy registration:
     * {@code GameRegistry.register(new BlockModel())} (registry name
     * {@code blockbuster:model}) + the {@code TileEntityModel} registered under
     * the legacy TE string {@code blockbuster_model_tile_entity} (P93.1 — a save
     * contract; see {@link mchorse.blockbuster.legacy.LegacyBlockEntityIds} for
     * the three spellings that still load). The 16 metadata variants become the LIGHT 0..15
     * state property; the 16 placement items are plain {@code Item}s registered
     * separately (P93.1/P98). Populated by {@link #registerContent()}.
     */
    public static BlockModel MODEL_BLOCK;
    public static BlockEntityType<TileEntityModel> MODEL_BLOCK_TILE;

    /**
     * The 16 model-block placement items, indexed by light value — legacy
     * {@code Blockbuster.modelBlockItems} (field name kept verbatim). Index 0 is
     * the {@code blockbuster:model} {@link net.minecraft.item.BlockItem}, 1..15
     * are {@link mchorse.blockbuster.common.item.ItemBlockModel}
     * ({@code model1}…{@code model15}). Populated by {@link #registerContent()}.
     */
    public static final Item[] modelBlockItems = new Item[16];

    /**
     * The actor spawn egg ({@code blockbuster:actor_spawn_egg}, P93 step 3).
     *
     * <p>1.12.2 shipped <b>no</b> egg item: {@code BlockbusterTab} conjured a
     * vanilla {@code Items.SPAWN_EGG} stack and stamped {@code blockbuster:actor}
     * into its NBT via {@code ItemMonsterPlacer.applyEntityIdToItemStack}. 1.20.4
     * flattened spawn eggs into one item per entity, so the port registers a real
     * {@link net.minecraft.item.SpawnEggItem} with the legacy egg colors
     * ({@code 0xffc1ab33} / {@code 0xffa08d2b} in {@code CommonProxy}, minus the
     * alpha byte {@code SpawnEggItem} does not take). It is added to the creative
     * tab manually, in the legacy egg's position (last, before the utility
     * blocks) — never through a tab attribute.</p>
     */
    public static SpawnEggItem ACTOR_SPAWN_EGG;

    /**
     * The director block + its block-entity type (P94 / P129). Legacy
     * registration: {@code GameRegistry.register(new BlockDirector())} (block id
     * {@code blockbuster:director}) + a plain {@code ItemBlock} + the
     * {@code TileEntityDirector} registered under the legacy TE string
     * {@code blockbuster_director_tile_entity} (kept verbatim — it is a save
     * contract). The block is a deprecated relic (redstone + farewell only);
     * its BE performs the old-world {@code Actors}-NBT scene migration.
     * Populated by {@link #registerContent()}.
     */
    public static BlockDirector DIRECTOR_BLOCK;
    public static BlockEntityType<TileEntityDirector> DIRECTOR_TILE;

    /**
     * P97: the chroma (green screen) blocks. Legacy {@code Blockbuster.greenBlock}
     * / {@code dimGreenBlock}, block ids {@code blockbuster:green} /
     * {@code blockbuster:dim_green}. One block id per legacy id with the color as
     * a blockstate property (matches the 1.12 disk format for the S20 migrator).
     * Populated by {@link #registerContent()}.
     */
    public static Block greenBlock;
    public static Block dimGreenBlock;

    /**
     * The BB gun item (S8 shell → S17 P194 logic). Registered as
     * {@code blockbuster:gun} in {@link #registerContent()}.
     */
    public static ItemGun GUN;

    /**
     * The gun projectile entity type (S17 P195). Registered as
     * {@code blockbuster:projectile} — the legacy id from {@code CommonProxy.load}
     * — with the legacy tracking parameters (256-block range, update frequency
     * <b>10</b>, velocity updates on). Default hitbox 0.25×0.25. The similarly
     * named {@code blockbuster:gun_projectile} is a <i>packet channel</i>
     * ({@link mchorse.mclib.network.ChannelLedger#GUN_PROJECTILE}), a different
     * registry entirely.
     */
    public static EntityType<EntityGunProjectile> GUN_PROJECTILE;

    @Override
    public void onInitialize()
    {
        /* P247: legacy CommonProxy registered PlayerHandler on the event bus at
         * EventPriority.HIGHEST. Fabric has no priorities but dispatches
         * interaction listeners in registration order and stops at the first
         * non-PASS, so the gun's prevention gates are registered before
         * anything else that can consume an interaction (ActionHandler's
         * recording hooks in particular). This call also installs the
         * server-side START-phase gun state pump. */
        PlayerHandler.register();

        /* P93.1 (batch U-I): configs BEFORE content. 1.20.4 bakes an entity
         * type's tracking distance into the immutable EntityType at
         * registration, and `actor.tracking_range` has to feed it — with the
         * old order (content, then configs) the value was always the inline
         * default, which is why the option had no effect at all. This is also
         * the legacy order: Forge loaded the config in pre-init, and
         * CommonProxy.load read `actorTrackingRange.get()` at init. Verified
         * safe: none of the four `onConfigRegister` callbacks (McLib, Aperture,
         * Metamorph, Blockbuster) touches a Minecraft registry, and neither does
         * the permission registration below it — they only build Value objects
         * and parse JSON. */
        registerConfigs();

        registerContent();
        registerNetworking();

        /* P88: rewrite old {@code blockbuster.actors} skin references to the
         * {@code b.a} shorthand and expand pre-2.x two-segment paths as
         * RLUtils parses model/morph skin locations (both sides). */
        RLUtils.register(new BlockbusterResourceTransformer());

        registerServerEvents();
        registerCommands();

        /* Bundled Aperture (S15): runtime hooks — camera folder root +
         * login profile resend (registries registered in registerContent) */
        mchorse.aperture.CommonProxy.load();

        /* Bundled Metamorph (S4): install the morph size-handler seam (P54)
         * so the player dimension/eye mixins serve morph hitboxes. */
        MetamorphCommon.init();

        /* Blockbuster morph pack (S14 P157): register the BlockbusterFactory
         * into the bundled MorphManager (after the vanilla-pack factory, so it
         * wins the reverse-order dispatch) and subscribe the MetamorphHandler
         * blacklist hook. */
        registerBlockbusterMorphs();

        /* Bundled Metamorph (S22 P222): subscribe the morphs.json / blacklist.json
         * / remap.json collectors and arm the server-start reload. Must run after
         * registerBlockbusterMorphs() — MorphSettingsAdapter resolves ability /
         * action / attack ids against MorphManager's maps at parse time. */
        MetamorphSettingsWiring.install();

        LOGGER.info("Blockbuster (Fabric port) initialized");
    }

    /**
     * S14 P157: register Blockbuster's own morph factory + Metamorph event hook.
     *
     * <p>Mirrors the legacy {@code CommonProxy} wiring
     * ({@code this.factory = new BlockbusterFactory(); this.factory.models =
     * this.models; MorphManager.INSTANCE.factories.add(this.factory)} plus the
     * {@code MetamorphHandler} event-bus subscription). The factory is registered
     * <b>after</b> the vanilla-pack {@link mchorse.vanilla_pack.MetamorphFactory}
     * so it wins the reverse-order dispatch. Guarded on list content (like
     * {@code MetamorphCommon.init}) so headless test isolation that clears the
     * shared {@code MorphManager.INSTANCE} self-heals and production never
     * double-registers.</p>
     */
    private static void registerBlockbusterMorphs()
    {
        /* Ensure the shared model handler exists (legacy created it eagerly as
         * this.models = getHandler(); loadModels(...) later fills its map). */
        if (CommonProxy.models == null)
        {
            CommonProxy.models = new ModelHandler();
        }

        /* Register the built-in trackers (legacy CommonProxy lines 182-183:
         * "aperture_tracker" -> MorphTracker, "apcam" -> ApertureCamera). P166
         * shipped TrackerRegistry.registerDefaults() but the init call was never
         * wired; without it TrackerRegistry.CLASS_TO_ID is empty and
         * TrackerMorph.toNBT NPEs (its tracker field defaults to a MorphTracker),
         * crashing the save of any record/scene containing a tracker morph.
         * Idempotent (LinkedHashMap re-put preserves the load-bearing order). */
        TrackerRegistry.registerDefaults();

        boolean present = MorphManager.INSTANCE.factories.stream()
            .anyMatch(f -> f instanceof BlockbusterFactory);

        if (!present)
        {
            BlockbusterFactory factory = new BlockbusterFactory();

            factory.models = CommonProxy.models;
            MorphManager.INSTANCE.factories.add(factory);
        }

        /* Bundled Chameleon: its factory owns the `chameleon.*` morph namespace
         * and the "chameleon" creative section. Registered AFTER Blockbuster's,
         * so it wins the reverse-order MorphManager dispatch for its own prefix
         * — the two namespaces are disjoint, so the order is only a formality,
         * but it matches 1.12.2's load order (Chameleon depended on Metamorph
         * and loaded alongside Blockbuster). Guarded on list content like the
         * block above, so headless test isolation self-heals. */
        boolean chameleon = MorphManager.INSTANCE.factories.stream()
            .anyMatch(f -> f instanceof ChameleonFactory);

        if (!chameleon)
        {
            MorphManager.INSTANCE.factories.add(new ChameleonFactory());
        }

        new MetamorphHandler().register();

        /* P49.1: run every registered factory's register(manager) hook, which
         * is what actually fills MorphManager.abilities/actions/attacks (and,
         * later, the creative MorphSections). Legacy did this once, from
         * Metamorph's CommonProxy.load(), after every mod's preInit had added
         * its factory; the equivalent point here is the end of Blockbuster's
         * own init, since Blockbuster registers the last factory. Without it
         * every "abilities"/"action"/"attack" id in morphs.json resolved to
         * nothing.
         *
         * Guarded on a one-shot flag rather than on map content: the ability
         * maps are idempotent under re-registration but MorphList.sections is
         * append-only, so a second pass would duplicate the creative sections. */
        if (!morphFactoriesRegistered)
        {
            morphFactoriesRegistered = true;
            MorphManager.INSTANCE.register();
        }
    }

    /**
     * One-shot guard for the {@code MorphManager.register()} fan-out above.
     */
    private static boolean morphFactoriesRegistered = false;

    /**
     * P22: the legacy CommonProxy init sequence — config registration
     * (mclib module first, then blockbuster's) followed by permission
     * registration. Fires during mod init so config/mclib/mclib.json +
     * config/blockbuster/blockbuster.json exist (defaults written on first
     * run) before anything reads them, exactly like 1.12.2's FML init.
     */
    /**
     * The bundled subsystems that contribute config modules, in the order
     * 1.12.2's mod-load order produced (mclib first — it owns {@code op_access}
     * — then the bundled deps, then Blockbuster's own module). Extracted from
     * {@link #registerConfigs()} so headless tests drive the <b>production</b>
     * list rather than a copy of it.
     */
    public static void seedConfigCallbacks()
    {
        ConfigManager.REGISTER_CALLBACKS.add(McLib::onConfigRegister);
        /* Bundled Aperture (S15 P186): register the aperture config module
         * (general/outside/editor/flight/smooth + the syncable camera_editor
         * op-access toggle) before Blockbuster's own module. */
        ConfigManager.REGISTER_CALLBACKS.add(Aperture::onConfigRegister);
        /* Bundled Metamorph (S4/S14): its module was written but never
         * subscribed, so config/metamorph/config.json was never created, the
         * metamorph module never appeared in the config panel and every
         * Metamorph.* value stayed at its inline default (the op_access
         * metamorph.entity_selectors toggle included). */
        ConfigManager.REGISTER_CALLBACKS.add(Metamorph::onConfigRegister);
        /* Bundled Chameleon: one client-side button row under
         * config/chameleon/chameleon.json. Registered after Metamorph (which it
         * depends on in 1.12.2) and before Blockbuster's own module, matching the
         * mod-load order the legacy dependency graph produced. */
        ConfigManager.REGISTER_CALLBACKS.add(Chameleon::onConfigRegister);
        ConfigManager.REGISTER_CALLBACKS.add(Blockbuster::onConfigRegister);
    }

    private static void registerConfigs()
    {
        seedConfigCallbacks();

        McLib.proxy.configs.register(
            FabricLoader.getInstance().getConfigDir().toFile());

        /* Permissions: mclib's own tree, then loadPermissions() populates
         * the factory (wire-id assignment for the S2 permission packets) */
        McLibEvents.REGISTER_PERMISSIONS.register(Blockbuster::onPermissionRegister);

        RegisterPermissionsEvent permissions = new RegisterPermissionsEvent();

        McLib.onPermissionRegister(permissions);
        McLibEvents.REGISTER_PERMISSIONS.invoker().accept(permissions);
        permissions.loadPermissions();
    }

    /**
     * S2 networking wiring: bundled-mclib channel registration (P26), server
     * capture for {@code sendToAll}/{@code sendToAllAround} (P23), the join
     * handshake (P27), per-connection chunk-state cleanup (P25) and the
     * next-tick queue drain (P28). Client counterparts live in
     * {@code BlockbusterClient}. The Blockbuster-channel dispatcher itself is
     * ported in the stages that own its packets (P115+); its identifiers are
     * reserved in {@link ChannelLedger}.
     */
    private static void registerNetworking()
    {
        Dispatcher.register();

        /* Blockbuster-channel dispatcher (blockbuster:* channel): recording
         * control plane (P116) + frame (P115) + action (P117) families over the
         * P25 chunked transport; the scene/gun/structure/camera families add
         * their registrations in their owning stages. Server-side receivers wire
         * during register(); client-side receivers wire in
         * BlockbusterClient.registerNetworking. */
        mchorse.blockbuster.network.Dispatcher.register();

        /* P26 config-sync seams — the legacy Dispatcher call sites inside
         * Config.save (client edits a server-side config → send to server)
         * and ConfigManager.synchronizeConfig (server pushes syncables to
         * every player; legacy passed exception=null, i.e. all players) */
        Config.serverSender = config ->
            Dispatcher.sendToServer(new PacketConfig(config));
        ConfigManager.synchronizer = config ->
            Dispatcher.DISPATCHER.sendToAll(new PacketConfig(config, true));

        ServerLifecycleEvents.SERVER_STARTING.register(AbstractDispatcher::setServer);
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
        {
            AbstractDispatcher.setServer(null);
            NextTickQueue.SERVER.clear();
        });

        /* P27: flag Blockbuster's presence to joining clients. Optional by
         * design (legacy always-true @NetworkCheckHandler parity): vanilla
         * clients never see the channel and nothing is required of them. */
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
        {
            PacketByteBuf buf = PacketByteBufs.create();

            buf.writeString(FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown"));

            sender.sendPacket(ChannelLedger.HANDSHAKE, buf);
        });

        /* P25: drop half-received chunk transfers of a leaving player */
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            AbstractDispatcher.onPlayerDisconnect(handler.getPlayer().getUuid()));

        /* P28: "run next tick" queue, drained after all other END handlers */
        ServerTickEvents.END_SERVER_TICK.register(server -> NextTickQueue.SERVER.drain());
    }

    /**
     * Server-shutdown teardown — legacy {@code Blockbuster.serverStopping}
     * ({@code FMLServerStoppingEvent}), which reset <b>all three</b> server-side
     * repositories: {@code CommonProxy.manager}, {@code CommonProxy.damage} and
     * {@code CommonProxy.scenes}.
     *
     * <p>Fabric's {@link ServerLifecycleEvents#SERVER_STOPPING} is the exact
     * counterpart: it is injected at the HEAD of {@code MinecraftServer.shutdown}
     * — the one shutdown path both a dedicated server and an <i>integrated</i>
     * server take, so single-player "quit to title" (which stops the integrated
     * server while the client keeps running) fires it too. That is the case a
     * user actually hits.</p>
     *
     * <p><b>Batch U-O:</b> {@code CommonProxy.scenes.reset()} was missing here.
     * {@link mchorse.blockbuster.recording.scene.SceneManager#tick()} evicts
     * every {@code !playing} scene each tick, so a <i>stopped</i> scene
     * self-heals on the next world's first tick — but a scene still
     * {@code playing} at shutdown is never evicted, so it survived world
     * teardown bound to a dead {@code World}: the next world's
     * {@code /scene play <name>} resolved that corpse out of the cache instead
     * of loading its own {@code scenes/<name>.dat}, and {@code tick()} kept
     * driving it against a removed world. Extracted out of the registration
     * lambda so the teardown is drivable (and pinnable) headlessly.</p>
     *
     * <p>{@code CommonProxy.damage.reset()} is the third leg and is registered
     * next to the feed that fills it, in
     * {@code ActionHandler.registerServerEvents}. Its registration runs later
     * than this one and Fabric fires listeners in registration order, which
     * P277 now depends on (see below).</p>
     *
     * <p><b>P277 — "Save and Quit to Title" left every actor standing.</b>
     * Batch U-O's {@code scenes.reset()} fixed the <i>cache</i> half and only
     * that half: {@code reset()} is a bare {@code Map.clear()}, so it dropped
     * the {@link mchorse.blockbuster.recording.scene.Scene} objects without
     * running the stop path that despawns their actors, restores the terrain
     * the scene damaged, fires the scene's {@code stopCommand} (how a scene
     * puts weather/time back) and un-does the first-person target player's
     * inventory. Quitting to title therefore saved the actors <i>into</i> the
     * world, and they were all still there on rejoin.
     * {@link mchorse.blockbuster.recording.scene.SceneManager#stopAll()} is the
     * teardown; the two ordering constraints it carries are why the calls below
     * are in this order and not legacy's:</p>
     * <ul>
     * <li>it runs <b>before</b> {@code CommonProxy.manager.reset()}, because
     *     {@code RecordManager.stop} returns early for an actor missing from
     *     {@code players} and {@code reset()} clears that map — reset first and
     *     nothing is ever discarded;</li>
     * <li>it runs on {@code SERVER_STOPPING} (HEAD of
     *     {@code MinecraftServer.shutdown}) rather than {@code SERVER_STOPPED}
     *     (TAIL) or {@code ServerWorldEvents.UNLOAD}, because only the HEAD is
     *     ahead of that method's {@code save(…)} call. Actors are ordinary
     *     persistent entities; discarded after the save they would simply be
     *     reloaded.</li>
     * </ul>
     * <p>{@code manager.reset()} still runs after, and still flushes dirty
     * records to the world that is going away.</p>
     */
    public static void onServerStopping()
    {
        CommonProxy.scenes.stopAll();
        CommonProxy.manager.reset();
        CommonProxy.server = null;
    }

    /**
     * Server lifecycle + tick wiring for the recording engine (P109/P111).
     * Legacy equivalents: Forge's {@code DimensionManager} statics (server
     * binding), {@code ActionHandler.onServerTick} END phase (manager tick),
     * world-unload {@code reset()}.
     */
    private static void registerServerEvents()
    {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> CommonProxy.server = server);

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> onServerStopping());

        /* P277 — the sibling teardown paths, so a scene can never outlive the
         * thing it was playing in. Both are idempotent and both are no-ops in
         * the single-player quit-to-title case that SERVER_STOPPING already
         * covered a moment earlier; they exist for the boundaries that
         * SERVER_STOPPING does not cross.
         *
         * UNLOAD: a dimension going away while the server keeps running. Only
         * scenes bound to *that* world are stopped. Note this fires from
         * ServerWorld.close, i.e. after the shutdown save — which is precisely
         * why it cannot be the primary hook, only the dimension-scoped one.
         *
         * DISCONNECT: a player cast for first-person playback leaving a
         * dedicated server. Narrow by design — it stops only the scenes that
         * actually targeted them, never everyone else's. */
        ServerWorldEvents.UNLOAD.register((server, world) -> CommonProxy.scenes.stopAllIn(world));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            CommonProxy.scenes.stopScenesTargeting(handler.getPlayer()));

        ServerTickEvents.END_SERVER_TICK.register(ActionHandler::onServerTick);

        /* P162: wire the structure save-root seam + poll structure files for
         * hot-reload each server tick (legacy StructureMorph.checkStructures,
         * driven off the Forge server tick). */
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
        {
            ServerHandlerStructureRequest.saveRoot =
                () -> server.getSavePath(WorldSavePath.ROOT).toFile();
            StructureMorph.STRUCTURE_CACHE.clear();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
        {
            ServerHandlerStructureRequest.saveRoot = null;
            StructureMorph.STRUCTURE_CACHE.clear();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> StructureMorph.checkStructures());

        /* P128: scene world tick — matches legacy WorldTickEvent Phase.START
         * (server worlds). Runs scene actor spawning + unsafe actions before
         * the world ticks so block-changing actions land the same tick. */
        ServerTickEvents.START_WORLD_TICK.register(ActionHandler::onWorldTick);

        /* P22.1: TickHandler server dispatch — WORLD both phases; PLAYER via
         * server tick + player-list iteration (exactly how Forge fired
         * PlayerTickEvent on the server side) */
        ServerTickEvents.START_SERVER_TICK.register(server ->
        {
            TickHandler handler = tickHandler;

            handler.runRunnables(TickHandler.TickType.WORLD, Side.SERVER, TickHandler.Phase.START);
            handler.runRunnables(TickHandler.TickType.PLAYER, Side.SERVER, TickHandler.Phase.START);
        });

        ServerTickEvents.END_SERVER_TICK.register(server ->
        {
            TickHandler handler = tickHandler;

            handler.runRunnables(TickHandler.TickType.WORLD, Side.SERVER, TickHandler.Phase.END);
            handler.runRunnables(TickHandler.TickType.PLAYER, Side.SERVER, TickHandler.Phase.END);
        });

        /* P108: the action-capture event matrix (the mixin half lives in
         * blockbuster.mixins.json) */
        ActionHandler.register();

        /* P112: IRecording login / start-tracking sync hooks (the per-player
         * attachment itself rides PlayerEntityRecordingMixin) */
        CapabilityHandler.register();

        /* P52.1: bundled Metamorph morph handler — per-player tick update,
         * attack ability, kill-to-acquire, dimension/clone/login hooks (the
         * IMorphing attachment rides PlayerEntityMorphingMixin) */
        MorphHandler.register();
    }

    /**
     * S10 (P120–P122): the legacy server-command families, mounted through
     * the bundled McLib command framework's single Brigadier seam
     * ({@code McCommandBase.register}). Legacy registered them in
     * {@code CommonProxy.registerServerCommands}; remaining families
     * (/model, /model-block, /scene, /mount, /on-head, /spectate,
     * /itemnbt) land with their owning stages (P125/P132/P207 sweep).
     * {@code /damage} lands here (P113, its owning stage).
     */
    private static void registerCommands()
    {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
        {
            new CommandAction().register(dispatcher);
            new CommandRecord().register(dispatcher);
            new CommandDamage().register(dispatcher);
            new CommandScene().register(dispatcher);


            /* Bundled Aperture (S15 P186): server command /aperture play. Legacy
             * registered it from Aperture.serverLoad; always registered here
             * since Aperture is bundled. */
            new CommandAperture().register(dispatcher);

            /* Bundled McLib (S19 P207.5): /mclib config print|set (vanilla
             * CommandBase default level 4 — legacy does not override it) and
             * /cheats, which legacy McLib.serverInit registered ONLY when
             * server.isSinglePlayer(). The 1.20.4 equivalent of that gate is the
             * INTEGRATED registration environment — the command is
             * permission-free, so registration is the only gate there is.
             * ({@code RegistrationEnvironment.integrated} is package-private;
             * "not DEDICATED" is the same predicate — ALL is integrated too.) */
            new CommandMcLib().register(dispatcher);

            if (environment != CommandManager.RegistrationEnvironment.DEDICATED)
            {
                new CommandCheats().register(dispatcher);
            }

            /* Bundled Metamorph (S4 P60): /morph, /acquire_morph, /metamorph
             * reload. Legacy registered them from Metamorph.serverLoad; always
             * registered here since Metamorph is bundled. */
            new CommandMorph().register(dispatcher);
            new CommandAcquireMorph().register(dispatcher);
            new CommandMetamorph().register(dispatcher);

            new CommandOnHead().register(dispatcher);
            new CommandSpectate().register(dispatcher);
            new CommandMount().register(dispatcher);

            /* S19 P207 sweep: /modelblock morph|property (server, op-level 2).
             * Legacy CommandModelBlock.getName() == "modelblock". */
            new CommandModelBlock().register(dispatcher);
        });
    }

    /**
     * The {@code actor.tracking_range} config value (1.12.2 semantics: a
     * distance in <b>blocks</b>, default 256, bounds 64…1024) converted to the
     * <b>chunks</b> that 1.20.4's {@code EntityType.Builder.maxTrackingRange}
     * takes — vanilla multiplies it straight back by 16 in
     * {@code ThreadedAnvilChunkStorage.loadEntity}.
     *
     * <p>Rounds <b>up</b>: a value that is not a clean multiple of 16 must never
     * track <i>less</i> far than the user asked for (256 → 16, 300 → 19 = 304
     * blocks, 1024 → 64). Floored at one chunk so a hand-edited config of 0
     * cannot produce an untrackable entity.</p>
     *
     * <p>Like 1.12.2, this is read <b>once at registration</b>: the value is
     * baked into the immutable {@code EntityType}, so a config change needs a
     * restart. Legacy had exactly the same constraint (Forge read the config in
     * pre-init and {@code CommonProxy.load} passed the value to
     * {@code EntityRegistry.registerModEntity} once).</p>
     */
    public static int trackingChunks(int blocks)
    {
        return Math.max(1, MathHelper.ceilDiv(blocks, 16));
    }

    /**
     * Registry-touching setup, idempotent so headless tests (which don't run
     * mod entrypoints) can call it too.
     *
     * <p>In production this runs <b>after</b> {@link #registerConfigs()} so the
     * entity types can be built from the loaded {@code actor.tracking_range};
     * headless tests call it on its own and get the inline defaults, which are
     * the legacy defaults.</p>
     */
    public static synchronized void registerContent()
    {
        /* Bundled Aperture (S15 P171/P174): fixture + modifier registries in
         * the legacy byte-id order (idempotent, before the ACTOR guard so
         * tests reusing registerContent always get them) */
        mchorse.aperture.CommonProxy.preLoad();

        /* S22 P240: Blockbuster's own "tracker" modifier, byte id 10 — must
         * follow Aperture's ten. Until this landed, a legacy camera profile
         * containing a tracker modifier lost it on load (legacy
         * CameraHandler.registerModifiers). */
        TrackerModifierWiring.install();

        /* P98/P99: hand items (register/playback/actor_config/gun) then the
         * creative tab (icon + entries reference the registered items). Both are
         * idempotent; placed before the ACTOR guard so registerContent reuse in
         * tests always re-establishes them. */
        BlockbusterItems.register();
        BlockbusterTab.register();

        /* Bundled Metamorph (S4 P56.1): the morph ghost entity type. Also
         * idempotent and outside the ACTOR guard, for the same reason. */
        MetamorphCommon.registerContent();

        if (ACTOR != null)
        {
            return;
        }

        ACTOR = Registry.register(Registries.ENTITY_TYPE, new Identifier(MOD_ID, "actor"),
            EntityType.Builder.<EntityActor>create((type, world) -> new EntityActor(type, world), SpawnGroup.MISC)
                .setDimensions(0.6F, 1.8F)
                /* Legacy CommonProxy.registerEntityWithEgg (line 241):
                 * trackingRange = Blockbuster.actorTrackingRange.get() (BLOCKS),
                 * updateFrequency = 3. */
                .maxTrackingRange(trackingChunks(actorTrackingRange.get()))
                .trackingTickInterval(3)
                .build("blockbuster:actor"));

        FabricDefaultAttributeRegistry.register(ACTOR, MobEntity.createMobAttributes());

        /* P93 step 3 / P99: the actor spawn egg. Legacy egg colors came from
         * CommonProxy.registerEntityWithEgg(..., 0xffc1ab33, 0xffa08d2b); the
         * alpha byte is dropped because SpawnEggItem takes plain RGB. */
        ACTOR_SPAWN_EGG = Registry.register(Registries.ITEM, new Identifier(MOD_ID, "actor_spawn_egg"),
            new SpawnEggItem(ACTOR, 0xC1AB33, 0xA08D2B, new Item.Settings()));

        /* Legacy CommonProxy.preLoad registration order (it is what the creative
         * tab's generic item run iterated): director, then model + its 16 items,
         * then the two chroma blocks. */
        registerDirectorBlock();
        registerModelBlock();
        registerBlocks();
    }

    /**
     * P94 / P129: the director block ({@code blockbuster:director}) + its block
     * entity type + a plain {@code BlockItem}. Legacy {@code BlockDirector}:
     * unbreakable ({@code setBlockUnbreakable} → hardness −1), resistance
     * 6,000,000. The BE type is registered under the legacy TE string
     * {@code blockbuster_director_tile_entity} (a save contract — do not
     * "modernize" to {@code blockbuster:director}). Unlike the model block, the
     * director block has a normal {@link net.minecraft.item.BlockItem}.
     */
    public static void registerDirectorBlock()
    {
        /* Idempotent: registries reject a duplicate id, and the creative-tab
         * test registers the director block on its own (P94 is post-P99). */
        if (DIRECTOR_BLOCK != null)
        {
            return;
        }

        AbstractBlock.Settings settings = AbstractBlock.Settings.create()
            .strength(-1.0F, 6000000.0F)
            .nonOpaque();

        DIRECTOR_BLOCK = Registry.register(Registries.BLOCK, new Identifier(MOD_ID, "director"),
            new BlockDirector(settings));

        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "director"),
            new BlockItem(DIRECTOR_BLOCK, new Item.Settings()));

        DIRECTOR_TILE = Registry.register(Registries.BLOCK_ENTITY_TYPE,
            LegacyBlockEntityIds.DIRECTOR,
            FabricBlockEntityTypeBuilder
                .create(TileEntityDirector::new, DIRECTOR_BLOCK)
                .build());
    }

    /**
     * P95: the model block ({@code blockbuster:model}) + its block entity type +
     * the 16 placement items. Legacy {@code BlockModel}: {@code Material.ROCK},
     * resistance 6000000, no baked model (BE-rendered), luminance = LIGHT state
     * value.
     *
     * <p>Item layout is legacy-exact ({@code CommonProxy.preLoad} lines 149-154):
     * {@code modelBlockItems[0]} is a plain {@code ItemBlock}/{@link
     * net.minecraft.item.BlockItem} sharing the <b>block's</b> id
     * ({@code blockbuster:model}), and {@code modelBlockItems[1..15]} are
     * {@link mchorse.blockbuster.common.item.ItemBlockModel} plain items
     * ({@code model1}…{@code model15}). Only index 0 is a {@code BlockItem}, so
     * {@code MODEL_BLOCK.asItem()} resolves to {@code blockbuster:model}.</p>
     */
    private static void registerModelBlock()
    {
        AbstractBlock.Settings settings = AbstractBlock.Settings.create()
            .strength(0.0F, 6000000.0F)
            .luminance(state -> state.get(BlockModel.LIGHT))
            .nonOpaque()
            /* The block has no baked model — it is drawn entirely by its block
             * entity renderer, and models/block/model.json is a bare
             * builtin/entity with no textures at all. Break particles are cut
             * from the block's particle texture, so there was nothing to cut
             * from and the burst came out as the missing-texture checkerboard.
             * The mining crack particles were already silent: that path skips
             * BlockRenderType.INVISIBLE, which BlockModel.getRenderType returns.
             * This is the matching opt-out for the break burst. */
            .noBlockBreakParticles();

        MODEL_BLOCK = Registry.register(Registries.BLOCK, new Identifier(MOD_ID, "model"),
            new BlockModel(settings));

        /* Legacy: ForgeRegistries.ITEMS.register(modelBlockItems[0] =
         * new ItemBlock(model).setRegistryName(model.getRegistryName())) */
        modelBlockItems[0] = Registry.register(Registries.ITEM, new Identifier(MOD_ID, "model"),
            new BlockItem(MODEL_BLOCK, new Item.Settings()));

        for (int i = 1; i < modelBlockItems.length; i++)
        {
            modelBlockItems[i] = Registry.register(Registries.ITEM, new Identifier(MOD_ID, "model" + i),
                new ItemBlockModel(MODEL_BLOCK, i));
        }

        /* Legacy BlockModel.getItemStack(meta) -> new ItemStack(modelBlockItems[meta]).
         * Installing the provider is what makes pick-block and the break drop
         * yield a stack at all (BlockModel clamps out-of-range light to 0, like
         * legacy's `meta >= 0 && meta <= 15` guard). */
        BlockModel.itemStackProvider =
            light -> new ItemStack(modelBlockItems[light >= 0 && light < modelBlockItems.length ? light : 0]);

        /* P93.1: the BE type id is the legacy TE string kept verbatim as the
         * path ("blockbuster_model_tile_entity"), exactly like the director's —
         * it is a save contract, not a name to modernize. Builds before
         * 2026-07-26 registered it as plain `blockbuster:model`; that spelling
         * (and the two 1.12.2 ones) still load, through
         * LegacyBlockEntityIds + BlockEntityMixin. */
        MODEL_BLOCK_TILE = Registry.register(Registries.BLOCK_ENTITY_TYPE,
            LegacyBlockEntityIds.MODEL,
            FabricBlockEntityTypeBuilder
                .create(TileEntityModel::new, MODEL_BLOCK)
                .build());
    }

    /**
     * P97: register the chroma blocks + their block items. Legacy
     * {@code CommonProxy.registerObjects} (ids {@code green} / {@code dim_green}).
     * {@code green} emits light level 1 ({@code luminance(1)}), {@code dim_green}
     * emits none ({@code luminance(0)}); full-bright <i>rendering</i> is a
     * separate lightmap override (P97 {@code WorldRendererLightmapMixin}). Runs
     * once inside the {@code ACTOR}-guarded region of {@link #registerContent()}.
     */
    private static void registerBlocks()
    {
        greenBlock = Registry.register(Registries.BLOCK, new Identifier(MOD_ID, "green"),
            new BlockGreen(
                BlockGreen.chromaSettings().luminance(state -> 1)));
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "green"),
            new ItemBlockGreen(greenBlock, new Item.Settings()));

        dimGreenBlock = Registry.register(Registries.BLOCK, new Identifier(MOD_ID, "dim_green"),
            new BlockDimGreen(
                BlockGreen.chromaSettings().luminance(state -> 0)));
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "dim_green"),
            new ItemBlockGreen(dimGreenBlock, new Item.Settings()));

        /* S17 gun item + projectile entity type (legacy S8 registration seam).
         * The gun item itself is registered once by BlockbusterItems.register()
         * above — this field just aliases it for the S17 call sites. */
        GUN = BlockbusterItems.GUN;

        /* P93.1: legacy CommonProxy.load line 194 —
         * EntityRegistry.registerModEntity(new ResourceLocation("blockbuster:projectile"),
         *     EntityGunProjectile.class, "blockbuster.GunProjectile", ID++,
         *     Blockbuster.instance, actorTrackingRange.get(), 10, true).
         * The id is `projectile`, not `gun_projectile` (that string is a packet
         * channel — a different registry, see plan/network-ledger.md), and the
         * update frequency is 10, not the actor's 3. Velocity updates were on;
         * 1.20.4 has no builder switch for that — EntityType.alwaysUpdateVelocity()
         * is a hard-coded vanilla-only exclusion list, so every modded type
         * (including this one) already reports true. */
        GUN_PROJECTILE = Registry.register(Registries.ENTITY_TYPE, new Identifier(MOD_ID, "projectile"),
            EntityType.Builder.<EntityGunProjectile>create(
                    (type, world) -> new EntityGunProjectile(type, world), SpawnGroup.MISC)
                .setDimensions(0.25F, 0.25F)
                .maxTrackingRange(trackingChunks(actorTrackingRange.get()))
                .trackingTickInterval(10)
                .build("blockbuster:projectile"));
    }
}
