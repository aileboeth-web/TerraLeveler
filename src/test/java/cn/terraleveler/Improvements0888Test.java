package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A material prefetch before a pillar no longer uses up the only pillar try (0.8.87 test run). */
class Improvements0888Test {
    @Test
    void aHighTargetGetsSeveralPillarTries() {
        // One try fetches material from the shulker box, the next one starts the pillar.
        assertTrue(ClimbBudgetRules.PILLAR_FIRST_ATTEMPTS >= 2);
        // 0.8.87: a 51-block pillar from Y=63 to an old bridge at Y=114 fits the raised pillar limit.
        assertTrue(ClimbBudgetRules.preferPillarAccess(114 - 65, 23));
    }
}
