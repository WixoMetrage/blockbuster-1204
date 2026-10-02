package mchorse.aperture;

import mchorse.aperture.utils.mclib.ValueShaderOption;
import mchorse.mclib.commands.utils.L10n;
import mchorse.mclib.config.ConfigBuilder;
import mchorse.mclib.config.values.Value;
import mchorse.mclib.config.values.ValueBoolean;
import mchorse.mclib.config.values.ValueFloat;
import mchorse.mclib.config.values.ValueInt;
import mchorse.mclib.config.values.ValueRL;
import mchorse.mclib.config.values.ValueString;
import mchorse.mclib.events.RegisterConfigEvent;
import mchorse.mclib.utils.KeyCodes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.util.function.Supplier;

/**
 * Bundled Aperture static holder (roadmap S15, partial — P186 owns the full
 * config schema).
 *
 * <p>Legacy {@code mchorse.aperture.Aperture} was the Forge {@code @Mod}
 * class whose config {@code Value*} statics were populated inside
 * {@code onConfigRegister(RegisterConfigEvent)}. The S15 non-GUI core
 * (P169–P181) compiles against these statics verbatim
 * ({@code Aperture.duration.get()} in {@code Envelope}/{@code RemapperModifier},
 * {@code Aperture.smoothClampPitch} in {@code Angle}, the runner/control
 * configs, flight keybinds), so this file declares the consumed subset now,
 * initialized inline with the legacy config defaults so pre-registration
 * reads are safe (legacy left most of them null until config registration
 * ran at startup — {@code Angle}'s null-guard on {@code smoothClampPitch} is
 * preserved anyway).</p>
 *
 * <p><b>Deliberate port deviation</b> (was a P186 TODO, resolved): legacy built
 * each {@code Value} <i>inside</i> {@code onConfigRegister}, so every static was
 * null until config registration ran. Here the statics stay inline-initialized
 * and {@link #onConfigRegister(RegisterConfigEvent)} registers those same
 * instances into the module — identity is preserved, so pre-registration reads
 * from the S15 core (and from plain-JUnit data-class tests, which never run
 * config registration) are safe instead of NPE-prone. Categories, ids, order,
 * defaults and {@code markClientSide()} calls match 1.8.2 exactly. Legacy's
 * {@code proxy.registerClientConfig} categories are folded in too: {@code minema}
 * (S18 P202) and {@code optifine} (S21 P218); only its
 * "hide the category when no shader mod is installed" line is deferred to
 * {@link #applyShaderpackVisibility(boolean)}, which the client entrypoint calls
 * after registration.</p>
 *
 * <p>Flight keybind values live in the legacy LWJGL2 {@code Keyboard} code
 * domain, like every persisted keybind since P39 — {@code Keys.keyDownPoller}
 * ({@code GuiUtils.isKeyDown}) polls in that domain, and 1.12.2
 * {@code aperture.json} keybind values parse 1:1 with no P186 migration.
 * Defaults are written as {@code glfwToLwjgl2(GLFW.…)} purely for
 * readability.</p>
 *
 * Legacy source: .tools/legacy-src/aperture/src/main/java/mchorse/aperture/Aperture.java
 */
public class Aperture
{
    /* Mod info */
    public static final String MOD_ID = "aperture";
    public static final String MODNAME = "Aperture";
    public static final String VERSION = "1.8.2";

    /* Mod's logger */
    public static Logger LOGGER = LogManager.getLogger("Aperture");

    public static L10n l10n = new L10n(MOD_ID);

    /* Configuration (legacy defaults; see class javadoc) */

    /* Camera (category "general") */
    public static ValueInt duration = new ValueInt("duration", 30, 1, 1000);
    public static ValueBoolean spectator = new ValueBoolean("spectator", true);
    public static ValueFloat stepFactor = new ValueFloat("step_factor", 0.01F, 0, 10);
    public static ValueFloat rotateFactor = new ValueFloat("rotate_factor", 0.1F, 0, 10);
    public static ValueString commandName = new ValueString("command_name", "camera");
    public static ValueBoolean debugTicks = new ValueBoolean("debug_ticks", false);
    public static ValueBoolean profileRender = new ValueBoolean("profile_render", true);
    public static ValueBoolean profileAutoSave = new ValueBoolean("auto_save", true);
    public static ValueBoolean essentialsTeleport = new ValueBoolean("essentials_tp", false);
    /* wixo.1 (CDC §5, C2): a drag (global or per fixture) starts over at every cut */
    public static ValueBoolean dragResetOnCut = new ValueBoolean("drag_reset_on_cut", true);

    /* Minema/recorder client config (category "minema", client-side).
     * Legacy: ClientProxy.registerClientConfig →
     * builder.category("minema").getBoolean("default_profile_name", false),
     * stashed as Aperture.minemaDefaultProfileName and read by
     * GuiMinemaPanel.getFilename (S18 P202). */
    public static ValueBoolean minemaDefaultProfileName = new ValueBoolean("default_profile_name", false);

    /* Shader-curve client config (category "optifine", client-side).
     * Legacy: ClientProxy.registerClientConfig lines 287-289 →
     * builder.category("optifine").getBoolean("shader_option_curve", true)
     * marked .invisible(), plus the visible ValueShaderOption("option") GUI
     * row. The category name and key are the 1.12.2 ones on purpose — an
     * "iris" rename would orphan every existing config/aperture.json (S21
     * P218). Gates the option-uniform machinery and, with it, the
     * double-gated shader_sun_path_rotation curve. */
    public static ValueBoolean optifineShaderOptionCurve = new ValueBoolean("shader_option_curve", true);

    /**
     * The {@code optifine} category node, captured during registration so
     * {@link #applyShaderpackVisibility(boolean)} can hide it later. Null
     * before {@link #onConfigRegister(RegisterConfigEvent)} has run.
     */
    private static Value optifineCategory;

    /* Camera outside mode (category "outside") */
    public static ValueBoolean outside = new ValueBoolean("enabled", false);
    public static ValueBoolean outsideHidePlayer = new ValueBoolean("hide_player", false);
    public static ValueBoolean outsideSky = new ValueBoolean("sky", true);

    /* Server-side op config (category "aperture" of the op-access config) */
    public static ValueBoolean opCameraEditor = new ValueBoolean("camera_editor", true);

    /* Camera editor (category "editor"; legacy registration defaults) */
    public static ValueBoolean editorSync = new ValueBoolean("sync", false);
    public static ValueBoolean editorLoop = new ValueBoolean("loop", false);
    public static ValueBoolean editorOverlay = new ValueBoolean("overlay", false);
    public static ValueRL editorOverlayRL = new ValueRL("overlay_rl", null);
    public static ValueBoolean editorF1Tooltip = new ValueBoolean("f1_tooltip", true);
    public static ValueBoolean editorDisplayPosition = new ValueBoolean("position", false);
    public static ValueInt editorGuidesColor = new ValueInt("guides_color", 0xcccc0000).colorAlpha();
    public static ValueBoolean editorRuleOfThirds = new ValueBoolean("rule_of_thirds", false);
    public static ValueBoolean editorCenterLines = new ValueBoolean("center_lines", false);
    public static ValueBoolean editorCrosshair = new ValueBoolean("crosshair", false);
    public static ValueBoolean editorLetterbox = new ValueBoolean("letter_box", false);
    public static ValueBoolean editorSeconds = new ValueBoolean("seconds", false);
    public static ValueString editorLetterboxAspect = new ValueString("aspect_ratio", "21:9");
    public static ValueBoolean editorHideChat = new ValueBoolean("hide_chat", true);
    public static ValueInt editorAutoSave = new ValueInt("auto_save", 0, 0, 600);

    /* Flight mode keybinds (category "flight"; LWJGL2 codes, see javadoc) */
    public static ValueInt flightForward = new ValueInt("forward", KeyCodes.glfwToLwjgl2(GLFW.GLFW_KEY_W));
    public static ValueInt flightBackward = new ValueInt("backward", KeyCodes.glfwToLwjgl2(GLFW.GLFW_KEY_S));
    public static ValueInt flightLeft = new ValueInt("left", KeyCodes.glfwToLwjgl2(GLFW.GLFW_KEY_A));
    public static ValueInt flightRight = new ValueInt("right", KeyCodes.glfwToLwjgl2(GLFW.GLFW_KEY_D));
    public static ValueInt flightUp = new ValueInt("up", KeyCodes.glfwToLwjgl2(GLFW.GLFW_KEY_SPACE));
    public static ValueInt flightDown = new ValueInt("down", KeyCodes.glfwToLwjgl2(GLFW.GLFW_KEY_LEFT_SHIFT));
    public static ValueInt flightCameraUp = new ValueInt("camera_up", KeyCodes.glfwToLwjgl2(GLFW.GLFW_KEY_UP));
    public static ValueInt flightCameraDown = new ValueInt("camera_down", KeyCodes.glfwToLwjgl2(GLFW.GLFW_KEY_DOWN));
    public static ValueInt flightCameraLeft = new ValueInt("camera_left", KeyCodes.glfwToLwjgl2(GLFW.GLFW_KEY_LEFT));
    public static ValueInt flightCameraRight = new ValueInt("camera_right", KeyCodes.glfwToLwjgl2(GLFW.GLFW_KEY_RIGHT));
    public static ValueInt flightCameraFovMinus = new ValueInt("fov_minus", KeyCodes.glfwToLwjgl2(GLFW.GLFW_KEY_LEFT_BRACKET));
    public static ValueInt flightCameraFovPlus = new ValueInt("fov_plus", KeyCodes.glfwToLwjgl2(GLFW.GLFW_KEY_RIGHT_BRACKET));
    public static ValueInt flightCameraRollMinus = new ValueInt("roll_minus", KeyCodes.glfwToLwjgl2(GLFW.GLFW_KEY_APOSTROPHE));
    public static ValueInt flightCameraRollPlus = new ValueInt("roll_plus", KeyCodes.glfwToLwjgl2(GLFW.GLFW_KEY_BACKSLASH));
    public static ValueInt flightCameraSpeedMinus = new ValueInt("speed_minus", KeyCodes.glfwToLwjgl2(GLFW.GLFW_KEY_O));
    public static ValueInt flightCameraSpeedPlus = new ValueInt("speed_plus", KeyCodes.glfwToLwjgl2(GLFW.GLFW_KEY_P));

    /* Smooth camera (category "smooth") */
    public static ValueBoolean smooth = new ValueBoolean("enabled", false);
    public static ValueBoolean smoothClampPitch = new ValueBoolean("clamp", true);
    public static ValueFloat smoothFricX = new ValueFloat("x_friction", 0.92F, 0.0F, 1.0F);
    public static ValueFloat smoothFricY = new ValueFloat("y_friction", 0.92F, 0.0F, 1.0F);
    public static ValueFloat rollFriction = new ValueFloat("roll_friction", 0.985F, 0.0F, 0.99999F);
    public static ValueFloat rollFactor = new ValueFloat("roll_speed", 0.01F, 0.0F, 10.0F);
    public static ValueFloat fovFriction = new ValueFloat("fov_friction", 0.985F, 0.0F, 0.99999F);
    public static ValueFloat fovFactor = new ValueFloat("fov_speed", 0.075F, 0.0F, 10.0F);
    /* wixo.1 (CDC §5, C6): roll/FOV smoothing speed is matched to legacy at this frame rate, then frame-rate independent */
    public static ValueInt smoothReferenceFps = new ValueInt("reference_fps", 60, 1, 1000);

    /* Port seams — the legacy code reads client-only statics
     * (ClientProxy.control.roll, gameSettings.fovSetting) from common data
     * classes ({@code Angle.set(PlayerEntity)}, {@code RenderFrame}). The
     * client entrypoint installs the real suppliers (P177/P178 —
     * mchorse.aperture.client.ApertureClient); headless defaults keep data
     * classes plain-JUnit testable. */

    /** Current camera roll — legacy {@code ClientProxy.control.roll}. */
    public static Supplier<Float> currentRoll = () -> 0F;

    /**
     * Current effective FOV — legacy {@code gameSettings.fovSetting}.
     * Client install prefers the camera-driven override (playback, editor
     * flight, smooth FOV) so fixture capture sees the live FOV, not only
     * the integer game option.
     */
    public static Supplier<Float> currentFov = () -> 70F;

    /**
     * Legacy {@code Aperture.onConfigRegister} (P186) — registers the pre-built
     * {@code Value*} statics into the {@code aperture} config module in the
     * exact legacy id/category/order, plus the syncable {@code camera_editor}
     * op-access toggle. Subscribed to {@code ConfigManager.REGISTER_CALLBACKS}
     * from {@code Blockbuster.registerConfigs}.
     *
     * <p>The values are the same pre-built instances the S15 core already
     * compiled against ({@code Aperture.duration.get()} etc.), so registration
     * only wires them to the module (folder/persistence/GUI); references stay
     * valid. Every {@code general/outside/editor/flight/smooth} category is
     * {@code markClientSide()}'d exactly as legacy.</p>
     *
     * <p>The minema/optifine client categories ({@code proxy.registerClientConfig})
     * are registered here as well (S18 P202 / S21 P218).</p>
     */
    public static void onConfigRegister(RegisterConfigEvent event)
    {
        /* Op-access: syncable camera_editor toggle (drives canUseCameraEditor) */
        event.opAccess.category(MOD_ID).register(opCameraEditor);
        opCameraEditor.syncable();

        ConfigBuilder builder = event.createBuilder(MOD_ID);

        /* Camera (general) */
        builder.category("general").register(duration);
        builder.register(spectator);
        builder.register(stepFactor);
        builder.register(rotateFactor);
        builder.register(commandName);
        builder.register(debugTicks);
        builder.register(profileRender);
        builder.register(profileAutoSave);
        builder.register(essentialsTeleport);
        builder.register(dragResetOnCut);

        /* Processing camera command name (legacy: empty sanitized → "camera") */
        if (sanitizeCommandName(commandName.get()).isEmpty())
        {
            commandName.set("camera");
        }

        builder.getCategory().markClientSide();

        /* Camera outside mode */
        builder.category("outside").register(outside);
        builder.register(outsideHidePlayer);
        builder.register(outsideSky);

        builder.getCategory().markClientSide();

        /* Camera editor (legacy registration order) */
        builder.category("editor").register(editorSync);
        builder.register(editorLoop);
        builder.register(editorOverlay);
        builder.register(editorOverlayRL);
        builder.register(editorF1Tooltip);
        builder.register(editorDisplayPosition);
        builder.register(editorGuidesColor);
        builder.register(editorRuleOfThirds);
        builder.register(editorCenterLines);
        builder.register(editorCrosshair);
        builder.register(editorLetterbox);
        builder.register(editorLetterboxAspect);
        builder.register(editorHideChat);
        builder.register(editorSeconds);
        builder.register(editorAutoSave);

        builder.getCategory().markClientSide();

        /* Flight mode keybinds (config ints, marked as keybind subtype) */
        builder.category("flight").register(flightForward.keybind());
        builder.register(flightBackward.keybind());
        builder.register(flightLeft.keybind());
        builder.register(flightRight.keybind());
        builder.register(flightUp.keybind());
        builder.register(flightDown.keybind());
        builder.register(flightCameraUp.keybind());
        builder.register(flightCameraDown.keybind());
        builder.register(flightCameraLeft.keybind());
        builder.register(flightCameraRight.keybind());
        builder.register(flightCameraFovMinus.keybind());
        builder.register(flightCameraFovPlus.keybind());
        builder.register(flightCameraRollMinus.keybind());
        builder.register(flightCameraRollPlus.keybind());
        builder.register(flightCameraSpeedMinus.keybind());
        builder.register(flightCameraSpeedPlus.keybind());

        builder.getCategory().markClientSide();

        /* Smooth camera */
        builder.category("smooth").register(smooth);
        builder.register(smoothClampPitch);
        builder.register(smoothFricX);
        builder.register(smoothFricY);
        builder.register(rollFriction);
        builder.register(rollFactor);
        builder.register(fovFriction);
        builder.register(fovFactor);
        builder.register(smoothReferenceFps);

        builder.getCategory().markClientSide();

        /* Minema/recorder client config (legacy proxy.registerClientConfig).
         * The recorder's own video.* category is registered by Blockbuster's
         * config (S19 P208); this keeps the legacy minema.default_profile_name
         * key/category for config-file parity (S18 P202). */
        builder.category("minema").register(minemaDefaultProfileName);

        builder.getCategory().markClientSide();

        /* Shader curves (legacy proxy.registerClientConfig, "optifine"
         * category — S21 P218). Legacy order, verbatim: the real boolean
         * first (then hidden — the ValueShaderOption row is what the user
         * sees), then the GUI row, then markClientSide. */
        builder.category("optifine").register(optifineShaderOptionCurve);
        optifineShaderOptionCurve.invisible();
        builder.register(new ValueShaderOption("option").clientSide());

        optifineCategory = builder.getCategory();
        optifineCategory.markClientSide();
    }

    /**
     * Legacy {@code ClientProxy.registerClientConfig} line 292:
     * {@code if (!OptifineHelper.shaderpackSupported) builder.getCategory().invisible();}
     * — hide the whole {@code optifine} category on an install with no shader
     * stack at all (no Optifine then, no Iris now).
     *
     * <p>It cannot happen inside {@link #onConfigRegister} the way it did in
     * legacy: the probe lives in the <b>client</b> source set
     * ({@code mchorse.aperture.utils.OptifineHelper.shaderpackSupported} →
     * {@code IrisCompat.isLoaded()}) and, more importantly, config registration
     * runs from the <i>main</i> entrypoint, before the client entrypoint exists
     * to install any seam. So the gate is applied afterwards by the client
     * entrypoint, exactly like {@code BlockbusterClient.registerConfigButtons}
     * fixes up the button rows post-registration (P210).</p>
     *
     * <p>One-way, like legacy — {@code Value} has no {@code visible()} — and a
     * no-op before registration has run.</p>
     *
     * @param shaderpackSupported {@code OptifineHelper.shaderpackSupported}
     */
    public static void applyShaderpackVisibility(boolean shaderpackSupported)
    {
        if (!shaderpackSupported && optifineCategory != null)
        {
            optifineCategory.invisible();
        }
    }

    /**
     * Legacy command-name sanitizer (used both in {@link #onConfigRegister}'s
     * empty-check and by the P186 {@code /camera} client-command registration).
     * Trims and strips every non {@code [\w\d_\-]} run; an empty result means
     * the configured name is unusable and the command falls back to
     * {@code "camera"}.
     */
    public static String sanitizeCommandName(String name)
    {
        return name.trim().replaceAll("[^\\w\\d_\\-]+", "");
    }

    /**
     * The effective {@code /camera} command name: the sanitized configured
     * name, or {@code "camera"} when the sanitized name is empty (legacy
     * fallback). Read at client-command registration time.
     */
    public static String cameraCommandName()
    {
        String sanitized = sanitizeCommandName(commandName.get());

        return sanitized.isEmpty() ? "camera" : sanitized;
    }
}
