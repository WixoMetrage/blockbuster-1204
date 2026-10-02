package mchorse.blockbuster.mixin;

import mchorse.blockbuster.recording.capturing.ActionHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * wixo (CDC §6): a block broken by the player is recorded when
 * {@code tryBreakBlock} says it was broken.
 *
 * <p>Fabric's {@code PlayerBlockBreakEvents.AFTER} (used until now) only fires
 * when the final {@code removeBlock} succeeds. Breaking a bed, a door or a tall
 * plant in creative removes the other half first ({@code onBreak}, flags 35),
 * the clicked half then turns to air through the shape update, and the final
 * {@code removeBlock} finds nothing: no event, the break was never recorded
 * and the bed stayed in the replay.</p>
 */
@Mixin(ServerPlayerInteractionManager.class)
public abstract class ServerPlayerInteractionManagerMixin
{
    @Shadow
    @Final
    protected ServerPlayerEntity player;

    @Inject(method = "tryBreakBlock(Lnet/minecraft/util/math/BlockPos;)Z", at = @At("RETURN"))
    private void blockbuster$onTryBreakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir)
    {
        if (cir.getReturnValueZ())
        {
            ActionHandler.onPlayerBreaksBlock(this.player, pos.toImmutable());
        }
    }
}
