package cn.terraleveler;

import java.util.List;

final class ToolBoxSelectionRules {
    private ToolBoxSelectionRules() { }

    static BoxSelection uniqueToolBox(List<BoxCandidate> candidates, List<String> allowedNames) {
        int selectedSlot = -1;
        String selectedName = null;
        boolean namedBoxFound = false;
        for (String allowedName : TerraLevelerConfig.sanitizeToolBoxNames(allowedNames)) {
            int sameNameCount = 0;
            for (BoxCandidate candidate : candidates) {
                if (!candidate.shulker() || !allowedName.equals(candidate.customName())) continue;
                namedBoxFound = true;
                sameNameCount++;
                // Differently named boxes holding the same tool kind: the first configured name wins.
                // Same-named duplicates stay ambiguous because the open box is identified by name.
                if (candidate.hasRequestedTool() && selectedSlot < 0) {
                    selectedSlot = candidate.inventorySlot();
                    selectedName = allowedName;
                }
            }
            if (sameNameCount > 1) return new BoxSelection(BoxStatus.AMBIGUOUS, -1, null);
        }
        return selectedSlot >= 0
                ? new BoxSelection(BoxStatus.FOUND, selectedSlot, selectedName)
                : new BoxSelection(namedBoxFound ? BoxStatus.NO_USABLE_TOOL : BoxStatus.MISSING, -1, null);
    }

    static int bestToolSlot(List<ToolCandidate> candidates, ToolKind requiredKind, int durabilityReserve) {
        int bestSlot = -1;
        float bestScore = -1.0F;
        int bestRemaining = -1;
        for (ToolCandidate candidate : candidates) {
            if (candidate.kind() != requiredKind || candidate.remainingDurability() <= durabilityReserve
                    || !ServerActionExecutor.isEffectiveMiningTool(candidate.score())) continue;
            if (ServerActionExecutor.betterToolCandidate(candidate.score(), candidate.remainingDurability(),
                    bestScore, bestRemaining)) {
                bestSlot = candidate.menuSlot();
                bestScore = candidate.score();
                bestRemaining = candidate.remainingDurability();
            }
        }
        return bestSlot;
    }

    static int hotbarDestination(int selectedSlot, List<HotbarCandidate> candidates, int durabilityReserve) {
        for (HotbarCandidate candidate : candidates) {
            if (candidate.slot() == selectedSlot && candidate.acceptsToolBoxSwap(durabilityReserve)) return selectedSlot;
        }

        int retiringTool = -1;
        int emptySlot = -1;
        for (HotbarCandidate candidate : candidates) {
            if (candidate.empty()) {
                if (emptySlot < 0) emptySlot = candidate.slot();
            } else if (candidate.kind().managed()) {
                if (retiringTool < 0 && candidate.remainingDurability() <= durabilityReserve) {
                    retiringTool = candidate.slot();
                }
            }
        }
        if (retiringTool >= 0) return retiringTool;
        return emptySlot;
    }

    static int hotbarEvacuationSlot(int selectedSlot, int excludedSlot, List<HotbarCandidate> candidates) {
        for (int index = candidates.size() - 1; index >= 0; index--) {
            HotbarCandidate candidate = candidates.get(index);
            if (candidate.empty() || candidate.slot() == excludedSlot
                    || candidate.kind().managed() || candidate.slot() == selectedSlot) continue;
            return candidate.slot();
        }
        return -1;
    }

    enum BoxStatus { FOUND, MISSING, AMBIGUOUS, NO_USABLE_TOOL }

    enum ToolKind {
        PICKAXE,
        SHOVEL,
        OTHER;

        boolean managed() {
            return this == PICKAXE || this == SHOVEL;
        }
    }

    record BoxCandidate(int inventorySlot, boolean shulker, String customName, boolean hasRequestedTool) { }

    record BoxSelection(BoxStatus status, int inventorySlot, String customName) { }

    record ToolCandidate(int menuSlot, ToolKind kind, float score, int remainingDurability) { }

    record HotbarCandidate(int slot, ToolKind kind, boolean empty, int remainingDurability) {
        boolean acceptsToolBoxSwap(int durabilityReserve) {
            return empty || kind.managed() && remainingDurability <= durabilityReserve;
        }
    }
}
