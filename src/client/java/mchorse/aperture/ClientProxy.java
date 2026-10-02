package mchorse.aperture;

import mchorse.aperture.camera.CameraControl;
import mchorse.aperture.camera.CameraRenderer;
import mchorse.aperture.camera.CameraRunner;
import mchorse.aperture.camera.CurveManager;
import mchorse.aperture.client.gui.GuiCameraEditor;
import mchorse.aperture.events.CameraEditorCallbacks;
import mchorse.mclib.utils.OpHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.io.File;

/**
 * Bundled Aperture client proxy (S15) — the static camera stuff plus the
 * client camera-profile folder resolution (P181).
 *
 * Port notes: {@code config/aperture/} folder parity is preserved via the
 * Fabric config dir; the server-IP key keeps the legacy regexes verbatim,
 * including the quirky 1–5-char port strip {@code :[\\w]{1,5}$} which also
 * eats non-numeric suffixes (by design). The GUI statics (cameraEditor,
 * keys) arrive with P183/P186; {@code curveManager} with P180.
 *
 * Legacy source: .tools/legacy-src/aperture/src/main/java/mchorse/aperture/ClientProxy.java
 */
public class ClientProxy
{
    /**
     * Whether the server the client is connected to runs Aperture (flipped
     * by the P182 {@code PacketAperture} handshake; always false until then).
     */
    public static boolean server = false;

    /**
     * Camera editor event bus (P183) — legacy was a Forge {@code EventBus};
     * this is the typed callback registry Blockbuster's P185.1 handlers
     * subscribe to.
     */
    public static CameraEditorCallbacks EVENT_BUS = new CameraEditorCallbacks();

    /* Camera stuff */
    public static CameraRenderer renderer = new CameraRenderer();
    public static CameraControl control = new CameraControl();
    public static CameraRunner runner = new CameraRunner();

    /**
     * Vanilla-safe render curve manager (P180). Refreshed when the camera
     * editor opens; drives the profile's {@code "curves"} every frame during
     * playback / preview via the {@link CameraRunner#curveApplier} seam.
     */
    public static CurveManager curveManager = new CurveManager();

    /**
     * Camera editor GUI (lazily created singleton, P183)
     */
    public static GuiCameraEditor cameraEditor;

    /* Files */
    public static File config = FabricLoader.getInstance().getConfigDir().resolve("aperture").toFile();
    public static File cameras = new File(config, "cameras");

    /**
     * Wire the config value references into the smooth camera / filters
     * (legacy {@code ClientProxy.load} tail — shared by reference).
     */
    public static void load()
    {
        renderer.smooth.enabled = Aperture.smooth;
        renderer.smooth.fricX = Aperture.smoothFricX;
        renderer.smooth.fricY = Aperture.smoothFricY;

        renderer.roll.friction = Aperture.rollFriction;
        renderer.roll.factor = Aperture.rollFactor;
        renderer.roll.reference = Aperture.smoothReferenceFps;

        renderer.fov.friction = Aperture.fovFriction;
        renderer.fov.factor = Aperture.fovFactor;
        renderer.fov.reference = Aperture.smoothReferenceFps;
    }

    /**
     * Get the camera editor GUI (lazily creating it)
     */
    public static GuiCameraEditor getCameraEditor()
    {
        if (cameraEditor == null)
        {
            cameraEditor = new GuiCameraEditor(MinecraftClient.getInstance(), runner);
        }

        return cameraEditor;
    }

    /**
     * Open the camera editor
     */
    public static GuiCameraEditor openCameraEditor()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        GuiCameraEditor editor = ClientProxy.getCameraEditor();

        editor.updateCameraEditor(mc.player);
        mc.player.setVelocity(0, 0, 0);
        mc.setScreen(editor);

        return editor;
    }

    /**
     * Whether the player can use the camera editor: singleplayer OR the
     * syncable {@code camera_editor} op-config OR the player is an OP.
     *
     * Port note: the OP test is the ported P21 client half of
     * {@code OpHelper}, as in legacy. The extra {@code mc.player == null}
     * guard is a port addition — legacy could not reach this from the main
     * menu.
     */
    public static boolean canUseCameraEditor()
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null || mc.player == null)
        {
            return false;
        }

        return mc.isIntegratedServerRunning() || Aperture.opCameraEditor.get() || OpHelper.isPlayerOp();
    }

    /**
     * Get client cameras storage location
     *
     * This method returns File pointer to a folder where client side camera
     * profiles are stored. This method will return {@code null} if it was
     * invoked when the game is in the main menu.
     */
    public static File getClientCameras()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        ServerInfo data = mc == null ? null : mc.getCurrentServerEntry();

        File file = null;

        if (data != null)
        {
            /* Removing port, because this will distort the folder name */
            file = new File(cameras, sanitizeServerIP(data.address));
        }
        else if (mc != null && mc.isIntegratedServerRunning() && mc.getServer() != null)
        {
            /* I probably should've used getFolderName() in the beginning ... */
            file = new File(cameras, sanitizeWorldName(mc.getServer().getSaveProperties().getLevelName()));
        }

        if (file != null)
        {
            file.mkdirs();
        }

        return file;
    }

    /**
     * Legacy server-IP folder key: strip a 1–5 word-char port suffix, then
     * replace illegal characters with underscores.
     */
    public static String sanitizeServerIP(String serverIP)
    {
        return serverIP.replaceAll(":[\\w]{1,5}$", "").replaceAll("[^\\w\\d_\\- ]", "_");
    }

    /**
     * Legacy integrated-server folder key (no port strip).
     */
    public static String sanitizeWorldName(String worldName)
    {
        return worldName.replaceAll("[^\\w\\d_\\- ]", "_");
    }
}
