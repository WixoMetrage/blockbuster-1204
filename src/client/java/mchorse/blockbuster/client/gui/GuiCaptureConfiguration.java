package mchorse.blockbuster.client.gui;

import mchorse.aperture.camera.minema.MinemaIntegration;
import mchorse.blockbuster.Blockbuster;
import mchorse.blockbuster.client.video.EncoderPresets;
import mchorse.blockbuster.client.video.MinemaBackend;
import mchorse.blockbuster.client.video.VideoConfig;
import mchorse.mclib.client.gui.framework.GuiBase;
import mchorse.mclib.client.gui.framework.elements.GuiElement;
import mchorse.mclib.client.gui.framework.elements.buttons.GuiButtonElement;
import mchorse.mclib.client.gui.framework.elements.buttons.GuiCirculateElement;
import mchorse.mclib.client.gui.framework.elements.input.GuiTextElement;
import mchorse.mclib.client.gui.framework.elements.input.GuiTrackpadElement;
import mchorse.mclib.client.gui.framework.elements.utils.GuiDraw;
import mchorse.mclib.client.gui.framework.elements.utils.GuiDrawable;
import mchorse.mclib.client.gui.framework.elements.utils.GuiLabel;
import mchorse.mclib.client.gui.mclib.GuiDashboard;
import mchorse.mclib.client.gui.utils.Elements;
import mchorse.mclib.client.gui.utils.LegacyKeyCodes;
import mchorse.mclib.client.gui.utils.ScreenOpener;
import mchorse.mclib.client.gui.utils.keys.IKey;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Minema's Shift+F4 capture configuration screen, ported onto Blockbuster's
 * built-in video recorder.
 *
 * <p>Legacy source:
 * {@code info.ata4.minecraft.minema.client.gui.GuiCaptureConfiguration}.</p>
 *
 * <p>Fields map onto the live {@code video.*} config (width / height / fps) plus
 * an optional output name handed to {@link MinemaIntegration#setName}. Layout is
 * an {@link Elements#column} for the form body and an {@link Elements#row} for
 * the bottom bar (Movies Folder / Settings… / Record).</p>
 */
public class GuiCaptureConfiguration extends GuiBase
{
    public GuiTextElement name;
    public GuiTrackpadElement videoWidth;
    public GuiTrackpadElement videoHeight;
    public GuiTrackpadElement frameRate;
    public GuiCaptureSummary summary;
    public GuiCirculateElement encoder;
    public GuiButtonElement movies;
    public GuiButtonElement settings;
    public GuiButtonElement record;

    private GuiLabel fileExistsLabel;
    private boolean movieExists;

    public GuiCaptureConfiguration(MinecraftClient mc)
    {
        /* Dimmed full-screen backdrop (vanilla drawDefaultBackground stand-in). */
        this.root.add(new GuiDrawable((ctx) ->
            GuiDraw.drawRect(0, 0, this.width, this.height, 0xC0101010)));

        this.name = new GuiTextElement(mc, 128, (s) -> this.updateMoviesExist());
        this.name.filename();
        this.name.flex().h(20);

        this.fileExistsLabel = Elements.label(IKey.lang("minema.gui.file_exists")).color(0xff3355);
        this.fileExistsLabel.flex().h(12);
        this.fileExistsLabel.setVisible(false);

        this.videoWidth = new GuiTrackpadElement(mc, Blockbuster.videoWidth);
        this.videoWidth.increment(1).values(10, 1, 100);
        this.videoWidth.flex().h(20);

        this.videoHeight = new GuiTrackpadElement(mc, Blockbuster.videoHeight);
        this.videoHeight.increment(1).values(10, 1, 100);
        this.videoHeight.flex().h(20);

        this.frameRate = new GuiTrackpadElement(mc, Blockbuster.videoFrameRate);
        this.frameRate.increment(1).values(1, 1, 10);
        this.frameRate.flex().h(20);

        GuiElement sizeRow = Elements.row(mc, 10,
            Elements.column(mc, 2,
                Elements.label(IKey.lang("minema.gui.width")).marginBottom(2),
                this.videoWidth),
            Elements.column(mc, 2,
                Elements.label(IKey.lang("minema.gui.height")).marginBottom(2),
                this.videoHeight));

        /* wixo.1 (CDC R1/R7): one click per usual size; "window" is 0×0. */
        GuiElement presetRow = Elements.row(mc, 5,
            this.sizeButton(mc, "blockbuster.video.gui.preset_1080", 1920, 1080),
            this.sizeButton(mc, "blockbuster.video.gui.preset_1440", 2560, 1440),
            this.sizeButton(mc, "blockbuster.video.gui.preset_4k", 3840, 2160),
            this.sizeButton(mc, "blockbuster.video.gui.preset_window", 0, 0));

        GuiElement verticalRow = Elements.row(mc, 5,
            this.sizeButton(mc, "blockbuster.video.gui.preset_vertical_1080", 1080, 1920),
            this.sizeButton(mc, "blockbuster.video.gui.preset_vertical_4k", 2160, 3840));

        this.summary = new GuiCaptureSummary(mc,
            () -> (int) Math.round(this.videoWidth.value),
            () -> (int) Math.round(this.videoHeight.value));
        this.summary.flex().h(12);

        List<String> encoders = EncoderPresets.ids();

        this.encoder = new GuiCirculateElement(mc, (b) -> Blockbuster.videoEncoder.set(encoders.get(b.getValue())));

        for (String id : encoders)
        {
            this.encoder.addLabel(IKey.lang("blockbuster.video.encoder." + id));
        }

        this.encoder.setValue(Math.max(0, encoders.indexOf(VideoConfig.encoder())));
        this.encoder.tooltip(IKey.lang("blockbuster.config.comments.video.encoder"));
        this.encoder.flex().h(20);

        GuiElement form = Elements.column(mc, 5,
            Elements.label(IKey.lang("minema.gui.title")).anchor(0.5F, 0.5F).marginBottom(12),
            Elements.label(IKey.lang("minema.gui.name")).marginBottom(2),
            this.name,
            this.fileExistsLabel,
            sizeRow.marginTop(8),
            presetRow,
            verticalRow,
            this.summary,
            Elements.label(IKey.lang("minema.gui.fps")).marginTop(8).marginBottom(2),
            this.frameRate,
            Elements.label(IKey.lang("blockbuster.video.gui.encoder")).marginTop(8).marginBottom(2),
            this.encoder);

        form.flex().relative(this.viewport).x(0.5F).y(24).w(300).anchorX(0.5F);

        this.movies = new GuiButtonElement(mc, IKey.lang("minema.gui.movies_folder"), (b) ->
            MinemaIntegration.openMovies());
        this.movies.flex().h(20);

        this.settings = new GuiButtonElement(mc, IKey.lang("minema.gui.settings"), (b) ->
            this.openSettings());
        this.settings.flex().h(20);

        this.record = new GuiButtonElement(mc, IKey.lang("minema.gui.record"), (b) ->
            this.startRecording());
        this.record.flex().h(20);

        GuiElement buttons = Elements.row(mc, 10, this.movies, this.settings, this.record);

        buttons.flex().relative(this.viewport).x(0.5F).y(1F, -30).w(300).h(20).anchorX(0.5F);

        this.root.add(form, buttons);

        this.root.keys().register(IKey.lang("minema.gui.record"), LegacyKeyCodes.KEY_RETURN, this::startRecording)
            .category(IKey.lang("minema.gui.title"));

        this.updateMoviesExist();
    }

    /**
     * Write the trackpad values into the live {@code video.*} config and save.
     * Called on Record, Settings, and ESC so edits persist off the form.
     */
    public void saveConfigValues()
    {
        Blockbuster.videoWidth.set((int) Math.round(this.videoWidth.value));
        Blockbuster.videoHeight.set((int) Math.round(this.videoHeight.value));
        Blockbuster.videoFrameRate.set((int) Math.round(this.frameRate.value));

        if (Blockbuster.videoWidth.getConfig() != null)
        {
            Blockbuster.videoWidth.getConfig().save();
        }
    }

    /**
     * Open the McLib dashboard on the config panel, with Blockbuster already
     * selected (the full {@code video.*} category and the rest of the mod).
     */
    private GuiButtonElement sizeButton(MinecraftClient mc, String key, int width, int height)
    {
        GuiButtonElement button = new GuiButtonElement(mc, IKey.lang(key), (b) ->
        {
            this.videoWidth.setValue(width);
            this.videoHeight.setValue(height);
        });

        button.flex().h(20);

        return button;
    }

    private void openSettings()
    {
        this.saveConfigValues();

        GuiDashboard dashboard = GuiDashboard.get();

        dashboard.panels.setPanel(dashboard.config);
        dashboard.config.selectConfig(Blockbuster.MOD_ID);

        ScreenOpener.open(dashboard);
    }

    private void startRecording()
    {
        if (this.movieExists)
        {
            return;
        }

        this.saveConfigValues();

        String filename = this.name.field.getText() == null ? "" : this.name.field.getText().trim();

        MinemaIntegration.setName(filename);

        try
        {
            MinemaIntegration.toggleRecording(true);
        }
        catch (Exception e)
        {
            /* Leave the screen open so the user can fix the name / settings. */
            return;
        }

        if (MinemaIntegration.isRecording())
        {
            MinecraftClient mc = this.context.mc;

            if (mc != null)
            {
                mc.getSoundManager().play(PositionedSoundInstance.master(
                    SoundEvents.ENTITY_CHICKEN_EGG, 1.0F));
            }

            this.closeScreen();
        }
    }

    private void updateMoviesExist()
    {
        String filename = this.name.field.getText() == null ? "" : this.name.field.getText().trim();
        File folder = MinemaBackend.moviesDir();

        this.movieExists = !filename.isEmpty()
            && (Files.exists(folder.toPath().resolve(filename))
            || Files.exists(folder.toPath().resolve(filename + ".mp4"))
            || Files.exists(folder.toPath().resolve(filename + ".mov")));

        this.fileExistsLabel.setVisible(this.movieExists);
        this.record.setEnabled(!this.movieExists);
    }

    @Override
    protected void closeScreen()
    {
        this.saveConfigValues();
        super.closeScreen();
    }

    @Override
    public boolean shouldPause()
    {
        /* Keep singleplayer ticking so the world is ready the moment Record
         * closes the form — matches in-world F4 starts. */
        return false;
    }
}
