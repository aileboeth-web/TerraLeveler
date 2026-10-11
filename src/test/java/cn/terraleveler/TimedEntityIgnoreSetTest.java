package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TimedEntityIgnoreSetTest {
    @Test
    void keepsMultipleUnreachableThreatsIgnoredAtTheSameTime() {
        TimedEntityIgnoreSet ignored = new TimedEntityIgnoreSet();
        ignored.ignore(464657, 200);
        ignored.ignore(464658, 201);

        assertTrue(ignored.contains(464657, 150));
        assertTrue(ignored.contains(464658, 150));
        assertEquals(2, ignored.size());
    }

    @Test
    void expirationOfOneThreatDoesNotReleaseTheOther() {
        TimedEntityIgnoreSet ignored = new TimedEntityIgnoreSet();
        ignored.ignore(1, 100);
        ignored.ignore(2, 140);

        assertFalse(ignored.contains(1, 100));
        assertTrue(ignored.contains(2, 100));
        assertEquals(1, ignored.size());
    }
}
