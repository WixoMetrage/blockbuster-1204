package mchorse.blockbuster.client.video;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoRecorderTest
{
    private static final VideoParams PARAMS = new VideoParams(2, 2, 60, 0, 1, VideoFormat.BGRA, new File("movies"), "test", null);

    /** Mimics the PBO ring: frame N is delivered when frame N + latency is captured. */
    private static class DelayedSource implements FrameSource
    {
        private final int latency;
        private final ArrayDeque<Integer> inFlight = new ArrayDeque<>();
        private int counter;
        boolean released;

        DelayedSource(int latency)
        {
            this.latency = latency;
        }

        @Override
        public void capture(Consumer out)
        {
            this.inFlight.add(this.counter++);

            if (this.inFlight.size() > this.latency)
            {
                this.deliver(out);
            }
        }

        @Override
        public void flush(Consumer out)
        {
            while (!this.inFlight.isEmpty())
            {
                this.deliver(out);
            }
        }

        @Override
        public void release()
        {
            this.released = true;
        }

        private void deliver(Consumer out)
        {
            ByteBuffer frame = ByteBuffer.allocate(PARAMS.frameByteSize());

            frame.put(0, (byte) (int) this.inFlight.poll());
            out.accept(frame);
        }
    }

    private static class CollectingSink implements FrameSink
    {
        final List<Integer> frames = Collections.synchronizedList(new ArrayList<>());
        boolean ended;

        @Override
        public void begin(int width, int height, VideoFormat format) throws IOException
        {}

        @Override
        public void frame(ByteBuffer data)
        {
            this.frames.add((int) data.get(data.position()));
        }

        @Override
        public void end() throws IOException
        {
            this.ended = true;
        }
    }

    @Test
    void framesInFlightAreFlushedInOrderAtStop() throws Exception
    {
        DelayedSource source = new DelayedSource(PboFrameSourceRing.LATENCY);
        CollectingSink sink = new CollectingSink();
        VideoRecorder recorder = new VideoRecorder();

        recorder.startRecording(PARAMS, source, sink);

        for (int i = 0; i < 10; i++)
        {
            recorder.recordFrame();
        }

        assertEquals(10 - PboFrameSourceRing.LATENCY, recorder.frames(), "frames still in flight are not counted yet");

        recorder.stopRecording();

        assertTrue(recorder.finalization().get(10, TimeUnit.SECONDS));

        assertEquals(10, recorder.frames());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), sink.frames);
        assertTrue(sink.ended);
        assertTrue(source.released);
        assertFalse(recorder.hasFailed());
    }

    @Test
    void shortFrameStopsTheRecordingInsteadOfDesyncingTheStream()
    {
        FrameSource shortSource = out -> out.accept(ByteBuffer.allocate(3));
        CollectingSink sink = new CollectingSink();
        VideoRecorder recorder = new VideoRecorder();

        recorder.startRecording(PARAMS, shortSource, sink);
        recorder.recordFrame();

        assertFalse(recorder.isRecording());
        assertTrue(recorder.hasFailed());
        assertTrue(sink.frames.isEmpty());
    }

    @Test
    void sinkThatCannotOpenFailsCleanly()
    {
        FrameSink broken = new CollectingSink()
        {
            @Override
            public void begin(int width, int height, VideoFormat format) throws IOException
            {
                throw new IOException("no ffmpeg");
            }
        };
        VideoRecorder recorder = new VideoRecorder();

        recorder.startRecording(PARAMS, new DelayedSource(0), broken);

        assertFalse(recorder.isRecording());
        assertTrue(recorder.hasFailed());
    }

    @Test
    void encoderFailureAtCloseIsReportedInRedWithTheLog() throws Exception
    {
        List<String> messages = Collections.synchronizedList(new ArrayList<>());
        CollectingSink failing = new CollectingSink()
        {
            @Override
            public void end() throws IOException
            {
                throw new IOException("ffmpeg exited with code 1");
            }

            @Override
            public List<String> diagnostic()
            {
                return List.of("Unknown encoder 'h264_nvenc'");
            }
        };
        VideoRecorder recorder = new VideoRecorder();

        VideoMessages.sink = (level, key, args) -> messages.add(level + " " + key);

        try
        {
            recorder.startRecording(PARAMS, new DelayedSource(0), failing);
            recorder.recordFrame();
            recorder.stopRecording();

            assertFalse(recorder.finalization().get(10, TimeUnit.SECONDS));
            assertEquals(List.of("ERROR blockbuster.video.msg.failed", "ERROR blockbuster.video.msg.log_line"), messages);
        }
        finally
        {
            VideoMessages.sink = (level, key, args) -> {};
        }
    }

    /** PboFrameSource lives in the client source set; its latency is RING - 1. */
    private static final class PboFrameSourceRing
    {
        static final int LATENCY = 2;
    }
}
