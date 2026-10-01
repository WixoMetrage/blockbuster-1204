package mchorse.blockbuster.client.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EncoderPresetsTest
{
    @Test
    void untouchedLegacyConfigMigratesToTheDefaultPreset()
    {
        assertEquals(EncoderPresets.DEFAULT,
            EncoderPresets.resolve("", VideoConfig.DEFAULT_ARGUMENTS, VideoConfig.DEFAULT_ARGUMENTS_AUDIO));
    }

    @Test
    void editedLegacyTemplatesAreKeptAsCustom()
    {
        String mine = VideoConfig.DEFAULT_ARGUMENTS.replace("-qp 18", "-crf 20");

        assertEquals(EncoderPreset.CUSTOM, EncoderPresets.resolve("", mine, VideoConfig.DEFAULT_ARGUMENTS_AUDIO));
    }

    @Test
    void storedIdWinsAndUnknownFallsBackToDefault()
    {
        assertEquals("nvenc", EncoderPresets.resolve("nvenc", "x", "y"));
        assertEquals(EncoderPreset.CUSTOM, EncoderPresets.resolve(EncoderPreset.CUSTOM, "x", "y"));
        assertEquals(EncoderPresets.DEFAULT, EncoderPresets.resolve("removed_preset", "x", "y"));
    }

    @Test
    void inputFormatFollowsTheTemplate()
    {
        assertEquals(VideoFormat.BGR, EncoderPresets.inputFormat(VideoConfig.DEFAULT_ARGUMENTS, false));
        assertEquals(VideoFormat.BGR0, EncoderPresets.inputFormat(EncoderPresets.get("x264").template(false), false));
        assertEquals(VideoFormat.BGRA, EncoderPresets.inputFormat(VideoConfig.DEFAULT_ARGUMENTS, true));
    }

    @Test
    void presetTemplatesConvertAndTagBt709()
    {
        for (EncoderPreset preset : EncoderPresets.all())
        {
            for (boolean audio : new boolean[] {false, true})
            {
                String template = preset.template(audio);

                assertTrue(template.contains("out_color_matrix=bt709"), template);
                assertTrue(template.contains("-colorspace bt709"), template);
                assertTrue(template.endsWith("%NAME%." + preset.extension()), template);
                assertEquals(audio, template.contains("%AUDIO_TRACK%"), template);
                assertEquals("%PIX_FMT%", EncoderPresets.inputPixelFormat(template));
            }
        }
    }

    /**
     * End to end against the real ffmpeg when it is installed: the template
     * must encode BGR0 frames into an H.264 yuv420p file tagged BT.709.
     */
    @Test
    void defaultPresetEncodesWithTheInstalledFfmpeg(@TempDir Path dir) throws Exception
    {
        assumeTrue(FfmpegLocator.checkAvailable("ffmpeg"), "ffmpeg not installed");

        String probe = encode(dir, EncoderPresets.get(EncoderPresets.DEFAULT));

        assertTrue(probe.contains("codec_name=h264"), probe);
        assertTrue(probe.contains("pix_fmt=yuv420p"), probe);
        assertTrue(probe.contains("color_space=bt709"), probe);
        assertTrue(probe.contains("color_primaries=bt709"), probe);
        assertTrue(probe.contains("width=64"), probe);
    }

    /** NVENC needs an NVIDIA GPU: skipped (not failed) elsewhere. */
    @Test
    void nvencPresetEncodesWhenTheGpuSupportsIt(@TempDir Path dir) throws Exception
    {
        assumeTrue(FfmpegLocator.checkAvailable("ffmpeg"), "ffmpeg not installed");

        String probe;

        try
        {
            probe = encode(dir, EncoderPresets.get("nvenc"));
        }
        catch (AssertionError | java.io.IOException e)
        {
            assumeTrue(false, "NVENC unavailable: " + e.getMessage());
            return;
        }

        assertTrue(probe.contains("codec_name=h264"), probe);
        assertTrue(probe.contains("color_space=bt709"), probe);
    }

    private static String encode(Path dir, EncoderPreset preset) throws Exception
    {
        int width = 64;
        int height = 36;
        VideoParams params = new VideoParams(width, height, 30, 0, 1, VideoFormat.BGR0, dir.toFile(), "take", null);
        List<String> args = FfmpegArgs.build("ffmpeg", preset.template(false), params, "take", null);
        File log = dir.resolve("video.log").toFile();
        FfmpegSink sink = new FfmpegSink(args, dir.toFile(), log);
        ByteBuffer frame = ByteBuffer.allocateDirect(params.frameByteSize());

        sink.begin(width, height, params.format());

        for (int i = 0; i < 10; i++)
        {
            frame.clear();

            while (frame.hasRemaining())
            {
                frame.put((byte) (i * 20));
            }

            frame.flip();
            sink.frame(frame);
        }

        try
        {
            sink.end();
        }
        catch (Exception e)
        {
            throw new AssertionError(e.getMessage() + " " + FfmpegSink.tail(log, 4));
        }

        File out = new File(sink.output());

        assertTrue(out.isFile() && out.length() > 0, "no output file " + out);

        return ffprobe(out);
    }

    private static String ffprobe(File file) throws Exception
    {
        List<String> cmd = new ArrayList<>(List.of("ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=codec_name,pix_fmt,width,color_space,color_primaries,color_transfer",
            "-of", "default=noprint_wrappers=1", file.getAbsolutePath()));
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = process.getInputStream().readAllBytes();

        process.waitFor(30, TimeUnit.SECONDS);

        return new String(out, StandardCharsets.UTF_8);
    }
}
