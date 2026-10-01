package mchorse.blockbuster.client.video;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

/**
 * The production {@link FrameSource} (wixo.1, CDC §4.3 R4/R6): an asynchronous
 * readback of the main framebuffer through a ring of pixel-pack buffers.
 *
 * <h2>Asynchronous</h2>
 * <p>{@code glReadPixels} into a bound {@code GL_PIXEL_PACK_BUFFER} returns
 * immediately; the copy happens on the GPU while the next frames render. A slot
 * is only mapped once the ring is full, i.e. {@value #RING} − 1 frames later, so
 * mapping no longer stalls the pipeline. The mapped memory is handed straight
 * to the encoder queue, which copies it once into its own buffer — the former
 * intermediate {@code scratch} copy is gone.</p>
 *
 * <h2>Never a skipped frame</h2>
 * <p>The old source emitted blank frames whenever the framebuffer and the video
 * size disagreed (odd window, resize mid-take): the silent black/frozen video of
 * CDC §4.2. Here every frame goes through a {@link ReadbackPlan}: same size is
 * read directly, a one-pixel-larger source is cropped, anything else is blitted
 * (linear, letterboxed) into an output-sized renderbuffer first. The video size
 * never changes during a take.</p>
 *
 * <h2>GL state</h2>
 * <p>Only state Minecraft does not cache is touched (framebuffer, pack-buffer and
 * renderbuffer bindings, pack alignment), and the previous values are restored.
 * No texture is bound and the clear colour is not changed
 * ({@code glClearBufferfv}), so {@code GlStateManager}'s caches stay valid.</p>
 *
 * <p>Render thread only. The framebuffer is resolved per frame: during a
 * custom-resolution recording {@code MinecraftClient.getFramebuffer()} is the
 * capture framebuffer ({@link CustomResolutionCapture}).</p>
 */
public class PboFrameSource implements FrameSource
{
    private static final Logger LOGGER = LoggerFactory.getLogger("blockbuster-video");

    /** Pixel-pack buffers in flight. 3 = frames are mapped two frames after their read. */
    public static final int RING = 3;

    private static final float[] BLACK = {0F, 0F, 0F, 1F};

    private final Supplier<Framebuffer> framebuffer;
    private final int width;
    private final int height;
    private final int glFormat;
    private final int frameSize;

    private final int[] pbos = new int[RING];
    /** FIFO of slots holding a read not yet delivered: {@code pending[head..head+count)}. */
    private final int[] pending = new int[RING];
    private int head;
    private int count;
    private int next;

    private int scaleFbo;
    private int scaleRbo;

    private ByteBuffer blank;
    private ReadbackPlan.Kind lastKind;
    private int lastSourceWidth;
    private int lastSourceHeight;
    private boolean warnedMissing;

    public PboFrameSource(Supplier<Framebuffer> framebuffer, VideoFormat format, int width, int height)
    {
        this.framebuffer = framebuffer;
        this.width = width;
        this.height = height;
        this.glFormat = format.bytesPerPixel() == 4 ? GL12.GL_BGRA : GL12.GL_BGR;
        this.frameSize = format.byteSize(width, height);
    }

    /** Read back whatever {@code MinecraftClient.getFramebuffer()} is at capture time. */
    public static PboFrameSource mainFramebuffer(VideoParams params)
    {
        return new PboFrameSource(
            () ->
            {
                MinecraftClient mc = MinecraftClient.getInstance();

                return mc == null ? null : mc.getFramebuffer();
            },
            params.format(), params.width(), params.height());
    }

    @Override
    public void capture(Consumer out)
    {
        Framebuffer source = this.framebuffer.get();

        if (source == null)
        {
            if (!this.warnedMissing)
            {
                this.warnedMissing = true;
                LOGGER.warn("No framebuffer to capture from; emitting black frames");
            }

            this.deliverBlank(out);

            return;
        }

        if (this.pbos[0] == 0)
        {
            this.allocate();
        }

        ReadbackPlan plan = ReadbackPlan.of(source.textureWidth, source.textureHeight, this.width, this.height);

        this.reportPlanChange(plan, source.textureWidth, source.textureHeight);

        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevAlign = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int readFrom = source.fbo;

        if (plan.kind() == ReadbackPlan.Kind.SCALE)
        {
            this.ensureScaleTarget();

            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.scaleFbo);
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, BLACK);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.fbo);
            GL30.glBlitFramebuffer(0, 0, source.textureWidth, source.textureHeight,
                plan.x0(), plan.y0(), plan.x1(), plan.y1(), GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);

            readFrom = this.scaleFbo;
        }

        int slot = this.next;

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFrom);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, this.pbos[slot]);
        /* Bottom-left origin: for CROP this drops the top row / right column. */
        GL11.glReadPixels(0, 0, this.width, this.height, this.glFormat, GL11.GL_UNSIGNED_BYTE, 0L);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);

        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevAlign);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);

        this.pending[(this.head + this.count) % RING] = slot;
        this.count++;
        this.next = (slot + 1) % RING;

        if (this.count == RING)
        {
            this.deliverOldest(out);
        }
    }

    @Override
    public void flush(Consumer out)
    {
        while (this.count > 0)
        {
            if (!this.deliverOldest(out))
            {
                this.count = 0;
            }
        }
    }

    @Override
    public void release()
    {
        for (int i = 0; i < RING; i++)
        {
            if (this.pbos[i] != 0)
            {
                GL15.glDeleteBuffers(this.pbos[i]);
                this.pbos[i] = 0;
            }
        }

        if (this.scaleFbo != 0)
        {
            GL30.glDeleteFramebuffers(this.scaleFbo);
            GL30.glDeleteRenderbuffers(this.scaleRbo);
            this.scaleFbo = 0;
            this.scaleRbo = 0;
        }

        this.count = 0;
        this.blank = null;
    }

    private void allocate()
    {
        for (int i = 0; i < RING; i++)
        {
            this.pbos[i] = GL15.glGenBuffers();

            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, this.pbos[i]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, this.frameSize, GL15.GL_STREAM_READ);
        }

        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
    }

    private void ensureScaleTarget()
    {
        if (this.scaleFbo != 0)
        {
            return;
        }

        int prevRbo = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        this.scaleRbo = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, this.scaleRbo);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, this.width, this.height);

        this.scaleFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.scaleFbo);
        GL30.glFramebufferRenderbuffer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, this.scaleRbo);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);

        if (status != GL30.GL_FRAMEBUFFER_COMPLETE)
        {
            LOGGER.error("Scaling framebuffer {}x{} is incomplete (status 0x{})", this.width, this.height, Integer.toHexString(status));
        }

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, prevRbo);
    }

    private boolean deliverOldest(Consumer out)
    {
        int slot = this.pending[this.head];

        this.head = (this.head + 1) % RING;
        this.count--;

        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, this.pbos[slot]);

        try
        {
            ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, this.frameSize, GL30.GL_MAP_READ_BIT);

            if (mapped == null)
            {
                LOGGER.error("Could not map the readback buffer; emitting a black frame");

                return this.deliverBlank(out);
            }

            try
            {
                return out.accept(mapped);
            }
            finally
            {
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            }
        }
        finally
        {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        }
    }

    private boolean deliverBlank(Consumer out)
    {
        if (this.blank == null)
        {
            this.blank = ByteBuffer.allocateDirect(this.frameSize);
        }

        this.blank.clear();

        return out.accept(this.blank);
    }

    /**
     * Tell the user once whenever the way frames are produced changes during a
     * take — typically the window was resized while recording at its native
     * size, so frames are now scaled to keep the video size (CDC R1/R4).
     */
    private void reportPlanChange(ReadbackPlan plan, int sourceWidth, int sourceHeight)
    {
        boolean first = this.lastKind == null;
        boolean changed = plan.kind() != this.lastKind || sourceWidth != this.lastSourceWidth || sourceHeight != this.lastSourceHeight;

        this.lastKind = plan.kind();
        this.lastSourceWidth = sourceWidth;
        this.lastSourceHeight = sourceHeight;

        if (first || !changed)
        {
            return;
        }

        if (plan.kind() == ReadbackPlan.Kind.SCALE)
        {
            VideoMessages.warning("blockbuster.video.msg.resized", sourceWidth, sourceHeight, this.width, this.height);
        }
        else
        {
            LOGGER.info("Capture source is {}x{} again; readback {}", sourceWidth, sourceHeight, plan.kind());
        }
    }
}
