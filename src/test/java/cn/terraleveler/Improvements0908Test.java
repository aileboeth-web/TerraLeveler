package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static cn.terraleveler.ShulkerBoxMenuTransaction.Kind.EXTRACT;
import static cn.terraleveler.ShulkerBoxMenuTransaction.Kind.STORE;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class Improvements0908Test {
    @Test
    void extractionTakesTheBoxStacksInOrder() {
        int[] boxSlots = {-1, -1, -1};
        assertEquals(1, ShulkerBoxMenuTransaction.nextSourceIndex(EXTRACT, boxSlots, new int[] {0, 64, 12}, -1));
        assertEquals(-1, ShulkerBoxMenuTransaction.nextSourceIndex(EXTRACT, boxSlots, new int[] {0, 0, 0}, -1));
    }

    @Test
    void storingNeverTakesTheSelectedHotbarSlot() {
        int[] inventorySlots = {0, 1};
        assertEquals(1, ShulkerBoxMenuTransaction.nextSourceIndex(STORE, inventorySlots, new int[] {64, 64}, 0));
        assertEquals(-1, ShulkerBoxMenuTransaction.nextSourceIndex(STORE, new int[] {0}, new int[] {64}, 0));
    }

    @Test
    void storingEmptiesTheMainInventoryBeforeTheHotbarSmallestStackFirst() {
        // hotbar 3 (64), main 20 (64), main 12 (7): the small main stack goes first.
        int[] inventorySlots = {3, 20, 12};
        int[] counts = {64, 64, 7};
        assertEquals(2, ShulkerBoxMenuTransaction.nextSourceIndex(STORE, inventorySlots, counts, 0));
        counts[2] = 0;
        assertEquals(1, ShulkerBoxMenuTransaction.nextSourceIndex(STORE, inventorySlots, counts, 0));
        counts[1] = 0;
        assertEquals(0, ShulkerBoxMenuTransaction.nextSourceIndex(STORE, inventorySlots, counts, 0));
    }
}
