package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FlattenExcavationPolicyTest {
    @Test
    void leavesAreDeferredAndLogsReceiveTheirOwnTreeCategory() {
        assertEquals(FlattenExcavationPolicy.Category.IGNORE,
                FlattenExcavationPolicy.classify(true, false, false, false));
        assertEquals(FlattenExcavationPolicy.Category.IGNORE,
                FlattenExcavationPolicy.classify(false, true, false, false));
        assertEquals(FlattenExcavationPolicy.Category.DEFERRED_FOLIAGE,
                FlattenExcavationPolicy.classify(false, false, true, false));
        assertEquals(FlattenExcavationPolicy.Category.TREE_LOG,
                FlattenExcavationPolicy.classify(false, false, false, true));
        assertEquals(FlattenExcavationPolicy.Category.PRIMARY,
                FlattenExcavationPolicy.classify(false, false, false, false));
    }
}
