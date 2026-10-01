package mchorse.blockbuster.client.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * User-facing messages of the video recorder (CDC §2.6, §4.3 R4): what the
 * user must see in game rather than only in {@code latest.log}. Every message
 * is also logged under {@code blockbuster-video}.
 *
 * <p>Messages are lang keys ({@code blockbuster.video.msg.*}) so they follow the
 * game language; an argument that is itself a lang key is wrapped in
 * {@link Tr}. The client installs a chat {@link #sink} in
 * {@code VideoCaptureWiring}; headless code and tests keep the logging
 * default.</p>
 */
public final class VideoMessages
{
    private static final Logger LOGGER = LoggerFactory.getLogger("blockbuster-video");

    public enum Level
    {
        /** White: neutral information (recording started). */
        INFO,
        /** Green: something finished well (video written). */
        SUCCESS,
        /** Yellow: the recording works but is degraded (scaled, cropped, fallback). */
        WARNING,
        /** Red: the recording failed or cannot start properly. */
        ERROR
    }

    /** A message argument that is itself a lang key. */
    public static final class Tr
    {
        public final String key;
        public final Object[] args;

        public Tr(String key, Object... args)
        {
            this.key = key;
            this.args = args;
        }

        @Override
        public String toString()
        {
            return this.args.length == 0 ? this.key : this.key + Arrays.toString(this.args);
        }
    }

    public interface Sink
    {
        void post(Level level, String key, Object... args);
    }

    /** Where messages go besides the log. Replaced by the client's chat sink. */
    public static Sink sink = (level, key, args) -> {};

    private VideoMessages()
    {}

    public static void info(String key, Object... args)
    {
        post(Level.INFO, key, args);
    }

    public static void success(String key, Object... args)
    {
        post(Level.SUCCESS, key, args);
    }

    public static void warning(String key, Object... args)
    {
        post(Level.WARNING, key, args);
    }

    public static void error(String key, Object... args)
    {
        post(Level.ERROR, key, args);
    }

    public static void post(Level level, String key, Object... args)
    {
        String line = "[{}] {} {}";

        if (level == Level.ERROR)
        {
            LOGGER.error(line, level, key, Arrays.toString(args));
        }
        else if (level == Level.WARNING)
        {
            LOGGER.warn(line, level, key, Arrays.toString(args));
        }
        else
        {
            LOGGER.info(line, level, key, Arrays.toString(args));
        }

        try
        {
            sink.post(level, key, args);
        }
        catch (Exception e)
        {
            LOGGER.warn("Could not display video message {}", key, e);
        }
    }
}
