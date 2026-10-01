package mchorse.blockbuster.client.video;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Fallback {@link FrameSink} that writes a PNG sequence (P201). Selected
 * automatically when {@link FfmpegLocator#checkAvailable} is {@code false}, so
 * capture never hard-fails just because ffmpeg is missing.
 *
 * <p>Frames arrive bottom-up (GL row order) and BGR(A), so this sink flips rows
 * and swizzles to ARGB itself (the ffmpeg path leaves both to the {@code vflip}
 * filter and {@code bgr24} input). Uses {@link ImageIO} rather than Minecraft's
 * {@code NativeImage} so it is headlessly testable — a documented divergence;
 * both are RGBA-capable, which doubles this sink as the P203 alpha writer.</p>
 */
public class PngSequenceSink implements FrameSink
{
    private final File parentDir;
    private final String name;

    private File dir;
    private int width;
    private int height;
    private VideoFormat format;
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
        this.width = width;
        this.height = height;
        this.format = format;
        this.counter = 1;
        this.dir = new File(this.parentDir, this.name);

        this.dir.mkdirs();
    }

    @Override
    public void frame(ByteBuffer data) throws IOException
    {
        int bpp = this.format.bytesPerPixel();
        boolean alpha = this.format.hasAlpha();
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage image = new BufferedImage(this.width, this.height, type);
        int base = data.position();

        for (int y = 0; y < this.height; y++)
        {
            /* GL rows are bottom-up: image row y (top-down) is buffer row (h-1-y). */
            int srcRow = this.height - 1 - y;

            for (int x = 0; x < this.width; x++)
            {
                int i = base + (srcRow * this.width + x) * bpp;
                int b = data.get(i) & 0xff;
                int g = data.get(i + 1) & 0xff;
                int r = data.get(i + 2) & 0xff;
                int a = alpha ? (data.get(i + 3) & 0xff) : 0xff;

                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }

        File file = new File(this.dir, String.format("%s_%06d.png", this.name, this.counter));

        ImageIO.write(image, "png", file);
        this.counter++;
    }

    @Override
    public void end() throws IOException
    {
        /* Nothing to finalize: each PNG is complete on write. */
    }

    @Override
    public String output()
    {
        return new File(this.parentDir, this.name).getAbsolutePath();
    }
}
