package mchorse.blockbuster.client.video;

/**
 * How one frame is read back from a framebuffer of {@code source} size into a
 * video frame of {@code output} size. Decided per frame, so a window resized
 * mid-recording changes how frames are read, never the size of the video
 * (CDC §4.3 R1/R4): there is no input for which this produces "skip the frame".
 *
 * <p>Pure — no GL, headlessly testable.</p>
 */
public final class ReadbackPlan
{
    public enum Kind
    {
        /** Same size: read the whole framebuffer. */
        DIRECT,
        /** Source is at most one pixel larger per axis: read the output-sized bottom-left rect. */
        CROP,
        /** Anything else: blit with linear filtering into an output-sized target, letterboxed. */
        SCALE
    }

    private final Kind kind;
    private final int x0;
    private final int y0;
    private final int x1;
    private final int y1;

    private ReadbackPlan(Kind kind, int x0, int y0, int x1, int y1)
    {
        this.kind = kind;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
    }

    /** Whether reading {@code output} from {@code source} only drops an odd row/column. */
    public static boolean isCrop(int sourceWidth, int sourceHeight, int outputWidth, int outputHeight)
    {
        int dw = sourceWidth - outputWidth;
        int dh = sourceHeight - outputHeight;

        return dw >= 0 && dw <= 1 && dh >= 0 && dh <= 1;
    }

    public static ReadbackPlan of(int sourceWidth, int sourceHeight, int outputWidth, int outputHeight)
    {
        if (sourceWidth == outputWidth && sourceHeight == outputHeight)
        {
            return new ReadbackPlan(Kind.DIRECT, 0, 0, outputWidth, outputHeight);
        }

        if (isCrop(sourceWidth, sourceHeight, outputWidth, outputHeight))
        {
            return new ReadbackPlan(Kind.CROP, 0, 0, outputWidth, outputHeight);
        }

        /* Fit the whole source inside the output, preserving its aspect ratio;
         * the bars around it stay black. */
        double scale = Math.min(outputWidth / (double) Math.max(1, sourceWidth), outputHeight / (double) Math.max(1, sourceHeight));
        int w = Math.max(1, (int) Math.round(sourceWidth * scale));
        int h = Math.max(1, (int) Math.round(sourceHeight * scale));
        int x = (outputWidth - w) / 2;
        int y = (outputHeight - h) / 2;

        return new ReadbackPlan(Kind.SCALE, x, y, x + w, y + h);
    }

    public Kind kind()
    {
        return this.kind;
    }

    /** Destination rectangle inside the output frame (for {@link Kind#SCALE}: the letterboxed image). */
    public int x0()
    {
        return this.x0;
    }

    public int y0()
    {
        return this.y0;
    }

    public int x1()
    {
        return this.x1;
    }

    public int y1()
    {
        return this.y1;
    }

    /** Whether black bars surround the image. */
    public boolean letterboxed()
    {
        return this.kind == Kind.SCALE && (this.x0 != 0 || this.y0 != 0);
    }
}
