package mchorse.blockbuster.client.video;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of {@link EncoderPreset}s (CDC §2.3) plus the rules that pick the
 * preset and the raw pixel format of a recording.
 *
 * <p>The built-in presets follow what Blockbuster 1.12.2 used through Minema
 * 3.7.1 ({@code libx264 -preset ultrafast -tune zerolatency -qp 18}), with only
 * the BT.709 colour fix added (see {@link EncoderPreset}).</p>
 */
public final class EncoderPresets
{
    /** The default: the 1.12.2/Minema encoder settings. */
    public static final String DEFAULT = "x264";

    private static final Map<String, EncoderPreset> PRESETS = new LinkedHashMap<>();

    static
    {
        register(new EncoderPreset(DEFAULT, "H.264",
            "-c:v libx264 -preset ultrafast -tune zerolatency -qp 18", "mp4"));
        register(new EncoderPreset("x264_hq", "H.264 qp 10",
            "-c:v libx264 -preset ultrafast -tune zerolatency -qp 10", "mp4"));
        register(new EncoderPreset("nvenc", "H.264 NVENC",
            "-c:v h264_nvenc -preset p4 -rc constqp -qp 18", "mp4"));
    }

    private EncoderPresets()
    {}

    public static void register(EncoderPreset preset)
    {
        PRESETS.put(preset.id(), preset);
    }

    /** The preset with this id, or {@code null} for {@link EncoderPreset#CUSTOM} / an unknown id. */
    public static EncoderPreset get(String id)
    {
        return id == null ? null : PRESETS.get(id);
    }

    /** Registered presets in registration order (the panel's cycle order). */
    public static List<EncoderPreset> all()
    {
        return Collections.unmodifiableList(new ArrayList<>(PRESETS.values()));
    }

    /** Every selectable id: the presets, then {@link EncoderPreset#CUSTOM}. */
    public static List<String> ids()
    {
        List<String> ids = new ArrayList<>(PRESETS.keySet());

        ids.add(EncoderPreset.CUSTOM);

        return ids;
    }

    /**
     * The value of {@code video.encoder} for a config written before wixo.1,
     * where the key did not exist (empty). A user who edited the ffmpeg
     * templates keeps them ({@code custom}); everyone else gets the default.
     * Never rewrites a template (CDC §2.1).
     */
    public static String migrate(String arguments, String argumentsAudio)
    {
        boolean edited = !VideoConfig.DEFAULT_ARGUMENTS.equals(arguments)
            || !VideoConfig.DEFAULT_ARGUMENTS_AUDIO.equals(argumentsAudio);

        return edited ? EncoderPreset.CUSTOM : DEFAULT;
    }

    /** Resolve a stored id: empty ⇒ migrate, unknown ⇒ default. */
    public static String resolve(String stored, String arguments, String argumentsAudio)
    {
        if (stored == null || stored.trim().isEmpty())
        {
            return migrate(arguments, argumentsAudio);
        }

        if (EncoderPreset.CUSTOM.equals(stored) || PRESETS.containsKey(stored))
        {
            return stored;
        }

        return DEFAULT;
    }

    /**
     * The raw pixel layout to read back for {@code template}. 4-byte BGRA is
     * the fast GPU path (CDC R6); only a template that explicitly asks for
     * {@code bgr24} input (the 1.12.2 default, still in user templates) gets
     * 3-byte rows.
     */
    public static VideoFormat inputFormat(String template, boolean alpha)
    {
        if (alpha)
        {
            return VideoFormat.BGRA;
        }

        return "bgr24".equals(inputPixelFormat(template)) ? VideoFormat.BGR : VideoFormat.BGR0;
    }

    /** The {@code -pix_fmt} given before the {@code -i -} input, or {@code null}. */
    static String inputPixelFormat(String template)
    {
        if (template == null)
        {
            return null;
        }

        String[] tokens = template.split(" ");
        String format = null;

        for (int i = 0; i < tokens.length; i++)
        {
            if ("-i".equals(tokens[i]))
            {
                return format;
            }

            if ("-pix_fmt".equals(tokens[i]) && i + 1 < tokens.length)
            {
                format = tokens[i + 1];
            }
        }

        return format;
    }
}
