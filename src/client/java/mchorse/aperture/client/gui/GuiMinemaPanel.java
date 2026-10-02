package mchorse.aperture.client.gui;

import mchorse.aperture.Aperture;
import mchorse.aperture.ClientProxy;
import mchorse.aperture.camera.CameraExporter;
import mchorse.aperture.camera.CameraProfile;
import mchorse.aperture.camera.fixtures.AbstractFixture;
import mchorse.aperture.camera.minema.MinemaIntegration;
import mchorse.aperture.camera.minema.RecordingFilename;
import mchorse.aperture.camera.minema.RecordingLifecycle;
import mchorse.aperture.camera.minema.RecordingRange;
import mchorse.aperture.client.gui.panels.modifiers.GuiLookModifierPanel;
import mchorse.aperture.client.gui.utils.GuiTextHelpElement;
import mchorse.aperture.events.CameraEditorEvent;
import mchorse.blockbuster.Blockbuster;
import mchorse.blockbuster.client.gui.GuiCaptureSummary;
import mchorse.mclib.client.gui.framework.elements.GuiElement;
import mchorse.mclib.client.gui.framework.elements.buttons.GuiButtonElement;
import mchorse.mclib.client.gui.framework.elements.buttons.GuiCirculateElement;
import mchorse.mclib.client.gui.framework.elements.buttons.GuiToggleElement;
import mchorse.mclib.client.gui.framework.elements.context.GuiContextMenu;
import mchorse.mclib.client.gui.framework.elements.context.GuiSimpleContextMenu;
import mchorse.mclib.client.gui.framework.elements.input.GuiTextElement;
import mchorse.mclib.client.gui.framework.elements.input.GuiTrackpadElement;
import mchorse.mclib.client.gui.framework.elements.modals.GuiMessageModal;
import mchorse.mclib.client.gui.framework.elements.modals.GuiModal;
import mchorse.mclib.client.gui.framework.elements.utils.GuiContext;
import mchorse.mclib.client.gui.framework.elements.utils.GuiDraw;
import mchorse.mclib.client.gui.framework.elements.utils.GuiDrawable;
import mchorse.mclib.client.gui.framework.elements.utils.GuiLabel;
import mchorse.mclib.client.gui.utils.Elements;
import mchorse.mclib.client.gui.utils.GuiUtils;
import mchorse.mclib.client.gui.utils.Icons;
import mchorse.mclib.client.gui.utils.keys.IKey;
import mchorse.mclib.utils.NBTUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Built-in video-recording panel (S18 P202) — the port of Aperture's
 * {@code GuiMinemaPanel} with the Minema backend swapped for Blockbuster's
 * internal recorder ({@link MinemaIntegration} → {@code VideoRecorder}). Kept
 * under the legacy class name/home so {@code GuiCameraEditor}'s call sites
 * ({@code editor.minema}, the {@code APIcons.MINEMA} popup toggle, the per-frame
 * {@code minema(ticks, partialTicks)} call, Escape interception, F1-tooltip
 * suppression, close-on-exit) diff clean.
 *
 * <p>The recording modes / naming / start-stop coupling / auto-stop / modals live
 * in headlessly-tested logic classes ({@link RecordingRange},
 * {@link RecordingFilename}, {@link RecordingLifecycle}); this panel is the thin
 * GUI shell that wires them to the real editor.</p>
 *
 * <p><b>P202.2 — tracking sub-panel.</b> The tracking toggle, the relative-origin
 * toggle + X/Y/Z trackpads with their copy/paste/generate/reset context menu, and
 * the entity-selector field are ported here and drive the P202.1
 * {@link #trackingExporter} — a {@code public static final} singleton exactly
 * like legacy, so tracking state survives panel rebuilds (and so
 * {@code MorphTracker}'s Aperture hook has a global to reach).</p>
 *
 * Legacy source: .tools/legacy-src/aperture/src/main/java/mchorse/aperture/client/gui/GuiMinemaPanel.java
 */
public class GuiMinemaPanel extends GuiElement
{
    public GuiCameraEditor editor;

    public GuiElement fields;
    public GuiTextElement name;
    public GuiCirculateElement mode;
    public GuiTrackpadElement left;
    public GuiTrackpadElement right;
    public GuiButtonElement setLeft;
    public GuiButtonElement setRight;
    public GuiButtonElement movies;
    public GuiToggleElement trackingButton;
    public GuiElement trackingElementsWrapper;
    public GuiElement trackingElements;
    public GuiToggleElement originButton;
    public GuiElement originElementsWrapper;
    public GuiLabel originTitle;
    public GuiRelativeOriginTransformation originRow;
    public GuiElement originElements;
    public GuiElement selectorElement;
    public GuiTextHelpElement selector;
    public GuiButtonElement record;

    /**
     * The tracking-data exporter (P202.1). Legacy declares it
     * {@code public static final} on this panel — a process-wide singleton the
     * morph trackers reach through; kept static so tracking state (relative
     * origin, selector, an in-flight session) survives panel rebuilds.
     */
    public static final CameraExporter trackingExporter = new CameraExporter();

    /** wrapper for custom recording mode elements */
    private GuiElement customWrapper;
    /** row for trackpads for start and end */
    private GuiElement leftRight;
    /** row for set start and end buttons */
    private GuiElement setLeftRight;

    private RecordingMode recordingMode = RecordingMode.FULL;

    private long lastUpdate;
    private String lastName;

    private final RecordingLifecycle lifecycle;

    public GuiMinemaPanel(MinecraftClient mc, GuiCameraEditor editor)
    {
        super(mc);

        this.editor = editor;
        this.lifecycle = new RecordingLifecycle(new PanelRecorder(), new PanelHost());

        this.fields = new GuiElement(mc);
        this.name = new GuiTextElement(mc, (Consumer<String>) null).filename();
        this.name.tooltip(IKey.lang("aperture.gui.minema.output"));
        this.mode = new GuiCirculateElement(mc, (b) ->
        {
            this.switchMode(b);
            this.updateButtons();
        });

        for (RecordingMode mode : RecordingMode.values())
        {
            this.mode.addLabel(IKey.lang("aperture.gui.minema.modes." + mode.id));
        }

        this.mode.tooltip(IKey.lang("aperture.gui.minema.modes.tooltip"));

        this.left = new GuiTrackpadElement(mc, (Consumer<Double>) null);
        this.left.limit(0).integer();
        this.left.setValue(0);
        this.right = new GuiTrackpadElement(mc, (Consumer<Double>) null);
        this.right.limit(0).integer();
        this.right.setValue(0);
        this.setLeft = new GuiButtonElement(mc, IKey.lang("aperture.gui.minema.set_start"), this::calculateLeft);
        this.setLeft.tooltip(IKey.lang("aperture.gui.minema.set_start_tooltip"));
        this.setRight = new GuiButtonElement(mc, IKey.lang("aperture.gui.minema.set_duration"), this::calculateRight);
        this.setRight.tooltip(IKey.lang("aperture.gui.minema.set_duration_tooltip"));
        this.record = new GuiButtonElement(mc, IKey.lang("aperture.gui.minema.record"), this::startRecording);
        this.movies = new GuiButtonElement(mc, IKey.lang("minema.gui.movies_folder"), this::openMovies);

        this.trackingButton = new GuiToggleElement(mc, IKey.lang("aperture.gui.minema.tracking_button"), (b) ->
        {
            this.updateButtons();
        });
        this.trackingButton.tooltip(IKey.lang("aperture.gui.minema.tracking_tooltip"));

        this.originButton = new GuiToggleElement(mc, IKey.lang("aperture.gui.minema.tracking_origin_title"), (b) ->
        {
            trackingExporter.setRelativeOrigin(b.isToggled());

            if (b.isToggled())
            {
                trackingExporter.setOriginX(this.originRow.originX.value);
                trackingExporter.setOriginY(this.originRow.originY.value);
                trackingExporter.setOriginZ(this.originRow.originZ.value);
            }
        });
        this.originButton.tooltip(IKey.lang("aperture.gui.minema.tracking_origin_title_tooltip"));

        this.trackingElementsWrapper = new GuiElement(mc);
        this.trackingElementsWrapper.flex().column(4).stretch().vertical().height(3);

        this.trackingElements = new GuiElement(mc);
        this.trackingElements.flex().column(4).stretch().vertical().height(3);
        this.trackingElements.marginTop(10).marginBottom(10);

        this.originElementsWrapper = new GuiElement(mc);
        this.originElementsWrapper.flex().column(4).stretch().vertical().height(3);

        this.originElements = new GuiElement(mc);
        this.originElements.flex().column(4).stretch().vertical().height(3);
        this.originElements.marginTop(7).marginBottom(7);

        this.originRow = new GuiRelativeOriginTransformation(mc);

        this.selectorElement = new GuiElement(mc);
        this.selectorElement.flex().column(4).stretch().vertical().height(1);

        /* NB: legacy creates originTitle but never adds it to any parent — the
         * originButton's own label carries the title. Kept (dead) for parity. */
        this.originTitle = Elements.label(IKey.lang("aperture.gui.minema.tracking_origin_title"), 20).anchor(0, 1F);
        this.originTitle.tooltip(IKey.lang("aperture.gui.minema.tracking_origin_title_tooltip"));

        this.selector = new GuiTextHelpElement(mc, 500, (str) ->
        {
            trackingExporter.setEntitiesSelector(str);
        });
        this.selector.link(GuiLookModifierPanel.TARGET_SELECTOR_HELP).tooltip(IKey.lang("aperture.gui.minema.tracking_entity_selector"));

        this.originElements.add(this.originButton, this.originRow);
        this.originElementsWrapper.add(this.originElements);

        this.selectorElement.add(this.selector);
        this.trackingElementsWrapper.add(this.originElementsWrapper, this.selectorElement);

        this.trackingElements.add(this.trackingButton);

        this.customWrapper = new GuiElement(mc);
        this.customWrapper.flex().column(4).stretch().vertical().height(2);

        this.leftRight = Elements.row(mc, 5, 0, 20, this.left, this.right);
        this.setLeftRight = Elements.row(mc, 5, 0, 20, this.setLeft, this.setRight);

        this.fields.flex().relative(this.flex()).w(1F).column(5).vertical().stretch().height(20).padding(10);
        this.flex().hTo(this.fields.flex(), 1F);

        this.fields.add(Elements.label(IKey.lang("aperture.gui.minema.title"), 12).background());
        /* wixo.1 (CDC R7): effective size and encoding of the next take. */
        GuiCaptureSummary summary = new GuiCaptureSummary(mc, Blockbuster.videoWidth::get, Blockbuster.videoHeight::get);

        summary.flex().h(12);
        this.fields.add(this.name, this.mode, summary);
        this.fields.add(this.customWrapper, this.trackingElements, Elements.row(mc, 5, 0, 20, this.movies, this.record));

        this.add(this.fields);
        this.add(new GuiDrawable(this::drawGhostFilename));

        this.fields.setVisible(MinemaIntegration.isLoaded() && MinemaIntegration.isAvailable());
        this.switchMode(this.mode);
        /* NB: legacy does NOT call updateButtons() here — it ends with
         * getParent().resize(), and the panel has no parent during construction
         * (GuiCameraEditor adds it only after the constructor returns), so calling
         * it here NPEs. Initial mode is FULL, so no custom-wrapper attach is
         * needed at construction anyway. */
    }

    private void updateButtons()
    {
        this.trackingElementsWrapper.removeFromParent();
        this.leftRight.removeFromParent();
        this.setLeftRight.removeFromParent();

        if (this.trackingButton.isToggled())
        {
            this.trackingElements.add(this.trackingElementsWrapper);
        }

        if (this.recordingMode == RecordingMode.CUSTOM)
        {
            this.customWrapper.add(this.leftRight);
            this.customWrapper.add(this.setLeftRight);
        }

        this.getParent().resize();
    }

    public void setProfile(CameraProfile profile)
    {
        this.left.setValue(0);
        this.right.setValue(profile == null ? 30 : profile.getDuration());
    }

    private void switchMode(GuiCirculateElement b)
    {
        this.recordingMode = RecordingMode.values()[b.getValue()];
    }

    public boolean isRecording()
    {
        return this.lifecycle.isRecording();
    }

    /** wixo.1 (C5): see {@link RecordingLifecycle#capturesFrame}. */
    public boolean capturesFrame()
    {
        return this.lifecycle.capturesFrame(this.isRunning(), this.editor.getRunner().ticks);
    }

    private boolean isRunning()
    {
        return this.editor.getRunner().isRunning();
    }

    /**
     * The panel filename (legacy {@code getFilename}) — explicit field wins, else
     * (with {@code minema.default_profile_name} on) the profile destination name
     * with a {@code -N} fixture suffix in FIXTURE mode.
     */
    private String getFilename()
    {
        String explicit = this.name.field.getText();
        boolean defaultProfileName = Aperture.minemaDefaultProfileName.get();
        boolean fixtureMode = this.recordingMode == RecordingMode.FIXTURE;

        boolean fixturePresent = false;
        int fixtureIndex = -1;
        String profileFilename = null;

        if (defaultProfileName)
        {
            profileFilename = this.editor.getProfile().getDestination().getFilename();

            if (fixtureMode)
            {
                AbstractFixture fixture = this.editor.getFixture();

                if (fixture != null)
                {
                    fixturePresent = true;
                    fixtureIndex = this.editor.getProfile().fixtures.indexOf(fixture);
                }
            }
        }

        return RecordingFilename.get(explicit, defaultProfileName, profileFilename, fixtureMode, fixturePresent, fixtureIndex);
    }

    private void calculateLeft(GuiButtonElement button)
    {
        double[] result = RecordingRange.setStart(this.left.value, this.right.value, this.editor.timeline.value);

        this.left.setValue(result[0]);
        this.right.setValue(result[1]);
    }

    private void calculateRight(GuiButtonElement button)
    {
        this.right.setValue(RecordingRange.setDuration(this.left.value, this.editor.timeline.value));
    }

    private void openMovies(GuiButtonElement button)
    {
        MinemaIntegration.openMovies();
    }

    private void startRecording(GuiButtonElement button)
    {
        RecordingRange range;

        if (this.recordingMode == RecordingMode.FIXTURE && this.editor.panel.delegate != null)
        {
            /* FIXTURE reads the currently open fixture panel, not the timeline. */
            AbstractFixture fixture = this.editor.panel.delegate.fixture;

            range = RecordingRange.fixture(this.editor.getProfile().calculateOffset(fixture), fixture.getDuration());
        }
        else if (this.recordingMode == RecordingMode.FULL)
        {
            range = RecordingRange.full(this.editor.getProfile().getDuration());
        }
        else
        {
            /* CUSTOM, or FIXTURE with no open fixture panel (legacy fall-through). */
            range = RecordingRange.custom((int) this.left.value, (int) this.right.value);
        }

        this.lifecycle.startRecording(range);
    }

    public void stop()
    {
        this.lifecycle.stop(false);
    }

    public void stop(boolean prematureStop)
    {
        this.lifecycle.stop(prematureStop);
    }

    /**
     * Per-frame recording hook (legacy {@code minema}). Drives the lifecycle then
     * draws the {@code debug_ticks} sync overlay while recording.
     */
    public void minema(int ticks, float partialTicks)
    {
        boolean wasRecording = this.lifecycle.isRecording();

        this.lifecycle.minema(ticks);

        if (wasRecording && Aperture.debugTicks.get())
        {
            GuiDraw.drawStringWithShadow(this.font, String.valueOf(ticks + partialTicks), 0, 0, 0xffffff);
        }
    }

    private void drawGhostFilename(GuiContext context)
    {
        if (this.fields.isVisible() && !this.name.isFocused() && this.name.field.getText().isEmpty())
        {
            long current = System.currentTimeMillis();

            /* Rate-limited to 1 Hz — the per-frame SimpleDateFormat.format was the
             * reason for the cache (legacy). */
            if (current > this.lastUpdate + 1000)
            {
                this.lastUpdate = current;
                this.lastName = RecordingFilename.timestamp(current);
            }

            String filename = Aperture.minemaDefaultProfileName.get() ? this.getFilename() : this.lastName;

            if (filename != null)
            {
                GuiDraw.drawStringWithShadow(this.font, filename, this.name.area.x + 5, this.name.area.my() - 4, 0x888888);
            }
        }
    }

    @Override
    public void draw(GuiContext context)
    {
        this.area.draw(0xaa000000);

        /* Dead-but-kept availability texts (translation-key parity, P8): the
         * built-in recorder is always loaded/available, so these never render. */
        int x = this.area.x + 10;
        int y = this.area.my();

        if (!MinemaIntegration.isLoaded())
        {
            GuiDraw.drawMultiText(this.font, I18n.translate("aperture.gui.minema.minema_not_installed"), x, y, 0xffffff, this.area.w - 20, 12, 0.5F, 0.5F);
        }
        else if (!MinemaIntegration.isAvailable())
        {
            GuiDraw.drawMultiText(this.font, I18n.translate("aperture.gui.minema.minema_wrong_version"), x, y, 0xffffff, this.area.w - 20, 12, 0.5F, 0.5F);
        }

        super.draw(context);
    }

    public static enum RecordingMode
    {
        FULL("full"), FIXTURE("fixture"), CUSTOM("custom");

        public final String id;

        private RecordingMode(String id)
        {
            this.id = id;
        }
    }

    /** {@link MinemaIntegration} facade adapter for the lifecycle. */
    private class PanelRecorder implements RecordingLifecycle.Recorder
    {
        @Override
        public boolean isRecording()
        {
            return MinemaIntegration.isRecording();
        }

        @Override
        public void toggleRecording(boolean state) throws Exception
        {
            MinemaIntegration.toggleRecording(state);
        }

        @Override
        public void setName(String name)
        {
            MinemaIntegration.setName(name);
        }

        @Override
        public String getMessage(Exception e)
        {
            return MinemaIntegration.getMessage(e);
        }
    }

    /** Editor/runner callbacks for the lifecycle. */
    private class PanelHost implements RecordingLifecycle.Host
    {
        @Override
        public boolean isRunning()
        {
            return GuiMinemaPanel.this.isRunning();
        }

        @Override
        public void togglePlayback()
        {
            GuiMinemaPanel.this.editor.togglePlayback();
        }

        @Override
        public void setRootVisible(boolean visible)
        {
            GuiMinemaPanel.this.editor.root.setVisible(visible);
        }

        @Override
        public void postOperation(Runnable operation)
        {
            GuiMinemaPanel.this.editor.postOperation(operation);
        }

        @Override
        public void showErrorModal(String message)
        {
            GuiMinemaPanel panel = GuiMinemaPanel.this;

            GuiModal.addFullModal(panel, () -> new GuiMessageModal(panel.mc, IKey.str(message)));
        }

        @Override
        public void showPrematureStopModal()
        {
            GuiMinemaPanel panel = GuiMinemaPanel.this;

            GuiModal.addFullModal(panel, () -> new GuiMessageModal(panel.mc, IKey.lang("aperture.gui.minema.premature_stop")));
        }

        @Override
        public void rewind(int start)
        {
            GuiMinemaPanel panel = GuiMinemaPanel.this;

            ClientProxy.EVENT_BUS.post(new CameraEditorEvent.Rewind(panel.editor, start));
            panel.editor.timeline.setValueFromScrub(start);
            panel.editor.updatePlayer(start, 0);
        }

        @Override
        public String getFilename()
        {
            return GuiMinemaPanel.this.getFilename();
        }

        /* Tracking seam (P202.1 CameraExporter / P202.2 tracking GUI). */

        @Override
        public boolean trackingToggled()
        {
            return GuiMinemaPanel.this.trackingButton.isToggled();
        }

        @Override
        public void trackingStart()
        {
            trackingExporter.start(GuiMinemaPanel.this.editor.getRunner());
        }

        @Override
        public boolean trackingIsTracking()
        {
            return trackingExporter.isTracking();
        }

        @Override
        public void trackingExport(String jsonFilename)
        {
            trackingExporter.exportTrackingData(jsonFilename);
        }

        @Override
        public void trackingReset()
        {
            trackingExporter.reset();
        }
    }

    /* --------------------------------------------------------------------- */
    /* P202.2 — relative-origin row                                          */
    /* --------------------------------------------------------------------- */

    /**
     * The X/Y/Z relative-origin trackpads, bound straight to the exporter's
     * {@code setOriginX/Y/Z}.
     *
     * <p><b>Load-bearing asymmetry (legacy):</b> the trackpad consumers are wired
     * unconditionally, so editing a value pushes it into the exporter even while
     * the relative-origin toggle is OFF; the toggle alone decides whether
     * {@link CameraExporter#reset()} preserves the stored origin (and re-pushes
     * all three on the way ON). Copied as-is.</p>
     */
    protected class GuiRelativeOriginTransformation extends GuiElement
    {
        public GuiTrackpadElement originX;
        public GuiTrackpadElement originY;
        public GuiTrackpadElement originZ;

        public GuiRelativeOriginTransformation(MinecraftClient mc)
        {
            super(mc);

            this.originX = new GuiTrackpadElement(mc, trackingExporter::setOriginX);
            this.originX.tooltip(IKey.lang("aperture.gui.minema.tracking_origin_x"));

            this.originY = new GuiTrackpadElement(mc, trackingExporter::setOriginY);
            this.originY.tooltip(IKey.lang("aperture.gui.minema.tracking_origin_y"));

            this.originZ = new GuiTrackpadElement(mc, trackingExporter::setOriginZ);
            this.originZ.tooltip(IKey.lang("aperture.gui.minema.tracking_origin_z"));

            this.add(this.originX, this.originY, this.originZ);
            this.flex().column(4).vertical().stretch();
        }

        @Override
        public GuiContextMenu createContextMenu(GuiContext context)
        {
            GuiSimpleContextMenu menu = new GuiSimpleContextMenu(context.mc);

            /* Legacy parses the clipboard when the menu is BUILT, and only then
             * adds the paste entry — a conditionally present item, not a greyed
             * out one. Reproduced. */
            NbtList transforms = parseOrigin(GuiUtils.getClipboardString());

            menu.action(Icons.COPY, IKey.lang("mclib.gui.transforms.context.copy"), this::copyTransformations);

            if (transforms != null)
            {
                final NbtList innerList = transforms;

                menu.action(Icons.PASTE, IKey.lang("mclib.gui.transforms.context.paste"), () ->
                {
                    this.pasteTransformations(innerList);
                });
            }

            menu.action(Icons.REFRESH, IKey.lang("aperture.gui.minema.tracking_origin_generate"), this::setRelativeOriginCoordinates);
            menu.action(Icons.CLOSE, IKey.lang("mclib.gui.transforms.context.reset"), this::resetTransformations);

            return menu;
        }

        private void resetTransformations()
        {
            this.originX.setValue(0);
            this.originY.setValue(0);
            this.originZ.setValue(0);
        }

        private void setRelativeOriginCoordinates()
        {
            this.originX.setValue(roundOrigin(GuiMinemaPanel.this.editor.position.point.x));
            this.originY.setValue(roundOrigin(GuiMinemaPanel.this.editor.position.point.y));
            this.originZ.setValue(roundOrigin(GuiMinemaPanel.this.editor.position.point.z));
        }

        private void copyTransformations()
        {
            GuiUtils.setClipboardString(copyOrigin(this.originX.value, this.originY.value, this.originZ.value));
        }

        private void pasteTransformations(NbtList list)
        {
            this.originX.setValue(list.getDouble(0));
            this.originY.setValue(list.getDouble(1));
            this.originZ.setValue(list.getDouble(2));
        }
    }

    /**
     * Parse a clipboard string into a &ge;3-double origin list, or {@code null}.
     *
     * <p>Legacy: {@code JsonToNBT.getTagFromJson("{Transforms:" + clipboard +
     * "}")} then {@code getTagList("Transforms", Constants.NBT.TAG_DOUBLE)} with
     * a {@code tagCount() >= 3} check — the wrapper key is deliberately the same
     * {@code Transforms} mclib's {@code GuiTransformations} copy/paste uses, so
     * the two interoperate. The check is {@code >=}, not {@code ==}: longer lists
     * are accepted and only the first three entries are read. 1.20.4 swaps
     * {@code JsonToNBT} for {@code StringNbtReader} (via
     * {@link NBTUtils#parseSnbtCompound(String)}) and {@code TAG_DOUBLE} for
     * {@link NbtElement#DOUBLE_TYPE}.</p>
     *
     * <p>Package-private static so it is headlessly testable.</p>
     */
    static NbtList parseOrigin(String clipboard)
    {
        try
        {
            NbtCompound tag = NBTUtils.parseSnbtCompound("{Transforms:" + clipboard + "}");

            if (tag == null)
            {
                return null;
            }

            NbtList list = tag.getList("Transforms", NbtElement.DOUBLE_TYPE);

            if (list.size() >= 3)
            {
                return list;
            }
        }
        catch (Exception e)
        {}

        return null;
    }

    /** The clipboard payload legacy's copy action wrote: an NBT double list. */
    static String copyOrigin(double x, double y, double z)
    {
        NbtList list = new NbtList();

        list.add(NbtDouble.of(x));
        list.add(NbtDouble.of(y));
        list.add(NbtDouble.of(z));

        return list.toString();
    }

    /**
     * Legacy's generate-coordinates rounding: {@code DecimalFormat("#.##")} with
     * {@code RoundingMode.HALF_UP}, re-parsed as a double. Two decimals, and
     * half-up rather than Java's default half-even — {@code 100.005} becomes
     * {@code 100.01}. Do not "improve" the precision.
     *
     * <p>One hardening: legacy used the JVM's default locale, so on a
     * comma-decimal locale {@code df.format} produced {@code "100,01"} and the
     * re-parse threw {@code NumberFormatException} out of the context menu. The
     * symbols are pinned to {@link Locale#ROOT}, which is byte-identical to
     * 1.12.2 on every dot-decimal locale and merely stops the crash elsewhere.</p>
     */
    static double roundOrigin(double value)
    {
        DecimalFormat df = new DecimalFormat("#.##", DecimalFormatSymbols.getInstance(Locale.ROOT));

        df.setRoundingMode(RoundingMode.HALF_UP);

        return Double.parseDouble(df.format(value));
    }
}
