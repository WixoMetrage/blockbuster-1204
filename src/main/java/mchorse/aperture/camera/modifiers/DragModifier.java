package mchorse.aperture.camera.modifiers;

import mchorse.aperture.Aperture;
import mchorse.aperture.camera.CameraProfile;
import mchorse.aperture.camera.data.Position;
import mchorse.aperture.camera.fixtures.AbstractFixture;
import mchorse.mclib.config.values.ValueFloat;
import mchorse.mclib.utils.Interpolations;

/**
 * Drag modifier (P174).
 *
 * Port notes: previous-position state lives on the modifier instance
 * <b>across frames</b> (per-client profile lifetime, like legacy); state
 * resets when {@code offset == 0} — that's what makes scrubbing back
 * deterministic. Yaw via {@code lerpYaw} (shortest path). Verbatim.
 *
 * Legacy source: .tools/legacy-src/aperture/src/main/java/mchorse/aperture/camera/modifiers/DragModifier.java
 */
public class DragModifier extends ComponentModifier
{
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private float roll;
    private float fov;

    /* wixo.1 (C2): the cut and time of the previous frame, see needsReset */
    private AbstractFixture lastCut;
    private double lastTime = -1;

    public final ValueFloat factor = new ValueFloat("factor", 0.5F, 0F, 1F);

    public DragModifier()
    {
        super();

        this.register(this.factor);
    }

    public void reset(Position position)
    {
        this.x = position.point.x;
        this.y = position.point.y;
        this.z = position.point.z;
        this.yaw = position.angle.yaw;
        this.pitch = position.angle.pitch;
        this.roll = position.angle.roll;
        this.fov = position.angle.fov;
    }

    /**
     * wixo.1 (CDC §5, C2): the drag starts over at every cut.
     *
     * <p>Legacy only reset at {@code offset == 0}. A global drag gets the
     * profile tick as offset, so it reset once at the start of the profile and
     * then dragged the camera across every cut, smearing the change of shot.
     * It now also resets when the cut changes ({@code aperture.general.drag_reset_on_cut},
     * on by default) and when time goes backwards (scrub, loop, second take),
     * so a fixture whose first frame is not at offset 0 never starts from the
     * position it was left at last time.</p>
     */
    protected boolean needsReset(long offset, AbstractFixture cut, double time)
    {
        if (offset == 0 || time < this.lastTime)
        {
            return true;
        }

        return cut != this.lastCut && Aperture.dragResetOnCut.get();
    }

    @Override
    public void modify(long ticks, long offset, AbstractFixture fixture, float partialTick, float previewPartialTick, CameraProfile profile, Position pos)
    {
        /* For a global drag (fixture == null) the cut is the fixture playing now. */
        AbstractFixture cut = fixture != null ? fixture : profile.atTick(ticks);
        double time = ticks + previewPartialTick;

        if (this.needsReset(offset, cut, time))
        {
            this.reset(pos);
        }

        this.lastCut = cut;
        this.lastTime = time;

        float factor = this.factor.get();

        if (this.isActive(0)) pos.point.x = this.x = Interpolations.lerp(this.x, pos.point.x, factor);
        if (this.isActive(1)) pos.point.y = this.y = Interpolations.lerp(this.y, pos.point.y, factor);
        if (this.isActive(2)) pos.point.z = this.z = Interpolations.lerp(this.z, pos.point.z, factor);
        if (this.isActive(3)) pos.angle.yaw = this.yaw = Interpolations.lerpYaw(this.yaw, pos.angle.yaw, factor);
        if (this.isActive(4)) pos.angle.pitch = this.pitch = Interpolations.lerp(this.pitch, pos.angle.pitch, factor);
        if (this.isActive(5)) pos.angle.roll = this.roll = Interpolations.lerp(this.roll, pos.angle.roll, factor);
        if (this.isActive(6)) pos.angle.fov = this.fov = Interpolations.lerp(this.fov, pos.angle.fov, factor);
    }

    @Override
    public AbstractModifier create()
    {
        return new DragModifier();
    }
}
