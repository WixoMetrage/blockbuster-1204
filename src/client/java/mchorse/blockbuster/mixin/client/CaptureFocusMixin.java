package mchorse.blockbuster.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mchorse.blockbuster.client.video.CaptureClock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * wixo.1 (CDC §4.3 R3, tests V4/V5): keep recording when the window loses
 * focus — alt-tab, another window on top, or the window minimized.
 *
 * <p>{@code GameRenderer.render} opens the pause menu half a second after the
 * window loses focus ({@code pauseOnLostFocus}, on by default). During a take
 * that pauses the integrated server, so the scene freezes and the video shows
 * a frozen frame for the rest of the take. While a capture is running the
 * window is reported as focused <i>to that check only</i>; the game is
 * otherwise untouched.</p>
 *
 * <p>Minimizing needs nothing else: GLFW reports a 0×0 framebuffer, which
 * {@code Window.onFramebufferSizeChanged} ignores (the framebuffer keeps its
 * size) and {@code MinecraftClient.render} still calls the game renderer.</p>
 */
@Mixin(GameRenderer.class)
public class CaptureFocusMixin
{
    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;isWindowFocused()Z"),
        require = 0
    )
    private boolean blockbuster$stayFocusedWhileCapturing(MinecraftClient client, Operation<Boolean> original)
    {
        return CaptureClock.isActive() || original.call(client);
    }
}
