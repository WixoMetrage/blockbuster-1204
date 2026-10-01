package mchorse.blockbuster.client.video;

/**
 * The pure half of capture-resolution handling: turn {@code video.width}/
 * {@code video.height} plus the window's framebuffer size into the size the
 * recording is made at, and say <i>how</i> that size is produced.
 *
 * <h2>The 1.12.2 contract (Minema 3.7.1)</h2>
 *
 * <pre>
 * int w = frameWidth.get();
 * if (w == 0) w = Display.getWidth();          // "Set to 0 to use the current window width"
 * if (useVideoEncoder) w = snapResolution.snap(w);   // MOD2 by default: snap DOWN to even
 * </pre>
 *
 * <p>and the display-size override engaged exactly when the snapped size
 * differs from the window ({@code MinemaConfig.useFrameSize()}). Both rules are
 * kept: {@code 0} means "the window", the output is snapped down to even
 * ({@link VideoParams#clampEven(int)}), and an odd window therefore engages the
 * custom path.</p>
 *
 * <h2>wixo.1 — the output size is always the requested size</h2>
 *
 * <p>Before wixo.1 a blocked custom path fell back to the <b>raw window size</b>,
 * which is odd for most windowed setups (1920×1009): {@code VideoParams} then
 * rounded it to even, the readback found a framebuffer one pixel larger than
 * the encoder was promised and every frame was dropped — a black or frozen video
 * with no message (CDC §4.2.1). Now the output size never depends on whether the
 * custom path engaged; only the {@link Mode} does, and the readback adapts
 * (see {@link ReadbackPlan}).</p>
 *
 * <p>Pure — no GL, no Minecraft, headlessly testable.</p>
 */
public final class CaptureResolution
{
    /** How the output frames are produced. */
    public enum Mode
    {
        /** The window framebuffer already has the output size: read it as is. */
        NATIVE,
        /** The world is rendered into a capture framebuffer of the output size. */
        CUSTOM,
        /** Window framebuffer one pixel larger (odd size): the extra row/column is dropped. */
        CROPPED,
        /** Window framebuffer rendered natively, then scaled (letterboxed) to the output size. */
        SCALED;

        /** Whether the frames are not a native render at the output size (CDC R4: yellow message). */
        public boolean degraded()
        {
            return this == SCALED || this == CROPPED;
        }
    }

    /** The resolved recording size plus how and why it is produced. */
    public static final class Decision
    {
        private final int width;
        private final int height;
        private final Mode mode;
        private final String reason;

        Decision(int width, int height, Mode mode, String reason)
        {
            this.width = width;
            this.height = height;
            this.mode = mode;
            this.reason = reason;
        }

        /** The width of the video file — always even. */
        public int width()
        {
            return this.width;
        }

        /** The height of the video file — always even. */
        public int height()
        {
            return this.height;
        }

        public Mode mode()
        {
            return this.mode;
        }

        /** Whether the world must be rendered into a capture framebuffer. */
        public boolean custom()
        {
            return this.mode == Mode.CUSTOM;
        }

        /** Why the custom path was not used, or {@code null}. */
        public String reason()
        {
            return this.reason;
        }

        /** The same size, produced by scaling the window instead (custom path refused late). */
        public Decision scaled(String reason)
        {
            return new Decision(this.width, this.height, Mode.SCALED, reason);
        }
    }

    private CaptureResolution()
    {}

    /**
     * Resolve the recording size.
     *
     * @param configWidth  {@code video.width} ({@code 0} ⇒ the window)
     * @param configHeight {@code video.height} ({@code 0} ⇒ the window)
     * @param windowWidth  the window framebuffer's width in pixels
     * @param windowHeight the window framebuffer's height in pixels
     * @param blocker      a reason the custom path cannot engage, or {@code null}
     */
    public static Decision resolve(int configWidth, int configHeight, int windowWidth, int windowHeight, String blocker)
    {
        int width = VideoParams.clampEven(configWidth > 0 ? configWidth : windowWidth);
        int height = VideoParams.clampEven(configHeight > 0 ? configHeight : windowHeight);

        if (width == windowWidth && height == windowHeight)
        {
            return new Decision(width, height, Mode.NATIVE, null);
        }

        if (blocker == null)
        {
            return new Decision(width, height, Mode.CUSTOM, null);
        }

        Mode fallback = ReadbackPlan.isCrop(windowWidth, windowHeight, width, height) ? Mode.CROPPED : Mode.SCALED;

        return new Decision(width, height, fallback, blocker);
    }
}
