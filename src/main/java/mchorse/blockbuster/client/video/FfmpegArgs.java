package mchorse.blockbuster.client.video;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure argument assembly for the ffmpeg process (P201). Kept separate from
 * {@link FfmpegSink} so template substitution is golden-testable with no
 * process spawn.
 *
 * <p><b>Deliberate divergence from BBS:</b> BBS substitutes placeholders into
 * the whole template string <i>before</i> {@code split(" ")}, which shatters any
 * path containing a space (and it works around that by wrapping
 * {@code %AUDIO_TRACK%} in literal quotes that then leak into the argv). Here we
 * tokenize first, then substitute per token, so {@code %AUDIO_TRACK%} /
 * {@code %NAME%} expand to a single argv element regardless of spaces — no
 * quoting needed. Only {@code %FILTERS%} carries internal commas (never spaces),
 * so it too stays one token.</p>
 */
public final class FfmpegArgs
{
    private FfmpegArgs()
    {}

    /**
     * Build the full argv (binary first) for the given recording.
     *
     * @param binary     resolved ffmpeg executable path
     * @param template   argument template (video-only or audio variant)
     * @param params     the recording parameters (dimensions, fps, filters)
     * @param name       the output filename stem (no extension; the template's
     *                   {@code %NAME%.mp4} adds it)
     * @param audioTrack absolute audio-file path for {@code %AUDIO_TRACK%}, or
     *                   {@code null} when the template has no audio input
     */
    public static List<String> build(String binary, String template, VideoParams params,
        String name, String audioTrack)
    {
        List<String> args = new ArrayList<>();

        args.add(binary);

        String width = String.valueOf(params.width());
        String height = String.valueOf(params.height());
        String fps = String.valueOf(params.captureFrameRate());
        String filters = params.filters();

        for (String token : template.split(" "))
        {
            if (token.isEmpty())
            {
                continue;
            }

            token = token.replace("%WIDTH%", width);
            token = token.replace("%HEIGHT%", height);
            token = token.replace("%FPS%", fps);
            token = token.replace("%PIX_FMT%", params.format().ffmpegPixFmt());
            token = token.replace("%FILTERS%", filters);
            token = token.replace("%NAME%", name);

            if (audioTrack != null)
            {
                token = token.replace("%AUDIO_TRACK%", audioTrack);
            }

            args.add(token);
        }

        return args;
    }
}
