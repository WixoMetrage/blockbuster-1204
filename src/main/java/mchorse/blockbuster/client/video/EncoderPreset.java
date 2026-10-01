package mchorse.blockbuster.client.video;

/**
 * One opaque-video encoding preset (wixo.1, CDC R5): the encoder arguments of
 * an ffmpeg command, wrapped by {@link #template(boolean)} in the parts every
 * preset shares — raw input, BT.709 conversion and tagging, optional audio mux.
 *
 * <h2>Colours for Premiere (BT.709)</h2>
 * <p>The 1.12.2/Minema template converted RGB to YUV with swscale's default
 * matrix, <b>BT.601</b>, and tagged nothing; Premiere then reads HD as BT.709
 * and the colours shift. Every preset converts with
 * {@code scale=out_color_matrix=bt709:out_range=tv} and tags the stream
 * BT.709/limited range, so the file looks like the game.</p>
 *
 * <p>Presets are registered in {@link EncoderPresets}; adding one is one
 * {@code register} call.</p>
 */
public final class EncoderPreset
{
    /** The id of the preset that uses the user's own {@code video.arguments*} templates. */
    public static final String CUSTOM = "custom";

    static final String INPUT = "-f rawvideo -pix_fmt %PIX_FMT% -s %WIDTH%x%HEIGHT% -r %FPS% -i -";
    /* setparams: ffmpeg 7+ lets frame colour properties override the -color_* options, so they are set on the frames too. */
    static final String COLOUR_FILTER = ",scale=out_color_matrix=bt709:out_range=tv,format=yuv420p,setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv";
    static final String COLOUR_TAGS = "-pix_fmt yuv420p -colorspace bt709 -color_primaries bt709 -color_trc bt709 -color_range tv";
    static final String AUDIO_CODEC = "-c:a aac -b:a 128k -shortest";

    private final String id;
    private final String label;
    private final String encoder;
    private final String extension;

    /**
     * @param id        config value of {@code video.encoder}
     * @param label     short name shown in chat and in the capture panel
     * @param encoder   the codec arguments, e.g. {@code -c:v libx264 -qp 18}
     * @param extension container extension without the dot
     */
    public EncoderPreset(String id, String label, String encoder, String extension)
    {
        this.id = id;
        this.label = label;
        this.encoder = encoder;
        this.extension = extension;
    }

    public String id()
    {
        return this.id;
    }

    public String label()
    {
        return this.label;
    }

    /** Lang key of the preset's name in the capture panel. */
    public String langKey()
    {
        return "blockbuster.video.encoder." + this.id;
    }

    public String encoder()
    {
        return this.encoder;
    }

    public String extension()
    {
        return this.extension;
    }

    /** The full ffmpeg argument template, with or without the scene audio input. */
    public String template(boolean audio)
    {
        StringBuilder sb = new StringBuilder(INPUT);

        if (audio)
        {
            sb.append(" -i %AUDIO_TRACK%");
        }

        sb.append(" -vf %FILTERS%").append(COLOUR_FILTER);

        if (audio)
        {
            sb.append(" -af apad");
        }

        sb.append(' ').append(this.encoder).append(' ').append(COLOUR_TAGS);

        if (audio)
        {
            sb.append(' ').append(AUDIO_CODEC);
        }

        return sb.append(" %NAME%.").append(this.extension).toString();
    }
}
