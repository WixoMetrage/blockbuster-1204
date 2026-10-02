package mchorse.aperture.camera.minema;

/**
 * Scene-coupled recording lifecycle (P202), extracted from
 * {@code GuiMinemaPanel.startRecording} / {@code stop} / {@code minema} so the
 * start/stop coupling, auto-stop conditions, premature-stop path, and
 * tracking-export gating are headlessly testable with fakes
 * ({@code RecordingLifecycleTest}). The ported {@code GuiMinemaPanel} owns one of
 * these and supplies the real {@link Recorder} (the {@link MinemaIntegration}
 * facade) and {@link Host} (editor/runner callbacks).
 *
 * <p><b>Load-bearing quirks preserved:</b></p>
 * <ul>
 *   <li>Start refuses when the runner is already running <i>or</i> the recorder
 *       is already recording.</li>
 *   <li>{@code end - start <= 0} silently aborts (no modal).</li>
 *   <li>{@code recording = true} is set via {@link Host#postOperation} — deferred
 *       one beat, so {@link #minema} early-returns until the operation queue
 *       flushes (the panel only <i>believes</i> it is recording after Rewind /
 *       playback already started).</li>
 *   <li>A {@code toggleRecording(true)} failure shows the error modal and aborts
 *       (no tracking, no rewind, GUI stays visible).</li>
 *   <li>Tracking JSON exports only on a <b>clean</b> stop <i>and</i> only when
 *       tracking is active; the export name uses a fresh stop-time timestamp when
 *       the filename is empty. The exporter is reset on <b>every</b> stop.</li>
 *   <li>{@code stop} swallows {@code toggleRecording(false)} exceptions — teardown
 *       must never crash the editor.</li>
 * </ul>
 *
 * Legacy source: .tools/legacy-src/aperture/src/main/java/mchorse/aperture/client/gui/GuiMinemaPanel.java
 */
public class RecordingLifecycle
{
    /** The recorder backend surface (the {@link MinemaIntegration} facade). */
    public interface Recorder
    {
        boolean isRecording();

        void toggleRecording(boolean state) throws Exception;

        void setName(String name);

        String getMessage(Exception e);
    }

    /** Editor/runner callbacks the panel wires to the real {@code GuiCameraEditor}. */
    public interface Host
    {
        boolean isRunning();

        void togglePlayback();

        void setRootVisible(boolean visible);

        void postOperation(Runnable operation);

        void showErrorModal(String message);

        void showPrematureStopModal();

        /**
         * Rewind to {@code start}: post the {@code CameraEditorEvent.Rewind},
         * scrub the timeline, and {@code updatePlayer(start, 0)} — everything the
         * legacy panel did between {@code EVENT_BUS.post} and the
         * {@code togglePlayback()} that the lifecycle issues next.
         */
        void rewind(int start);

        /** The panel's ghost-resolved filename ({@link RecordingFilename}). */
        String getFilename();

        /* Tracking seam (P202.1 CameraExporter / P202.2 tracking GUI). */

        /** Whether the tracking toggle is on (P202.2). */
        boolean trackingToggled();

        /** Begin tracking against the current runner (P202.1 exporter). */
        void trackingStart();

        /** Whether the exporter actually captured any tracked frames (P202.1). */
        boolean trackingIsTracking();

        /** Write the tracking JSON to {@code jsonFilename} (P202.1). */
        void trackingExport(String jsonFilename);

        /** Reset the exporter (P202.1). */
        void trackingReset();
    }

    private final Recorder recorder;
    private final Host host;

    private boolean recording;
    /** wixo.1 (C5): from a successful {@code toggleRecording(true)} to {@link #stop}. */
    private boolean take;
    private int start;
    private int end;

    public RecordingLifecycle(Recorder recorder, Host host)
    {
        this.recorder = recorder;
        this.host = host;
    }

    public boolean isRecording()
    {
        return this.recording;
    }

    public int start()
    {
        return this.start;
    }

    public int end()
    {
        return this.end;
    }

    /**
     * Begin recording over {@code range}. Returns {@code true} when the recorder
     * was started (note: {@code isRecording()} still reports {@code false} until
     * the deferred {@link Host#postOperation} runs — the legacy one-beat delay).
     */
    public boolean startRecording(RecordingRange range)
    {
        if (this.host.isRunning() || this.recorder.isRecording())
        {
            return false;
        }

        this.start = range.start;
        this.end = range.end;

        if (this.end - this.start <= 0)
        {
            /* Silent abort (no modal) — legacy behavior. */
            return false;
        }

        this.recorder.setName(this.host.getFilename());

        try
        {
            this.recorder.toggleRecording(true);
            this.take = true;
            this.host.postOperation(() -> this.recording = true);
        }
        catch (Exception e)
        {
            this.host.showErrorModal(this.recorder.getMessage(e));

            return false;
        }

        if (this.host.trackingToggled())
        {
            this.host.trackingStart();
        }

        this.host.rewind(this.start);

        if (!this.host.isRunning())
        {
            this.host.togglePlayback();
        }

        this.host.setRootVisible(false);

        return true;
    }

    /**
     * wixo.1 (CDC §5, C5): whether the frame being rendered belongs to the take.
     *
     * <p>The recorder starts on the click but the runner only at the next client
     * tick, and the runner stops itself inside the frame where it reaches its
     * duration while {@link #minema} only notices once the world is drawn.
     * Those frames showed the editor / player view: a 30-tick take came out at
     * 96 frames instead of 90. Only frames where the profile plays inside
     * {@code [start, end)} belong to the take; outside a take every frame does.</p>
     */
    public boolean capturesFrame(boolean running, long ticks)
    {
        return !this.take || (running && ticks >= this.start && ticks < this.end);
    }

    public void stop()
    {
        this.stop(false);
    }

    public void stop(boolean prematureStop)
    {
        this.take = false;

        if (!this.recording)
        {
            return;
        }

        try
        {
            this.recorder.toggleRecording(false);
        }
        catch (Exception e)
        {
            /* Teardown must never crash the editor — swallow (legacy). */
        }

        if (this.host.isRunning())
        {
            this.host.togglePlayback();
        }

        this.host.setRootVisible(true);
        this.recording = false;

        if (!prematureStop && this.host.trackingIsTracking())
        {
            this.host.trackingExport(RecordingFilename.exportJsonName(this.host.getFilename(), System.currentTimeMillis()));
        }

        this.host.trackingReset();
    }

    /**
     * Per-frame recording tick (legacy {@code minema(int ticks, float)} — the
     * {@code partialTicks} argument was only used for the debug overlay, which
     * stays in the panel). Drives auto-stop, stop-on-playback-halt, and the
     * external-failure (premature-stop) path.
     */
    public void minema(int ticks)
    {
        if (!this.recording)
        {
            return;
        }

        if (!this.recorder.isRecording())
        {
            this.stop(true);
            this.host.showPrematureStopModal();

            return;
        }

        if (this.host.isRunning() && ticks >= this.end)
        {
            this.host.togglePlayback();
            this.stop();
        }
        else if (!this.host.isRunning())
        {
            this.stop();
        }
    }
}
