package mchorse.blockbuster.client.textures;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.imageio.ImageIO;

import mchorse.blockbuster.Blockbuster;
import mchorse.blockbuster.ClientProxy;
import mchorse.blockbuster.client.ActorsPack;
import mchorse.blockbuster.utils.mclib.GifFolder;
import mchorse.blockbuster.utils.mclib.GifFrameFile;
import mchorse.mclib.utils.NextTickQueue;
import mchorse.mclib.utils.resources.ResourceLocation;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/**
 * S22 P231 + P232 — the client-side installer that makes the S7 skin pipeline
 * actually run.
 *
 * <p>P89/P90 landed every heavy piece (decoder, {@code GifFolder}/
 * {@code GifFrameFile}, {@code GifTexture}, {@code GifProcessThread},
 * {@code URLDownloadThread}) but nothing ever assigned the seams
 * {@link ActorsPack} exposes, so on a real client a {@code .gif} skin produced a
 * missing texture and a URL skin never downloaded. This class is the one place
 * that assigns them; {@code BlockbusterClient} calls {@link #install()} once.</p>
 *
 * <p>{@link ActorsPack} deliberately stays free of {@code MinecraftClient}/GL
 * imports so {@code ActorsPackResolveTest} can run without a game — hence a
 * separate installer rather than inlining any of this there.</p>
 *
 * <h3>Re-entrancy: why the deferral is mandatory</h3>
 *
 * <p>{@code ActorsPack.openFile} is reached from inside
 * {@code TextureManager.registerTexture} &rarr; {@code ResourceTexture.load}
 * &rarr; the resource-manager mixin. Calling {@code GifProcessThread.create}
 * inline would re-enter {@code registerTexture} while the texture map is being
 * mutated — and worse, it would lose the race against that same
 * {@code registerTexture}:</p>
 *
 * <pre>
 *   registerTexture(id, resourceTexture):
 *     loadTexture(...)          // opens the stream → schedule(create)
 *       create() puts GifTexture under id
 *     textures.put(id, resourceTexture)   // overwrites GifTexture
 *     closeTexture(GifTexture)            // animation gone forever
 * </pre>
 *
 * <p>Legacy's {@code new Thread(() -> mc.addScheduledTask(...))} double-hop
 * existed for exactly this reason: the outer thread guaranteed the scheduled
 * task could never run <i>during</i> {@code load}. Collapsing that to
 * {@code MinecraftClient.execute} looked equivalent, but {@code execute} runs
 * the runnable <b>inline</b> when already on the render thread — which is
 * exactly where texture loads happen. The animated proxy was therefore
 * registered and immediately clobbered by the static first-frame
 * {@code ResourceTexture}, so whole-{@code .gif} skins never animated (frame
 * picks still worked because their identifiers are different). Creation is
 * therefore posted on {@link NextTickQueue#CLIENT}, which drains at
 * {@code END_CLIENT_TICK} after the load has finished putting the placeholder.</p>
 *
 * <h3>Deliberate parity deviation: the STB/GIF placeholder</h3>
 *
 * <p>1.12.2 served the <b>whole {@code .gif} bytes</b> as the resource for both a
 * gif and a gif-frame request, and its ImageIO-backed {@code TextureUtil} decoded
 * that into a static first frame — so the skin was visible for the tick between
 * resolve and {@code create()}. On 1.20.4 {@code NativeImage.read} is STB-backed
 * and <b>cannot decode GIF at all</b>: serving the raw bytes throws, the texture
 * becomes the missing-texture checkerboard, and that is <i>worse</i> than legacy.
 * {@link #handleGif} therefore re-encodes the requested frame as PNG and serves
 * that instead. The bytes on the wire differ from legacy; the pixels a player sees
 * do not (they are strictly more correct — a frame request gets frame N rather
 * than legacy's frame 0). If the gif does not decode at all, {@code null} is
 * returned and the raw bytes are served exactly as legacy did.</p>
 */
@Environment(EnvType.CLIENT)
public class SkinPipelineWiring
{
    /**
     * Gif locations whose {@code create()} has been scheduled but has not run
     * yet. Legacy's {@code handleGif} guard was
     * {@code GifProcessThread.THREADS.containsKey(location)}, but {@code THREADS}
     * is only populated <i>during</i> {@code create()} — across the
     * schedule/execute hop it is always empty, so the guard could never fire and
     * a burst of frame requests each queued their own decode. Keeping a pending
     * set makes legacy's own intent effective; the guard itself is unchanged.
     */
    private static final Set<Identifier> PENDING = Collections.synchronizedSet(new HashSet<Identifier>());

    /**
     * SEAM (tests): where deferred {@code create()} tasks are posted. {@code null}
     * uses the live {@link MinecraftClient} (its {@code execute} is 1.12.2's
     * {@code addScheduledTask}); headless tests install a collecting executor so
     * the hop is observable without a client.
     */
    public static Executor renderExecutor;

    private SkinPipelineWiring()
    {}

    /**
     * Assign every {@link ActorsPack} seam. Idempotent — re-installing simply
     * overwrites with the same values. (The GIF animation clock used to be
     * registered here too; S22 P247 moved it into the client player-tick loop.)
     */
    public static void install()
    {
        /* P231 (1/3): materialise ".gif>/frameN.png" requests. */
        ActorsPack.gifFileFactory = (folder, child) -> new GifFrameFile(folder, child);

        /* P231 (2/3): the scheduling half of legacy ActorsPack.getInputStream. */
        ActorsPack.gifHandler = SkinPipelineWiring::handleGif;

        /* P232: http/https skins. Legacy hanldeURLSkins (sic) lives on
         * URLDownloadThread; this is the only thing that ever calls it. */
        ActorsPack.urlSkinResolver = (domain, path) ->
            URLDownloadThread.handleURLSkins(new ResourceLocation(domain, path));

        /* P231 (3/3): the animation clock. Nothing advanced it before, so a
         * registered GIF froze on frame 0 forever.
         *
         * S22 P247: the registration is gone — the clock is the *fifth and last*
         * duty of legacy PlayerHandler.updateClient, and it now runs from there
         * (BlockbusterClient.registerPlayerTick installs GifTexture::updateTick
         * as PlayerHandler.gifPump). This class kept its own END_CLIENT_TICK
         * listener only because P247's loop did not exist yet; keeping both
         * would advance every GIF twice per tick. {@link #tick()} survives as
         * the standalone, player-gated form for tests. */
    }

    /**
     * One client tick of the GIF animation clock, with the local-player gate
     * spelled out.
     *
     * <p>Legacy {@code PlayerHandler.updateClient} ran once per client tick for
     * the <b>local player only</b> and {@code GifTexture.updateTick()} was its
     * last statement, so GIFs do not advance with no player in world (main menu,
     * disconnected). That is 1.12.2 behaviour and is kept; the gate reuses the
     * existing {@code ClientProxy.clientPlayerPresent} probe so it is stubbable
     * headlessly.</p>
     *
     * <p>In production the same gate is the {@code mc.player != null} check in
     * {@code PlayerHandler.endTickClient}, which is why this method is no longer
     * registered on the client tick itself (S22 P247).</p>
     */
    public static void tick()
    {
        if (ClientProxy.clientPlayerPresent.getAsBoolean())
        {
            GifTexture.updateTick();
        }
    }

    /**
     * Legacy {@code ActorsPack.handleGif} plus the placeholder decode. Returns
     * the stream to serve for this request, or {@code null} to fall back to the
     * raw gif bytes.
     *
     * <p><b>Legacy quirk preserved:</b> for an Optifine {@code _n}/{@code _s}
     * frame request that resolved onto a sibling {@code name_n.gif}, the
     * {@code gifPath} the caller computed is the <i>base</i> {@code name.gif}
     * (legacy truncated the request path at its last {@code '>'}), so the normal
     * map's frames would be registered under the base gif's identifiers. That is
     * exactly what 1.12.2 did. The path is effectively dead here anyway: P217.1's
     * Iris bridge reads {@code _n}/{@code _s} gifs off disk through
     * {@code PbrFramePacker}, never through the resource manager.</p>
     */
    public static InputStream handleGif(String domain, String gifPath, File file)
    {
        GifFolder folder;
        int index = 0;

        if (file instanceof GifFrameFile)
        {
            GifFrameFile frame = (GifFrameFile) file;

            folder = frame.parent;
            index = Math.max(frame.index, 0);
        }
        else
        {
            /* GifFolder decodes (or hits its own static cache) in its ctor. */
            folder = new GifFolder(file.getPath());
        }

        if (folder == null || !folder.exists())
        {
            /* Undecodable — legacy still streamed the bytes and let the texture
             * loader fail. Total: never throw out of a resource resolve. */
            return null;
        }

        schedule(new ResourceLocation(domain, gifPath).toIdentifier(), folder);

        return placeholder(folder, index);
    }

    /**
     * Defer {@code GifProcessThread.create} until <b>after</b> the in-flight
     * {@code ResourceTexture} load has finished registering its placeholder.
     *
     * <p>Legacy: {@code new Thread(() -> mc.addScheduledTask(() -> create(...)))}.
     * The outer thread was not cosmetic — it made the scheduled task unable to
     * run during {@code load}. See the class javadoc for the overwrite race that
     * a plain {@code MinecraftClient.execute} re-introduced.</p>
     *
     * <p>Production posts on {@link NextTickQueue#CLIENT} (drained at end of
     * client tick on the render thread). Tests install {@link #renderExecutor}
     * and collect tasks themselves.</p>
     */
    private static void schedule(Identifier location, GifFolder gif)
    {
        if (GifProcessThread.THREADS.containsKey(location) || !PENDING.add(location))
        {
            return;
        }

        Runnable task = () ->
        {
            PENDING.remove(location);
            GifProcessThread.create(location, gif);
        };

        Executor executor = renderExecutor;

        if (executor != null)
        {
            executor.execute(task);

            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null)
        {
            /* No client to hop onto: drop the schedule rather than run GL work
             * on whatever thread we are on. */
            PENDING.remove(location);

            return;
        }

        /* Never client.execute: that runs inline on the render thread and loses
         * the race against TextureManager.registerTexture's post-load put. */
        NextTickQueue.CLIENT.post(task);
    }

    /**
     * Re-encode one decoded frame as PNG so 1.20.4's STB-backed
     * {@code NativeImage.read} can consume it. Total — {@code null} on any
     * failure, which makes the caller fall back to the legacy raw bytes.
     */
    private static InputStream placeholder(GifFolder folder, int index)
    {
        try
        {
            BufferedImage frame = folder.gif.getFrame(index);

            if (frame == null)
            {
                return null;
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            if (!ImageIO.write(frame, "png", bytes))
            {
                return null;
            }

            return new ByteArrayInputStream(bytes.toByteArray());
        }
        catch (Exception e)
        {
            Blockbuster.LOGGER.warn("Failed to build a PNG placeholder for a GIF skin frame {}", index, e);

            return null;
        }
    }

    /** Test/teardown helper. */
    public static void resetPending()
    {
        PENDING.clear();
    }
}
