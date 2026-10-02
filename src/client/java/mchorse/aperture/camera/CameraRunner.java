package mchorse.aperture.camera;

import mchorse.aperture.Aperture;
import mchorse.aperture.ClientProxy;
import mchorse.aperture.camera.data.Angle;
import mchorse.aperture.camera.data.Point;
import mchorse.aperture.camera.data.Position;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.function.Supplier;

/**
 * Profile runner (P177).
 *
 * This class is responsible for running camera profiles (i.e. applying
 * current's fixture camera transformations on player).
 *
 * Port notes:
 * <ul>
 * <li>{@link #onRenderTick(float)} replaces the Forge
 * {@code RenderTickEvent} Phase.START body — invoked once per frame from
 * the P178 {@code Camera.update} mixin path
 * ({@code ApertureClient.frame}). It does <b>not</b> touch the camera
 * entity: legacy's Phase.START/END {@code setRenderViewEntity} pair only
 * ever moved where the fog/clear colour was sampled (the {@code FogColors}
 * handler re-pinned the camera immediately afterwards), and that is
 * reproduced by {@code CameraOutside.onFrame()} +
 * {@code CameraOutside.skyColorPosition} instead — S22 P252.</li>
 * <li>{@link #onPlayerTick()} replaces client {@code PlayerTickEvent}
 * Phase.START (registered on the client tick events).</li>
 * <li>Gamemode: legacy sent the literal chat string {@code "/gamemode 3"};
 * 1.20.4 → {@code sendChatCommand("gamemode spectator")} — documented
 * non-parity (S15 open question 1).</li>
 * <li>The {@code sin(progress)*1e-9 + 1e-9} y jitter (legacy Optifine
 * disappearing-entities workaround) is ported verbatim per the plan
 * (harmless; revisit under S21).</li>
 * <li>The ≥10-block {@code /tp} chat command fires only on multiplayer;
 * {@code essentials_tp} switches to {@code minecraft:tp}.</li>
 * <li>Camera math (duration resolution, stop-at-end, profile application
 * into {@link #position}) runs before any {@code MinecraftClient}
 * touchpoint and every touchpoint is null-guarded — the state machine is
 * plain-JUnit testable.</li>
 * <li>Profile curves (P180) hang off the {@link #curveApplier} seam
 * (legacy {@code profile.applyCurves}) — no-op until CurveManager lands.</li>
 * </ul>
 *
 * Legacy source: .tools/legacy-src/aperture/src/main/java/mchorse/aperture/camera/CameraRunner.java
 */
public class CameraRunner implements CameraExporter.PositionSource
{
    /**
     * P180 seam (legacy {@code ClientProxy.curveManager.applyCurves(...)}).
     */
    public interface CurveApplier
    {
        void applyCurves(CameraProfile profile, long progress, float partialTick);
    }

    /**
     * Installed by P180's CurveManager; null = no-op.
     */
    public static CurveApplier curveApplier;

    /**
     * Is camera runner running?
     */
    private boolean isRunning = false;

    /**
     * Camera profile which is getting currently played
     */
    private CameraProfile profile;

    /**
     * Position used to apply fixtures and modifiers upon
     */
    private Position position = new Position(0, 0, 0, 0, 0);

    /**
     * How many ticks passed since the beginning
     */
    public long ticks;

    /**
     * This field is responsible for handling the outside mode
     */
    public CameraOutside outside = new CameraOutside();

    /* Used by camera renderer */
    public float yaw = 0.0F;
    public float pitch = 0.0F;
    public Supplier<Long> duration;

    /* Skip first tick update after scrub */
    public boolean skipUpdate = false;

    /* Profile access methods */

    public boolean isRunning()
    {
        return this.isRunning;
    }

    public Position getPosition()
    {
        return this.position;
    }

    public CameraProfile getProfile()
    {
        return this.profile;
    }

    /* Playback methods (start/stop) */

    public void toggle(CameraProfile profile, long ticks)
    {
        this.toggle(profile, ticks, null);
    }

    public void toggle(CameraProfile profile, long ticks, Supplier<Long> duration)
    {
        if (this.isRunning)
        {
            this.stop();
        }
        else
        {
            this.start(profile, ticks, duration);
        }
    }

    /**
     * Start the profile runner from the first tick
     */
    public void start(CameraProfile profile)
    {
        this.start(profile, 0);
    }

    /**
     * Start the profile runner from the first tick
     */
    public void start(CameraProfile profile, long ticks)
    {
        this.start(profile, ticks, null);
    }

    /**
     * Start the profile runner. This method also responsible for setting
     * important values before starting the run (like setting duration, and
     * reseting ticks).
     */
    public void start(CameraProfile profile, long start, Supplier<Long> duration)
    {
        if (profile == null)
        {
            return;
        }

        this.profile = profile;

        ClientPlayerEntity player = player();

        if (!this.isRunning)
        {
            ClientProxy.control.cache();

            if (player != null)
            {
                this.position.set(player);

                if (Aperture.spectator.get() && !Aperture.outside.get() && CameraControl.getGameMode() != GameMode.SPECTATOR)
                {
                    /* Parity note: legacy sent the chat string "/gamemode 3" */
                    player.networkHandler.sendChatCommand("gamemode spectator");
                }
            }

            this.attachOutside();
        }

        if (player != null)
        {
            this.position.set(player);
        }

        this.isRunning = true;
        this.ticks = start;
        this.duration = duration;
        this.skipUpdate = true;
    }

    /**
     * Stop playback of camera profile
     */
    public void stop()
    {
        if (this.isRunning)
        {
            ClientProxy.control.restore();

            this.detachOutside();
        }

        this.isRunning = false;
        this.profile = null;
        this.duration = null;
        this.skipUpdate = false;

        ClientProxy.control.resetRoll();
    }

    /**
     * Attach outside mode handler
     */
    public void attachOutside()
    {
        if (!this.outside.active && Aperture.outside.get())
        {
            this.outside.start();
        }
    }

    /**
     * Detach outside mode handler
     */
    public void detachOutside()
    {
        if (this.outside.active)
        {
            this.outside.stop();
        }
    }

    public void setTick(long ticks)
    {
        this.ticks = ticks;
        this.skipUpdate = this.isRunning ? true : false;
    }

    /** Partial tick of the last {@link #onRenderTick} (diagnostics: video.debug). */
    public float lastPartialTick;

    /**
     * The method that does the most exciting thing! This method is responsible
     * for applying interpolated fixture on position and apply the output from
     * fixture onto player. See the class javadoc for the event → seam
     * mapping.
     */
    public void onRenderTick(float partialTick)
    {
        this.lastPartialTick = partialTick;

        if (!this.isRunning)
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        if (this.profile == null || (mc != null && mc.player == null))
        {
            this.stop();

            return;
        }

        long profileDuration = this.profile.getDuration();
        long duration = this.duration == null ? profileDuration : this.duration.get();
        long progress = this.ticks;

        if (progress >= duration)
        {
            this.stop();
        }
        else
        {
            if (Aperture.debugTicks.get())
            {
                Aperture.LOGGER.info("Camera render frame: " + partialTick + " " + this.ticks);
            }

            double prevX = this.position.point.x;
            double prevY = this.position.point.y;
            double prevZ = this.position.point.z;

            if (curveApplier != null)
            {
                curveApplier.applyCurves(this.profile, progress, partialTick);
            }

            if (progress < profileDuration)
            {
                this.profile.applyProfile(progress, partialTick, this.position);
            }

            Point point = this.position.point;
            Angle angle = this.position.angle;

            /* Setting up the camera: fov/roll flow through the P178 mixins
             * (legacy mutated gameSettings.fovSetting here).
             *
             * wixo.1 (CDC §5): setRoll, not a bare field write — it also clears
             * prevRollMode. A Blockbuster scene playback leaves that mode on
             * (CameraHandler.rollSetter), and getRoll() then lerped this
             * frame's roll with a stale prevRoll for the rest of the camera
             * playback. */
            ClientProxy.control.setRoll(angle.roll);

            /* Fighting with Optifine disappearing entities bug */
            double y = point.y + Math.sin(progress) * 0.000000001 + 0.000000001;

            ClientPlayerEntity player = player();

            if (player != null)
            {
                /* Velocity simulation (useful for recording the player) */
                this.setCameraPosition(player, point.x, y, point.z, angle);

                if (!this.outside.active)
                {
                    player.setVelocity(Vec3d.ZERO);
                }

                if (mc != null && !mc.isIntegratedServerRunning() && !this.outside.active)
                {
                    double dx = point.x - prevX;
                    double dy = point.y - prevY;
                    double dz = point.z - prevZ;

                    if (dx * dx + dy * dy + dz * dz >= 10 * 10)
                    {
                        /* Make it compatible with Essentials plugin, which replaced the native /tp command */
                        if (Aperture.essentialsTeleport.get())
                        {
                            player.networkHandler.sendChatCommand("minecraft:tp " + point.x + " " + point.y + " " + point.z + " " + angle.yaw + " " + angle.pitch);
                        }
                        else
                        {
                            player.networkHandler.sendChatCommand("tp " + point.x + " " + point.y + " " + point.z + " " + angle.yaw + " " + angle.pitch);
                        }
                    }
                }

                if (player.isSneaking())
                {
                    player.setSneaking(false);
                }
            }

            this.yaw = angle.yaw;
            this.pitch = angle.pitch;

            /* No view-entity swap here (S22 P252). Legacy's RenderTickEvent
             * pair moved the render-view entity for the sky decision, but
             * CameraOutside.onFogColor put it straight back to the camera
             * before anything rendered; on 1.20.4 the swap cannot move the sky
             * at all and only changes which entity WorldRenderer skips. The
             * camera entity is pinned by CameraOutside.onFrame() and the sky
             * option is honoured in CameraOutside.skyColorPosition. */
        }
    }

    /**
     * Set camera position
     *
     * This method is responsible for setting camera's position and rotation.
     * Why are these two methods invoked? Good question.
     *
     * {@code setLocationAndAngles} (yarn {@code refreshPositionAndAngles})
     * updates some client side values such as lastTick* and prevPos* values,
     * which makes the transition between two distant cameras, seamless, meanwhile
     * {@code setPositionAndRotation} (yarn {@code updatePositionAndAngles})
     * is responsible for fixing/clamping player's rotation.
     *
     * By using only one of these methods wouldn't guarantee supreme
     * quality of the camera animation.
     */
    public void setCameraPosition(ClientPlayerEntity player, double x, double y, double z, Angle angle)
    {
        LivingEntity camera = this.outside.active ? this.outside.camera : player;

        camera.refreshPositionAndAngles(x, Math.max(y - camera.getStandingEyeHeight(), -64.0), z, angle.yaw % 360, angle.pitch);
        camera.updatePositionAndAngles(x, Math.max(y - camera.getStandingEyeHeight(), -64.0), z, angle.yaw % 360, angle.pitch);
        camera.setHeadYaw(angle.yaw);
        camera.prevHeadYaw = angle.yaw;
    }

    /**
     * This is going to count ticks (used for camera synchronization).
     * Replaces the client-side {@code PlayerTickEvent} Phase.START handler.
     */
    public void onPlayerTick()
    {
        if (!this.isRunning)
        {
            return;
        }

        if (Aperture.debugTicks.get())
        {
            Aperture.LOGGER.info("Camera frame: " + this.ticks);
        }

        if (this.skipUpdate)
        {
            this.skipUpdate = false;
        }
        else
        {
            this.ticks++;
        }
    }

    private static ClientPlayerEntity player()
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        return mc == null ? null : mc.player;
    }
}
