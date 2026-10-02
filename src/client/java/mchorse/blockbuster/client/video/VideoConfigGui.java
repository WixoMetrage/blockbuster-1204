package mchorse.blockbuster.client.video;

import mchorse.blockbuster.Blockbuster;
import mchorse.blockbuster.client.gui.GuiCaptureSummary;
import mchorse.blockbuster.utils.mclib.ValueVideoEncoder;
import mchorse.blockbuster.utils.mclib.ValueVideoResolution;
import mchorse.mclib.client.gui.framework.elements.GuiElement;
import mchorse.mclib.client.gui.framework.elements.buttons.GuiButtonElement;
import mchorse.mclib.client.gui.framework.elements.buttons.GuiCirculateElement;
import mchorse.mclib.client.gui.framework.elements.input.GuiTrackpadElement;
import mchorse.mclib.client.gui.utils.Elements;
import mchorse.mclib.client.gui.utils.keys.IKey;
import mchorse.mclib.config.gui.ConfigGuiProviders;
import mchorse.mclib.config.gui.GuiConfigPanel;
import mchorse.mclib.config.values.ValueInt;
import net.minecraft.client.MinecraftClient;

import java.util.Arrays;
import java.util.List;

/**
 * wixo.1 (CDC R1/R5/R7): the video settings widgets of the Blockbuster config
 * panel — where the resolution and the encoding are chosen.
 *
 * <ul>
 * <li>{@link ValueVideoResolution}: preset buttons (1080p, 1440p, 4K, window
 * size), the width/height fields ({@code video.width}/{@code video.height},
 * hidden from the generic list) and the live effective-resolution line.</li>
 * <li>{@link ValueVideoEncoder}: a selector over {@link EncoderPresets} instead
 * of a text field.</li>
 * </ul>
 */
public final class VideoConfigGui
{
    private VideoConfigGui()
    {}

    /** Register the widget factories (idempotent). */
    public static void register()
    {
        ConfigGuiProviders.register(ValueVideoResolution.class, VideoConfigGui::resolutionFields);
        ConfigGuiProviders.register(ValueVideoEncoder.class, VideoConfigGui::encoderFields);
    }

    private static List<GuiElement> resolutionFields(MinecraftClient mc, GuiConfigPanel config, ValueVideoResolution value)
    {
        GuiTrackpadElement width = trackpad(mc, Blockbuster.videoWidth);
        GuiTrackpadElement height = trackpad(mc, Blockbuster.videoHeight);

        GuiElement presets = Elements.row(mc, 5, 0, 20,
            preset(mc, "blockbuster.video.gui.preset_1080", 1920, 1080, width, height),
            preset(mc, "blockbuster.video.gui.preset_1440", 2560, 1440, width, height),
            preset(mc, "blockbuster.video.gui.preset_4k", 3840, 2160, width, height),
            preset(mc, "blockbuster.video.gui.preset_window", 0, 0, width, height));

        /* Vertical (9:16) videos rendered directly: same framing as Aperture's
         * 9:16 letterbox, without rendering the discarded sides. */
        GuiElement vertical = Elements.row(mc, 5, 0, 20,
            preset(mc, "blockbuster.video.gui.preset_vertical_1080", 1080, 1920, width, height),
            preset(mc, "blockbuster.video.gui.preset_vertical_4k", 2160, 3840, width, height));

        GuiCaptureSummary summary = new GuiCaptureSummary(mc, Blockbuster.videoWidth::get, Blockbuster.videoHeight::get);

        summary.flex().h(12);

        return Arrays.asList(
            Elements.label(IKey.lang("blockbuster.config.video.resolution"), 0).anchor(0, 0.5F)
                .tooltip(IKey.lang("blockbuster.config.comments.video.resolution")),
            presets,
            vertical,
            row(mc, Blockbuster.videoWidth, width),
            row(mc, Blockbuster.videoHeight, height),
            summary);
    }

    private static List<GuiElement> encoderFields(MinecraftClient mc, GuiConfigPanel config, ValueVideoEncoder value)
    {
        List<String> ids = EncoderPresets.ids();
        GuiCirculateElement selector = new GuiCirculateElement(mc, (b) -> value.set(ids.get(b.getValue())));

        for (String id : ids)
        {
            selector.addLabel(IKey.lang("blockbuster.video.encoder." + id));
        }

        selector.setValue(Math.max(0, ids.indexOf(VideoConfig.encoder())));
        selector.flex().h(20);

        GuiElement label = Elements.label(IKey.lang(value.getLabelKey()), 0).anchor(0, 0.5F);

        return Arrays.asList(
            label.tooltip(IKey.lang(value.getCommentKey())),
            selector.tooltip(IKey.lang(value.getCommentKey())));
    }

    private static GuiTrackpadElement trackpad(MinecraftClient mc, ValueInt value)
    {
        GuiTrackpadElement trackpad = new GuiTrackpadElement(mc, value);

        trackpad.integer().increment(1).values(10, 1, 100);
        trackpad.flex().w(90);

        return trackpad;
    }

    private static GuiElement row(MinecraftClient mc, ValueInt value, GuiTrackpadElement trackpad)
    {
        GuiElement element = new GuiElement(mc);

        element.flex().row(0).preferred(0).height(20);
        element.add(Elements.label(IKey.lang(value.getLabelKey()), 0).anchor(0, 0.5F), trackpad);

        return element.tooltip(IKey.lang(value.getCommentKey()));
    }

    private static GuiButtonElement preset(MinecraftClient mc, String key, int w, int h,
        GuiTrackpadElement width, GuiTrackpadElement height)
    {
        return new GuiButtonElement(mc, IKey.lang(key), (b) ->
        {
            Blockbuster.videoWidth.set(w);
            Blockbuster.videoHeight.set(h);
            width.setValue(w);
            height.setValue(h);
        });
    }
}
