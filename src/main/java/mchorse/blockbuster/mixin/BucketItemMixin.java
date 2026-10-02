package mchorse.blockbuster.mixin;

import mchorse.blockbuster.recording.capturing.ActionHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BucketItem;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bucket placement fires no place event, so here's mchorse's hack for
 * placing water and lava blocks.
 *
 * <p>wixo (CDC §6, R3): captured from the result, at the RETURN of
 * {@code placeFluid}, where the fluid actually went. Legacy guessed it at the
 * start of {@code use} as "next to the block aimed at", which is wrong
 * whenever vanilla fills the aimed block itself (tall grass, a waterloggable
 * block, a cauldron...). {@code placeFluid} retries itself next to the block
 * when the first spot refuses: only the call that left a fluid at its
 * position is recorded.</p>
 */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin
{
    @Inject(method = "placeFluid(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/hit/BlockHitResult;)Z", at = @At("RETURN"))
    private void blockbuster$onPlaceFluid(PlayerEntity player, World world, BlockPos pos, BlockHitResult hit, CallbackInfoReturnable<Boolean> cir)
    {
        if (cir.getReturnValueZ() && player != null && !world.isClient() && !world.getFluidState(pos).isEmpty())
        {
            ActionHandler.onPlayerPlacedFluid(player, world, pos);
        }
    }
}
