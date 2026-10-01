package mchorse.blockbuster.client.gui;

import mchorse.blockbuster.client.video.CaptureResolution;
import mchorse.blockbuster.client.video.CustomResolutionCapture;
import mchorse.blockbuster.client.video.VideoConfig;
import mchorse.mclib.client.gui.framework.elements.utils.GuiContext;
import mchorse.mclib.client.gui.framework.elements.utils.GuiLabel;
import mchorse.mclib.client.gui.utils.keys.LangKey;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.util.Window;

import java.util.Locale;
import java.util.function.IntSupplier;

/**
 * wixo.1 (CDC R7): one line telling, live, what a recording started now would
 * produce — the effective video size, how it is rendered (native, at this size,
 * cropped, scaled) and the encoding — so the size typed once on another screen
 * can no longer surprise (CDC §4.2.3). Yellow when the frames would be degraded.
 */
public class GuiCaptureSummary extends GuiLabel
{
    private static final int NORMAL = 0xaaaaaa;
    private static final int DEGRADED = 0xffff55;

    private final IntSupplier width;
    private final IntSupplier height;
    private final LangKey text = new LangKey("blockbuster.video.gui.effective");

    private String state = "";

    /**
     * @param width  the requested width ({@code 0} = window), read every frame
     * @param height the requested height ({@code 0} = window)
     */
    public GuiCaptureSummary(MinecraftClient mc, IntSupplier width, IntSupplier height)
    {
        super(mc, null, NORMAL);

        this.label = this.text;
        this.width = width;
        this.height = height;
    }

    @Override
    public void draw(GuiContext context)
    {
        this.refresh();

        super.draw(context);
    }

    private void refresh()
    {
        Window window = this.mc.getWindow();
        int width = this.width.getAsInt();
        int height = this.height.getAsInt();
        String blocker = CustomResolutionCapture.blocker();
        String encoder = VideoConfig.encoderLabel();
        String state = width + "x" + height + "/" + window.getFramebufferWidth() + "x" + window.getFramebufferHeight() + "/" + blocker + "/" + encoder;

        /* Recompute only when an input changed: no per-frame formatting. */
        if (state.equals(this.state))
        {
            return;
        }

        this.state = state;

        CaptureResolution.Decision decision = CaptureResolution.resolve(width, height,
            window.getFramebufferWidth(), window.getFramebufferHeight(), blocker);
        String mode = I18n.translate("blockbuster.video.mode." + decision.mode().name().toLowerCase(Locale.ROOT));

        this.text.args(decision.width(), decision.height(), mode, encoder).update();
        this.color(decision.mode().degraded() ? DEGRADED : NORMAL);
    }
}
