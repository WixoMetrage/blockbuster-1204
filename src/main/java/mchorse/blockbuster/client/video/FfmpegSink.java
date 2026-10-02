package mchorse.blockbuster.client.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link FrameSink} that streams raw frames to an external {@code ffmpeg}
 * process over stdin (P201).
 *
 * <p>Process lifecycle mirrors the BBS pattern minus its {@code Unsafe} tricks:
 * {@code redirectErrorStream(true)} + a file redirect so an un-drained stderr
 * can never deadlock the encoder. Closing stdin (not killing the process) is
 * what tells ffmpeg to finalize the container, so {@link #end()} does
 * {@code close()} → {@code waitFor(timeout)} →
 * {@code destroy()} in that order.</p>
 */
public class FfmpegSink implements FrameSink
{
    private static final Logger LOGGER = LoggerFactory.getLogger("blockbuster-video");

    /** How long the finalizer waits for ffmpeg to write the file (off the render thread). */
    static final int FINALIZE_TIMEOUT_MINUTES = 10;

    /** Log lines shown in chat when ffmpeg fails. */
    static final int DIAGNOSTIC_LINES = 4;

    private final List<String> args;
    private final File workingDir;
    private final File logFile;

    private Process process;
    private OutputStream stdin;

    /**
     * wixo.1 (CDC R6): frames go to ffmpeg in {@value #CHUNK} chunks. The
     * former {@code Channels.newChannel(stdin)} wrote 8 KB at a time — about
     * 4000 pipe round trips per 4K frame, capped at ~300 MB/s (9 fps in 4K,
     * measured). 4 MB chunks measured ~2 GB/s on the same pipe. Allocated once
     * per recording, never per frame.
     */
    static final int CHUNK = 4 << 20;

    private byte[] chunk;

    public FfmpegSink(List<String> args, File workingDir, File logFile)
    {
        this.args = args;
        this.workingDir = workingDir;
        this.logFile = logFile;
    }

    /**
     * Assemble a sink from recording parameters and a resolved binary. The
     * argument template (audio vs. video-only) is chosen by whether
     * {@code params} carries an audio track.
     */
    public static FfmpegSink create(VideoParams params, String binary, String videoTemplate, String audioTemplate, boolean encoderLog)
    {
        String template = params.hasAudio() ? audioTemplate : videoTemplate;
        String audio = params.hasAudio() ? params.audioTrack().getAbsolutePath() : null;
        List<String> args = FfmpegArgs.build(binary, template, params, params.name(), audio);

        File dir = params.exportDir();
        File log = encoderLog ? new File(dir, params.name() + ".log") : new File(dir, "video.log");

        return new FfmpegSink(args, dir, log);
    }

    public List<String> args()
    {
        return this.args;
    }

    /**
     * The file ffmpeg's merged stdout/stderr is redirected to: a shared
     * {@code video.log} next to the output, or a per-recording
     * {@code <name>.log} when {@code video.encoder_log} is on.
     */
    public File logFile()
    {
        return this.logFile;
    }

    @Override
    public void begin(int width, int height, VideoFormat format) throws IOException
    {
        if (this.workingDir != null)
        {
            this.workingDir.mkdirs();
        }

        LOGGER.info("Recording video with ffmpeg arguments: {}", this.args);

        ProcessBuilder builder = new ProcessBuilder(this.args);

        builder.redirectErrorStream(true);

        if (this.logFile != null)
        {
            builder.redirectOutput(this.logFile);
        }

        if (this.workingDir != null)
        {
            builder.directory(this.workingDir);
        }

        this.process = builder.start();
        this.stdin = this.process.getOutputStream();
        this.chunk = new byte[CHUNK];
    }

    @Override
    public void frame(ByteBuffer data) throws IOException
    {
        if (this.stdin == null)
        {
            throw new IOException("ffmpeg sink written before begin()");
        }

        while (data.hasRemaining())
        {
            int n = Math.min(this.chunk.length, data.remaining());

            data.get(this.chunk, 0, n);
            this.stdin.write(this.chunk, 0, n);
        }
    }

    /**
     * Close stdin (ffmpeg then writes the container trailer), wait for the
     * process and check its exit code (wixo.1, CDC R6). Runs on the finalizer
     * thread, so the generous timeout never freezes the game.
     */
    @Override
    public void end() throws IOException
    {
        try
        {
            if (this.stdin != null)
            {
                this.stdin.close();
            }
        }
        catch (IOException e)
        {
            /* ffmpeg already gone (broken pipe): its exit code below says why. */
            LOGGER.warn("Closing ffmpeg's input failed: {}", e.getMessage());
        }
        finally
        {
            this.stdin = null;
            this.chunk = null;
        }

        if (this.process == null)
        {
            return;
        }

        Process process = this.process;

        this.process = null;

        try
        {
            if (!process.waitFor(FINALIZE_TIMEOUT_MINUTES, TimeUnit.MINUTES))
            {
                process.destroyForcibly();

                throw new IOException("ffmpeg did not finish within " + FINALIZE_TIMEOUT_MINUTES + " minutes and was stopped");
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            process.destroyForcibly();

            throw new IOException("interrupted while waiting for ffmpeg");
        }

        int code = process.exitValue();

        if (code != 0)
        {
            throw new IOException("ffmpeg exited with code " + code);
        }
    }

    /** The output file: the template's last argument, relative to the export folder. */
    @Override
    public String output()
    {
        String last = this.args.isEmpty() ? "" : this.args.get(this.args.size() - 1);
        File file = new File(last);

        if (!file.isAbsolute() && this.workingDir != null)
        {
            file = new File(this.workingDir, last);
        }

        return file.getAbsolutePath();
    }

    @Override
    public List<String> diagnostic()
    {
        return tail(this.logFile, DIAGNOSTIC_LINES);
    }

    /** The last {@code count} non-blank lines of {@code file} (ffmpeg's log). */
    static List<String> tail(File file, int count)
    {
        if (file == null || !file.isFile())
        {
            return Collections.emptyList();
        }

        try
        {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            ArrayDeque<String> last = new ArrayDeque<>(count);

            for (String line : lines)
            {
                /* ffmpeg rewrites its progress line with \r: keep the last state only. */
                int cr = line.lastIndexOf('\r');
                String clean = (cr >= 0 ? line.substring(cr + 1) : line).trim();

                if (clean.isEmpty())
                {
                    continue;
                }

                if (last.size() == count)
                {
                    last.removeFirst();
                }

                last.addLast(clean);
            }

            return new ArrayList<>(last);
        }
        catch (IOException e)
        {
            return Collections.singletonList("(log unreadable: " + e.getMessage() + ")");
        }
    }
}
