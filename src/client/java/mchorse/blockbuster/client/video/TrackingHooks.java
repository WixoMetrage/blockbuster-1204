package mchorse.blockbuster.client.video;

import mchorse.aperture.camera.CameraExporter;
import mchorse.aperture.client.gui.GuiMinemaPanel;
import mchorse.aperture.utils.EntitySelector;
import mchorse.blockbuster_pack.trackers.MorphTracker;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

import javax.vecmath.Vector3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Client-side installation of the P202.1 {@link CameraExporter} seams: the
 * per-frame camera/frame-end callbacks that replace Minema's
 * {@code MinemaEventbus}, the camera-position source that replaces the
 * {@code GL_MODELVIEW_MATRIX} readback, the render partial ticks, the entity
 * resolver on top of the ported {@link EntitySelector}, the export directory,
 * and the {@code MorphTracker} Aperture hook (the legacy
 * {@code MorphTracker.ApertureTracker} body, verbatim).
 *
 * <p><b>Where the frame hooks sit.</b> Legacy's {@code cameraBUS} fired once the
 * camera matrices were final and before the world drew;
 * {@code WorldRenderEvents.AFTER_SETUP} is that point on 1.20.4 (the camera and
 * projection are set up, entities have not rendered, so a morph's
 * {@code track(...)} during the entity pass still sees {@code trackedCamera}).
 * {@code endRenderBUS} fired at the end of the frame — {@code WorldRenderEvents
 * .END}. Both exporter methods no-op while the exporter is not building, so the
 * hooks cost a boolean read outside of a tracked recording.</p>
 *
 * <p><b>Dropped divergence.</b> Legacy {@code MorphTracker} ALSO fed
 * {@code MinemaAPI.doTrack(name)} through an inner {@code MinemaTracker} — a
 * second, Minema-native tracking backend. Minema is gone and nothing in the port
 * consumes such a side-channel, so {@link MorphTracker#minemaHook} stays
 * uninstalled; the JSON exporter is the surviving contract.</p>
 */
public final class TrackingHooks
{
    private TrackingHooks()
    {}

    /** Installed once from {@code ApertureClient}, after the recorder backend. */
    public static void install()
    {
        CameraExporter.cameraPosition = TrackingHooks::cameraPosition;
        CameraExporter.partialTicks = TrackingHooks::partialTicks;
        CameraExporter.entitySource = TrackingHooks::findEntities;
        CameraExporter.captureInfo = TrackingHooks::captureInfo;
        CameraExporter.exportDir = () -> MinemaBackend.moviesDir().toPath();

        MorphTracker.apertureHook = TrackingHooks::trackMorph;

        WorldRenderEvents.AFTER_SETUP.register(context -> GuiMinemaPanel.trackingExporter.addCameraFrame());
        WorldRenderEvents.END.register(context -> GuiMinemaPanel.trackingExporter.frameEnd());
    }

    /* --------------------------------------------------------------------- */
    /* Seam implementations                                                  */
    /* --------------------------------------------------------------------- */

    /**
     * The camera's world position. Legacy inverted the GL model-view (after
     * folding in the render entity's interpolated position) and read its
     * translation column; on core profile that value <i>is</i>
     * {@code Camera#getPos()} — see
     * {@link CameraExporter#legacyCameraPosition(javax.vecmath.Matrix4d, double, double, double)}
     * and {@code CameraPositionEquivalenceTest}.
     */
    private static Vector3d cameraPosition()
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null || mc.gameRenderer == null)
        {
            return null;
        }

        Camera camera = mc.gameRenderer.getCamera();

        if (camera == null)
        {
            return null;
        }

        Vec3d pos = camera.getPos();

        return new Vector3d(pos.x, pos.y, pos.z);
    }

    private static float partialTicks()
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        return mc == null ? 0F : mc.getTickDelta();
    }

    /**
     * The header's capture settings. While a recording is live they come from
     * the P199 clock and the window/params the P200 capture is using; outside a
     * recording (tracking can be started before the first captured frame) the
     * configured defaults stand in, exactly like legacy reading Minema's config.
     */
    private static CameraExporter.CaptureInfo captureInfo()
    {
        VideoParams params = VideoCaptureWiring.CAPTURE.recorder().params();

        if (!VideoCaptureWiring.CAPTURE.recorder().isRecording())
        {
            CaptureResolution.Decision size = videoSize();

            params = MinemaBackend.buildParams("tracking", null, size.width(), size.height());
        }

        return new CameraExporter.CaptureInfo(
            params.fps(),
            params.width(),
            params.height(),
            params.heldFrames(),
            1 << params.motionBlur()
        );
    }

    /**
     * wixo.1 (CDC R8): the size of the <i>video</i>, not of the window. While
     * recording that is the live recording's size (above); otherwise the size a
     * recording started now would have — the same arithmetic as the recorder.
     */
    private static CaptureResolution.Decision videoSize()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        int width = mc == null || mc.getWindow() == null ? 0 : mc.getWindow().getFramebufferWidth();
        int height = mc == null || mc.getWindow() == null ? 0 : mc.getWindow().getFramebufferHeight();

        return CaptureResolution.resolve(VideoConfig.width(), VideoConfig.height(), width, height,
            CustomResolutionCapture.blocker());
    }

    /**
     * Resolve one normalized selector token against the client world and
     * snapshot each match. The snapshot carries the previous-tick and current
     * position (and, for living entities, the previous/current body yaw) so the
     * exporter can do legacy's own {@code Interpolations.lerp}.
     */
    private static Collection<CameraExporter.TrackedEntity> findEntities(String token) throws Exception
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null || mc.player == null)
        {
            return Collections.emptyList();
        }

        List<Entity> matched = EntitySelector.matchEntities(mc.player, token, Entity.class);
        List<CameraExporter.TrackedEntity> snapshots = new ArrayList<CameraExporter.TrackedEntity>();

        for (Entity entity : matched)
        {
            snapshots.add(snapshot(entity));
        }

        return snapshots;
    }

    /** Package-visible for the snapshot test. */
    static CameraExporter.TrackedEntity snapshot(Entity entity)
    {
        boolean living = entity instanceof LivingEntity;

        return new CameraExporter.TrackedEntity(
            entity.getUuidAsString(),
            entity.getName().getString(),
            /* P286: legacy CameraExporter.addEntitiesData lerped from
             * lastTickPos*, not prevPos* — hence the record field's name. On
             * yarn those are lastRenderX/Y/Z; prevX/Y/Z is the other field. */
            entity.lastRenderX, entity.lastRenderY, entity.lastRenderZ,
            entity.getX(), entity.getY(), entity.getZ(),
            living,
            living ? ((LivingEntity) entity).prevBodyYaw : 0,
            living ? ((LivingEntity) entity).bodyYaw : 0
        );
    }

    /**
     * Legacy {@code MorphTracker.ApertureTracker.track(MorphTracker)}, verbatim:
     * gate on the exporter tracking, lazily build the packet with the
     * {@code "Unnamed"} fallback, keep it only when {@code addTracker} returns
     * true (which it unconditionally does — the guard is legacy's, not a real
     * branch), then feed the exporter every render.
     */
    static void trackMorph(MorphTracker tracker)
    {
        if (GuiMinemaPanel.trackingExporter.isTracking())
        {
            if (tracker.getTrackingPacket() == null)
            {
                CameraExporter.TrackingPacket packet = new CameraExporter.TrackingPacket(
                    tracker.name.isEmpty() ? "Unnamed" : tracker.name, tracker.getCombineTracking());

                if (GuiMinemaPanel.trackingExporter.addTracker(packet))
                {
                    tracker.setTrackingPacket(packet);
                }
            }

            GuiMinemaPanel.trackingExporter.track(tracker.getTrackingPacket());
        }
    }
}
