package mchorse.blockbuster.client.video;

import javax.imageio.ImageIO;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Fallback {@link FrameSink} that writes a PNG sequence (P201). Selected
 * automatically when {@link FfmpegLocator#checkAvailable} is {@code false}, so
 * capture never hard-fails just because ffmpeg is missing.
 *
 * <p>wixo.1 (CDC R5): no per-pixel conversion. The image is built once over a
 * byte array laid out exactly like the readback (BGR, BGR0 or BGRA: the colour
 * model reads R, G, B — and A — at the matching byte offsets), so each frame is
 * just its rows copied in reverse order (GL rows are bottom-up). Uses
 * {@link ImageIO} rather than Minecraft's {@code NativeImage} so it is headlessly
 * testable; RGBA-capable, which doubles this sink as the alpha writer.</p>
 */
public class PngSequenceSink implements FrameSink
{
    private final File parentDir;
    private final String name;

    private File dir;
    private int width;
    private int height;
    private int rowBytes;
    private byte[] pixels;
    private BufferedImage image;
    private int counter;

    public PngSequenceSink(File parentDir, String name)
    {
        this.parentDir = parentDir;
        this.name = name;
    }

    /** The per-recording output folder (available after {@link #begin}). */
    public File directory()
    {
        return this.dir;
    }

    @Override
    public void begin(int width, int height, VideoFormat format) throws IOException
    {
        int bpp = format.bytesPerPixel();
        boolean alpha = format.hasAlpha();

        this.width = width;
        this.height = height;
        this.rowBytes = width * bpp;
        this.counter = 1;
        this.dir = new File(this.parentDir, this.name);
        this.pixels = new byte[this.rowBytes * height];

        /* Byte offsets of R, G, B (and A) inside one BGR/BGR0/BGRA pixel. */
        int[] offsets = alpha ? new int[] {2, 1, 0, 3} : new int[] {2, 1, 0};
        ComponentColorModel model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB),
            alpha, false, alpha ? Transparency.TRANSLUCENT : Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
        WritableRaster raster = Raster.createInterleavedRaster(new DataBufferByte(this.pixels, this.pixels.length),
            width, height, this.rowBytes, bpp, offsets, null);

        this.image = new BufferedImage(model, raster, false, null);
        this.dir.mkdirs();
    }

    @Override
    public void frame(ByteBuffer data) throws IOException
    {
        int base = data.position();

        for (int y = 0; y < this.height; y++)
        {
            /* GL rows are bottom-up: image row y (top-down) is buffer row (h-1-y). */
            data.position(base + (this.height - 1 - y) * this.rowBytes);
            data.get(this.pixels, y * this.rowBytes, this.rowBytes);
        }

        data.position(base + this.rowBytes * this.height);

        File file = new File(this.dir, String.format("%s_%06d.png", this.name, this.counter));

        ImageIO.write(this.image, "png", file);
        this.counter++;
    }

    @Override
    public void end() throws IOException
    {
        /* Nothing to finalize: each PNG is complete on write. */
        this.pixels = null;
        this.image = null;
    }

    @Override
    public String output()
    {
        return new File(this.parentDir, this.name).getAbsolutePath();
    }
}
