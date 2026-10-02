package mchorse.aperture.camera.minema;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingLifecycleTest
{
    private static class FakeRecorder implements RecordingLifecycle.Recorder
    {
        boolean recording;

        @Override
        public boolean isRecording()
        {
            return this.recording;
        }

        @Override
        public void toggleRecording(boolean state)
        {
            this.recording = state;
        }

        @Override
        public void setName(String name)
        {}

        @Override
        public String getMessage(Exception e)
        {
            return e.getMessage();
        }
    }

    private static class FakeHost implements RecordingLifecycle.Host
    {
        boolean running;
        final List<Runnable> operations = new ArrayList<>();

        void flush()
        {
            this.operations.forEach(Runnable::run);
            this.operations.clear();
        }

        @Override
        public boolean isRunning()
        {
            return this.running;
        }

        @Override
        public void togglePlayback()
        {
            this.running = !this.running;
        }

        @Override
        public void setRootVisible(boolean visible)
        {}

        @Override
        public void postOperation(Runnable operation)
        {
            this.operations.add(operation);
        }

        @Override
        public void showErrorModal(String message)
        {}

        @Override
        public void showPrematureStopModal()
        {}

        @Override
        public void rewind(int start)
        {}

        @Override
        public String getFilename()
        {
            return "take";
        }

        @Override
        public boolean trackingToggled()
        {
            return false;
        }

        @Override
        public void trackingStart()
        {}

        @Override
        public boolean trackingIsTracking()
        {
            return false;
        }

        @Override
        public void trackingExport(String jsonFilename)
        {}

        @Override
        public void trackingReset()
        {}
    }

    private static RecordingRange range(int start, int end)
    {
        return new RecordingRange(start, end);
    }

    @Test
    void outsideATakeEveryFrameIsCaptured()
    {
        RecordingLifecycle lifecycle = new RecordingLifecycle(new FakeRecorder(), new FakeHost());

        assertTrue(lifecycle.capturesFrame(false, 0));
        assertTrue(lifecycle.capturesFrame(true, 500));
    }

    /**
     * Non-regression (C5): a 30-tick take at 3 frames per tick gave 96 frames —
     * 2 before the runner started, 1 after it stopped (plus 3 from the late
     * skipUpdate, fixed in the tick order). Only the profile's frames are kept.
     */
    @Test
    void aTakeKeepsOnlyTheFramesWhereTheProfilePlays()
    {
        FakeRecorder recorder = new FakeRecorder();
        FakeHost host = new FakeHost();
        RecordingLifecycle lifecycle = new RecordingLifecycle(recorder, host);

        assertTrue(lifecycle.startRecording(range(0, 30)));

        /* Clicked mid-frame: the runner starts at the next client tick. */
        host.running = false;
        assertFalse(lifecycle.capturesFrame(host.running, 0));

        host.running = true;
        host.flush();

        int kept = 0;

        for (int tick = 0; tick < 30; tick++)
        {
            for (int frame = 0; frame < 3; frame++)
            {
                kept += lifecycle.capturesFrame(true, tick) ? 1 : 0;
            }
        }

        assertEquals(90, kept);

        /* The runner stopped itself at its duration, before minema() noticed. */
        assertFalse(lifecycle.capturesFrame(false, 30));
        /* A range shorter than the profile: the frame at `end` is not part of it. */
        assertFalse(lifecycle.capturesFrame(true, 30));

        lifecycle.minema(30);

        assertFalse(recorder.isRecording());
        assertTrue(lifecycle.capturesFrame(false, 30), "the take is over: plain recording rules again");
    }

    @Test
    void framesBeforeTheRangeStartAreOutsideTheTake()
    {
        RecordingLifecycle lifecycle = new RecordingLifecycle(new FakeRecorder(), new FakeHost());

        lifecycle.startRecording(range(10, 20));

        assertFalse(lifecycle.capturesFrame(true, 9));
        assertTrue(lifecycle.capturesFrame(true, 10));
        assertTrue(lifecycle.capturesFrame(true, 19));
        assertFalse(lifecycle.capturesFrame(true, 20));
    }
}
