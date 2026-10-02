package mchorse.aperture.camera.smooth;

import mchorse.mclib.config.values.ValueFloat;
import mchorse.mclib.config.values.ValueInt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Non-regression (CDC §5, C6): the smooth roll/FOV filter does not depend on
 * the frame rate.
 */
class FilterTest
{
    private static Filter filter()
    {
        Filter filter = new Filter();

        filter.friction = new ValueFloat("friction", 0.985F, 0F, 0.99999F);
        filter.factor = new ValueFloat("factor", 0.01F, 0F, 10F);
        filter.reference = new ValueInt("reference_fps", 60, 1, 1000);

        return filter;
    }

    /**
     * Holds the key for 20 ticks, rendering framesPerTick frames each tick;
     * returns the value shown at 2/3 of the last tick.
     */
    private static float run(int framesPerTick)
    {
        Filter filter = filter();
        float sample = 0;

        for (int tick = 0; tick < 20; tick++)
        {
            filter.accelerate(1F);

            for (int frame = 0; frame < framesPerTick; frame++)
            {
                float value = filter.interpolate((float) frame / framesPerTick);

                if (frame * 3 == framesPerTick * 2)
                {
                    sample = value;
                }
            }
        }

        return sample;
    }

    @Test
    void sameMotionAtAnyFrameRate()
    {
        /* 60, 120 and 180 fps show the same value at the same instant. */
        float at60 = run(3);

        assertEquals(at60, run(6), 1e-4);
        assertEquals(at60, run(9), 1e-4);
    }

    /** The default reference keeps the speed users had at 60 fps (legacy added acc once per frame). */
    @Test
    void referenceMatchesTheLegacySpeedAtThatFrameRate()
    {
        Filter filter = filter();
        float legacy = 0;
        float acc = 0;

        for (int tick = 0; tick < 20; tick++)
        {
            filter.accelerate(1F);

            acc = (acc + 1F) * 0.985F;
            legacy += 3 * acc;
        }

        assertEquals(legacy, filter.value, 1e-3);
    }

    @Test
    void interpolationStaysBetweenTheLastTwoTicks()
    {
        Filter filter = filter();

        filter.set(10);
        filter.accelerate(1F);

        assertEquals(10, filter.interpolate(0F), 1e-6);
        assertEquals(filter.value, filter.interpolate(1F), 1e-6);
        assertEquals(filter.interpolate(0.5F), filter.interpolate(0.5F), 0, "interpolate does not mutate the filter");
    }
}
