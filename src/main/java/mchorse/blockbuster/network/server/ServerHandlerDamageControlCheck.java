package mchorse.blockbuster.network.server;

import java.util.Map;

import mchorse.blockbuster.CommonProxy;
import mchorse.blockbuster.network.common.PacketDamageControlCheck;
import mchorse.blockbuster.recording.capturing.DamageControl;
import mchorse.blockbuster.recording.scene.Scene;
import mchorse.mclib.network.ServerMessageHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Server handler for {@link PacketDamageControlCheck} (roadmap P131.2). <b>No
 * permission check</b> — any player may ask which scene owns a block.
 *
 * <p>Guard: {@code pointPos != null && !world.isAir(pointPos)}. Iterates
 * {@code CommonProxy.damage.damage}, skipping non-{@link Scene} keys. Match
 * order: (1) a block change at {@code pointPos} is journaled
 * ({@link DamageControl#touches}); (2) else, when the distance is limited, a
 * per-axis <b>box</b> test {@code |target.pos − pointPos| <= maxDistance}.
 * Replies via actionbar (overlay) with
 * {@code blockbuster.info.damage_control.message} carrying the scene id; no
 * reply when unowned.</p>
 *
 * <p>wixo (R1): without a distance limit the box test would claim every
 * block, so only journaled blocks answer.</p>
 */
public class ServerHandlerDamageControlCheck extends ServerMessageHandler<PacketDamageControlCheck>
{
    @Override
    public void run(ServerPlayerEntity player, PacketDamageControlCheck packet)
    {
        if (packet.pointPos == null || player.getWorld().getBlockState(packet.pointPos).isAir())
        {
            return;
        }

        for (Map.Entry<Object, DamageControl> entry : CommonProxy.damage.damage.entrySet())
        {
            if (!(entry.getKey() instanceof Scene scene))
            {
                continue;
            }

            DamageControl control = entry.getValue();

            if (control.touches(packet.pointPos) || (control.maxDistance > 0 && control.isInRange(packet.pointPos)))
            {
                player.sendMessage(Text.translatable("blockbuster.info.damage_control.message", scene.getId()), true);

                return;
            }
        }
    }
}
