package mchorse.aperture.client;

import java.util.Arrays;
import java.util.function.Supplier;
import mchorse.aperture.Aperture;
import mchorse.aperture.ClientProxy;
import mchorse.aperture.camera.CameraRunner;
import mchorse.aperture.camera.FixtureRegistry;
import mchorse.aperture.camera.ModifierRegistry;
import mchorse.aperture.camera.curves.BrightnessCurve;
import mchorse.aperture.camera.curves.ShaderCenterDepthCurve;
import mchorse.aperture.camera.data.Position;
import mchorse.aperture.camera.data.RenderFrame;
import mchorse.aperture.camera.destination.AbstractDestination;
import mchorse.aperture.camera.fixtures.CircularFixture;
import mchorse.aperture.camera.fixtures.DollyFixture;
import mchorse.aperture.camera.fixtures.IdleFixture;
import mchorse.aperture.camera.fixtures.KeyframeFixture;
import mchorse.aperture.camera.fixtures.ManualFixture;
import mchorse.aperture.camera.fixtures.NullFixture;
import mchorse.aperture.camera.fixtures.PathFixture;
import mchorse.aperture.camera.modifiers.AngleModifier;
import mchorse.aperture.camera.modifiers.DollyZoomModifier;
import mchorse.aperture.camera.modifiers.DragModifier;
import mchorse.aperture.camera.modifiers.EntityModifier;
import mchorse.aperture.camera.modifiers.FollowModifier;
import mchorse.aperture.camera.modifiers.LookModifier;
import mchorse.aperture.camera.modifiers.MathModifier;
import mchorse.aperture.camera.modifiers.OrbitModifier;
import mchorse.aperture.camera.modifiers.RemapperModifier;
import mchorse.aperture.camera.modifiers.ShakeModifier;
import mchorse.aperture.camera.modifiers.TranslateModifier;
import mchorse.aperture.client.gui.GuiCameraEditor;
import mchorse.aperture.client.gui.GuiModifiersManager;
import mchorse.aperture.client.gui.panels.GuiAbstractFixturePanel;
import mchorse.aperture.client.gui.panels.GuiCircularFixturePanel;
import mchorse.aperture.client.gui.panels.GuiDollyFixturePanel;
import mchorse.aperture.client.gui.panels.GuiIdleFixturePanel;
import mchorse.aperture.client.gui.panels.GuiKeyframeFixturePanel;
import mchorse.aperture.client.gui.panels.GuiManualFixturePanel;
import mchorse.aperture.client.gui.panels.GuiNullFixturePanel;
import mchorse.aperture.client.gui.panels.GuiPathFixturePanel;
import mchorse.aperture.client.gui.panels.modifiers.GuiAngleModifierPanel;
import mchorse.aperture.client.gui.panels.modifiers.GuiDollyZoomModifierPanel;
import mchorse.aperture.client.gui.panels.modifiers.GuiDragModifierPanel;
import mchorse.aperture.client.gui.panels.modifiers.GuiFollowModifierPanel;
import mchorse.aperture.client.gui.panels.modifiers.GuiLookModifierPanel;
import mchorse.aperture.client.gui.panels.modifiers.GuiMathModifierPanel;
import mchorse.aperture.client.gui.panels.modifiers.GuiOrbitModifierPanel;
import mchorse.aperture.client.gui.panels.modifiers.GuiRemapperModifierPanel;
import mchorse.aperture.client.gui.panels.modifiers.GuiShakeModifierPanel;
import mchorse.aperture.client.gui.panels.modifiers.GuiTranslateModifierPanel;
import mchorse.aperture.events.CameraProfileChangedEvent;
import mchorse.aperture.utils.EntitySelector;
import mchorse.aperture.utils.OptifineHelper;
import mchorse.aperture.utils.mclib.ValueShaderOption;
import mchorse.blockbuster.aperture.CameraHandler;
import mchorse.blockbuster.client.aperture.TrackerModifierClientWiring;
import mchorse.blockbuster.client.compat.iris.ShaderCurveClientWiring;
import mchorse.blockbuster.client.video.MinemaBackend;
import mchorse.blockbuster.client.video.TrackingHooks;
import mchorse.blockbuster.client.video.VideoCaptureWiring;
import mchorse.blockbuster.common.item.ItemPlayback;
import mchorse.blockbuster.recording.scene.SceneLocation;
import mchorse.mclib.client.gui.framework.elements.buttons.GuiToggleElement;
import mchorse.mclib.config.gui.ConfigGuiProviders;
import mchorse.mclib.utils.Color;
import mchorse.mclib.utils.OpHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import org.lwjgl.glfw.GLFW;

/**
 * Bundled Aperture client wiring (S15) — the P178 "CameraSeam" singleton:
 * installs the main-source seams (roll/fov capture, entity finder,
 * destination folders), registers the client fixture/modifier info tables
 * (legacy {@code ClientProxy.load} colors) and the client tick hooks, and
 * mediates between runner/control/smooth-camera and the render mixins.
 *
 * Invoked from {@code BlockbusterClient.onInitializeClient}.
 */
public class ApertureClient
{
    /**
     * P183 seam: the camera editor's preview position (non-null while the
     * editor is open and driving the camera).
     */
    public static Supplier<Position> editorPosition = () -> null;

    /**
     * P183: open-editor keybind (legacy KeyboardHandler.toggleCameraEditor,
     * default C). The full keybind family arrives with P186.
     */
    public static KeyBinding openCameraEditorKey;

    private static boolean initialized;

    public static synchronized void init()
    {
        if (initialized)
        {
            return;
        }

        initialized = true;

        ClientProxy.load();

        /* Main-source seams (see each seam's javadoc) */
        Aperture.currentRoll = () -> ClientProxy.control.roll;
        /* Effective FOV: prefer the camera-driven override (playback / editor
         * flight / smooth FOV) so Angle.set(PlayerEntity) and getPosition()
         * capture flight MMB FOV edits. Legacy wrote gameSettings.fovSetting
         * during flight; the port keeps that option intact and routes FOV
         * through getFovOverride instead. */
        Aperture.currentFov = () ->
        {
            Float override = getFovOverride();

            if (override != null)
            {
                return override;
            }

            MinecraftClient mc = MinecraftClient.getInstance();

            return mc == null || mc.options == null ? 70F : mc.options.getFov().getValue().floatValue();
        };

        EntityModifier.clientPlayer = () ->
        {
            MinecraftClient mc = MinecraftClient.getInstance();

            return mc == null ? null : mc.player;
        };
        EntityModifier.clientEntityFinder = selector ->
        {
            MinecraftClient mc = MinecraftClient.getInstance();

            if (mc == null || mc.player == null)
            {
                return null;
            }

            try
            {
                return EntitySelector.matchEntities(mc.player, selector, Entity.class);
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        };

        AbstractDestination.clientCameras = ClientProxy::getClientCameras;

        /* P180: curve system. Gamma bypasses the (clamped) SimpleOption via
         * this seam so the vanilla-safe brightness curve stays headless-testable;
         * the render-curve driver hangs off the CameraRunner curve seam. */
        BrightnessCurve.gammaGetter = () ->
        {
            MinecraftClient mc = MinecraftClient.getInstance();

            return mc == null || mc.options == null ? 1.0 : mc.options.getGamma().getValue();
        };
        BrightnessCurve.gammaSetter = (value) ->
        {
            MinecraftClient mc = MinecraftClient.getInstance();

            if (mc != null && mc.options != null)
            {
                mc.options.getGamma().setValue(value);
            }
        };

        /* S21 P218: the shader-curve family's non-Iris seams. The render
         * distance drives ShaderCenterDepthCurve's far plane (legacy
         * gameSettings.renderDistanceChunks); the config row's reload guard
         * asks whether a pack is loaded (legacy Shaders.shaderPackLoaded).
         * The remaining cross-half seams (CurveManager.setOptionSource,
         * ValueShaderOption.reloadShaders, the bridge's config + refresh hooks)
         * are installed by ShaderCurveClientWiring just below — see its javadoc
         * for why AsmShaderHandler.sunPathRotationSink stays a no-op on Iris. */
        ShaderCenterDepthCurve.renderDistance = () ->
        {
            MinecraftClient mc = MinecraftClient.getInstance();

            return mc == null || mc.options == null ? 12 : mc.options.getViewDistance().getValue();
        };

        ValueShaderOption.shaderLoaded = OptifineHelper::isShaderLoaded;

        /* Legacy ClientProxy.registerClientConfig's optifine block: the GUI
         * row (widget keyed on the main-source value class, P210 pattern) and
         * the "no shader mod ⇒ hide the category" gate, which cannot run
         * during main-entrypoint config registration. */
        registerShaderOptionConfigRow();
        Aperture.applyShaderpackVisibility(OptifineHelper.shaderpackSupported);

        CameraRunner.curveApplier = (profile, progress, partialTick) ->
            ClientProxy.curveManager.applyCurves(profile.curves, progress, partialTick);

        /* S21 P218 merge step: join the curve layer (R-A) to the Iris platform
         * layer (R-B). Must precede the first refreshCurves() so the option
         * source is installed before the curve table is first built. */
        ShaderCurveClientWiring.install();

        ClientProxy.curveManager.refreshCurves();

        /* S18 P202: install the built-in recorder backend behind the
         * MinemaIntegration facade (the ported GuiMinemaPanel drives it). */
        MinemaBackend.install();

        /* S22 P234: the recorder's live capture controller + scene-audio
         * resolver (the two MinemaBackend seams that were never assigned, so
         * recording produced no file and exports were silent). */
        VideoCaptureWiring.install();

        /* S18 P202.1: the tracking-data exporter's client seams (camera
         * position, partial ticks, entity selector, export dir), the per-frame
         * camera/frame-end hooks that replace MinemaEventbus, and the
         * MorphTracker Aperture hook. */
        TrackingHooks.install();

        /* Blockbuster's recording roll seam (P101/P102 shim) */
        CameraHandler.rollGetter = () -> ClientProxy.control.roll;
        CameraHandler.rollSetter = (prevRoll, roll) -> ClientProxy.control.setRoll(prevRoll, roll);

        /* P186 shim-collapse client seams: the CameraHandler sentinels/proxies
         * whose real implementations touch client-only Aperture state. */
        CameraHandler.rollPartialGetter = (partialTicks) -> ClientProxy.control.getRoll(partialTicks);
        CameraHandler.rollResetter = () -> ClientProxy.control.resetRoll();
        CameraHandler.outsideAttacher = () -> ClientProxy.runner.attachOutside();
        CameraHandler.outsideDetacher = () -> ClientProxy.runner.detachOutside();
        CameraHandler.offsetGetter = () ->
        {
            /* -1 sentinel unless the camera editor is the current screen; then
             * the timeline scrub value (legacy CameraHandler.getCameraOffset) */
            MinecraftClient mc = MinecraftClient.getInstance();

            if (mc != null && mc.currentScreen instanceof GuiCameraEditor editor)
            {
                return editor.timeline.value;
            }

            return -1;
        };
        /* Legacy CameraHandler.get(): the main-hand ItemPlayback's "Scene"
         * string tag wins over the stashed CameraHandler.location. */
        CameraHandler.heldPlaybackLocation = () ->
        {
            MinecraftClient mc = MinecraftClient.getInstance();

            if (mc == null || mc.player == null)
            {
                return null;
            }

            ItemStack right = mc.player.getMainHandStack();

            if (right.getItem() instanceof ItemPlayback)
            {
                NbtCompound tag = right.getNbt();

                if (tag != null && tag.contains("Scene"))
                {
                    return new SceneLocation(tag.getString("Scene"));
                }
            }

            return null;
        };

        CameraHandler.screenCloser = () ->
        {
            MinecraftClient mc = MinecraftClient.getInstance();

            if (mc == null)
            {
                return;
            }

            /* Legacy closeCameraEditor(screen): when the camera editor is the
             * current screen, run ITS exit logic (which restores the player and
             * fires the scene-stop path) and stop there; otherwise plain close. */
            if (mc.currentScreen instanceof GuiCameraEditor editor)
            {
                editor.closeThisScreen();

                return;
            }

            mc.setScreen(null);
        };

        /* Legacy: ClientProxy.server && OpHelper.isPlayerOp() — verbatim,
         * now that the P21 client half of OpHelper is installed. */
        AbstractDestination.serverDestinationCheck = () -> ClientProxy.server && OpHelper.isPlayerOp();

        /* S22 P239: profile auto-save on disconnect, the loaded/renamed
         * profile callbacks into the editor list, and the camera-editor-open
         * predicate Blockbuster's record list + morph action panel gate on. */
        CameraProfileWiring.install();

        registerClientInfo();

        /* Legacy client PlayerTickEvent Phase.START — see onClientTickStart. */
        ClientTickEvents.START_CLIENT_TICK.register(ApertureClient::onClientTickStart);

        /* P184: manual fixture recording seam — legacy RenderFrame read
         * GuiManualFixturePanel.tick directly */
        RenderFrame.currentRecordingTick = () -> GuiManualFixturePanel.tick;

        /* P218.1: legacy RenderFrame.fromPlayer called the client-only
         * OptifineHelper.isZooming() directly. Answers false on 1.20.4 (no
         * Optifine zoom key) — the branch is kept live, not the behavior. */
        RenderFrame.zooming = OptifineHelper::isZooming;

        /* P184: manual-recording HUD (countdown + "Recording (tick)") —
         * legacy RenderingHandler.onHUDRender(RenderGameOverlayEvent.Post) */
        HudRenderCallback.EVENT.register((context, tickDelta) ->
        {
            MinecraftClient mc = MinecraftClient.getInstance();

            if (mc != null && mc.player != null)
            {
                GuiManualFixturePanel.drawHUD(context, mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
            }
        });

        /* P178.1: in-world camera profile visualization (legacy
         * CameraRenderer.onLastRender, a RenderWorldLastEvent handler). The
         * manual-fixture frame capture that shared that handler already runs
         * from ApertureClient.frame (P184), so only the path pass lands here.
         * AFTER_TRANSLUCENT keeps the cards/lines composited over the world
         * without fighting the vanilla LAST consumers (the screenshot capture
         * hook in BlockbusterClient sits on LAST). */
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context ->
            ClientProxy.renderer.onLastRender(context));

        /* P178.1: letterbox bars — legacy RenderingHandler.onPreRenderOverlay
         * (McLib's RenderOverlayEvent.Pre); the tree's P44.1 seam is
         * HudRenderCallback, see RenderingHandler's layering note. */
        HudRenderCallback.EVENT.register((context, tickDelta) ->
            RenderingHandler.drawLetterbox(context));

        /* P183: SAVE/SAVED dirty icon kept live via CameraProfileChangedEvent
         * (legacy KeyboardHandler.onCameraProfileChanged) */
        CameraProfileChangedEvent.LISTENERS.add(event ->
        {
            MinecraftClient mc = MinecraftClient.getInstance();

            if (mc != null && mc.currentScreen instanceof GuiCameraEditor)
            {
                ((GuiCameraEditor) mc.currentScreen).updateSaveButton(event.profile);
            }
        });

        /* P186: full keybind family (legacy translation keys + P/Z/C defaults,
         * the rest unbound) with the per-tick roll/fov/step/rotate handlers and
         * ReplayMod guard. The camera-editor open key (C) is now owned by
         * KeyboardHandler; the back-compat field mirrors it for existing P183
         * callers. */
        KeyboardHandler.HANDLER.register();
        openCameraEditorKey = KeyboardHandler.HANDLER.cameraEditor;

        /* P186: client commands /camera (+ subcommands) and /load_chunks */
        CameraCommands.register();
    }

    /**
     * S21 P218 — the {@code optifine.option} config row's widget.
     *
     * <p>Legacy {@code ValueShaderOption.getFields} built a
     * {@code GuiToggleElement} over {@code Aperture.optifineShaderOptionCurve}
     * whose callback reloaded the shader pack. The value class is main-source
     * in the port, so the body lives here and is keyed on it in the client
     * value→widget registry (the P208/P210 {@code ValueMainButtons} pattern).
     * The reload itself is {@code ValueShaderOption.reload()}, i.e. a no-op
     * until the Iris platform layer installs
     * {@code ValueShaderOption.reloadShaders}.</p>
     */
    private static void registerShaderOptionConfigRow()
    {
        ConfigGuiProviders.register(
            ValueShaderOption.class,
            (mc, gui, value) ->
            {
                GuiToggleElement toggle =
                    new GuiToggleElement(mc, Aperture.optifineShaderOptionCurve, (element) ->
                        ValueShaderOption.reload());

                toggle.flex().reset();

                return Arrays.asList(toggle);
            });
    }

    /**
     * The client half of Aperture's legacy {@code PlayerTickEvent} Phase.START
     * subscribers, in one place: {@code CameraRunner.onPlayerTick} (line 334 of
     * the 1.8.2 source — the camera playback tick counter),
     * {@code CameraRenderer.onPlayerTick} (line 175 — the smooth-camera update)
     * and, from the same handler's {@code Phase.START} arm,
     * {@code GuiManualFixturePanel.update()}.
     *
     * <p><b>Pause parity (S22 P267).</b> Both legacy handlers are
     * {@code PlayerTickEvent} subscribers, <i>not</i> {@code ClientTickEvent}
     * ones: the event is posted from {@code EntityPlayer.onUpdate}, which the
     * client only reaches through {@code WorldClient.updateEntities()}, and
     * {@code Minecraft.runTick} guards that call with
     * {@code if (!this.isGamePaused)}. So none of these ran behind 1.12.2's
     * escape menu. {@code CameraRunner.ticks} is <i>the</i> camera playback
     * clock — what the profile is sampled against and what
     * {@code PacketCameraState} syncs — so an ungated Fabric tick let a running
     * camera profile keep travelling while the world it was filming stood still,
     * and the camera arrived somewhere else than the take did.</p>
     *
     * <p>This gate is narrower than it looks: the camera editor deliberately
     * does <b>not</b> pause the game ({@code GuiCameraEditor.shouldPause()}
     * returns false — "the camera runs on the world's update loop"), so it only
     * bites the in-world playback case, exactly as in 1.12.2. A named method
     * rather than an inline lambda so {@code ClientTickPauseGateTest} can read
     * the gate out of the bytecode.</p>
     */
    static void onClientTickStart(MinecraftClient client)
    {
        /* Legacy ClientTickEvent (ungated) ran before PlayerTickEvent: a profile
         * the editor starts this tick must see this tick's runner update. */
        GuiCameraEditor.flushPendingOperations();

        if (client == null || client.player == null || client.isPaused())
        {
            return;
        }

        ClientProxy.runner.onPlayerTick();
        ClientProxy.renderer.updateSmooth();
        GuiManualFixturePanel.update();
    }

    /**
     * Legacy {@code ClientProxy.load} client info tables (titles + timeline
     * colors). GUI panel classes attach in P183/P184.
     */
    private static void registerClientInfo()
    {
        /* Register camera fixture panels (legacy ClientProxy.load head;
         * registration order of registerClient below = Ctrl+1..7 mapping) */
        GuiCameraEditor.PANELS.put(IdleFixture.class, GuiIdleFixturePanel.class);
        GuiCameraEditor.PANELS.put(PathFixture.class, GuiPathFixturePanel.class);
        GuiCameraEditor.PANELS.put(CircularFixture.class, GuiCircularFixturePanel.class);
        GuiCameraEditor.PANELS.put(KeyframeFixture.class, GuiKeyframeFixturePanel.class);
        GuiCameraEditor.PANELS.put(NullFixture.class, GuiNullFixturePanel.class);
        GuiCameraEditor.PANELS.put(ManualFixture.class, GuiManualFixturePanel.class);
        GuiCameraEditor.PANELS.put(DollyFixture.class, GuiDollyFixturePanel.class);

        FixtureRegistry.registerClient(IdleFixture.class, "aperture.gui.fixtures.idle", new Color(0.085F, 0.62F, 0.395F));
        FixtureRegistry.registerClient(PathFixture.class, "aperture.gui.fixtures.path", new Color(0.408F, 0.128F, 0.681F));
        FixtureRegistry.registerClient(CircularFixture.class, "aperture.gui.fixtures.circular", new Color(0.298F, 0.631F, 0.247F));
        FixtureRegistry.registerClient(KeyframeFixture.class, "aperture.gui.fixtures.keyframe", new Color(0.874F, 0.184F, 0.625F));
        FixtureRegistry.registerClient(NullFixture.class, "aperture.gui.fixtures.null", new Color(0.1F, 0.1F, 0.12F));
        FixtureRegistry.registerClient(ManualFixture.class, "aperture.gui.fixtures.manual", new Color().set(0x0050b3));
        FixtureRegistry.registerClient(DollyFixture.class, "aperture.gui.fixtures.dolly", new Color().set(0xffa500));

        ModifierRegistry.registerClient(ShakeModifier.class, "aperture.gui.modifiers.shake", new Color(0.085F, 0.62F, 0.395F));
        ModifierRegistry.registerClient(MathModifier.class, "aperture.gui.modifiers.math", new Color(0.408F, 0.128F, 0.681F));
        ModifierRegistry.registerClient(LookModifier.class, "aperture.gui.modifiers.look", new Color(0.1F, 0.5F, 1F));
        ModifierRegistry.registerClient(FollowModifier.class, "aperture.gui.modifiers.follow", new Color(0.85F, 0.137F, 0.329F));
        ModifierRegistry.registerClient(TranslateModifier.class, "aperture.gui.modifiers.translate", new Color(0.298F, 0.631F, 0.247F));
        ModifierRegistry.registerClient(AngleModifier.class, "aperture.gui.modifiers.angle", new Color(0.847F, 0.482F, 0.043F));
        ModifierRegistry.registerClient(OrbitModifier.class, "aperture.gui.modifiers.orbit", new Color(0.874F, 0.184F, 0.625F));
        ModifierRegistry.registerClient(DragModifier.class, "aperture.gui.modifiers.drag", new Color(0.298F, 0.690F, 0.972F));
        ModifierRegistry.registerClient(RemapperModifier.class, "aperture.gui.modifiers.remapper", new Color().set(0x111111));
        ModifierRegistry.registerClient(DollyZoomModifier.class, "aperture.gui.modifiers.dolly_zoom", new Color().set(0x222222));

        /* Register camera modifier panels (legacy ClientProxy.load; P185).
         * Blockbuster's "tracker" panel + client info is registered by
         * TrackerModifierClientWiring at the end of this method (S22 P240). */
        GuiModifiersManager.PANELS.put(ShakeModifier.class, GuiShakeModifierPanel.class);
        GuiModifiersManager.PANELS.put(MathModifier.class, GuiMathModifierPanel.class);
        GuiModifiersManager.PANELS.put(LookModifier.class, GuiLookModifierPanel.class);
        GuiModifiersManager.PANELS.put(FollowModifier.class, GuiFollowModifierPanel.class);
        GuiModifiersManager.PANELS.put(TranslateModifier.class, GuiTranslateModifierPanel.class);
        GuiModifiersManager.PANELS.put(AngleModifier.class, GuiAngleModifierPanel.class);
        GuiModifiersManager.PANELS.put(OrbitModifier.class, GuiOrbitModifierPanel.class);
        GuiModifiersManager.PANELS.put(DragModifier.class, GuiDragModifierPanel.class);
        GuiModifiersManager.PANELS.put(RemapperModifier.class, GuiRemapperModifierPanel.class);
        GuiModifiersManager.PANELS.put(DollyZoomModifier.class, GuiDollyZoomModifierPanel.class);

        /* S22 P240: Blockbuster's "tracker" modifier (byte id 10) — its
         * registration, timeline colour, panel, scene actor query and the
         * matrix capture that replaces the legacy GL_MODELVIEW readback.
         * It sits here rather than in init() on purpose: GuiModifiersManager
         * dereferences ModifierRegistry.CLIENT for every registered id, so the
         * registration and its client info must never be observable apart —
         * and this method is the one path both production and the headless
         * editor harness take. */
        TrackerModifierClientWiring.install();
    }

    /* Render-seam mediation (consumed by the P178/P179 mixins) */

    /**
     * Per-frame camera update, called from the {@code Camera.update} mixin
     * (replaces the Forge RenderTickEvent Phase.START registration).
     * Returns the position to take over with, or null when inactive.
     */
    public static Position frame(float tickDelta)
    {
        /* P184: manual fixture per-frame capture (legacy CameraRenderer
         * RenderWorldLast head) — one sample per rendered frame while the
         * static recording flag is up */
        if (GuiManualFixturePanel.recording)
        {
            MinecraftClient mc = MinecraftClient.getInstance();
            GuiAbstractFixturePanel<?> panel = ClientProxy.getCameraEditor().panel.delegate;

            if (mc != null && mc.player != null && panel instanceof GuiManualFixturePanel)
            {
                ((GuiManualFixturePanel) panel).recordFrame(mc.player, tickDelta);
            }
        }

        /* Legacy CameraOutside.onFogColor: while outside mode is attached the
         * detached "Camera" dummy is the view entity, re-asserted every frame
         * and never conditional on any option (S22 P252). Outside of the runner
         * on purpose — outside mode attaches/detaches with playback stopped. */
        ClientProxy.runner.outside.onFrame();

        ClientProxy.runner.onRenderTick(tickDelta);

        if (ClientProxy.runner.isRunning())
        {
            return ClientProxy.runner.getPosition();
        }

        Position editor = editorPosition.get();

        if (editor != null)
        {
            return editor;
        }

        /* Smooth camera (writes angles back into the player; the camera
         * then picks them up from the player naturally) */
        ClientProxy.renderer.orientSmooth(tickDelta);

        return null;
    }

    /**
     * Whether a camera context (playback or editor preview) currently
     * drives the render camera.
     */
    public static boolean isCameraActive()
    {
        return ClientProxy.runner.isRunning() || editorPosition.get() != null;
    }

    /**
     * FOV override for the {@code getFov} mixin; null = vanilla.
     */
    public static Float getFovOverride()
    {
        if (ClientProxy.runner.isRunning())
        {
            return ClientProxy.runner.getPosition().angle.fov;
        }

        Position editor = editorPosition.get();

        if (editor != null)
        {
            return editor.angle.fov;
        }

        if (ClientProxy.renderer.smoothFovActive)
        {
            return ClientProxy.renderer.fov.value;
        }

        return null;
    }

    /**
     * Current camera roll (legacy applied it whenever nonzero, playback or
     * not — the {@code /camera roll} command relies on that).
     */
    public static float getRoll(float tickDelta)
    {
        return ClientProxy.control.getRoll(tickDelta);
    }

    /**
     * Legacy {@code KeyboardHandler.setSmoothCamera} — seeds the filters
     * from player yaw and <b>negated pitch</b> (the sign must match the
     * render hook or the camera flips on enable). The toggle keybind
     * arrives with P186.
     */
    public static void setSmoothCamera(boolean enabled)
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        Aperture.smooth.set(enabled);

        if (enabled && mc != null && mc.player != null)
        {
            ClientProxy.renderer.smooth.set(mc.player.getYaw(), -mc.player.getPitch());
            ClientProxy.renderer.roll.reset(ClientProxy.control.roll);
            ClientProxy.renderer.fov.reset(mc.options.getFov().getValue());
        }
    }
}
