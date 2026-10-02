package mchorse.blockbuster.mixin.client;

import mchorse.blockbuster.client.video.VideoCaptureWiring;
import net.minecraft.client.Keyboard;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * wixo.1: hands the capture key handler the modifiers of the key press itself,
 * so Shift+F4 is recognised even when Shift is released before the next client
 * tick (see {@code VideoCaptureKeyHandler#onRawKey}).
 */
@Mixin(Keyboard.class)
public class CaptureKeyMixin
{
    @Inject(method = "onKey", at = @At("HEAD"), require = 0)
    private void blockbuster$onCaptureKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo info)
    {
        if (action == GLFW.GLFW_PRESS)
        {
            VideoCaptureWiring.KEYS.onRawKey(key, scancode, modifiers);
        }
    }
}
