package mchorse.blockbuster.client.video;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegArgsTest
{
    private static VideoParams params(int width, int height, String name)
    {
        return new VideoParams(width, height, 60, 0, 1, VideoFormat.BGR, new File("movies"), name, null);
    }

    @Test
    void substitutesPlaceholdersPerToken()
    {
        List<String> args = FfmpegArgs.build("ffmpeg", "-s %WIDTH%x%HEIGHT% -r %FPS% -vf %FILTERS% %NAME%.mp4",
            params(1920, 1080, "take"), "take", null);

        assertEquals(List.of("ffmpeg", "-s", "1920x1080", "-r", "60.0", "-vf", "vflip", "take.mp4"), args);
    }

    @Test
    void nameWithSpacesStaysOneArgument()
    {
        List<String> args = FfmpegArgs.build("ffmpeg", "-i - %NAME%.mp4", params(2, 2, "ma prise"), "ma prise", null);

        assertTrue(args.contains("ma prise.mp4"));
        assertEquals(4, args.size());
    }
}
