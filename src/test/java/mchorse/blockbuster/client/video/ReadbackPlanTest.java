package mchorse.blockbuster.client.video;

import mchorse.blockbuster.client.video.ReadbackPlan.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadbackPlanTest
{
    @Test
    void sameSizeIsDirect()
    {
        assertEquals(Kind.DIRECT, ReadbackPlan.of(1920, 1080, 1920, 1080).kind());
    }

    @Test
    void onePixelLargerIsCropped()
    {
        assertEquals(Kind.CROP, ReadbackPlan.of(1920, 1009, 1920, 1008).kind());
        assertEquals(Kind.CROP, ReadbackPlan.of(1921, 1081, 1920, 1080).kind());
    }

    @Test
    void smallerSourceIsScaledNeverSkipped()
    {
        /* The window shrank mid-take: the old readback emitted black frames here. */
        assertEquals(Kind.SCALE, ReadbackPlan.of(1280, 720, 1920, 1080).kind());
        assertEquals(Kind.SCALE, ReadbackPlan.of(1919, 1080, 1920, 1080).kind());
    }

    @Test
    void sameAspectFillsTheFrame()
    {
        ReadbackPlan plan = ReadbackPlan.of(1920, 1080, 3840, 2160);

        assertEquals(0, plan.x0());
        assertEquals(0, plan.y0());
        assertEquals(3840, plan.x1());
        assertEquals(2160, plan.y1());
        assertFalse(plan.letterboxed());
    }

    @Test
    void otherAspectIsLetterboxedAndCentred()
    {
        /* A 4:3 window into a 16:9 video: pillarbox bars left and right. */
        ReadbackPlan plan = ReadbackPlan.of(1440, 1080, 1920, 1080);

        assertEquals(Kind.SCALE, plan.kind());
        assertEquals(240, plan.x0());
        assertEquals(1680, plan.x1());
        assertEquals(0, plan.y0());
        assertEquals(1080, plan.y1());
        assertTrue(plan.letterboxed());
    }
}
