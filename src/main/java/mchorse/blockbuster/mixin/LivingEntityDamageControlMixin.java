package mchorse.blockbuster.mixin;

import mchorse.blockbuster.recording.capturing.WorldEventListener;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * wixo (CDC §6, R1): damage control journals a living entity when it dies, so
 * the restore brings it back (its body is only removed a second later).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageControlMixin
{
    @Inject(method = "onDeath(Lnet/minecraft/entity/damage/DamageSource;)V", at = @At("HEAD"))
    private void blockbuster$onDeath(DamageSource source, CallbackInfo info)
    {
        WorldEventListener.onEntityDeath((LivingEntity) (Object) this);
    }
}
