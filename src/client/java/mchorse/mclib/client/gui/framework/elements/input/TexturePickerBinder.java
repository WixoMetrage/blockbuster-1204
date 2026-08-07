package mchorse.mclib.client.gui.framework.elements.input;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.blockbuster.client.textures.GifTexture;
import mchorse.mclib.utils.resources.ResourceLocation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;

/**
 * S22/P242 — the production {@link GuiTexturePicker.TextureBinder}, the seam P40
 * declared and left {@code null} ("until S7 installs the real implementation").
 * S7 landed in P87/P91; this is that install.
 *
 * <p>Legacy did two things at every one of these call sites:
 * {@code mc.renderEngine.bindTexture(rl)} — which registers and loads the
 * texture on demand, so a bind is also a "does this resolve?" probe — followed
 * by {@code glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH/HEIGHT)}
 * on whatever ended up bound. Both survive verbatim; the only 1.20.4 changes
 * are:</p>
 *
 * <ul>
 * <li>{@code TextureManager.getTexture(id)} is the 1.20.4 spelling of
 * "register a {@code SimpleTexture} for this location if absent, then hand it
 * back". Like 1.12.2 it never throws for a missing file — it parks the shared
 * missing-sprite under the id — so a bind of a bogus path is accepted with the
 * missing texture's size, exactly as legacy accepted it.</li>
 * <li>the bind is doubled: {@code GlStateManager._bindTexture} for the
 * {@code glGetTexLevelParameteri} query (which reads the <i>bound</i> texture,
 * there being no named query in the core profile) and
 * {@code RenderSystem.setShaderTexture(0, id)} so the picker's following
 * {@code GuiDraw.drawBillboard} samples it — 1.12.2's single
 * {@code bindTexture} did both jobs at once.</li>
 * <li>textures we own on the CPU side ({@link NativeImageBackedTexture} — GIF
 * frames, runtime uploads) answer their size from the {@code NativeImage}
 * instead of GL, the same shortcut {@code TextureSizes} takes.</li>
 * </ul>
 *
 * <p>Throwing is the picker's "don't use this selection" signal, so it is
 * reserved for the cases legacy would also have failed on: no location, no
 * client/texture manager (headless), or a texture object with no GL name. A
 * multiskin {@link mchorse.mclib.utils.resources.MultiResourceLocation}
 * resolves through its sanitized identifier like any other location; note that
 * the three consumers ({@code GuiTexturePicker.selectCurrent}, its preview quad
 * and {@code GuiMultiSkinEditor}) only ever bind <i>child</i> paths and the
 * currently picked file, never a composite.</p>
 */
public class TexturePickerBinder implements GuiTexturePicker.TextureBinder
{
    private int width;
    private int height;

    /** Install as the P40 seam. Idempotent. */
    public static void install()
    {
        GuiTexturePicker.textureBinder = new TexturePickerBinder();
    }

    @Override
    public void bind(ResourceLocation location) throws Exception
    {
        Identifier id = location == null ? null : location.toIdentifier();

        if (id == null)
        {
            throw new IllegalArgumentException("No texture location to bind");
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        TextureManager manager = mc == null ? null : mc.getTextureManager();

        if (manager == null)
        {
            throw new IllegalStateException("No texture manager (headless)");
        }

        /* Legacy bindTexture: registers + loads on demand. For a whole .gif the
         * first getTexture schedules GifProcessThread (next tick); once the
         * proxy is registered, resolveFrame picks the current animation frame
         * so the picker preview animates rather than freezing on frame 0. */
        manager.getTexture(id);

        int ticks = mc.player == null ? GifTexture.globalTick : mc.player.age;
        Identifier bound = GifTexture.resolveFrame(id, ticks, mc.getTickDelta());
        AbstractTexture texture = manager.getTexture(bound);
        int glId = texture == null ? 0 : texture.getGlId();

        if (glId <= 0)
        {
            throw new IllegalStateException("Texture " + id + " has no GL object");
        }

        RenderSystem.setShaderTexture(0, bound);
        GlStateManager._bindTexture(glId);

        if (texture instanceof NativeImageBackedTexture && ((NativeImageBackedTexture) texture).getImage() != null)
        {
            this.width = ((NativeImageBackedTexture) texture).getImage().getWidth();
            this.height = ((NativeImageBackedTexture) texture).getImage().getHeight();
        }
        else
        {
            this.width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            this.height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        }
    }

    @Override
    public int getWidth()
    {
        return this.width;
    }

    @Override
    public int getHeight()
    {
        return this.height;
    }
}
