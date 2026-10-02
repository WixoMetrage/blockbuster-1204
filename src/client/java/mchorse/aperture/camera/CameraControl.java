package mchorse.aperture.camera;

import java.util.Objects;
import mchorse.aperture.Aperture;
import mchorse.aperture.ClientProxy;
import mchorse.aperture.camera.destination.AbstractDestination;
import mchorse.aperture.client.gui.GuiCameraEditor;
import mchorse.mclib.utils.Interpolations;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.world.GameMode;

/**
 * Camera control class (P177).
 *
 * Port notes:
 * <ul>
 * <li>{@code lastCounter} is a <b>reference count</b> — {@code cache()}
 * increments, {@code restore()} decrements and only restores at 0 (nested
 * GUIs must not double-restore).</li>
 * <li>Gamemode restore: legacy sent the literal chat string
 * {@code "/gamemode " + id} (numeric); 1.20.4 has named gamemodes only, so
 * the port sends {@code sendChatCommand("gamemode <name>")} — documented
 * non-parity (S15 open question 1), same observable behavior.</li>
 * <li>FOV: 1.20.4's FOV option is an integer {@code SimpleOption}; during
 * playback the FOV flows through the P178 {@code getFov} mixin override
 * instead of mutating game settings, so cache/restore round the option
 * value (documented non-parity, no float {@code fovSetting} exists).</li>
 * <li>{@code reset()} (disconnect) auto-saves dirty profiles when
 * {@code auto_save} is on — the profile list lives in the P183 GUI, wired
 * through {@link #dirtyProfilesSaver}.</li>
 * <li>All {@code MinecraftClient} touchpoints are null-guarded so the
 * refcount/roll state machine runs in plain JUnit.</li>
 * </ul>
 *
 * Legacy source: .tools/legacy-src/aperture/src/main/java/mchorse/aperture/camera/CameraControl.java
 */
public class CameraControl
{
    /**
     * Currently rendered/editing camera profile
     */
    public CameraProfile currentProfile;

    private boolean prevRollMode;
    private float prevRoll;

    /**
     * Roll of the camera
     */
    public float roll = 0;

    public int lastCounter;
    public float lastRoll;
    public float lastFov;
    public Perspective lastPerspective = Perspective.FIRST_PERSON;
    public GameMode lastGameMode = null;

    /**
     * P183 seam: saves all dirty profiles from the editor's profile list
     * (legacy {@code saveCameraProfiles(cameraEditor)}).
     */
    public static Runnable dirtyProfilesSaver;

    public void cache()
    {
        if (this.lastCounter == 0)
        {
            MinecraftClient mc = MinecraftClient.getInstance();

            this.lastGameMode = getGameMode();
            this.lastRoll = roll;

            if (mc != null && mc.options != null)
            {
                this.lastFov = mc.options.getFov().getValue();
                this.lastPerspective = mc.options.getPerspective();
            }
        }

        this.lastCounter ++;
    }

    public void restore()
    {
        this.lastCounter --;

        if (this.lastCounter == 0)
        {
            MinecraftClient mc = MinecraftClient.getInstance();

            this.roll = this.lastRoll;

            if (mc != null && mc.options != null)
            {
                mc.options.getFov().setValue((int) this.lastFov);
                mc.options.setPerspective(this.lastPerspective);
            }

            if (this.lastGameMode != null && this.lastGameMode != getGameMode() && mc != null && mc.player != null)
            {
                /* Parity note: legacy sent "/gamemode <numeric id>" */
                mc.player.networkHandler.sendChatCommand("gamemode " + this.lastGameMode.getName());
            }

            this.lastRoll = this.lastFov = 0F;
        }
    }

    /**
     * Find a loaded camera profile by its destination (P182). Scans the
     * editor's profile list; used by the {@code PacketCameraState} handler to
     * pick which profile to play back.
     */
    public CameraProfile getProfile(AbstractDestination destination)
    {
        GuiCameraEditor editor = ClientProxy.cameraEditor;

        if (editor == null)
        {
            return null;
        }

        for (CameraProfile profile : editor.profiles.profiles.list.getList())
        {
            if (Objects.equals(profile.getDestination(), destination))
            {
                return profile;
            }
        }

        return null;
    }

    /**
     * Current client gamemode (legacy McLib EntityUtils.getGameMode()).
     */
    public static GameMode getGameMode()
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc != null && mc.interactionManager != null)
        {
            return mc.interactionManager.getCurrentGameMode();
        }

        return null;
    }

    /**
     * Reset camera profiles
     */
    public void reset()
    {
        /* Saving dirty camera profiles */
        if (Aperture.profileAutoSave.get() && dirtyProfilesSaver != null)
        {
            dirtyProfilesSaver.run();
        }

        this.currentProfile = null;
        this.lastCounter = 0;
        this.lastRoll = this.lastFov = 0;
        this.lastGameMode = null;
    }

    /**
     * Add roll (it can be negative too)
     */
    public void setRoll(float value)
    {
        this.prevRollMode = false;

        ClientProxy.renderer.roll.reset(value);
        this.roll = value;
    }

    /**
     * Add roll (it can be negative too)
     */
    public void setRoll(float prevValue, float value)
    {
        this.prevRollMode = true;

        ClientProxy.renderer.roll.reset(value);
        this.prevRoll = prevValue;
        this.roll = value;
    }

    /**
     * Reset roll (set it back to 0)
     */
    public void resetRoll()
    {
        this.setRoll(0.0F);
    }

    /**
     * Set FOV
     */
    public void setFOV(float value)
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        ClientProxy.renderer.fov.reset(value);

        if (mc != null && mc.options != null)
        {
            mc.options.getFov().setValue((int) value);
        }
    }

    /**
     * Reset FOV to default value
     */
    public void resetFOV()
    {
        this.setFOV(70.0F);
    }

    /**
     * Set both roll and FOV at the same time
     */
    public void setRollAndFOV(float roll, float fov)
    {
        this.setRoll(roll);

        /* wixo.1 (CDC §5): called every frame by the editor preview. Only the
         * smooth-FOV filter is seeded; the vanilla FOV option is no longer
         * written — the camera FOV reaches the renderer through
         * ApertureClient.getFovOverride. Writing it overwrote the user's own FOV
         * setting and, outside 30-110, logged "Illegal option value" every frame. */
        ClientProxy.renderer.fov.reset(fov);
    }

    public float getRoll(float partialTicks)
    {
        return this.prevRollMode ? Interpolations.lerp(this.prevRoll, this.roll, partialTicks) : this.roll;
    }
}
