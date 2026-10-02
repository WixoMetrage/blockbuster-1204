package mchorse.blockbuster.network.server.recording;

import mchorse.blockbuster.network.common.recording.PacketRecordingTick;
import mchorse.blockbuster.recording.capturing.ActionHandler;
import mchorse.mclib.network.ServerMessageHandler;
import net.minecraft.server.network.ServerPlayerEntity;

/** wixo (R4): closes the recording player's action list for one client tick. */
public class ServerHandlerRecordingTick extends ServerMessageHandler<PacketRecordingTick>
{
    @Override
    public void run(ServerPlayerEntity player, PacketRecordingTick message)
    {
        ActionHandler.onRecordingTick(player);
    }
}
