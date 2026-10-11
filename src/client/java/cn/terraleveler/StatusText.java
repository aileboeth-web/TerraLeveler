package cn.terraleveler;

/** Words for the status bar (0.9.10): internal state names were shown before (EXCAVATING ...). */
final class StatusText {
    private StatusText() { }

    static String stateLabel(String stateName) {
        if (stateName == null) return "";
        return switch (stateName) {
            case "IDLE" -> "空闲";
            case "EXCAVATING" -> "削平";
            case "POSITIONING" -> "移动";
            case "LOADING" -> "等待区块加载";
            case "CONTAINING_FLUID" -> "排水";
            case "RECOVERING_WATER" -> "离开水中";
            case "RECOVERING_FALL" -> "脱困";
            case "DEFENDING" -> "战斗";
            case "EATING" -> "进食";
            case "UNPACKING_MATERIAL" -> "存取潜影盒";
            case "SWAPPING_TOOL" -> "换工具";
            case "CLEANING_SCAFFOLDS" -> "拆临时脚手";
            case "BUILDING_WALL" -> "铺地表";
            case "FLATTEN_PILLARING" -> "垫柱";
            case "PAUSED" -> "已暂停";
            case "COMPLETE" -> "已完成";
            case "SCANNING" -> "扫描暗处";
            case "PLACING" -> "放火把";
            case "UNPACKING_TORCH" -> "取火把";
            default -> stateName;
        };
    }

    /** "45%" of the planned work; never 100% before the job is really complete. */
    static String percent(long done, long total, boolean complete) {
        if (complete) return "100%";
        if (total <= 0) return "";
        long value = Math.max(0L, Math.min(99L, done * 100L / total));
        return value + "%";
    }
}
