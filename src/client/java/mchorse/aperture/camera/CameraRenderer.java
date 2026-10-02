package mchorse.aperture.camera;

import mchorse.aperture.Aperture;
import mchorse.aperture.ClientProxy;
import mchorse.aperture.camera.data.Position;
import mchorse.aperture.camera.smooth.Filter;
import mchorse.aperture.camera.smooth.SmoothCamera;
import mchorse.aperture.client.KeyboardHandler;
import mchorse.aperture.client.gui.GuiCameraEditor;
import mchorse.aperture.utils.TimeUtils;
import mchorse.mclib.utils.resources.ResourceLocation;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Camera renderer — non-GUI core (P179 slice).
 *
 * Legacy {@code CameraRenderer} mixed four responsibilities; the port
 * splits them by phase:
 * <ul>
 * <li><b>CameraSetup orientation</b> (yaw/pitch/roll injection) → the P178
 * {@code CameraMixin}/{@code GameRendererMixin} consuming
 * {@link #orientSmooth(float)} and {@code control.getRoll(pt)}. The legacy
 * CFM guard (yaw-angle heuristic) translates to context-flag gating in the
 * mixins, per the plan.</li>
 * <li><b>Smooth camera per-tick update</b> → {@link #updateSmooth()},
 * called from the client tick hook while smooth mode is on; mouse deltas
 * accumulate via {@link #accumulateMouse(double, double)} from the P179
 * {@code MouseMixin} (legacy read {@code mouseHelper.deltaX} per tick).
 * Roll/FOV keybind acceleration (P186) reads the bundled
 * {@link mchorse.aperture.client.KeyboardHandler} bindings and drives
 * {@link #accelerateRoll(float)}/{@link #accelerateFov(float)}.</li>
 * <li><b>World path rendering</b> (P178.1) → {@link #onLastRender} off
 * {@code WorldRenderEvents.AFTER_TRANSLUCENT}, with the profile walk itself
 * factored out into the headless {@link ProfileRenderGeometry}. The HUD half
 * of P178.1 (letterbox, chat/F3 suppression, "Camera ticks N") lives in
 * {@link mchorse.aperture.client.RenderingHandler}, as in legacy.</li>
 * </ul>
 *
 * Legacy source: .tools/legacy-src/aperture/src/main/java/mchorse/aperture/camera/CameraRenderer.java
 */
public class CameraRenderer
{
    /**
     * Background texture for a fixture rendering (legacy {@code TEXTURE};
     * 34×16 atlas — a 16px card cell at u 0–16 and a 2px path-point cell at
     * u 32–34).
     */
    public static final ResourceLocation TEXTURE = new ResourceLocation(Aperture.MOD_ID, "textures/gui/fixture.png");

    /** Legacy world text is unlit fixed-function — draw it full bright. */
    private static final int FULL_BRIGHT = 0xf000f0;

    public SmoothCamera smooth = new SmoothCamera();
    public Filter roll = new Filter();
    public Filter fov = new Filter();

    /* Mouse deltas accumulated between client ticks (raw pixels) */
    private double mouseDX;
    private double mouseDY;

    /** Smooth-camera FOV override active (fov filter accelerating) */
    public boolean smoothFovActive;

    /** Smooth-camera FOV of the current frame (valid while {@link #smoothFovActive}) */
    public float smoothFov;

    public void accumulateMouse(double dx, double dy)
    {
        this.mouseDX += dx;
        this.mouseDY += dy;
    }

    /**
     * Per-client-tick smooth camera update (legacy {@code onPlayerTick}
     * smooth branch): vanilla sensitivity replication
     * {@code (sens*0.6+0.2)³ * 8 * 0.15} on the accumulated mouse deltas.
     */
    public void updateSmooth()
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null || mc.player == null || !this.smooth.enabled.get())
        {
            this.mouseDX = this.mouseDY = 0;

            return;
        }

        /* Copied from EntityRenderer (1.12) */
        float sensetivity = (float) (mc.options.getMouseSensitivity().getValue() * 0.6F + 0.2F);
        float finalSensetivity = sensetivity * sensetivity * sensetivity * 8.0F;
        float dx = (float) (this.mouseDX * finalSensetivity * 0.15F);
        float dy = (float) (this.mouseDY * finalSensetivity * 0.15F);

        this.mouseDX = this.mouseDY = 0;

        /* Updating smooth camera */
        this.smooth.update(mc.player, dx, dy);

        /* Roll and FOV acceleration (legacy: ClientProxy.keys) */
        KeyboardHandler keys = KeyboardHandler.HANDLER;

        this.accelerateRoll(direction(keys.addRoll, keys.reduceRoll));
        this.accelerateFov(direction(keys.addFov, keys.reduceFov));
    }

    /**
     * Legacy {@code keys.addX.isKeyDown() ? 1 : (keys.reduceX.isKeyDown() ? -1 : 0F)}.
     *
     * <p>The null guard is a port addition: the bundled keybind family is
     * created lazily by {@link KeyboardHandler#register()} at client init, and
     * {@link #updateSmooth()} must stay callable before that (headless tests,
     * and the smooth-camera tick hook is installed independently).</p>
     */
    public static float direction(KeyBinding add, KeyBinding reduce)
    {
        if (add == null || reduce == null)
        {
            return 0F;
        }

        return add.isPressed() ? 1 : (reduce.isPressed() ? -1 : 0F);
    }

    /** P186 keybind seam: {@code accelerate(direction * roll.factor)} */
    public void accelerateRoll(float direction)
    {
        this.roll.accelerate(direction * this.roll.factor.get());
    }

    /** P186 keybind seam: {@code accelerate(direction * fov.factor)} */
    public void accelerateFov(float direction)
    {
        this.fov.accelerate(direction * this.fov.factor.get());
    }

    /**
     * Per-frame smooth camera orientation (legacy {@code onCameraOrient}
     * smooth branch): writes the smoothed angles back into the player
     * (legacy behavior — the render camera then picks them up) and advances
     * the roll/FOV filters. Returns the (yaw, pitch) to render with, or
     * null when smooth mode is inactive.
     */
    public float[] orientSmooth(float ticks)
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null || mc.player == null || !this.smooth.enabled.get() || mc.isPaused())
        {
            return null;
        }

        /* Yaw and pitch */
        float yaw = this.smooth.getInterpYaw(ticks);
        float pitch = this.smooth.getInterpPitch(ticks);

        mc.player.setYaw(yaw);
        mc.player.setPitch(-pitch);

        mc.player.prevYaw = yaw;
        mc.player.prevPitch = -pitch;

        /* Roll and FOV */
        if (this.roll.acc != 0.0F)
        {
            ClientProxy.control.roll = this.roll.interpolate(ticks);
        }
        else
        {
            this.roll.set(ClientProxy.control.roll);
        }

        if (this.fov.acc != 0.0F)
        {
            /* wixo.1 (C6): the filter advances per tick; the frame gets the
             * interpolated value (it used to read the end-of-tick value). */
            this.fov.value = MathHelper.clamp(this.fov.value, 0.0001F, 179.9999F);
            this.smoothFov = MathHelper.clamp(this.fov.interpolate(ticks), 0.0001F, 179.9999F);

            this.smoothFovActive = true;
        }
        else
        {
            this.fov.set(mc.options.getFov().getValue());
            this.smoothFovActive = false;
        }

        return new float[] {yaw, -pitch};
    }

    /* P178.1 — in-world profile path rendering */

    /**
     * Legacy {@code onLastRender} gate, verbatim: {@code profile_render} on,
     * the runner <b>stopped</b> (otherwise the path flickers over the shot),
     * and a profile with at least one fixture loaded.
     */
    public static boolean shouldRenderProfile(CameraProfile profile, boolean running)
    {
        if (!Aperture.profileRender.get())
        {
            return false;
        }

        if (running)
        {
            return false;
        }

        return profile != null && profile.size() >= 1;
    }

    /**
     * Screen-space-constant sizing for the two anchor dots, which legacy drew
     * as {@code GL_POINTS} with {@code glPointSize(10)}/{@code (8)}.
     *
     * <p>The core profile has no comparable fixed-size point primitive that
     * composes with a {@code VertexConsumerProvider} batch, so the port draws
     * camera-facing quads whose <b>world</b> size is derived from the viewer
     * distance so they cover the same pixel count. Returns the full edge
     * length in blocks for {@code pixels} vertical pixels at {@code distance}
     * blocks under a vertical FOV of {@code fov} degrees.</p>
     */
    public static double pixelSize(double distance, double fov, int screenHeight, double pixels)
    {
        if (screenHeight <= 0 || distance <= 0)
        {
            return 0;
        }

        return 2.0 * distance * Math.tan(Math.toRadians(fov) / 2.0) / screenHeight * pixels;
    }

    /**
     * Legacy {@code drawCard}'s duration label: the formatted time plus a
     * {@code 's'}/{@code 't'} unit suffix picked by {@code editorSeconds}.
     */
    public static String durationLabel(long duration)
    {
        return TimeUtils.formatTime(duration) + (Aperture.editorSeconds.get() ? "s" : "t");
    }

    /**
     * Legacy {@code onLastRender} (a {@code RenderWorldLastEvent} handler) —
     * the in-world profile visualization.
     *
     * <p>Port notes:</p>
     * <ul>
     * <li>The legacy handler also did the manual-fixture frame capture and the
     * "temporarily unbind the active GL shader program" hack. The capture moved
     * to {@code ApertureClient.frame} (P184); the shader unbind is a
     * fixed-function-era workaround with <b>no core-profile equivalent</b> —
     * every draw below names its own {@code RenderLayer}, so the program is
     * bound per layer. Documented non-parity, identical visual result.</li>
     * <li>Legacy translated the tessellator by the interpolated position of the
     * player (or the outside camera entity); the modern equivalent is the
     * render camera position, which <em>is</em> that entity's interpolated
     * position once {@code CameraOutside} calls {@code setCameraEntity}.</li>
     * <li>{@code glLineWidth(4)} has no per-layer equivalent —
     * {@code RenderLayer.getLines()} picks its own width from the window
     * height. Documented non-parity (P215 visual check).</li>
     * </ul>
     */
    public void onLastRender(WorldRenderContext context)
    {
        CameraRunner runner = ClientProxy.runner;
        CameraProfile profile = ClientProxy.control.currentProfile;

        if (!shouldRenderProfile(profile, runner.isRunning()))
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null || context.camera() == null)
        {
            return;
        }

        VertexConsumerProvider consumers = context.consumers();

        if (consumers == null)
        {
            consumers = mc.getBufferBuilders().getEntityVertexConsumers();
        }

        ProfileRenderGeometry geometry = ProfileRenderGeometry.build(profile);

        MatrixStack matrices = context.matrixStack();
        Vec3d camera = context.camera().getPos();

        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        this.drawLines(matrices, consumers, geometry);
        this.drawAnchors(mc, matrices, consumers, geometry, camera);
        this.drawCards(mc, matrices, consumers, geometry);
        this.drawPathPoints(mc, matrices, consumers, geometry);

        matrices.pop();

        if (consumers instanceof VertexConsumerProvider.Immediate immediate)
        {
            immediate.draw();
        }
    }

    /**
     * The raw {@code GL_LINES} stream: consecutive vertex pairs, trailing odd
     * vertex dropped (exactly what GL did).
     */
    private void drawLines(MatrixStack matrices, VertexConsumerProvider consumers, ProfileRenderGeometry geometry)
    {
        if (geometry.lines.size() < 2)
        {
            return;
        }

        VertexConsumer buffer = consumers.getBuffer(RenderLayer.getLines());
        MatrixStack.Entry entry = matrices.peek();

        for (int i = 0; i + 1 < geometry.lines.size(); i += 2)
        {
            ProfileRenderGeometry.LineVertex a = geometry.lines.get(i);
            ProfileRenderGeometry.LineVertex b = geometry.lines.get(i + 1);

            /* RenderLayer.getLines() is POSITION_COLOR_NORMAL — the normal is
             * the line direction the lines shader expands along. Element order
             * position → color → normal is mandatory. */
            float nx = (float) (b.x - a.x);
            float ny = (float) (b.y - a.y);
            float nz = (float) (b.z - a.z);
            float length = MathHelper.sqrt(nx * nx + ny * ny + nz * nz);

            if (length == 0)
            {
                nx = 0;
                ny = 1;
                nz = 0;
            }
            else
            {
                nx /= length;
                ny /= length;
                nz /= length;
            }

            buffer.vertex(entry.getPositionMatrix(), (float) a.x, (float) a.y, (float) a.z).color(a.r, a.g, a.b, a.a).normal(entry.getNormalMatrix(), nx, ny, nz).next();
            buffer.vertex(entry.getPositionMatrix(), (float) b.x, (float) b.y, (float) b.z).color(b.r, b.g, b.b, b.a).normal(entry.getNormalMatrix(), nx, ny, nz).next();
        }
    }

    /**
     * Legacy anchor: a black size-10 point with a white size-8 point on top,
     * at the circular-interpolation centre of a path fixture.
     */
    private void drawAnchors(MinecraftClient mc, MatrixStack matrices, VertexConsumerProvider consumers, ProfileRenderGeometry geometry, Vec3d camera)
    {
        if (geometry.anchors.isEmpty())
        {
            return;
        }

        VertexConsumer buffer = consumers.getBuffer(RenderLayer.getDebugQuads());
        double fov = mc.options == null ? 70 : mc.options.getFov().getValue();
        int height = mc.getWindow() == null ? 0 : mc.getWindow().getScaledHeight();

        for (ProfileRenderGeometry.Anchor anchor : geometry.anchors)
        {
            double distance = Math.sqrt(camera.squaredDistanceTo(anchor.x, anchor.y, anchor.z));

            double outer = pixelSize(distance, fov, height, ProfileRenderGeometry.ANCHOR_OUTER_PIXELS);
            double inner = pixelSize(distance, fov, height, ProfileRenderGeometry.ANCHOR_INNER_PIXELS);

            matrices.push();
            matrices.translate(anchor.x, anchor.y, anchor.z);
            this.billboard(matrices);

            this.colorQuad(buffer, matrices, (float) (outer / 2), 0, 0, 0);
            this.colorQuad(buffer, matrices, (float) (inner / 2), 1, 1, 1);

            matrices.pop();
        }
    }

    /** Legacy {@code drawCard}: colored billboard + index + duration label. */
    private void drawCards(MinecraftClient mc, MatrixStack matrices, VertexConsumerProvider consumers, ProfileRenderGeometry geometry)
    {
        Identifier texture = TEXTURE.toIdentifier();

        for (ProfileRenderGeometry.Card card : geometry.cards)
        {
            matrices.push();
            matrices.translate(card.x, card.y, card.z);
            this.billboard(matrices);

            /* 34px-wide atlas, 16px card cell; UVs mirrored horizontally as
             * in legacy (minX takes the right edge). */
            this.texturedQuad(consumers.getBuffer(RenderLayer.getText(texture)), matrices, ProfileRenderGeometry.CARD_SIZE, 0F, 16F / 34F, 0F, 1F, card.r, card.g, card.b, 0.8F);

            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180));
            matrices.scale(0.05F, 0.05F, 0.05F);
            matrices.translate(0, -3.5, -0.1);

            String indexString = String.valueOf(card.index);
            String durationString = durationLabel(card.duration);

            this.text(mc, matrices, consumers, indexString);

            matrices.translate(0, -13, 0);
            matrices.scale(0.5F, 0.5F, 0.5F);

            this.text(mc, matrices, consumers, durationString);

            matrices.pop();
        }
    }

    /** Legacy {@code drawPathPoint}: small white billboard + point index. */
    private void drawPathPoints(MinecraftClient mc, MatrixStack matrices, VertexConsumerProvider consumers, ProfileRenderGeometry geometry)
    {
        Identifier texture = TEXTURE.toIdentifier();

        for (ProfileRenderGeometry.PathPoint point : geometry.points)
        {
            matrices.push();
            matrices.translate(point.x, point.y, point.z);
            this.billboard(matrices);

            /* 2px-wide cell at u 32–34, 2px tall (v 0 – 2/16) */
            this.texturedQuad(consumers.getBuffer(RenderLayer.getText(texture)), matrices, ProfileRenderGeometry.POINT_SIZE, 32F / 34F, 1F, 0F, 2F / 16F, 1, 1, 1, 1);

            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180));
            matrices.scale(0.03F, 0.03F, 0.03F);
            matrices.translate(0, -3.5, -0.1);
            matrices.translate(0, -12, 0);

            this.text(mc, matrices, consumers, String.valueOf(point.index));

            matrices.pop();
        }
    }

    /**
     * Legacy billboard orientation: {@code rotate(-yaw, 0, 1, 0)} then
     * {@code rotate(pitch, 1, 0, 0)} — the camera editor uses its own preview
     * angles, everything else the render camera's (legacy read
     * {@code RenderManager.playerViewY/playerViewX}).
     */
    private void billboard(MatrixStack matrices)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        float yaw = 0;
        float pitch = 0;

        if (mc != null && mc.currentScreen instanceof GuiCameraEditor)
        {
            Position position = ClientProxy.getCameraEditor().position;

            yaw = position.angle.yaw;
            pitch = position.angle.pitch;
        }
        else if (mc != null && mc.gameRenderer != null && mc.gameRenderer.getCamera() != null)
        {
            yaw = mc.gameRenderer.getCamera().getYaw();
            pitch = mc.gameRenderer.getCamera().getPitch();
        }

        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yaw));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
    }

    /**
     * Legacy quad winding {@code (minX,minY) (minX,maxY) (maxX,maxY)
     * (maxX,minY)} on {@code RenderLayer.getText} — vertex element order
     * position → color → texture → light (POSITION_COLOR_TEXTURE_LIGHT).
     */
    private void texturedQuad(VertexConsumer buffer, MatrixStack matrices, float factor, float u1, float u2, float v1, float v2, float r, float g, float b, float a)
    {
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        buffer.vertex(matrix, -factor, -factor, 0).color(r, g, b, a).texture(u2, v2).light(FULL_BRIGHT).next();
        buffer.vertex(matrix, -factor, factor, 0).color(r, g, b, a).texture(u2, v1).light(FULL_BRIGHT).next();
        buffer.vertex(matrix, factor, factor, 0).color(r, g, b, a).texture(u1, v1).light(FULL_BRIGHT).next();
        buffer.vertex(matrix, factor, -factor, 0).color(r, g, b, a).texture(u1, v2).light(FULL_BRIGHT).next();
    }

    /**
     * Flat colored quad on {@code RenderLayer.getDebugQuads()} — vertex element
     * order position → color (POSITION_COLOR).
     */
    private void colorQuad(VertexConsumer buffer, MatrixStack matrices, float factor, float r, float g, float b)
    {
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        buffer.vertex(matrix, -factor, -factor, 0).color(r, g, b, 1F).next();
        buffer.vertex(matrix, -factor, factor, 0).color(r, g, b, 1F).next();
        buffer.vertex(matrix, factor, factor, 0).color(r, g, b, 1F).next();
        buffer.vertex(matrix, factor, -factor, 0).color(r, g, b, 1F).next();
    }

    /**
     * Legacy {@code fontRenderer.drawString(s, -width / 2, 0, -1)} — centered,
     * white, no shadow.
     */
    private void text(MinecraftClient mc, MatrixStack matrices, VertexConsumerProvider consumers, String string)
    {
        if (mc.textRenderer == null)
        {
            return;
        }

        int width = mc.textRenderer.getWidth(string) / 2;

        mc.textRenderer.draw(string, -width, 0, -1, false, matrices.peek().getPositionMatrix(), consumers, TextRenderer.TextLayerType.NORMAL, 0, FULL_BRIGHT);
    }
}
