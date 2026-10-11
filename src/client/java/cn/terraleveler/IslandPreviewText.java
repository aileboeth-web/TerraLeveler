package cn.terraleveler;

/**
 * The island preview used to be one long chat message with every detection figure (0.9.14):
 * the chat line keeps what the player decides on, the figures go into a hover tooltip.
 */
final class IslandPreviewText {
    private IslandPreviewText() { }

    /** regions is 0 when the island is levelled in one go. */
    static String summary(long columns, int minX, int maxX, int minZ, int maxZ, int regions, String startKey) {
        return "TerraLeveler 岛屿预览：平整 " + columns + " 列，范围 X " + minX + ".." + maxX + "、Z " + minZ + ".." + maxZ
                + (regions > 0 ? "，分 " + regions + " 个区域逐块施工" : "")
                + "。确认无误后再按一次" + startKey + "开始。";
    }

    static String details(long connectedLand, long shoreExcluded, long interiorWater, long narrowCuts, int regionBlocks) {
        StringBuilder text = new StringBuilder()
                .append("连通的陆地：").append(connectedLand).append(" 列")
                .append("\n岸边保留不平整：").append(shoreExcluded).append(" 列")
                .append("\n一起填平的内部水塘：").append(interiorWater).append(" 列")
                .append("\n截断的窄陆桥：").append(narrowCuts).append(" 处");
        if (regionBlocks > 0) {
            text.append("\n岛屿超出当前已加载范围，每个区域最大 ").append(regionBlocks).append("×").append(regionBlocks)
                    .append("，每块都按实时地形重新规划");
        }
        return text.toString();
    }
}
