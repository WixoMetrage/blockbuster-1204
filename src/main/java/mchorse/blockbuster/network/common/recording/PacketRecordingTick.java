package mchorse.blockbuster.network.common.recording;

import io.netty.buffer.ByteBuf;
import mchorse.mclib.network.IMessage;

/**
 * wixo (CDC §6, R4): sent by the client at the end of each tick it records a
 * frame for. The server closes the tick's action list on it rather than on its
 * own tick, so action N lines up with frame N even when the two ticks drift
 * apart (server lag catching up, "Can't keep up" skips). The packet travels
 * on the same ordered connection as the clicks, so every action the player
 * caused during client tick N reaches the server before it.
 */
public class PacketRecordingTick implements IMessage
{
    @Override
    public void fromBytes(ByteBuf buf)
    {}

    @Override
    public void toBytes(ByteBuf buf)
    {}
}
