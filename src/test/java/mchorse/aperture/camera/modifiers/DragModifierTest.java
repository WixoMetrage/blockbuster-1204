package mchorse.aperture.camera.modifiers;

import mchorse.aperture.Aperture;
import mchorse.aperture.camera.CameraProfile;
import mchorse.aperture.camera.data.Position;
import mchorse.aperture.camera.fixtures.IdleFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Non-regression (CDC §5, C2): a drag starts over at every cut.
 */
class DragModifierTest
{
    private static final float[] PARTIALS = {0F, 1F / 3F, 2F / 3F};

    @AfterEach
    void restoreConfig()
    {
        Aperture.dragResetOnCut.set(true);
    }

    /** Two 10-tick idle shots, at x = 0 then x = 100. */
    private static CameraProfile twoShots()
    {
        CameraProfile profile = new CameraProfile(null);

        profile.add(shot(0));
        profile.add(shot(100));

        return profile;
    }

    private static IdleFixture shot(float x)
    {
        IdleFixture fixture = new IdleFixture(10);

        fixture.position.set(new Position(x, 64, 0, 0, 0));

        return fixture;
    }

    private static DragModifier drag()
    {
        DragModifier drag = new DragModifier();

        drag.active.set(1); /* x only */

        return drag;
    }

    /** Plays the profile at 3 frames per tick and returns x for each frame. */
    private static double[] play(CameraProfile profile, long from, long to)
    {
        double[] xs = new double[(int) (to - from) * PARTIALS.length];
        Position position = new Position();
        int i = 0;

        for (long tick = from; tick < to; tick++)
        {
            for (float partial : PARTIALS)
            {
                profile.applyProfile(tick, partial, position);
                xs[i++] = position.point.x;
            }
        }

        return xs;
    }

    @Test
    void globalDragCutsCleanlyOnTheFirstFrameOfTheNextShot()
    {
        CameraProfile profile = twoShots();

        profile.modifiers.add(drag());

        double[] xs = play(profile, 0, 20);

        /* Frame 30 is the first frame of the second shot (tick 10). */
        assertEquals(0, xs[29], 1e-9);
        assertEquals(100, xs[30], 1e-9, "the first frame after the cut is the new shot, not a drag from the old one");
        assertEquals(100, xs[59], 1e-9);
    }

    @Test
    void optionOffKeepsTheLegacyDragAcrossTheCut()
    {
        CameraProfile profile = twoShots();

        Aperture.dragResetOnCut.set(false);
        profile.modifiers.add(drag());

        double[] xs = play(profile, 0, 20);

        assertEquals(50, xs[30], 1e-9, "legacy: factor 0.5 drags halfway from the old shot");
    }

    @Test
    void fixtureDragDoesNotStartFromWhereItWasLeftLastTime()
    {
        CameraProfile profile = twoShots();
        DragModifier drag = drag();

        profile.get(1).modifiers.add(drag);

        play(profile, 0, 20);

        /* Second take: the shot is entered at offset 1 (a frame was skipped).
         * The modifier still holds x = 100 from the first take; move the shot
         * so a stale state would show. */
        ((IdleFixture) profile.get(1)).position.set(new Position(200, 64, 0, 0, 0));

        double[] xs = play(profile, 11, 12);

        assertEquals(200, xs[0], 1e-9, "time went backwards: the drag starts over");
    }

    @Test
    void dragStillSmoothsInsideAShot()
    {
        CameraProfile profile = new CameraProfile(null);
        IdleFixture shot = shot(0);

        profile.add(shot);
        profile.modifiers.add(drag());

        Position position = new Position();

        /* Tick 1: offset 0 always resets (legacy), whatever the cut. */
        profile.applyProfile(1, 0, position);
        shot.position.set(new Position(10, 64, 0, 0, 0));
        profile.applyProfile(1, 0.5F, position);

        assertNotEquals(10, position.point.x, 1e-9);
        assertEquals(5, position.point.x, 1e-9);
    }
}
