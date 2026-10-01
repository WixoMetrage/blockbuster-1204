package mchorse.blockbuster.client.video;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The client's {@link VideoMessages.Sink}: shows recorder messages in the chat,
 * coloured by level (CDC R4). Safe from any thread — the encoder finalizer
 * posts from its own — because the chat is only touched on the client thread.
 */
public final class VideoChat
{
    private VideoChat()
    {}

    public static void post(VideoMessages.Level level, String key, Object... args)
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null)
        {
            return;
        }

        MutableText text = Text.literal("[Blockbuster] ").formatted(Formatting.GRAY)
            .append(Text.translatable(key, convert(args)).formatted(color(level)));

        mc.execute(() ->
        {
            if (mc.inGameHud != null)
            {
                mc.inGameHud.getChatHud().addMessage(text);
            }
        });
    }

    private static Formatting color(VideoMessages.Level level)
    {
        switch (level)
        {
            case SUCCESS:
                return Formatting.GREEN;
            case WARNING:
                return Formatting.YELLOW;
            case ERROR:
                return Formatting.RED;
            default:
                return Formatting.WHITE;
        }
    }

    private static Object[] convert(Object[] args)
    {
        Object[] out = new Object[args.length];

        for (int i = 0; i < args.length; i++)
        {
            Object arg = args[i];

            out[i] = arg instanceof VideoMessages.Tr
                ? Text.translatable(((VideoMessages.Tr) arg).key, convert(((VideoMessages.Tr) arg).args))
                : String.valueOf(arg);
        }

        return out;
    }
}
