package mchorse.blockbuster.mixin;

import mchorse.blockbuster.recording.capturing.ActionHandler;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * wixo (CDC §6, R3): explosions are recorded as their result, and a replayed
 * one destroys no block (see {@link ActionHandler#onExplosion}). Runs once the
 * blocks to destroy are known and entities already took the blast.
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin
{
    @Shadow
    @Final
    private World world;

    @Inject(method = "affectWorld(Z)V", at = @At("HEAD"))
    private void blockbuster$onAffectWorld(boolean particles, CallbackInfo info)
    {
        ActionHandler.onExplosion(this.world, (Explosion) (Object) this);
    }
}
