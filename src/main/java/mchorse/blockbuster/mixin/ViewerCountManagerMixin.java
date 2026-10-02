package mchorse.blockbuster.mixin;

import mchorse.blockbuster.CommonProxy;
import mchorse.blockbuster.common.entity.EntityActor;
import net.minecraft.block.entity.ViewerCountManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * wixo (CDC §6): a chest opened by an actor stays open until the actor closes
 * it ({@code CloseContainerAction}).
 *
 * <p>An actor opens containers through its {@link EntityActor.EntityFakePlayer},
 * which is not in the world's entity list. Vanilla recounts the viewers every
 * few ticks by scanning the players around the container, never found the
 * fake player, and closed the lid right after it opened.</p>
 */
@Mixin(ViewerCountManager.class)
public abstract class ViewerCountManagerMixin
{
    @Shadow
    protected abstract boolean isPlayerViewing(PlayerEntity player);

    @Inject(method = "getInRangeViewerCount(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)I", at = @At("RETURN"), cancellable = true)
    private void blockbuster$countActors(World world, BlockPos pos, CallbackInfoReturnable<Integer> cir)
    {
        int actors = 0;

        for (LivingEntity entity : CommonProxy.manager.players.keySet())
        {
            if (entity instanceof EntityActor actor && actor.fakePlayer != null && actor.getWorld() == world
                && actor.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64
                && this.isPlayerViewing(actor.fakePlayer))
            {
                actors++;
            }
        }

        if (actors > 0)
        {
            cir.setReturnValue(cir.getReturnValueI() + actors);
        }
    }
}
