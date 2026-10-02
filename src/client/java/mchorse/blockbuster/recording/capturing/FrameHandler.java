package mchorse.blockbuster.recording.capturing;

import java.util.Objects;

import mchorse.blockbuster.Blockbuster;
import mchorse.blockbuster.ClientProxy;
import mchorse.blockbuster.aperture.CameraHandler;
import mchorse.blockbuster.network.Dispatcher;
import mchorse.blockbuster.network.common.PacketDamageControlCheck;
import mchorse.blockbuster.network.common.recording.PacketRecordingTick;
import mchorse.blockbuster.recording.RecordRecorder;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/**
 * Client-side frame capture + damage-control probe (roadmap P116 tail, wired by
 * S22 P244).
 *
 * <p>1:1 port of 1.12.2 {@code recording/capturing/FrameHandler}, a
 * client-only {@code PlayerTickEvent} END subscriber registered from
 * {@code ClientProxy.preLoad}. It does two unrelated things, both of which were
 * dark before P244 because the class did not exist:</p>
 *
 * <ol>
 *   <li><b>Client frame capture.</b> {@code ClientHandlerPlayerRecording}
 *       starts a {@code Mode.FRAMES} recorder on the client mirror
 *       ({@code ClientProxy.manager}) and, on stop, uploads
 *       {@code recorder.record.frames} to the server as
 *       {@code PacketFramesChunk}es. Nothing ever called
 *       {@link RecordRecorder#record} on that client recorder, so every upload
 *       was an empty frame list.</li>
 *   <li><b>Damage-control probe.</b> The only production sender of
 *       {@link PacketDamageControlCheck}: while
 *       {@code Blockbuster.damageControlMessage} is on and the Aperture camera
 *       editor is closed, the block currently under the crosshair is reported to
 *       the server whenever it changes. The server answers with the action-bar
 *       "this block belongs to scene X" warning
 *       ({@code ServerHandlerDamageControlCheck}). The {@code last} field is the
 *       legacy dedup — one packet per new block, not one per tick.</li>
 * </ol>
 *
 * <p><b>Deliberate deviation.</b> 1.12.2's {@code Minecraft.objectMouseOver}
 * was never null while in a world and carried {@code BlockPos.ORIGIN} on a MISS
 * (see {@code EntityRenderer.getMouseOver}), so looking at the sky sent a probe
 * for {@code (0, 0, 0)} every time the crosshair left a block. 1.20.4's
 * {@code crosshairTarget} carries the floored ray end instead, which would make
 * that stray probe both noisier and occasionally meaningful. The port therefore
 * probes only on a real {@code Type.BLOCK} hit and treats everything else as
 * "nothing" ({@code last = null}), exactly like legacy's entity-hit branch.
 * Server-side the two are indistinguishable — a non-block probe hit
 * {@code !world.isAirBlock(pos)} and no-oped in the overwhelming majority of
 * cases.</p>
 *
 * <p><b>Pause parity (S22).</b> The Forge event this ports could not fire while
 * the game was paused; {@code END_CLIENT_TICK} can. See {@link #tick} for the
 * full derivation — without its gate, pausing mid-recording desynced the take by
 * exactly the pause duration.</p>
 *
 * <p>Legacy source:
 * blockbuster-1.12/src/main/java/mchorse/blockbuster/recording/capturing/FrameHandler.java</p>
 */
public class FrameHandler
{
    private static FrameHandler instance;

    /** Legacy {@code last} — the block the previous probe reported. */
    private BlockPos last = null;

    /**
     * Install the client-tick handler (idempotent). One call site:
     * {@code mchorse.blockbuster.client.UnsentPacketWiring#install()}.
     */
    public static void install()
    {
        if (instance != null)
        {
            return;
        }

        instance = new FrameHandler();

        ClientTickEvents.END_CLIENT_TICK.register(instance::onPlayerTick);
    }

    /** The installed handler, or {@code null} before {@link #install()}. */
    public static FrameHandler get()
    {
        return instance;
    }

    public void onPlayerTick(MinecraftClient mc)
    {
        if (mc == null)
        {
            return;
        }

        ClientPlayerEntity player = mc.player;

        if (player == null)
        {
            return;
        }

        this.tick(player, ClientProxy.manager.recorders.get(player), mc.isPaused(), mc.crosshairTarget);
    }

    /**
     * The legacy {@code PlayerTickEvent} END body, with the client's pause state
     * passed in so it can be driven headlessly (a {@code MinecraftClient} cannot
     * exist in the test JVM).
     *
     * <p><b>The pause gate is the port's whole reason for this split.</b> Legacy
     * never wrote one because Forge's {@code PlayerTickEvent} could not fire
     * while the game was paused: it is posted from {@code EntityPlayer.onUpdate}
     * ({@code FMLCommonHandler.onPlayerPreTick}/{@code onPlayerPostTick}), which
     * the client only reaches through {@code WorldClient.updateEntities()}, and
     * {@code Minecraft.runTick} guards that call with
     * {@code if (!this.isGamePaused)}. Fabric's {@code END_CLIENT_TICK} is
     * injected at {@code MinecraftClient.tick()}'s RETURN instead, and
     * {@code MinecraftClient.render} calls {@code tick()} unconditionally — only
     * the <i>contents</i> of {@code tick()} (including
     * {@code ClientWorld.tickEntities()}) are guarded by the {@code paused}
     * field. Ungated, the escape-menu pause therefore kept appending client
     * frames while the server-side action slots stood still, and the saved
     * {@code .dat} was permanently out of sync by the length of the pause.</p>
     *
     * <p>{@code MinecraftClient.isPaused()} is the exact 1.20.4 analogue of
     * 1.12.2's {@code Minecraft.isGamePaused}: both are
     * "integrated server + a screen that pauses + not LAN-published", so
     * multiplayer (where opening a GUI pauses nothing) keeps recording in both
     * versions.</p>
     *
     * @param player   the recording player.
     * @param recorder that player's client-side recorder, or {@code null}.
     * @param paused   {@code MinecraftClient.isPaused()}.
     * @param target   {@code MinecraftClient.crosshairTarget}.
     */
    public void tick(PlayerEntity player, RecordRecorder recorder, boolean paused, HitResult target)
    {
        if (paused)
        {
            return;
        }

        if (recorder != null)
        {
            recorder.record(player);

            /* wixo (CDC §6, R4): the server closes this tick's actions on it */
            Dispatcher.sendToServer(new PacketRecordingTick());
        }

        if (Blockbuster.damageControlMessage.get() && !CameraHandler.isCameraEditorOpen())
        {
            this.probe(targetBlock(target));
        }
    }

    /**
     * The dedup half of the legacy probe, split out so it can be driven
     * headlessly: sends a {@link PacketDamageControlCheck} only when the
     * targeted block differs from the previous tick's.
     *
     * @param pos the block under the crosshair, or {@code null} for "nothing".
     * @return whether a packet was sent.
     */
    public boolean probe(BlockPos pos)
    {
        if (pos == null)
        {
            this.last = null;

            return false;
        }

        boolean changed = !Objects.equals(this.last, pos);

        if (changed)
        {
            Dispatcher.sendToServer(new PacketDamageControlCheck(pos));
        }

        this.last = pos;

        return changed;
    }

    /** Legacy {@code objectMouseOver.getBlockPos()}; see the class deviation note. */
    public static BlockPos targetBlock(HitResult target)
    {
        if (target instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK)
        {
            return hit.getBlockPos();
        }

        return null;
    }

    /** Test seam — the last block reported to the server. */
    public BlockPos getLast()
    {
        return this.last;
    }
}
