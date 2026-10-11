package cn.terraleveler;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigString;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class TerraLevelerMalilibConfigOptions {
    static final ConfigInteger OPERATION_DELAY = integer("operationDelayTicks", 0, 0, 20,
            "每块完成后的额外等待时间；20 tick 等于 1 秒。", "操作间隔（tick）");
    static final ConfigInteger FAST_BREAK_BLOCKS_PER_TICK = integer("fastBreakBlocksPerTick", 20, 0, 20,
            "操作间隔为 0 时，每 tick 最多批量提交的计划内快速破坏方块数；0 表示关闭批量。",
            "批量快速挖掘（块/tick）");
    static final ConfigInteger SCAFFOLD_BUDGET = integer("scaffoldBudget", 128, 0, 4096,
            "单次施工计划允许放置的自有临时脚手方块上限。", "脚手预算");
    static final ConfigString FLATTEN_TARGET_Y = string("flattenTargetY", "",
            "直接填写平地机最终地面方块的 Y 坐标（-64 到 512）；留空会清除已记录高度。"
                    + "原快捷键仍可读取玩家脚下完整方块的高度。",
            "平地目标地面 Y");
    static final ConfigBoolean FLATTEN_PROTECT_BLOCK_ENTITIES = bool("flattenProtectBlockEntities", true,
            "削高或填低遇到箱子、潜影盒、熔炉等方块实体时暂停并报告坐标。",
            "保护容器和方块实体");
    static final ConfigBoolean FLATTEN_CLEAN_BOUNDARY_FOLIAGE = bool("flattenCleanBoundaryFoliage", false,
            "开启后清理树干不在选区内、但树冠伸入选区的边界树叶；关闭时仅在挡路时必要清障。",
            "清理边界外来树叶");
    static final ConfigBoolean FLATTEN_AUTO_ISLAND = bool("flattenAutoIslandEnabled", false,
            "开启后取消苹果两点框选，以玩家当前所在陆地为种子，按连续水面识别不规则岛屿范围。",
            "自动识别岛屿范围");
    static final ConfigBoolean FLATTEN_ISLAND_FILL_INTERIOR_WATER = bool("flattenIslandFillInteriorWater", false,
            "开启后把未与外部水域连通的内部池塘也纳入泥土地面；关闭时保留内部水塘。",
            "岛内水塘一并铺平");
    static final ConfigBoolean FLATTEN_ISLAND_CUT_NARROW_CONNECTIONS = bool("flattenIslandCutNarrowConnections", true,
            "将两侧临水、宽度不超过阈值的狭窄陆桥视为岛屿边界，避免沿小接触进入大陆。",
            "自动截断窄陆桥");
    static final ConfigInteger FLATTEN_ISLAND_SHORE_INSET = integer("flattenIslandShoreInset", 2, 0, 8,
            "从外部连续水域向岛内保留的自然岸线宽度；0 表示一直铺到临水陆地方块。",
            "岛屿岸线保留（格）");
    static final ConfigInteger FLATTEN_ISLAND_MAX_RADIUS = integer("flattenIslandMaximumRadius",
            TerraLevelerConfig.DEFAULT_FLATTEN_ISLAND_MAXIMUM_RADIUS, 32, TerraLevelerConfig.MAX_FLATTEN_ISLAND_MAXIMUM_RADIUS,
            "从玩家种子位置向外识别岛屿的最大切比雪夫半径；到达上限仍有陆地时拒绝施工。",
            "岛屿最大识别半径");
    static final ConfigInteger FLATTEN_ISLAND_MAX_COLUMNS = integer("flattenIslandMaximumColumns",
            TerraLevelerConfig.DEFAULT_FLATTEN_ISLAND_MAXIMUM_COLUMNS, 1024, TerraLevelerConfig.MAX_FLATTEN_ISLAND_MAXIMUM_COLUMNS,
            "岛屿蒙版允许包含的最大 XZ 地面列数，防止边界失败后误识别大片大陆。",
            "岛屿最大面积（列）");
    static final ConfigInteger FLATTEN_ISLAND_MAX_NECK_WIDTH = integer("flattenIslandMaximumNeckWidth", 4, 1, 12,
            "窄陆桥截断开启时，两侧临水且不超过该宽度的连接会被视为边界。",
            "窄陆桥最大宽度");
    static final ConfigInteger LIGHTING_BLOCK_LIGHT_THRESHOLD = integer("lightingBlockLightThreshold", 0, 0, 15,
            "只处理方块光不高于该值的可放置地面；不读取天空光。", "照明方块光阈值");
    static final ConfigInteger LIGHTING_SCAN_BLOCKS_PER_TICK = integer("lightingScanBlocksPerTick", 2048, 64, 65536,
            "区域照明每 tick 扫描的三维方块数量；较低数值更平滑。", "照明扫描预算（块/tick）");

    static final ConfigInteger DURABILITY_RESERVE = integer("durabilityReserve", 32, 1, 1000,
            "工具剩余耐久达到该值时停止使用并寻找替代工具。", "工具耐久保护值");
    static final ConfigInteger MINIMUM_HEALTH = integer("minimumHealth", 12, 1, 20,
            "生命值低于该值时进入高警戒；实际攻击者与直接挡路威胁仍会被处理。", "低血量警戒值");
    static final ConfigBoolean AUTO_DEFEND = bool("autoDefend", true,
            "仅反击实际攻击者、明确锁定玩家的威胁或直接挡住施工路线的实体。", "受威胁自动反击");
    static final ConfigBoolean CLEAR_BLOCKING_ANIMALS = bool("clearBlockingAnimals", true,
            "走路或放方块时被普通动物挡住（牛、羊、猪、鸡、野马等），就击杀它。村民、傀儡、驯服的宠物和马、改过名字或拴着的动物不会被攻击：走路时绕开，放方块时等它走开或稍后再铺。敌对怪物总是会被清除。需要同时开启“受威胁自动反击”。",
            "击杀挡路的动物");
    static final ConfigBoolean SOUND_ALERTS = bool("soundAlerts", true,
            "施工暂停且需要你处理时响一声铃，施工完成时播放升级音效。只有你自己听得到。", "提示音");
    static final ConfigInteger DEFENSE_RANGE = integer("defenseRange", 12, 4, 24,
            "自动防御评估和接近威胁时使用的最大警戒距离。", "防御警戒范围");
    static final ConfigBoolean AUTO_EAT = bool("autoEat", true,
            "饥饿时自动食用安全食物；普通苹果始终保留给选区工具。", "自动进食");
    static final ConfigBoolean APPLE_SELECTION = bool("appleSelectionEnabled", true,
            "主手拿普通苹果，左键设置角点一，右键设置角点二。", "苹果选点模式");
    static final ConfigBoolean DIAGNOSTICS_ENABLED = bool("diagnosticsEnabled", false,
            "记录 TerraLeveler 的运行过程，遇到问题时可以把日志发给作者。文件在游戏目录的 logs 文件夹：terraleveler-diagnostic.log（当前）和 terraleveler-diagnostic.previous*.log（较早的），每份最多 8 MB、最多 6 份。用启动器的版本隔离时，游戏目录是 .minecraft/versions/<版本名>/。本机位置：" + TerraLevelerDiagnostics.logPath(), "诊断日志");
    static final ConfigBoolean USE_TOOL_BOX_FOR_TOOLS = bool("useToolBoxForTools", false,
            "工具耐久不足时，从背包里按下面名称命名的潜影盒中取出替换工具（通过 QuickShulker 打开盒子；联机时服务器也要装 QuickShulker）。", "从潜影盒取工具");
    static final int MAX_PICKUP_ITEM_FIELDS = 12;
    static final ConfigString[] PICKUP_ITEM_FIELDS = new ConfigString[MAX_PICKUP_ITEM_FIELDS];
    static final ConfigInteger PICKUP_ITEM_LIMIT = integer("pickupItemLimit", 64, 0, 4096,
            "每种施工收集物品在潜影盒补满后允许留在玩家背包中的总数量；超过后按单个堆整组清理。有装着该物品的潜影盒时，多出来的会通过 QuickShulker 存进盒子、缺的时候取出来（联机时服务器也要装 QuickShulker）；没有盒子或服务器不支持时，多出来的直接丢弃，缺土时改为挖选区内高于目标高度的土补充。",
            "收集物品上限");
    static final int MAX_TOOL_BOX_NAME_FIELDS = 12;
    static final ConfigString[] TOOL_BOX_NAME_FIELDS = new ConfigString[MAX_TOOL_BOX_NAME_FIELDS];
    private static int pickupItemFieldCount = 1;
    private static int toolBoxNameFieldCount = 1;

    static {
        for (int index = 0; index < PICKUP_ITEM_FIELDS.length; index++) {
            PICKUP_ITEM_FIELDS[index] = string("pickupItem" + (index + 1), "",
                    "用于施工收集和临时脚手的物品 ID；必须是可站立的非重力方块才能作为脚手。",
                    "施工收集物品 ID " + (index + 1));
        }
        for (int index = 0; index < TOOL_BOX_NAME_FIELDS.length; index++) {
            TOOL_BOX_NAME_FIELDS[index] = string("toolBoxName" + (index + 1), "",
                    "存放替换工具的潜影盒名称（物品自定义名称）；有多个时按顺序优先使用。不用就留空。",
                    "工具盒名称 " + (index + 1));
        }
    }

    static final List<IConfigBase> FLATTEN_CONSTRUCTION = List.of(
            FLATTEN_TARGET_Y, OPERATION_DELAY, FAST_BREAK_BLOCKS_PER_TICK, SCAFFOLD_BUDGET,
            PICKUP_ITEM_FIELDS[0], PICKUP_ITEM_LIMIT, DIAGNOSTICS_ENABLED,
            FLATTEN_AUTO_ISLAND, FLATTEN_ISLAND_FILL_INTERIOR_WATER,
            FLATTEN_ISLAND_CUT_NARROW_CONNECTIONS, FLATTEN_ISLAND_SHORE_INSET,
            FLATTEN_ISLAND_MAX_RADIUS, FLATTEN_ISLAND_MAX_COLUMNS, FLATTEN_ISLAND_MAX_NECK_WIDTH);
    static final List<IConfigBase> FLATTEN_SAFETY = List.of(
            DIAGNOSTICS_ENABLED, SOUND_ALERTS, USE_TOOL_BOX_FOR_TOOLS, DURABILITY_RESERVE, MINIMUM_HEALTH, AUTO_DEFEND, CLEAR_BLOCKING_ANIMALS, DEFENSE_RANGE, AUTO_EAT,
            APPLE_SELECTION, FLATTEN_PROTECT_BLOCK_ENTITIES, FLATTEN_CLEAN_BOUNDARY_FOLIAGE);
    static final List<IConfigBase> LIGHTING_CONSTRUCTION = List.of(
            OPERATION_DELAY, LIGHTING_BLOCK_LIGHT_THRESHOLD, LIGHTING_SCAN_BLOCKS_PER_TICK, DIAGNOSTICS_ENABLED);
    static final List<IConfigBase> LIGHTING_SAFETY = List.of(
            DIAGNOSTICS_ENABLED, SOUND_ALERTS, USE_TOOL_BOX_FOR_TOOLS, MINIMUM_HEALTH, APPLE_SELECTION);

    private TerraLevelerMalilibConfigOptions() { }

    static void loadFrom(TerraLevelerConfig config) {
        DIAGNOSTICS_ENABLED.setBooleanValue(config.diagnosticsEnabled);
        OPERATION_DELAY.setIntegerValue(config.operationDelayTicks);
        FAST_BREAK_BLOCKS_PER_TICK.setIntegerValue(config.fastBreakBlocksPerTick);
        USE_TOOL_BOX_FOR_TOOLS.setBooleanValue(config.useToolBoxForTools);
        List<String> pickupItems = TerraLevelerConfig.sanitizeItemIds(config.pickupItems);
        pickupItemFieldCount = Math.max(1, Math.min(MAX_PICKUP_ITEM_FIELDS, pickupItems.size()));
        for (int index = 0; index < PICKUP_ITEM_FIELDS.length; index++) {
            PICKUP_ITEM_FIELDS[index].setStringValue(index < pickupItems.size() ? pickupItems.get(index) : "");
        }
        PICKUP_ITEM_LIMIT.setIntegerValue(config.pickupItemLimit);
        SCAFFOLD_BUDGET.setIntegerValue(config.scaffoldBudget);
        Integer flattenTargetY = config == TerraLeveler.CONFIG
                ? TerraLeveler.flattenTargetYForSettings(
                        net.minecraft.client.Minecraft.getInstance())
                : config.lastFlattenTargetY;
        FLATTEN_TARGET_Y.setStringValue(flattenTargetY == null ? "" : Integer.toString(flattenTargetY));
        FLATTEN_PROTECT_BLOCK_ENTITIES.setBooleanValue(config.flattenProtectBlockEntities);
        FLATTEN_CLEAN_BOUNDARY_FOLIAGE.setBooleanValue(config.flattenCleanBoundaryFoliage);
        FLATTEN_AUTO_ISLAND.setBooleanValue(config.flattenAutoIslandEnabled);
        FLATTEN_ISLAND_FILL_INTERIOR_WATER.setBooleanValue(config.flattenIslandFillInteriorWater);
        FLATTEN_ISLAND_CUT_NARROW_CONNECTIONS.setBooleanValue(config.flattenIslandCutNarrowConnections);
        FLATTEN_ISLAND_SHORE_INSET.setIntegerValue(config.flattenIslandShoreInset);
        FLATTEN_ISLAND_MAX_RADIUS.setIntegerValue(config.flattenIslandMaximumRadius);
        FLATTEN_ISLAND_MAX_COLUMNS.setIntegerValue(config.flattenIslandMaximumColumns);
        FLATTEN_ISLAND_MAX_NECK_WIDTH.setIntegerValue(config.flattenIslandMaximumNeckWidth);
        LIGHTING_BLOCK_LIGHT_THRESHOLD.setIntegerValue(config.lightingBlockLightThreshold);
        LIGHTING_SCAN_BLOCKS_PER_TICK.setIntegerValue(config.lightingScanBlocksPerTick);
        DURABILITY_RESERVE.setIntegerValue(config.durabilityReserve);
        MINIMUM_HEALTH.setIntegerValue(config.minimumHealth);
        AUTO_DEFEND.setBooleanValue(config.autoDefend);
        SOUND_ALERTS.setBooleanValue(config.soundAlerts);
        CLEAR_BLOCKING_ANIMALS.setBooleanValue(config.clearBlockingAnimals);
        DEFENSE_RANGE.setIntegerValue(config.defenseRange);
        AUTO_EAT.setBooleanValue(config.autoEat);
        APPLE_SELECTION.setBooleanValue(config.appleSelectionEnabled);
        List<String> names = TerraLevelerConfig.sanitizeToolBoxNames(config.toolBoxNames);
        toolBoxNameFieldCount = Math.max(1, Math.min(MAX_TOOL_BOX_NAME_FIELDS, names.size()));
        for (int index = 0; index < TOOL_BOX_NAME_FIELDS.length; index++) {
            TOOL_BOX_NAME_FIELDS[index].setStringValue(index < names.size() ? names.get(index) : "");
        }
        allOptions().forEach(IConfigBase::markClean);
    }

    static void applyTo(TerraLevelerConfig config) {
        config.diagnosticsEnabled = DIAGNOSTICS_ENABLED.getBooleanValue();
        config.operationDelayTicks = OPERATION_DELAY.getIntegerValue();
        config.fastBreakBlocksPerTick = FAST_BREAK_BLOCKS_PER_TICK.getIntegerValue();
        config.useToolBoxForTools = USE_TOOL_BOX_FOR_TOOLS.getBooleanValue();
        List<String> pickupItems = new ArrayList<>();
        for (int index = 0; index < pickupItemFieldCount; index++) {
            pickupItems.add(PICKUP_ITEM_FIELDS[index].getStringValue());
        }
        config.pickupItems = TerraLevelerConfig.sanitizeItemIds(pickupItems);
        config.pickupItemLimit = PICKUP_ITEM_LIMIT.getIntegerValue();
        config.scaffoldBudget = SCAFFOLD_BUDGET.getIntegerValue();
        config.flattenProtectBlockEntities = FLATTEN_PROTECT_BLOCK_ENTITIES.getBooleanValue();
        config.flattenCleanBoundaryFoliage = FLATTEN_CLEAN_BOUNDARY_FOLIAGE.getBooleanValue();
        config.flattenAutoIslandEnabled = FLATTEN_AUTO_ISLAND.getBooleanValue();
        config.flattenIslandFillInteriorWater = FLATTEN_ISLAND_FILL_INTERIOR_WATER.getBooleanValue();
        config.flattenIslandCutNarrowConnections = FLATTEN_ISLAND_CUT_NARROW_CONNECTIONS.getBooleanValue();
        config.flattenIslandShoreInset = FLATTEN_ISLAND_SHORE_INSET.getIntegerValue();
        config.flattenIslandMaximumRadius = FLATTEN_ISLAND_MAX_RADIUS.getIntegerValue();
        config.flattenIslandMaximumColumns = FLATTEN_ISLAND_MAX_COLUMNS.getIntegerValue();
        config.flattenIslandMaximumNeckWidth = FLATTEN_ISLAND_MAX_NECK_WIDTH.getIntegerValue();
        config.lightingBlockLightThreshold = LIGHTING_BLOCK_LIGHT_THRESHOLD.getIntegerValue();
        config.lightingScanBlocksPerTick = LIGHTING_SCAN_BLOCKS_PER_TICK.getIntegerValue();
        config.durabilityReserve = DURABILITY_RESERVE.getIntegerValue();
        config.minimumHealth = MINIMUM_HEALTH.getIntegerValue();
        config.autoDefend = AUTO_DEFEND.getBooleanValue();
        config.soundAlerts = SOUND_ALERTS.getBooleanValue();
        config.clearBlockingAnimals = CLEAR_BLOCKING_ANIMALS.getBooleanValue();
        config.defenseRange = DEFENSE_RANGE.getIntegerValue();
        config.autoEat = AUTO_EAT.getBooleanValue();
        config.appleSelectionEnabled = APPLE_SELECTION.getBooleanValue();
        List<String> names = new ArrayList<>();
        for (int index = 0; index < toolBoxNameFieldCount; index++) {
            names.add(TOOL_BOX_NAME_FIELDS[index].getStringValue());
        }
        config.toolBoxNames = TerraLevelerConfig.sanitizeToolBoxNames(names);
        config.sanitized();
        allOptions().forEach(IConfigBase::markClean);
    }

    static Integer flattenTargetY() {
        return TerraLevelerConfig.parseFlattenTargetY(FLATTEN_TARGET_Y.getStringValue());
    }

    static List<IConfigBase> allOptions() {
        List<IConfigBase> options = new ArrayList<>();
        options.addAll(FLATTEN_CONSTRUCTION);
        options.addAll(FLATTEN_SAFETY);
        options.addAll(LIGHTING_CONSTRUCTION);
        options.addAll(LIGHTING_SAFETY);
        options.addAll(List.of(TOOL_BOX_NAME_FIELDS));
        options.addAll(List.of(PICKUP_ITEM_FIELDS));
        return options.stream().distinct().toList();
    }

    static List<IConfigBase> toolBoxNameOptions() {
        List<IConfigBase> options = new ArrayList<>();
        for (int index = 0; index < toolBoxNameFieldCount; index++) {
            options.add(TOOL_BOX_NAME_FIELDS[index]);
        }
        return options;
    }

    static List<IConfigBase> pickupItemOptions() {
        List<IConfigBase> options = new ArrayList<>();
        for (int index = 0; index < pickupItemFieldCount; index++) {
            options.add(PICKUP_ITEM_FIELDS[index]);
        }
        return options;
    }

    static boolean isDynamicTextField(String name) {
        return pickupItemIndex(name) >= 0 || toolBoxNameIndex(name) >= 0;
    }

    static void applyVisibleDynamicTextValues(TerraLevelerConfig config, Map<String, String> visibleValues) {
        if (visibleValues == null || visibleValues.isEmpty()) return;

        List<String> pickupItems = new ArrayList<>();
        for (IConfigBase option : pickupItemOptions()) {
            pickupItems.add(option instanceof ConfigString string ? string.getStringValue() : "");
        }
        List<String> toolBoxNames = new ArrayList<>();
        for (IConfigBase option : toolBoxNameOptions()) {
            toolBoxNames.add(option instanceof ConfigString string ? string.getStringValue() : "");
        }
        for (Map.Entry<String, String> entry : visibleValues.entrySet()) {
            int pickupIndex = pickupItemIndex(entry.getKey());
            if (pickupIndex >= 0) {
                while (pickupItems.size() <= pickupIndex) pickupItems.add("");
                pickupItems.set(pickupIndex, entry.getValue());
                continue;
            }
            int toolBoxIndex = toolBoxNameIndex(entry.getKey());
            if (toolBoxIndex >= 0) {
                while (toolBoxNames.size() <= toolBoxIndex) toolBoxNames.add("");
                toolBoxNames.set(toolBoxIndex, entry.getValue());
            }
        }
        config.pickupItems = TerraLevelerConfig.sanitizeItemIds(pickupItems);
        config.toolBoxNames = TerraLevelerConfig.sanitizeToolBoxNames(toolBoxNames);
        config.sanitized();
    }

    private static int pickupItemIndex(String name) {
        return numberedFieldIndex(name, "pickupItem");
    }

    private static int toolBoxNameIndex(String name) {
        return numberedFieldIndex(name, "toolBoxName");
    }

    private static int numberedFieldIndex(String name, String prefix) {
        if (name == null || !name.startsWith(prefix)) return -1;
        String suffix = name.substring(prefix.length());
        if (suffix.isEmpty()) return -1;
        try {
            int number = Integer.parseInt(suffix);
            return number >= 1 && number <= 12 ? number - 1 : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static void addToolBoxNameField() {
        int next = Math.min(MAX_TOOL_BOX_NAME_FIELDS, toolBoxNameFieldCount + 1);
        if (next > toolBoxNameFieldCount) {
            TOOL_BOX_NAME_FIELDS[next - 1].setStringValue("");
        }
        toolBoxNameFieldCount = next;
    }

    static void addPickupItemField() {
        int next = Math.min(MAX_PICKUP_ITEM_FIELDS, pickupItemFieldCount + 1);
        if (next > pickupItemFieldCount) {
            PICKUP_ITEM_FIELDS[next - 1].setStringValue("");
        }
        pickupItemFieldCount = next;
    }

    private static ConfigInteger integer(String name, int defaultValue, int min, int max,
                                         String comment, String prettyName) {
        ConfigInteger option = new ConfigInteger(name, defaultValue, min, max, comment, prettyName);
        option.setTranslatedName(prettyName);
        return option;
    }

    private static ConfigBoolean bool(String name, boolean defaultValue, String comment, String prettyName) {
        ConfigBoolean option = new ConfigBoolean(name, defaultValue, comment, prettyName);
        option.setTranslatedName(prettyName);
        return option;
    }

    private static ConfigString string(String name, String defaultValue, String comment, String prettyName) {
        ConfigString option = new ConfigString(name, defaultValue, comment, prettyName);
        option.setTranslatedName(prettyName);
        return option;
    }

}
