package mchorse.blockbuster.mixin;

import mchorse.blockbuster.recording.capturing.ActionHandler;
import mchorse.blockbuster.recording.capturing.WorldEventListener;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Legacy {@code EntityMountEvent} replacement (Fabric has no mount event) —
 * players only, like the legacy listener. {@code stopRiding} is hooked at
 * HEAD so the vehicle is still attached; mount switching fires
 * dismount-then-mount, same as Forge did.
 */
@Mixin(Entity.class)
public abstract class EntityMixin
{
    @Inject(method = "startRiding(Lnet/minecraft/entity/Entity;Z)Z", at = @At("RETURN"))
    private void blockbuster$onStartRiding(Entity entity, boolean force, CallbackInfoReturnable<Boolean> cir)
    {
        if (cir.getReturnValue() && (Object) this instanceof PlayerEntity player && !player.getWorld().isClient())
        {
            ActionHandler.onPlayerMountsSomething(player, entity, true);
        }
    }

    /** wixo (R1): damage control journals an entity before it is destroyed. */
    @Inject(method = "setRemoved(Lnet/minecraft/entity/Entity$RemovalReason;)V", at = @At("HEAD"))
    private void blockbuster$onSetRemoved(Entity.RemovalReason reason, CallbackInfo info)
    {
        WorldEventListener.onEntityRemoved((Entity) (Object) this, reason);
    }

    @Inject(method = "stopRiding()V", at = @At("HEAD"))
    private void blockbuster$onStopRiding(CallbackInfo info)
    {
        if ((Object) this instanceof PlayerEntity player && !player.getWorld().isClient())
        {
            Entity vehicle = player.getVehicle();

            if (vehicle != null)
            {
                ActionHandler.onPlayerMountsSomething(player, vehicle, false);
            }
        }
    }
}
