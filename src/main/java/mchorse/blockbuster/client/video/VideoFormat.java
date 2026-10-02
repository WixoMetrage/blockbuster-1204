package mchorse.blockbuster.client.video;

/**
 * Pixel layout a {@link FrameSink} receives frames in (P200).
 *
 * <p>GL readback for opaque video uses {@code GL_BGR} (3 bytes/pixel, matches
 * ffmpeg {@code -pix_fmt bgr24}); alpha capture (the P203 transparent-video
 * addition) uses {@code GL_BGRA} (4 bytes/pixel). This enum stays GL-free — the
 * GL pixel-format constant lives in the readback adapter
 * ({@code VideoRecorder}) — so the encoding pipeline is headlessly testable.</p>
 */
public enum VideoFormat
{
    /** 3 bytes/pixel, blue-green-red order (opaque video, ffmpeg {@code bgr24}). */
    BGR(3, false, "bgr24"),
    /** 4-byte rows whose 4th byte is ignored: the fast BGRA readback for opaque video (wixo.1). */
    BGR0(4, false, "bgr0"),

    /** 4 bytes/pixel, blue-green-red-alpha order (transparent video, P203). */
    BGRA(4, true, "bgra");

    private final int bytesPerPixel;
    private final boolean alpha;
    private final String ffmpegPixFmt;

    VideoFormat(int bytesPerPixel, boolean alpha, String ffmpegPixFmt)
    {
        this.bytesPerPixel = bytesPerPixel;
        this.alpha = alpha;
        this.ffmpegPixFmt = ffmpegPixFmt;
    }

    public int bytesPerPixel()
    {
        return this.bytesPerPixel;
    }

    public boolean hasAlpha()
    {
        return this.alpha;
    }

    public String ffmpegPixFmt()
    {
        return this.ffmpegPixFmt;
    }

    /** Row-stride / total byte count for a {@code width * height} frame. */
    public int byteSize(int width, int height)
    {
        return width * height * this.bytesPerPixel;
    }
}
