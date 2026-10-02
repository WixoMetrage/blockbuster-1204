package mchorse.blockbuster.mixin;

import java.util.OptionalInt;

import mchorse.blockbuster.recording.capturing.ActionHandler;
import mchorse.blockbuster.recording.capturing.WorldEventListener;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Two legacy seams:
 *
 * <p>{@code playerTick} tail = Forge's server-side {@code PlayerTickEvent}
 * END phase (1.12.2's {@code onUpdateEntity}, called from the network
 * handler's tick, dead or alive — the death-halt check relies on that).</p>
 *
 * <p>{@code dropItem} = {@code ItemTossEvent}; {@code retainOwnership} is
 * the modern equivalent of legacy's {@code traceItem} flag (true for Q-drops
 * and inventory throw-outs, false for death drops — which legacy didn't
 * record either).</p>
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin
{
    @Inject(method = "playerTick", at = @At("TAIL"))
    private void blockbuster$onPlayerTick(CallbackInfo info)
    {
        ActionHandler.onPlayerTick((ServerPlayerEntity) (Object) this);
    }

    /** wixo (R1): containers next to an opened screen are journaled by damage control. */
    @Inject(method = "openHandledScreen(Lnet/minecraft/screen/NamedScreenHandlerFactory;)Ljava/util/OptionalInt;", at = @At("HEAD"))
    private void blockbuster$onOpenHandledScreen(NamedScreenHandlerFactory factory, CallbackInfoReturnable<OptionalInt> cir)
    {
        WorldEventListener.onScreenOpened((ServerPlayerEntity) (Object) this);
    }

    @Inject(method = "dropItem(Lnet/minecraft/item/ItemStack;ZZ)Lnet/minecraft/entity/ItemEntity;", at = @At("RETURN"))
    private void blockbuster$onItemToss(ItemStack stack, boolean throwRandomly, boolean retainOwnership, CallbackInfoReturnable<ItemEntity> cir)
    {
        ItemEntity item = cir.getReturnValue();

        if (item != null && retainOwnership)
        {
            ActionHandler.onItemToss((ServerPlayerEntity) (Object) this, item.getStack());
        }
    }
}
