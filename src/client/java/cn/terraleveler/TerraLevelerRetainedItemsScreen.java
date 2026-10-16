package cn.terraleveler;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class TerraLevelerRetainedItemsScreen extends Screen {
    private static final int HEADER_HEIGHT = 54;
    private static final int ROW_HEIGHT = 24;
    private static final int FOOTER_HEIGHT = 38;
    private final Screen parent;
    private final List<String> draft = new ArrayList<>();
    private int scrollOffset;

    public TerraLevelerRetainedItemsScreen(Screen parent) {
        super(Component.literal("TerraLeveler 自定义保留清单"));
        this.parent = parent;
        this.draft.addAll(TerraLeveler.CONFIG.retainedItemIds);
    }

    @Override
    protected void init() {
        int visibleRows = visibleRowCount();
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScrollOffset()));
        int left = contentLeft();
        int right = contentRight();
        int numberWidth = 34;
        int removeWidth = 62;
        int fieldX = left + numberWidth;
        int fieldWidth = right - fieldX - removeWidth - 8;
        int end = Math.min(draft.size(), scrollOffset + visibleRows);
        for (int index = scrollOffset; index < end; index++) {
            int row = index - scrollOffset;
            String id = draft.get(index);
            EditBox idField = new EditBox(font, fieldX, HEADER_HEIGHT + row * ROW_HEIGHT,
                    fieldWidth, 20, Component.literal("保留物品 " + (index + 1)));
            idField.setValue(id);
            idField.setEditable(false);
            addRenderableWidget(idField);
            addRenderableWidget(Button.builder(Component.literal("移除"), button -> remove(id))
                    .bounds(right - removeWidth, HEADER_HEIGHT + row * ROW_HEIGHT, removeWidth, 20).build());
        }
        int footerY = height - 28;
        addRenderableWidget(Button.builder(Component.literal("清空清单"), button -> clearList())
                .bounds(width / 2 - 155, footerY, 150, 20).build()).active = !draft.isEmpty();
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
                .bounds(width / 2 + 5, footerY, 150, 20).build());
    }

    private void remove(String id) {
        draft.remove(id);
        persist();
        clearWidgets();
        init();
    }

    private void clearList() {
        draft.clear();
        persist();
        clearWidgets();
        init();
    }

    /** Every removal is saved at once, like the settings screen (0.9.07). */
    private void persist() {
        TerraLeveler.CONFIG.retainedItemIds = TerraLevelerConfig.sanitizeItemIds(draft);
        TerraLeveler.CONFIG.save();
        TerraLeveler.CONTROLLER.onRetentionConfigChanged();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 18, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal("共 " + draft.size() + " 种 · 鼠标滚轮上下浏览 · 只记录物品类型，不记录数量"),
                width / 2, 34, 0xFFB0E0E6);
        int end = Math.min(draft.size(), scrollOffset + visibleRowCount());
        for (int index = scrollOffset; index < end; index++) {
            int rowY = HEADER_HEIGHT + (index - scrollOffset) * ROW_HEIGHT + 6;
            graphics.text(font, Component.literal((index + 1) + "."), contentLeft(), rowY, 0xFFD0D0D0);
        }
        if (draft.isEmpty()) {
            graphics.centeredText(font, Component.literal("清单为空：主手拿住物品后按" + KeyHintText.hint(KeyHintText.RETAIN_ITEM) + "添加"),
                    width / 2, HEADER_HEIGHT + 24, 0xFFB0B0B0);
        }
        renderScrollbar(graphics);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= contentLeft() && mouseX <= contentRight()
                && mouseY >= HEADER_HEIGHT && mouseY < height - FOOTER_HEIGHT
                && verticalAmount != 0.0) {
            int direction = verticalAmount > 0.0 ? -1 : 1;
            int nextOffset = Math.max(0, Math.min(maxScrollOffset(), scrollOffset + direction));
            if (nextOffset != scrollOffset) {
                scrollOffset = nextOffset;
                clearWidgets();
                init();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private int contentLeft() {
        // At least 20 px from the edge: the old minimum half-width of 180 put the list
        // off-screen on a 320 px wide GUI (large GUI scale).
        return width / 2 - Math.max(0, Math.min(310, (width - 40) / 2));
    }

    private int contentRight() {
        return width - contentLeft() - 10;
    }

    private int visibleRowCount() {
        return Math.max(1, (height - HEADER_HEIGHT - FOOTER_HEIGHT) / ROW_HEIGHT);
    }

    private int maxScrollOffset() {
        return Math.max(0, draft.size() - visibleRowCount());
    }

    private void renderScrollbar(GuiGraphicsExtractor graphics) {
        int maxOffset = maxScrollOffset();
        if (maxOffset <= 0) return;
        int trackTop = HEADER_HEIGHT;
        int trackBottom = height - FOOTER_HEIGHT;
        int trackHeight = trackBottom - trackTop;
        int thumbHeight = Math.max(20, trackHeight * visibleRowCount() / draft.size());
        int thumbTravel = trackHeight - thumbHeight;
        int thumbTop = trackTop + thumbTravel * scrollOffset / maxOffset;
        int scrollbarX = contentRight() + 4;
        graphics.fill(scrollbarX, trackTop, scrollbarX + 4, trackBottom, 0x80404040);
        graphics.fill(scrollbarX, thumbTop, scrollbarX + 4, thumbTop + thumbHeight, 0xFFE0E0E0);
    }

    @Override
    public void onClose() {
        TerraLeveler.refreshMalilibConfigOptions();
        if (parent instanceof TerraLevelerMalilibConfigScreen screen) screen.reloadLiveConfig();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
