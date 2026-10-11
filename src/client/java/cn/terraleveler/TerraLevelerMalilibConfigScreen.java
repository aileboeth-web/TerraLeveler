package cn.terraleveler;

import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TerraLevelerMalilibConfigScreen extends GuiConfigsBase {
    private static final int CONFIG_LIST_MAX_WIDTH = 560;
    private static final String RETAINED_ITEMS_LABEL = "保留物品清单";
    private static final String LOG_FILE_LABEL = "诊断日志文件";
    private final ButtonGeneric[] hotkeyButtons = new ButtonGeneric[4];
    private int capturingHotkey = -1;
    private boolean cancelCloseRequested;
    /** Esc could not apply the target Y; the next Esc leaves without it (everything else is already saved). */
    private boolean escapeSaveRejected;
    private boolean listRefreshAfterMouseRelease;
    /**
     * A key that was just captured as a shortcut also produces a character event; it must
     * not reach malilib, which would open its search bar with it (before 0.9.05 binding
     * the backslash key opened the search bar).
     */
    private boolean swallowNextChar;

    TerraLevelerMalilibConfigScreen(Screen parent) {
        super(10, 50, TerraLeveler.MOD_ID, parent,
                "TerraLeveler 配置 - " + TerraLeveler.VERSION);
        setConfigWidth(SettingsLayoutRules.CONTROL_WIDTH);
        TerraLevelerMalilibConfigOptions.loadFrom(TerraLeveler.CONFIG);
    }

    @Override
    public void initGui() {
        setListPosition(10, 50);
        // Size the columns for the current screen: at a large GUI scale the screen can be
        // only 320 px wide, and the fixed 180 + 80 px layout pushed buttons off-screen.
        int listWidth = configListWidth();
        int controlWidth = SettingsLayoutRules.controlWidth(listWidth);
        setConfigWidth(controlWidth);
        if (!(getListWidget() instanceof TerraLevelerConfigList current) || !current.fits(listWidth, controlWidth)) {
            reCreateListWidget();
        }
        for (int action = 0; action < hotkeyButtons.length; action++) {
            hotkeyButtons[action] = null;
        }
        capturingHotkey = -1;
        cancelCloseRequested = false;
        escapeSaveRejected = false;
        super.initGui();
        clearOptions();

        int modeGap = 2;
        int modeX = 10;
        for (WorkMode mode : WorkMode.SELECTABLE) {
            ButtonGeneric button = new ButtonGeneric(modeX, 26, -1, 20, mode.label());
            button.setTextCentered(true);
            button.setEnabled(mode != TerraLeveler.ACTIVE_MODE);
            addButton(button, (clicked, mouseButton) -> {
                applyAndSave();
                if (!TerraLeveler.setActiveMode(mode)) {
                    addMessage(Message.MessageType.ERROR, "请先停止当前施工，再切换作业模式");
                    return;
                }
                TerraLevelerMalilibConfigOptions.loadFrom(TerraLeveler.CONFIG);
                reCreateListWidget();
                initGui();
            });
            modeX += button.getWidth() + modeGap;
        }
        // No save button (0.9.05): every edit is saved as soon as it is made.
    }

    private void addToolBoxNameField() {
        applyAndSave();
        TerraLevelerMalilibConfigOptions.addToolBoxNameField();
        rebuildList(true);
    }

    private void addPickupItemField() {
        applyAndSave();
        TerraLevelerMalilibConfigOptions.addPickupItemField();
        rebuildList(true);
    }

    private void rebuildList(boolean preserveScroll) {
        int scrollValue = 0;
        WidgetListConfigOptions list = getListWidget();
        if (preserveScroll && list != null) {
            scrollValue = list.getScrollbar().getValue();
        }
        reCreateListWidget();
        initGui();
        if (preserveScroll) {
            WidgetListConfigOptions refreshedList = getListWidget();
            if (refreshedList != null) {
                refreshedList.getScrollbar().setValue(scrollValue);
            }
        }
    }
    private String hotkeyLabel(int action) {
        return TerraLeveler.boundKeyDisplayName(action);
    }

    private String hotkeyActionLabel(int action) {
        return KeyHintText.label(action);
    }

    private String hotkeyCaptureLabel() {
        return "请按新按键（Esc 设为无）";
    }

    private void beginHotkeyCapture(int action, ButtonGeneric button) {
        if (capturingHotkey >= 0 && capturingHotkey != action
                && hotkeyButtons[capturingHotkey] != null) {
            hotkeyButtons[capturingHotkey].setDisplayString(hotkeyLabel(capturingHotkey));
        }
        capturingHotkey = action;
        button.setDisplayString(hotkeyCaptureLabel());
    }

    private void resetHotkey(int action, ButtonGeneric button) {
        if (capturingHotkey == action) capturingHotkey = -1;
        TerraLeveler.clearBoundKey(action);
        button.setDisplayString(hotkeyLabel(action));
    }

    @Override
    public boolean onKeyTyped(KeyEvent event) {
        if (capturingHotkey < 0) swallowNextChar = false;
        if (capturingHotkey >= 0) {
            if (event.key() != InputConstants.KEY_ESCAPE
                    && TerraLeveler.isModifierKey(event.key())) {
                return true;
            }
            int action = capturingHotkey;
            capturingHotkey = -1;
            // Esc while waiting for a key clears the shortcut; any other key becomes it.
            if (event.key() == InputConstants.KEY_ESCAPE) {
                TerraLeveler.clearBoundKey(action);
            } else {
                TerraLeveler.setBoundKeyComposite(action, event);
                swallowNextChar = true;
            }
            if (hotkeyButtons[action] != null) {
                hotkeyButtons[action].setDisplayString(hotkeyLabel(action));
            }
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE) {
            WidgetListConfigOptions list = getListWidget();
            if (list instanceof TerraLevelerConfigList wetaConfigList
                    && wetaConfigList.clearFocusedTextField()) {
                applyAndSave();
                return true;
            }
            // malilib returns to a parent screen without calling onClose, so apply here.
            if (!applyAndSave()) {
                if (!escapeSaveRejected) {
                    escapeSaveRejected = true;
                    addMessage(Message.MessageType.WARNING, "目标高度没有保存；改正后再退出，或再按一次 Esc 放弃这一项（其他修改已保存）");
                    return true;
                }
                TerraLevelerMalilibConfigOptions.loadFrom(TerraLeveler.CONFIG);
            }
            cancelCloseRequested = true;
            return super.onKeyTyped(event);
        }
        boolean handled = super.onKeyTyped(event);
        // A text field is committed with Enter or Tab.
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER
                || event.key() == GLFW.GLFW_KEY_TAB) {
            applyAndSave();
        }
        return handled;
    }
    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<ConfigOptionWrapper> configs = new ArrayList<>();
        int hotkeyCount = TerraLeveler.CONFIG.customRetainedItemsEnabled ? 4 : 3;
        for (int action = 0; action < hotkeyCount; action++) {
            configs.add(new ConfigOptionWrapper(hotkeyActionLabel(action)));
        }
        configs.addAll(ConfigOptionWrapper.createFor(modeConfigs()));
        // The log file row sits right under the diagnostics switch.
        for (int index = 0; index < configs.size(); index++) {
            if (configs.get(index).getConfig() == TerraLevelerMalilibConfigOptions.DIAGNOSTICS_ENABLED) {
                configs.add(index + 1, new ConfigOptionWrapper(LOG_FILE_LABEL));
                break;
            }
        }
        configs.add(new ConfigOptionWrapper(ProtectionOption.CUSTOM.label));
        if (TerraLeveler.CONFIG.customRetainedItemsEnabled) {
            configs.add(new ConfigOptionWrapper(ProtectionOption.FULL_INVENTORY.label));
            configs.add(new ConfigOptionWrapper(RETAINED_ITEMS_LABEL));
        }
        configs.add(new ConfigOptionWrapper(ProtectionOption.DISCARD_GOLD.label));
        configs.add(new ConfigOptionWrapper(ProtectionOption.DISCARD_IRON.label));
        return configs;
    }

    private int hotkeyActionForLabel(String label) {
        for (int action = 0; action < hotkeyButtons.length; action++) {
            if (hotkeyActionLabel(action).equals(label)) {
                return action;
            }
        }
        return -1;
    }

    private static List<IConfigBase> modeConfigs() {
        boolean lighting = TerraLeveler.ACTIVE_MODE == WorkMode.LIGHTING;
        List<IConfigBase> options = new ArrayList<>();
        options.addAll(withPickupItems(lighting
                ? TerraLevelerMalilibConfigOptions.LIGHTING_CONSTRUCTION
                : TerraLevelerMalilibConfigOptions.FLATTEN_CONSTRUCTION));
        options.addAll(withToolBoxNames(lighting
                ? TerraLevelerMalilibConfigOptions.LIGHTING_SAFETY
                : TerraLevelerMalilibConfigOptions.FLATTEN_SAFETY));
        return options.stream().distinct().toList();
    }

    private static List<IConfigBase> withPickupItems(List<IConfigBase> base) {
        List<IConfigBase> options = new ArrayList<>();
        for (IConfigBase option : base) {
            options.add(option);
            if (option == TerraLevelerMalilibConfigOptions.PICKUP_ITEM_FIELDS[0]) {
                options.addAll(TerraLevelerMalilibConfigOptions.pickupItemOptions().stream().skip(1).toList());
            }
        }
        return options;
    }

    private static List<fi.dy.masa.malilib.config.IConfigBase> withToolBoxNames(List<fi.dy.masa.malilib.config.IConfigBase> base) {
        List<fi.dy.masa.malilib.config.IConfigBase> options = new ArrayList<>();
        for (fi.dy.masa.malilib.config.IConfigBase option : base) {
            options.add(option);
            if (option == TerraLevelerMalilibConfigOptions.USE_TOOL_BOX_FOR_TOOLS
                    && TerraLevelerMalilibConfigOptions.USE_TOOL_BOX_FOR_TOOLS.getBooleanValue()) {
                options.addAll(TerraLevelerMalilibConfigOptions.toolBoxNameOptions());
            }
        }
        return options;
    }

    @Override
    protected int getBrowserHeight() {
        return Math.max(80, height - getListY() - 20);
    }

    @Override
    protected boolean useKeybindSearch() {
        return false;
    }

    private int configListWidth() {
        return Math.min(getBrowserWidth(), Math.max(480, Math.min(CONFIG_LIST_MAX_WIDTH, width - 80)));
    }

    @Override
    protected WidgetListConfigOptions createListWidget(int x, int y) {
        int listWidth = configListWidth();
        return new TerraLevelerConfigList(x, y, listWidth, getBrowserHeight(), getConfigWidth(), this, this,
                this::addToolBoxNameField, this::addPickupItemField);
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent event, boolean doubled) {
        boolean wasEnabled = TerraLevelerMalilibConfigOptions.USE_TOOL_BOX_FOR_TOOLS.getBooleanValue();
        boolean handled = super.onMouseClicked(event, doubled);
        if (wasEnabled != TerraLevelerMalilibConfigOptions.USE_TOOL_BOX_FOR_TOOLS.getBooleanValue()) {
            listRefreshAfterMouseRelease = true;
        }
        return handled;
    }

    @Override
    public boolean onMouseReleased(MouseButtonEvent event) {
        boolean handled = super.onMouseReleased(event);
        // Every click (a switch, a button, or leaving a text field) is saved at once,
        // before the list is rebuilt, so a rebuild can only read back the saved values.
        applyAndSave();
        if (listRefreshAfterMouseRelease) {
            listRefreshAfterMouseRelease = false;
            rebuildList(true);
        }
        return handled;
    }

    @Override
    public boolean onCharTyped(CharacterEvent event) {
        if (capturingHotkey >= 0 || swallowNextChar) {
            swallowNextChar = false;
            return true;
        }
        return super.onCharTyped(event);
    }

    private static final class TerraLevelerConfigList extends WidgetListConfigOptions {
        private final int preferredWidth;
        private final TerraLevelerMalilibConfigScreen owner;
        private final Runnable addToolBoxNameAction;
        private final Runnable addPickupItemAction;

        private TerraLevelerConfigList(int x, int y, int width, int height, int configWidth,
                               GuiConfigsBase parent, TerraLevelerMalilibConfigScreen owner,
                               Runnable addToolBoxNameAction, Runnable addPickupItemAction) {
            super(x, y, width, height, configWidth, 0.0f, false, parent);
            this.preferredWidth = width;
            this.owner = owner;
            this.addToolBoxNameAction = addToolBoxNameAction;
            this.addPickupItemAction = addPickupItemAction;
        }

        private boolean isHotkeyEntry(ConfigOptionWrapper wrapper) {
            return wrapper.getType() == ConfigOptionWrapper.Type.LABEL
                    && this.owner.hotkeyActionForLabel(wrapper.getLabel()) >= 0;
        }

        private boolean isProtectionEntry(ConfigOptionWrapper wrapper) {
            return wrapper.getType() == ConfigOptionWrapper.Type.LABEL
                    && ProtectionOption.forLabel(wrapper.getLabel()) != null;
        }

        private boolean isLogFileEntry(ConfigOptionWrapper wrapper) {
            return wrapper.getType() == ConfigOptionWrapper.Type.LABEL
                    && LOG_FILE_LABEL.equals(wrapper.getLabel());
        }

        private boolean isRetainedItemsEntry(ConfigOptionWrapper wrapper) {
            return wrapper.getType() == ConfigOptionWrapper.Type.LABEL
                    && RETAINED_ITEMS_LABEL.equals(wrapper.getLabel());
        }

        @Override
        public void setSize(int width, int height) {
            super.setSize(this.preferredWidth > 0 ? Math.min(width, this.preferredWidth) : width, height);
        }

        @Override
        protected WidgetConfigOption createListEntryWidget(int x, int y, int entryIndex,
                                                            boolean selected, ConfigOptionWrapper wrapper) {
            if (isHotkeyEntry(wrapper)) {
                return new TerraLevelerHotkeyOption(x, y, this.browserEntryWidth, this.browserEntryHeight,
                        this.maxLabelWidth, this.configWidth, wrapper, entryIndex, this.parent, this,
                        this.addToolBoxNameAction, this.addPickupItemAction, this.owner,
                        this.owner.hotkeyActionForLabel(wrapper.getLabel()));
            }
            if (isProtectionEntry(wrapper)) {
                return new TerraLevelerProtectionOption(x, y, this.browserEntryWidth, this.browserEntryHeight,
                        this.maxLabelWidth, this.configWidth, wrapper, entryIndex, this.parent, this,
                        this.addToolBoxNameAction, this.addPickupItemAction, this.owner,
                        ProtectionOption.forLabel(wrapper.getLabel()));
            }
            if (isLogFileEntry(wrapper)) {
                return new TerraLevelerLogFileOption(x, y, this.browserEntryWidth, this.browserEntryHeight,
                        this.maxLabelWidth, this.configWidth, wrapper, entryIndex, this.parent, this,
                        this.addToolBoxNameAction, this.addPickupItemAction);
            }
            if (isRetainedItemsEntry(wrapper)) {
                return new TerraLevelerRetainedItemsOption(x, y, this.browserEntryWidth, this.browserEntryHeight,
                        this.maxLabelWidth, this.configWidth, wrapper, entryIndex, this.parent, this,
                        this.addToolBoxNameAction, this.addPickupItemAction, this.owner);
            }
            return new TerraLevelerConfigOption(x, y, this.browserEntryWidth, this.browserEntryHeight,
                    this.maxLabelWidth, this.configWidth, wrapper, entryIndex, this.parent, this,
                    this.addToolBoxNameAction, this.addPickupItemAction);
        }

        @Override
        protected int getBrowserEntryHeightFor(ConfigOptionWrapper entry) {
            return super.getBrowserEntryHeightFor(entry);
        }

        @Override
        protected List<String> getEntryStringsForFilter(ConfigOptionWrapper entry) {
            List<String> strings = new ArrayList<>(super.getEntryStringsForFilter(entry));
            if (isHotkeyEntry(entry)) {
                strings.add(entry.getLabel().toLowerCase());
            }
            if (isProtectionEntry(entry)) {
                strings.add(entry.getLabel().toLowerCase());
            }
            if (isRetainedItemsEntry(entry)) {
                strings.add(entry.getLabel().toLowerCase());
                strings.addAll(TerraLeveler.CONFIG.retainedItemIds);
            }
            return strings;
        }

        @Override
        public int getMaxNameLengthWrapped(List<ConfigOptionWrapper> entries) {
            int longest = super.getMaxNameLengthWrapped(entries);
            if (this.preferredWidth <= 0) return longest + SettingsLayoutRules.MAX_LABEL_PADDING;
            return longest + SettingsLayoutRules.labelPadding(this.preferredWidth, longest, this.configWidth);
        }

        private boolean fits(int listWidth, int controlWidth) {
            return this.preferredWidth == listWidth && this.configWidth == controlWidth;
        }

        private boolean clearFocusedTextField() {
            for (var textField : this.textFields) {
                if (textField.isFocused()) {
                    clearTextFieldFocus();
                    return true;
                }
            }
            return false;
        }

        private void applyVisibleDynamicTextValues(TerraLevelerConfig config) {
            Map<String, String> values = new LinkedHashMap<>();
            for (var widget : this.listWidgets) {
                if (widget instanceof TerraLevelerConfigOption option) {
                    option.collectDynamicTextValue(values);
                }
            }
            TerraLevelerMalilibConfigOptions.applyVisibleDynamicTextValues(config, values);
        }
    }

    private static class TerraLevelerConfigOption extends WidgetConfigOption {
        protected TerraLevelerConfigOption(int x, int y, int width, int height, int maxLabelWidth,
                                   int configWidth, ConfigOptionWrapper wrapper, int entryIndex,
                                   fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui host,
                                   TerraLevelerConfigList parent, Runnable addToolBoxNameAction,
                                   Runnable addPickupItemAction) {
            super(x, y, width, height, maxLabelWidth, configWidth, wrapper, entryIndex, host, parent);
            if (wrapper.getConfig() != null && ("toolBoxName1".equals(wrapper.getConfig().getName())
                    || "pickupItem1".equals(wrapper.getConfig().getName()))) {
                ButtonGeneric addButton = new ButtonGeneric(
                        x + maxLabelWidth + configWidth - 8, y + 1, 18, 20, "+");
                addButton.setTextCentered(true);
                boolean pickup = "pickupItem1".equals(wrapper.getConfig().getName());
                addButton.setEnabled(pickup
                        ? TerraLevelerMalilibConfigOptions.pickupItemOptions().size()
                            < TerraLevelerMalilibConfigOptions.MAX_PICKUP_ITEM_FIELDS
                        : TerraLevelerMalilibConfigOptions.toolBoxNameOptions().size()
                            < TerraLevelerMalilibConfigOptions.MAX_TOOL_BOX_NAME_FIELDS);
                addButton(addButton, (button, mouseButton) ->
                        (pickup ? addPickupItemAction : addToolBoxNameAction).run());
            }
        }

        private void collectDynamicTextValue(Map<String, String> values) {
            if (this.wrapper.getType() != ConfigOptionWrapper.Type.CONFIG || this.textField == null) {
                return;
            }
            String name = this.wrapper.getConfig().getName();
            if (TerraLevelerMalilibConfigOptions.isDynamicTextField(name)) {
                values.put(name, this.textField.textField().getValue());
            }
        }

    }

    private static final class TerraLevelerHotkeyOption extends TerraLevelerConfigOption {
        private TerraLevelerHotkeyOption(int x, int y, int width, int height, int maxLabelWidth,
                                 int configWidth, ConfigOptionWrapper wrapper, int entryIndex,
                                 fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui host,
                                 TerraLevelerConfigList parent, Runnable addToolBoxNameAction,
                                 Runnable addPickupItemAction,
                                 TerraLevelerMalilibConfigScreen owner, int action) {
            super(x, y, width, height, maxLabelWidth, configWidth, wrapper, entryIndex, host,
                    parent, addToolBoxNameAction, addPickupItemAction);
            int keyWidth = configWidth;
            int keyX = x + maxLabelWidth + SettingsLayoutRules.CONTROL_GAP;
            int resetX = keyX + keyWidth + 2;
            ButtonGeneric keyButton = new ButtonGeneric(
                    keyX, y + 1, keyWidth, 20, owner.hotkeyLabel(action));
            keyButton.setTextCentered(true);
            owner.hotkeyButtons[action] = keyButton;
            addButton(keyButton, (button, mouseButton) ->
                    owner.beginHotkeyCapture(action, keyButton));
            ButtonGeneric resetButton = new ButtonGeneric(resetX, y + 1, -1, 20, "重置");
            resetButton.setTextCentered(true);
            addButton(resetButton, (button, mouseButton) ->
                    owner.resetHotkey(action, keyButton));
        }

        @Override
        public void render(GuiContext context, int mouseX, int mouseY, boolean selected) {
            drawSubWidgets(context, mouseX, mouseY);
        }
    }

    private static final class TerraLevelerProtectionOption extends TerraLevelerConfigOption {
        private final TerraLevelerMalilibConfigScreen owner;
        private final ProtectionOption option;
        private final ButtonGeneric valueButton;
        private final ButtonGeneric resetButton;

        private TerraLevelerProtectionOption(int x, int y, int width, int height, int maxLabelWidth,
                                     int configWidth, ConfigOptionWrapper wrapper, int entryIndex,
                                     fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui host,
                                     TerraLevelerConfigList parent, Runnable addToolBoxNameAction,
                                     Runnable addPickupItemAction, TerraLevelerMalilibConfigScreen owner,
                                     ProtectionOption option) {
            super(x, y, width, height, maxLabelWidth, configWidth, wrapper, entryIndex, host,
                    parent, addToolBoxNameAction, addPickupItemAction);
            this.owner = owner;
            this.option = option;
            int valueX = x + maxLabelWidth + 10;
            this.valueButton = addWidget(new ButtonGeneric(
                    valueX, y + 1, configWidth, 20, booleanLabel(owner.protectionValue(option))));
            this.valueButton.setTextCentered(true);
            this.resetButton = addWidget(new ButtonGeneric(
                    valueX + configWidth + 2, y + 1, -1, 20, "重置"));
            this.resetButton.setTextCentered(true);
            this.resetButton.setEnabled(owner.protectionValue(option));
        }

        @Override
        public boolean onMouseClicked(MouseButtonEvent event, boolean doubled) {
            if (event.input() != 0) return false;
            int mouseX = (int) event.x();
            int mouseY = (int) event.y();
            if (valueButton.isMouseOver(mouseX, mouseY)) {
                owner.toggleProtection(option);
                updateButtons();
                return true;
            }
            if (resetButton.isMouseOver(mouseX, mouseY) && owner.protectionValue(option)) {
                owner.setProtectionValue(option, false);
                updateButtons();
                return true;
            }
            return false;
        }

        private void updateButtons() {
            boolean enabled = owner.protectionValue(option);
            valueButton.setDisplayString(booleanLabel(enabled));
            resetButton.setEnabled(enabled);
        }

        private static String booleanLabel(boolean enabled) {
            return enabled ? "§2是§r" : "§4否§r";
        }

        @Override
        public void render(GuiContext context, int mouseX, int mouseY, boolean selected) {
            drawSubWidgets(context, mouseX, mouseY);
        }
    }

    /** "诊断日志文件": hover shows where the log is, a click shows it in the file manager (0.9.10). */
    private static final class TerraLevelerLogFileOption extends TerraLevelerConfigOption {
        private final ButtonGeneric openButton;

        private TerraLevelerLogFileOption(int x, int y, int width, int height, int maxLabelWidth,
                                          int configWidth, ConfigOptionWrapper wrapper, int entryIndex,
                                          fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui host,
                                          TerraLevelerConfigList parent, Runnable addToolBoxNameAction,
                                          Runnable addPickupItemAction) {
            super(x, y, width, height, maxLabelWidth, configWidth, wrapper, entryIndex, host,
                    parent, addToolBoxNameAction, addPickupItemAction);
            int valueX = x + maxLabelWidth + 10;
            this.openButton = addWidget(new ButtonGeneric(valueX, y + 1, configWidth, 20, "打开日志所在位置"));
            this.openButton.setTextCentered(true);
            this.openButton.setHoverStrings("日志文件：" + TerraLevelerDiagnostics.logPath(),
                    "点击在文件管理器中显示，可以把它发给作者");
        }

        @Override
        public boolean onMouseClicked(MouseButtonEvent event, boolean doubled) {
            if (event.input() != 0) return false;
            if (openButton.isMouseOver((int) event.x(), (int) event.y())) {
                TerraLevelerDiagnostics.revealLog();
                return true;
            }
            return false;
        }

        @Override
        public void render(GuiContext context, int mouseX, int mouseY, boolean selected) {
            drawSubWidgets(context, mouseX, mouseY);
        }
    }

    private static final class TerraLevelerRetainedItemsOption extends TerraLevelerConfigOption {
        private final TerraLevelerMalilibConfigScreen owner;
        private final ButtonGeneric summaryButton;
        private final ButtonGeneric openButton;

        private TerraLevelerRetainedItemsOption(int x, int y, int width, int height, int maxLabelWidth,
                                        int configWidth, ConfigOptionWrapper wrapper, int entryIndex,
                                        fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui host,
                                        TerraLevelerConfigList parent, Runnable addToolBoxNameAction,
                                        Runnable addPickupItemAction, TerraLevelerMalilibConfigScreen owner) {
            super(x, y, width, height, maxLabelWidth, configWidth, wrapper, entryIndex, host,
                    parent, addToolBoxNameAction, addPickupItemAction);
            this.owner = owner;
            int valueX = x + maxLabelWidth + 10;
            this.summaryButton = addWidget(new ButtonGeneric(
                    valueX, y + 1, configWidth, 20, owner.retainedItemsSummary(configWidth - 12)));
            this.summaryButton.setTextCentered(false);
            this.openButton = addWidget(new ButtonGeneric(
                    valueX + configWidth + 2, y + 1, -1, 20, "查看"));
            this.openButton.setTextCentered(true);
        }

        @Override
        public boolean onMouseClicked(MouseButtonEvent event, boolean doubled) {
            if (event.input() != 0) return false;
            int mouseX = (int) event.x();
            int mouseY = (int) event.y();
            if (summaryButton.isMouseOver(mouseX, mouseY) || openButton.isMouseOver(mouseX, mouseY)) {
                owner.openRetainedItemsScreen();
                return true;
            }
            return false;
        }

        @Override
        public void render(GuiContext context, int mouseX, int mouseY, boolean selected) {
            drawSubWidgets(context, mouseX, mouseY);
        }
    }

    private enum ProtectionOption {
        CUSTOM("自定义保留物品"),
        FULL_INVENTORY("保留全背包"),
        DISCARD_GOLD("丢弃金制"),
        DISCARD_IRON("丢弃铁制");

        private final String label;

        ProtectionOption(String label) {
            this.label = label;
        }

        private static ProtectionOption forLabel(String label) {
            for (ProtectionOption option : values()) {
                if (option.label.equals(label)) return option;
            }
            return null;
        }
    }

    private boolean protectionValue(ProtectionOption option) {
        return switch (option) {
            case CUSTOM -> TerraLeveler.CONFIG.customRetainedItemsEnabled;
            case FULL_INVENTORY -> TerraLeveler.CONFIG.retainEntireInventory;
            case DISCARD_GOLD -> TerraLeveler.CONFIG.discardGoldItems;
            case DISCARD_IRON -> TerraLeveler.CONFIG.discardIronItems;
        };
    }

    private void toggleProtection(ProtectionOption option) {
        setProtectionValue(option, !protectionValue(option));
    }

    private void setProtectionValue(ProtectionOption option, boolean enabled) {
        switch (option) {
            case CUSTOM -> {
                TerraLeveler.CONFIG.customRetainedItemsEnabled = enabled;
                if (!enabled) TerraLeveler.CONFIG.retainEntireInventory = false;
                listRefreshAfterMouseRelease = true;
            }
            case FULL_INVENTORY -> TerraLeveler.CONFIG.retainEntireInventory = enabled;
            case DISCARD_GOLD -> TerraLeveler.CONFIG.discardGoldItems = enabled;
            case DISCARD_IRON -> TerraLeveler.CONFIG.discardIronItems = enabled;
        }
        TerraLeveler.CONFIG.save();
        TerraLeveler.CONTROLLER.onRetentionConfigChanged();
        TerraLeveler.LOGGER.info("TerraLeveler protection saved option={} value={}",
                option, protectionValue(option));
    }

    private String retainedItemsSummary(int maxWidth) {
        List<String> ids = TerraLeveler.CONFIG.retainedItemIds;
        if (ids.isEmpty()) return "[]（点击查看）";
        StringBuilder summary = new StringBuilder("[");
        for (int index = 0; index < ids.size(); index++) {
            String separator = index == 0 ? "" : ", ";
            String complete = summary + separator + ids.get(index) + "]";
            if (mc.font.width(complete) > maxWidth) {
                String clipped = summary + (index == 0 ? "…]" : ", …]");
                return mc.font.width(clipped) <= maxWidth ? clipped : "已保留 " + ids.size() + " 种";
            }
            summary.append(separator).append(ids.get(index));
        }
        return summary.append(']').toString();
    }

    private void openRetainedItemsScreen() {
        applyAndSave();
        mc.setScreen(new TerraLevelerRetainedItemsScreen(this));
    }

    @Override
    public void onClose() {
        if (!cancelCloseRequested) applyAndSave();
        super.onClose();
    }

    @Override
    public void removed() {
        // Leaving through malilib's mod switcher or another screen also keeps the edits.
        if (!cancelCloseRequested) applyAndSave();
        super.removed();
    }

    /** Settings that change what a flatten scan finds; changing one drops the cached plan. */
    private static String scanSettings(TerraLevelerConfig config) {
        return config.flattenAutoIslandEnabled + "|" + config.flattenIslandFillInteriorWater
                + "|" + config.flattenIslandCutNarrowConnections + "|" + config.flattenIslandShoreInset
                + "|" + config.flattenIslandMaximumRadius + "|" + config.flattenIslandMaximumColumns
                + "|" + config.flattenIslandMaximumNeckWidth + "|" + config.flattenProtectBlockEntities
                + "|" + config.flattenCleanBoundaryFoliage;
    }

    /**
     * Copies the option widgets into the live config and saves it when anything changed
     * (0.9.05: no save button and no draft copy, so shortcuts and switches bound in the
     * meantime can no longer be overwritten by a stale draft). Returns false when the
     * flatten target Y could not be applied; every other setting is still saved.
     */
    private boolean applyAndSave() {
        TerraLevelerConfig config = TerraLeveler.CONFIG;
        WidgetListConfigOptions list = getListWidget();
        if (list != null) list.applyPendingModifications();
        String before = config.toJson();
        String scanBefore = scanSettings(config);
        boolean toolBoxBefore = config.useToolBoxForTools;
        TerraLevelerMalilibConfigOptions.applyTo(config);
        if (list instanceof TerraLevelerConfigList wetaConfigList) {
            wetaConfigList.applyVisibleDynamicTextValues(config);
        }
        boolean targetApplied = true;
        if (TerraLeveler.ACTIVE_MODE == WorkMode.FLATTEN) {
            try {
                String problem = TerraLeveler.applyFlattenTargetYFromSettings(mc,
                        TerraLevelerMalilibConfigOptions.flattenTargetY());
                if (problem != null) {
                    addMessage(Message.MessageType.ERROR, problem);
                    targetApplied = false;
                }
            } catch (IllegalArgumentException exception) {
                addMessage(Message.MessageType.ERROR, exception.getMessage());
                targetApplied = false;
            }
        }
        if (toolBoxBefore != config.useToolBoxForTools) {
            TerraLeveler.LOGGER.info("TerraLeveler useToolBoxForTools {} -> {}", toolBoxBefore, config.useToolBoxForTools);
        }
        if (before.equals(config.toJson())) return targetApplied;
        if (!scanBefore.equals(scanSettings(config))) TerraLeveler.invalidatePlanCache();
        TerraLevelerDiagnostics.setEnabled(config.diagnosticsEnabled);
        config.save();
        TerraLeveler.CONTROLLER.onRetentionConfigChanged();
        TerraLevelerDiagnostics.log("CONFIG", "saved pickupItems=" + config.pickupItems
                + " pickupItemLimit=" + config.pickupItemLimit);
        return targetApplied;
    }

    void reloadLiveConfig() {
        TerraLevelerMalilibConfigOptions.loadFrom(TerraLeveler.CONFIG);
    }

}

