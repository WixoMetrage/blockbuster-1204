package mchorse.blockbuster.client.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegSinkTest
{
    @Test
    void tailKeepsTheLastLinesAndTheLastProgressState(@TempDir Path dir) throws Exception
    {
        File log = dir.resolve("video.log").toFile();

        Files.write(log.toPath(), ("ffmpeg version 9.0.1\n"
            + "Input #0, rawvideo\n"
            + "frame=  10 fps=5\rframe=  20 fps=6\rframe=  30 fps=7\n"
            + "\n"
            + "[libx264] error: something\n"
            + "Conversion failed!\n").getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of("frame=  30 fps=7", "[libx264] error: something", "Conversion failed!"), FfmpegSink.tail(log, 3));
    }

    @Test
    void tailOfAMissingLogIsEmpty(@TempDir Path dir)
    {
        assertTrue(FfmpegSink.tail(dir.resolve("absent.log").toFile(), 4).isEmpty());
    }

    @Test
    void outputIsTheLastArgumentInTheExportFolder(@TempDir Path dir)
    {
        FfmpegSink sink = new FfmpegSink(List.of("ffmpeg", "-i", "-", "take.mp4"), dir.toFile(), null);

        assertEquals(dir.resolve("take.mp4").toFile().getAbsolutePath(), sink.output());
    }
}
