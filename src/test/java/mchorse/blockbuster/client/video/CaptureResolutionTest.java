package mchorse.blockbuster.client.video;

import mchorse.blockbuster.client.video.CaptureResolution.Decision;
import mchorse.blockbuster.client.video.CaptureResolution.Mode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CaptureResolutionTest
{
    private static final String BLOCKED = "blocked";

    private static void assertDecision(Decision d, int width, int height, Mode mode)
    {
        assertEquals(width, d.width(), "width");
        assertEquals(height, d.height(), "height");
        assertEquals(mode, d.mode(), "mode");
    }

    @Test
    void evenWindowAtWindowSizeIsNative()
    {
        assertDecision(CaptureResolution.resolve(0, 0, 1920, 1080, null), 1920, 1080, Mode.NATIVE);
        assertDecision(CaptureResolution.resolve(0, 0, 1920, 1080, BLOCKED), 1920, 1080, Mode.NATIVE);
    }

    /** CDC §4.2.1: a windowed 1080p screen has an odd framebuffer height. */
    @Test
    void oddWindowRendersEvenWhenCustomPathIsAvailable()
    {
        assertDecision(CaptureResolution.resolve(0, 0, 1920, 1009, null), 1920, 1008, Mode.CUSTOM);
    }

    /** The regression itself: blocked used to record the raw odd window size. */
    @Test
    void oddWindowBlockedIsCroppedToEvenNeverOdd()
    {
        Decision d = CaptureResolution.resolve(0, 0, 1920, 1009, BLOCKED);

        assertDecision(d, 1920, 1008, Mode.CROPPED);
        assertEquals(BLOCKED, d.reason());
    }

    @Test
    void presetLargerThanWindowIsHonoured()
    {
        assertDecision(CaptureResolution.resolve(3840, 2160, 1920, 1009, null), 3840, 2160, Mode.CUSTOM);
    }

    @Test
    void presetBlockedKeepsTheRequestedSizeByScaling()
    {
        Decision d = CaptureResolution.resolve(3840, 2160, 1920, 1009, BLOCKED);

        assertDecision(d, 3840, 2160, Mode.SCALED);
        assertEquals(BLOCKED, d.reason());
    }

    @Test
    void oddConfiguredSizeIsSnappedDown()
    {
        assertDecision(CaptureResolution.resolve(1921, 1081, 3840, 2160, null), 1920, 1080, Mode.CUSTOM);
    }

    @Test
    void customRefusedLateBecomesScaledAtTheSameSize()
    {
        Decision d = CaptureResolution.resolve(3840, 2160, 1920, 1080, null).scaled("driver");

        assertDecision(d, 3840, 2160, Mode.SCALED);
        assertEquals("driver", d.reason());
    }

    @Test
    void nativeHasNoReason()
    {
        assertNull(CaptureResolution.resolve(0, 0, 2560, 1440, null).reason());
    }
}
