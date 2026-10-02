package mchorse.aperture.camera.smooth;

import mchorse.mclib.config.values.ValueFloat;
import mchorse.mclib.config.values.ValueInt;
import mchorse.mclib.utils.Interpolations;

/**
 * Acceleration based linear filter (P179).
 *
 * Used for animation camera roll and FOV.
 *
 * Port notes: acceleration is zeroed in the {@code |acc| < 0.005} dead
 * zone.
 *
 * <p>wixo.1 (CDC §5, C6): legacy added {@code acc} to the value in
 * {@code interpolate}, i.e. once per <b>frame</b>, while {@code acc} itself
 * is updated once per tick: the smooth roll/FOV ran three times faster at
 * 60 fps than at 20, and a recording at a fixed frame rate did not move like
 * the live preview. The value now advances in {@link #accelerate} (once per
 * tick) by {@code acc × reference / 20}, and {@link #interpolate} only lerps
 * between the last two ticks. With the default reference of 60 fps the speed
 * is the legacy speed at 60 fps, at any frame rate.</p>
 *
 * Legacy source: .tools/legacy-src/aperture/src/main/java/mchorse/aperture/camera/smooth/Filter.java
 */
public class Filter
{
    /**
     * Acceleration factor
     */
    public float acc;

    /**
     * Current value
     */
    public float value;

    /**
     * Previous value
     */
    public float prevValue;

    /**
     * Friction for acceleration, this value decides how fast acceleration
     * slow downs.
     */
    public ValueFloat friction;

    /**
     * Factor for acceleration (should be used externally)
     */
    public ValueFloat factor;

    /**
     * wixo.1 (C6): the frame rate the legacy per-frame speed is matched at
     * ({@code aperture.smooth.reference_fps}); null means 20 (one step per tick).
     */
    public ValueInt reference;

    /**
     * Set the value for the filter
     */
    public void set(float value)
    {
        this.value = this.prevValue = value;
    }

    /**
     * Reset the value
     *
     * Same thing as set, but also resetting the acceleration
     */
    public void reset(float value)
    {
        this.acc = 0.0F;
        this.value = this.prevValue = value;
    }

    /**
     * Accelerate the acceleration and advance the value by one tick
     */
    public void accelerate(float value)
    {
        this.acc += value;
        this.acc *= this.friction.get();

        if (Math.abs(this.acc) < 0.005F)
        {
            this.acc = 0.0F;
        }

        this.prevValue = this.value;
        this.value += this.acc * this.stepsPerTick();
    }

    private float stepsPerTick()
    {
        return this.reference == null ? 1F : this.reference.get() / 20F;
    }

    /**
     * The value at this frame, between the last two ticks
     */
    public float interpolate(float ticks)
    {
        return Interpolations.lerp(this.prevValue, this.value, ticks);
    }
}
