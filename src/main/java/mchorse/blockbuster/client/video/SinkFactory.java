package mchorse.blockbuster.client.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chooses the {@link FrameSink} for a recording (P200/P201): an {@link FfmpegSink}
 * when ffmpeg is available, otherwise the {@link PngSequenceSink} fallback with a
 * logged warning. Extracted so the selection rule is unit testable without
 * spawning a process.
 */
public final class SinkFactory
{
    private static final Logger LOGGER = LoggerFactory.getLogger("blockbuster-video");

    private SinkFactory()
    {}

    /**
     * @param params          the recording
     * @param ffmpegPath      configured ffmpeg path (default {@code "ffmpeg"})
     * @param ffmpegAvailable result of {@link FfmpegLocator#checkAvailable}
     * @param encoderLog      whether to keep a per-movie {@code .log}
     */
    public static FrameSink select(VideoParams params, String ffmpegPath, boolean ffmpegAvailable, boolean encoderLog)
    {
        /* S22/P250: the four templates are LIVE config reads (video.arguments*),
         * not the folded DEFAULT_* constants — a user may point them at any
         * codec/container, and the alpha pair is what makes P203 reachable. */
        boolean alpha = params.format().hasAlpha();
        /* wixo.1: opaque video uses the chosen encoding preset (CDC R5); the
         * transparent templates stay user-defined. */
        String video = alpha ? VideoConfig.argumentsAlpha() : VideoConfig.opaqueTemplate(false);
        String audio = alpha ? VideoConfig.argumentsAlphaAudio() : VideoConfig.opaqueTemplate(true);

        return select(params, ffmpegPath, ffmpegAvailable, encoderLog, video, audio);
    }

    /**
     * Explicit-template variant (P203): chooses the ffmpeg sink only when ffmpeg
     * is present <b>and</b>, for an alpha recording, the video template's output
     * pixel format can actually carry alpha ({@link AlphaSinkPolicy}). Otherwise
     * — no ffmpeg, or alpha requested into a non-alpha template such as the
     * default {@code yuv420p} preset — falls back to the always-RGBA PNG sequence
     * with a logged warning, so the alpha channel is never silently dropped.
     */
    public static FrameSink select(VideoParams params, String ffmpegPath, boolean ffmpegAvailable,
        boolean encoderLog, String videoTemplate, String audioTemplate)
    {
        if (ffmpegAvailable && !AlphaSinkPolicy.shouldFallbackToPng(params, videoTemplate))
        {
            String binary = FfmpegLocator.resolve(ffmpegPath);

            return FfmpegSink.create(params, binary, videoTemplate, audioTemplate, encoderLog);
        }

        if (!ffmpegAvailable)
        {
            LOGGER.warn("ffmpeg unavailable — capturing '{}' as a PNG sequence instead", params.name());
        }
        else
        {
            LOGGER.warn("alpha capture requested but the ffmpeg template cannot carry alpha — capturing '{}' as an RGBA PNG sequence instead", params.name());
        }

        return new PngSequenceSink(params.exportDir(), params.name());
    }
}
