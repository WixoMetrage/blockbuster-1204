package mchorse.blockbuster.client.video;

import mchorse.aperture.camera.minema.MinemaIntegration;
import mchorse.blockbuster.client.gui.GuiCaptureConfiguration;
import mchorse.mclib.client.gui.utils.ScreenOpener;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minema's F4 capture keybind, wired to Blockbuster's built-in recorder.
 *
 * <p>Legacy Minema ({@code info.ata4.minecraft.minema.Minema#onKeyInput}):</p>
 * <ul>
 *   <li><b>F4</b> — start recording if idle, stop if already recording.</li>
 *   <li><b>Shift+F4</b> — open {@link GuiCaptureConfiguration} when not
 *       already recording (never opens mid-take).</li>
 * </ul>
 *
 * <p>Self-contained like {@link ScreenshotKeyHandler}: registers its own
 * binding and tick poll so it does not depend on another phase owning the
 * keybind family. Installed from {@link VideoCaptureWiring#install()}.</p>
 */
public class VideoCaptureKeyHandler
{
    private static final Logger LOGGER = LoggerFactory.getLogger("blockbuster-video");

    private KeyBinding capture;
    private boolean registered;

    /** Whether Shift was held when the capture key went down (set by {@link #onRawKey}). */
    private boolean shiftAtPress;

    /** Register the binding + tick poll (idempotent). */
    public void register()
    {
        if (this.registered)
        {
            return;
        }

        this.registered = true;

        this.capture = this.registerBinding(new KeyBinding(
            "key.blockbuster.capture",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F4,
            "key.blockbuster.category"));

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    /** Overridable so headless tests can skip the live {@code KeyBindingHelper}. */
    protected KeyBinding registerBinding(KeyBinding binding)
    {
        return KeyBindingHelper.registerKeyBinding(binding);
    }

    private void onClientTick(MinecraftClient mc)
    {
        if (this.capture == null || mc == null)
        {
            return;
        }

        /* Fabric only feeds wasPressed while no screen is open — same gate as
         * ScreenshotKeyHandler. Shift+F4 therefore only opens from in-world. */
        while (this.capture.wasPressed())
        {
            this.handleCaptureKey(mc);
        }

        /* A press made while a screen was open is never queued: drop its flag. */
        this.shiftAtPress = false;
    }

    /**
     * Raw key press, from {@code CaptureKeyMixin} ({@code Keyboard.onKey} HEAD).
     *
     * <p>wixo.1 fix: Shift used to be read only when the queued press was
     * processed, on the next client tick — up to 50 ms later, more when the game
     * lags under shaders. A quick Shift+F4 had Shift already released by then,
     * so it started a recording instead of opening the panel. The modifiers of
     * the press itself are now remembered.</p>
     */
    public void onRawKey(int key, int scancode, int modifiers)
    {
        if (this.capture != null && this.capture.matchesKey(key, scancode))
        {
            this.shiftAtPress = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        }
    }

    /**
     * The Minema key action. Package-visible so the configuration screen's
     * "Record" button can reuse the start path without re-opening the UI.
     */
    void handleCaptureKey(MinecraftClient mc)
    {
        boolean recording = MinemaIntegration.isRecording();
        boolean shift = this.shiftAtPress || Screen.hasShiftDown();

        /* Diagnostic (one line per key press): Shift+F4 was reported to start a
         * recording instead of opening the panel. */
        LOGGER.info("Capture key: shift at press {}, shift now {}, recording {}",
            this.shiftAtPress, Screen.hasShiftDown(), recording);

        this.shiftAtPress = false;

        if (shift && !recording)
        {
            ScreenOpener.open(new GuiCaptureConfiguration(mc));

            return;
        }

        this.toggleRecording(mc);
    }

    /**
     * Start or stop the built-in recorder and play Minema's chicken-plop cue.
     *
     * @return {@code true} if recording is active after the call
     */
    public boolean toggleRecording(MinecraftClient mc)
    {
        boolean start = !MinemaIntegration.isRecording();

        try
        {
            MinemaIntegration.toggleRecording(start);
        }
        catch (Exception e)
        {
            LOGGER.error("Failed to {} video recording", start ? "start" : "stop", e);

            if (mc != null && mc.player != null)
            {
                mc.player.sendMessage(Text.literal(MinemaIntegration.getMessage(e)), false);
            }

            return MinemaIntegration.isRecording();
        }

        boolean active = MinemaIntegration.isRecording();

        /* Minema CaptureNotification: plop on enable (pitch 1) / disable (0.75). */
        if (mc != null)
        {
            float pitch = active ? 1.0F : 0.75F;

            mc.getSoundManager().play(PositionedSoundInstance.master(
                SoundEvents.ENTITY_CHICKEN_EGG, pitch));
        }

        return active;
    }
}
