package mchorse.blockbuster.client.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PngSequenceSinkTest
{
    /** One 2×2 frame, bottom-up: bottom row red / green, top row blue / white. */
    private static ByteBuffer frame(VideoFormat format, int alpha)
    {
        int[][] bgr = {{0, 0, 255}, {0, 255, 0}, {255, 0, 0}, {255, 255, 255}};
        ByteBuffer buffer = ByteBuffer.allocate(format.byteSize(2, 2));

        for (int[] pixel : bgr)
        {
            buffer.put((byte) pixel[0]).put((byte) pixel[1]).put((byte) pixel[2]);

            if (format.bytesPerPixel() == 4)
            {
                buffer.put((byte) alpha);
            }
        }

        return buffer.flip();
    }

    private static BufferedImage write(Path dir, VideoFormat format, int alpha) throws Exception
    {
        PngSequenceSink sink = new PngSequenceSink(dir.toFile(), "take");

        sink.begin(2, 2, format);
        sink.frame(frame(format, alpha));
        sink.end();

        return ImageIO.read(new File(dir.toFile(), "take/take_000001.png"));
    }

    private static void assertFlippedColours(BufferedImage image, int alpha)
    {
        int a = alpha << 24;

        assertEquals(a | 0x0000ff, image.getRGB(0, 0), "top-left = blue (GL top row)");
        assertEquals(a | 0xffffff, image.getRGB(1, 0), "top-right = white");
        assertEquals(a | 0xff0000, image.getRGB(0, 1), "bottom-left = red (GL first row)");
        assertEquals(a | 0x00ff00, image.getRGB(1, 1), "bottom-right = green");
    }

    @Test
    void bgrRowsAreFlippedWithoutPerPixelConversion(@TempDir Path dir) throws Exception
    {
        assertFlippedColours(write(dir, VideoFormat.BGR, 0xff), 0xff);
    }

    @Test
    void bgr0IgnoresTheFourthByte(@TempDir Path dir) throws Exception
    {
        assertFlippedColours(write(dir, VideoFormat.BGR0, 0x12), 0xff);
    }

    @Test
    void bgraKeepsAlpha(@TempDir Path dir) throws Exception
    {
        assertFlippedColours(write(dir, VideoFormat.BGRA, 0x80), 0x80);
    }
}
