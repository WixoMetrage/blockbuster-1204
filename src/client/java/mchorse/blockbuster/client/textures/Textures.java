package mchorse.blockbuster.client.textures;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.mclib.McLib;
import mchorse.mclib.client.gui.utils.keys.LangKey;
import mchorse.mclib.utils.ReflectionUtils;
import mchorse.mclib.utils.resources.MultiResourceLocation;
import mchorse.mclib.utils.resources.MultiskinThread;
import mchorse.mclib.utils.resources.ResourceLocation;
import mchorse.mclib.utils.resources.TextureProcessor;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Client-side wiring for the S7 texture pipeline (roadmap P87/P91): installs the
 * {@link MultiskinThread} GL-upload seam, subscribes the
 * {@code MULTISKIN_PROCESSED} consumer, and registers the resource-reload
 * listener that (mirroring legacy {@code mchorse.mclib.ClientProxy.init}) bumps
 * the l10n cache time and, when {@code multiskin.clear} is set, evicts every
 * multiskin texture so composites regenerate after F3+T.
 */
public class Textures
{
    /**
     * Identifiers under which multiskins are currently registered in the
     * vanilla texture map. Needed because the map is keyed by
     * {@link Identifier} (sanitized) and an Identifier does not self-identify as
     * a multiskin the way a legacy {@code MultiResourceLocation} key did — so
     * the port keeps a lightweight key index (not a value cache; the texture
     * objects still live only in the real map, preserving the "no drift"
     * quirk).
     */
    public static final Set<Identifier> MULTISKIN_KEYS = Collections.synchronizedSet(new HashSet<Identifier>());

    public static void init()
    {
        installSeams();

        /* MULTISKIN_PROCESSED consumer (extruded 3D skin regeneration, S6/P79)
         * is the legacy GuiBlockbusterPanels.onMultiskinLoad hook and is now
         * subscribed there (P135, from the client entrypoint) so it survives
         * even when no dashboard is open. Do NOT re-register the forceReload
         * consumer here — a second subscriber would rebuild extruded layers
         * twice per composite. */

        /* Legacy mclib ClientProxy resource-reload listener */
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener()
        {
            @Override
            public Identifier getFabricId()
            {
                return new Identifier("mclib", "multiskin_reload");
            }

            @Override
            public void reload(ResourceManager manager)
            {
                LangKey.lastTime = System.currentTimeMillis();

                MinecraftClient mc = MinecraftClient.getInstance();

                if (mc != null && McLib.multiskinClear.get())
                {
                    clearMultiTextures(ReflectionUtils.getTextures(mc.getTextureManager()));
                }
            }
        });
    }

    /**
     * The three {@link MultiskinThread} / {@link TextureProcessor} seams, split
     * out of {@link #init()} so a headless test can install them without a
     * client, a resource manager or a GL context.
     *
     * <p>Idempotent — every assignment is a constant delegate.</p>
     */
    public static void installSeams()
    {
        /* P91: composited multiskin → GL upload on the render thread with
         * GL_NEAREST min/mag (legacy MultiskinThread upload). */
        MultiskinThread.uploader = Textures::uploadMultiskin;

        /* P91 / S22 batch V-J: legacy's compositor handshake. The worker polls
         * "is a texture object registered for this location yet?" and only
         * composites once it is — see isMultiskinReady. */
        MultiskinThread.ready = Textures::isMultiskinReady;

        /* P91: in multithreaded mode postProcess runs on the MultiskinThread
         * worker; legacy fired MultiskinProcessedEvent via addScheduledTask so
         * the GL consumer (ModelExtrudedLayer.forceReload / P79) ran on the
         * render thread. Install the render-thread dispatcher here (the shared
         * TextureProcessor defaults to inline for headless tests). */
        TextureProcessor.renderThreadExecutor = (runnable) -> MinecraftClient.getInstance().execute(runnable);

        /* P268 (S22 batch W-C): legacy resolved multiskin children through
         * Minecraft's resource manager, not off the classpath — see
         * multiskinStream. The shared TextureProcessor keeps the classpath
         * fallback as its declaration default for the headless case. */
        TextureProcessor.streamProvider = Textures::multiskinStream;
    }

    /**
     * Legacy {@code TextureProcessor.process}'s child lookup, restored (S7/P91,
     * wired in S22 <b>P268</b>).
     *
     * <p>1.12.2 resolved every multiskin child with
     * {@code Minecraft.getMinecraft().getResourceManager().getResource(child.path)}.
     * That single call carried a precedence order the port has to reproduce
     * rather than improve on, because in 1.12.2 the resource manager was the
     * <i>only</i> loader involved:</p>
     *
     * <ol>
     * <li><b>McLib's coremod branch</b> — {@code SimpleReloadableResourceManager
     * .getResource} was patched to return {@code RLUtils.getStreamForMultiskin}
     * for a child that is itself a {@code MultiResourceLocation}, ahead of every
     * pack. Nested composites therefore composite recursively.</li>
     * <li><b>Blockbuster's {@code ActorsPack}</b> — the only pack declaring the
     * {@code b.a}, {@code http} and {@code https} domains, so those namespaces
     * never reach the vanilla stack at all: {@code b.a} walks
     * {@code ModelPack.folders} (first folder that has the file wins) and
     * {@code http}/{@code https} download.</li>
     * <li><b>The vanilla pack stack</b> for every other namespace —
     * {@code FallbackResourceManager.getResource} iterates its packs
     * <i>backwards</i>, so the highest-priority enabled <b>resource pack</b>
     * wins, then mod jars, then the vanilla jar.</li>
     * <li><b>Miss</b> — {@code FileNotFoundException}, caught by
     * {@code process()}, which appends {@code null} and skips the layer.</li>
     * </ol>
     *
     * <p>The port reproduces all four rungs by doing exactly what legacy did:
     * asking the resource manager. Rungs 1 and 2 are
     * {@code ReloadableResourceManagerImplMixin}, injected at {@code HEAD} of
     * {@code getResource} and cancelling — which is what puts them ahead of the
     * pack stack, the same ordering the coremod and the appended
     * {@code IResourcePack} gave legacy. Nothing is re-implemented here; the one
     * bespoke rung would be a second answer to a question the mixin already
     * answers (see batch V-I on what two tables for one question cost).</p>
     *
     * <p>The identifier comes from {@link ResourceLocation#toIdentifier()}, so
     * the P252 pre-flattening redirect and the P249 {@code VerbatimPaths}
     * recording apply to multiskin children exactly as they do to every other
     * bind — the child's own string is untouched and still serializes byte for
     * byte.</p>
     *
     * <p><b>Threading.</b> This runs on the {@code MultiskinThread} worker in
     * multithreaded mode. Legacy read the resource manager off-thread from the
     * same worker; the port keeps that rather than marshalling, because
     * marshalling would deadlock the poll (the worker blocks on the composite it
     * is producing). Same trade-off, and the same one
     * {@link #isMultiskinReady(MultiResourceLocation)} documents.</p>
     */
    public static InputStream multiskinStream(ResourceLocation location) throws IOException
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        ResourceManager manager = mc == null ? null : mc.getResourceManager();

        if (manager == null)
        {
            /* No client (dedicated server, headless test): there is no resource
             * manager to consult, so fall back to the shared classpath lookup
             * rather than failing every child. */
            return TextureProcessor.defaultStream(location);
        }

        return multiskinStream(manager, location);
    }

    /**
     * {@link #multiskinStream(ResourceLocation)} against an explicit resource
     * manager (headless-testable — the live one needs a {@code MinecraftClient}).
     */
    public static InputStream multiskinStream(ResourceManager manager, ResourceLocation location) throws IOException
    {
        if (manager == null || location == null)
        {
            throw new FileNotFoundException(String.valueOf(location));
        }

        Identifier id = location.toIdentifier();
        Optional<Resource> resource = manager.getResource(id);

        if (resource.isPresent())
        {
            return resource.get().getInputStream();
        }

        /* Legacy's FallbackResourceManager threw FileNotFoundException here;
         * 1.20.4 returns an empty Optional. Throwing keeps the contract
         * TextureProcessor.process() is written against — caught there, logged,
         * and the layer is skipped (the S07 multiskin-child placeholder). */
        throw new FileNotFoundException(id.toString());
    }

    /**
     * Legacy {@code MultiskinThread.run}'s gate, verbatim in intent:
     * {@code ReflectionUtils.getTextures(mc.renderEngine).get(location) != null}
     * — the compositor waits until something has actually registered a texture
     * object for this composite, then overwrites its pixels.
     *
     * <p><b>Why this is not optional (S22, batch V-J).</b> The seam shipped as
     * {@code (l) -> true} with no writer, allowlisted on the argument that "P87
     * auto-creates a placeholder so the predicate is always true anyway". It
     * does not: {@link TextureRegistry} is a <i>view</i> over the live vanilla
     * texture map and creates nothing, and 1.20.4's
     * {@code TextureManager.registerTexture} runs {@code loadTexture} (which is
     * what reaches {@code RLUtils.getStreamForMultiskin} and enqueues the job)
     * <b>before</b> it puts the texture in the map — verified with {@code javap}
     * against the loom-cache named jar. So at enqueue time the map entry does
     * not exist yet, and an always-true predicate composites into a texture that
     * may never appear: {@code Textures.uploadMultiskin} then finds {@code null},
     * drops the finished image, and the location has already been popped off the
     * queue, so nothing ever retries. Legacy's poll loop is the retry.</p>
     *
     * <p>Off-thread map read, as legacy: the worker peeks the live map every
     * 100&nbsp;ms while the render thread may be writing it. That race is
     * 1.12.2's, unchanged — the only alternative is a shadow index, and the port
     * deliberately keeps "operate on the real map, no registry that can drift".</p>
     */
    public static boolean isMultiskinReady(MultiResourceLocation location)
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null || mc.getTextureManager() == null)
        {
            /* No client, no texture objects and no uploader — nothing to hand
             * a composite to, so never "ready". */
            return false;
        }

        return isMultiskinReady(ReflectionUtils.getTextures(mc.getTextureManager()), location);
    }

    /** {@link #isMultiskinReady(MultiResourceLocation)} against an explicit map (headless-testable). */
    public static boolean isMultiskinReady(Map<Identifier, AbstractTexture> textures, MultiResourceLocation location)
    {
        return textures != null && location != null && textures.get(location.toIdentifier()) != null;
    }

    /**
     * Legacy {@code ClientProxy.clearMultiTextures}: delete every multiskin
     * entry from the texture map (closing the GL/native object) so bound
     * multiskins re-composite on next use. Pure map surgery — headless-testable.
     */
    public static void clearMultiTextures(Map<Identifier, AbstractTexture> textures)
    {
        synchronized (MULTISKIN_KEYS)
        {
            for (Identifier id : MULTISKIN_KEYS)
            {
                AbstractTexture texture = textures.remove(id);

                if (texture != null)
                {
                    texture.close();
                }
            }

            MULTISKIN_KEYS.clear();
        }
    }

    /**
     * Upload a composited multiskin onto the texture already registered for
     * {@code location} (the 1×1 placeholder from the multithreaded bind path,
     * or the first-load resource texture).
     *
     * <p>Uses {@link NativeImages#fromBufferedImage} + {@link TextureUtil#prepareImage}
     * + {@link NativeImage#upload} rather than a raw {@code glTexImage2D} of a
     * hand-packed {@code ByteBuffer}:</p>
     * <ul>
     *   <li>{@code prepareImage} reallocates storage to the composite size
     *       (the placeholder is 1×1; {@code NativeImage.upload} is a
     *       {@code glTexSubImage2D} and cannot grow the texture);</li>
     *   <li>{@code NativeImage.upload} sets {@code GL_UNPACK_*} correctly.
     *       A bare {@code glTexImage2D} after any prior Minecraft texture load
     *       inherits leftover {@code GL_UNPACK_ROW_LENGTH}/{@code SKIP_*} and
     *       reads the tightly-packed RGBA buffer with the wrong stride —
     *       multicoloured noise with transparent holes.</li>
     * </ul>
     */
    private static void uploadMultiskin(MultiResourceLocation location, BufferedImage image)
    {
        NativeImage nativeImage;

        try
        {
            nativeImage = NativeImages.fromBufferedImage(image);
        }
        catch (Exception e)
        {
            return;
        }

        Identifier id = location.toIdentifier();
        int w = nativeImage.getWidth();
        int h = nativeImage.getHeight();

        MinecraftClient.getInstance().execute(() ->
        {
            try
            {
                TextureRegistry registry = TextureRegistry.get();
                AbstractTexture texture = registry.get(id);

                if (texture == null)
                {
                    return;
                }

                int glId = texture.getGlId();

                /* Grow (or shrink) the GL texture to the composite size, then
                 * upload pixels. prepareImage binds the id. */
                TextureUtil.prepareImage(glId, w, h);

                /* Defensive: clear any leftover unpack state before upload so
                 * a future raw-GL path cannot inherit a bad ROW_LENGTH. */
                GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
                GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
                GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
                GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);

                RenderSystem.bindTexture(glId);
                nativeImage.upload(0, 0, 0, false);

                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            }
            finally
            {
                nativeImage.close();
            }
        });
    }
}
