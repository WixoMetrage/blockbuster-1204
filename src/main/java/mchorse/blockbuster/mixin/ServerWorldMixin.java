package mchorse.blockbuster.mixin;

import mchorse.blockbuster.recording.capturing.WorldEventListener;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Server-side halves of 1.12.2's {@code IWorldEventListener}:
 *
 * <ul>
 * <li>{@code sendBlockBreakProgress} (block-break animation capture during
 * recording) — HEAD of {@code ServerWorld.setBlockBreakingInfo}, the
 * server-side entry point vanilla calls when a player's mining progress
 * changes;</li>
 * <li>{@code onEntityAdded} (damage control) — RETURN of the private
 * {@code ServerWorld.addEntity}, reached by {@code spawnEntity},
 * {@code tryLoadEntity} and {@code spawnNewEntityAndPassengers} but not by
 * chunk loading (wixo R1).</li>
 * </ul>
 *
 * <p>The damage-control block feed moved to {@code WorldMixin}
 * ({@code World.setBlockState} HEAD) in wixo R1: {@code onBlockChanged} runs
 * after the old block entity was emptied. Signatures verified with
 * {@code javap} against the loom-named 1.20.4 jar.</p>
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin
{
    @Inject(method = "setBlockBreakingInfo(ILnet/minecraft/util/math/BlockPos;I)V", at = @At("HEAD"))
    private void blockbuster$onSetBlockBreakingInfo(int entityId, BlockPos pos, int progress, CallbackInfo info)
    {
        WorldEventListener.sendBlockBreakProgress((World) (Object) this, entityId, pos, progress);
    }

    @Inject(method = "addEntity(Lnet/minecraft/entity/Entity;)Z", at = @At("RETURN"))
    private void blockbuster$onAddEntity(Entity entity, CallbackInfoReturnable<Boolean> cir)
    {
        if (cir.getReturnValueZ())
        {
            WorldEventListener.onEntityAdded(entity);
        }
    }
}
