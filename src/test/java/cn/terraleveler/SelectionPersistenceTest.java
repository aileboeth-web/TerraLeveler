package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SelectionPersistenceTest {
    @Test
    void roundTripsSignedCoordinates() {
        BlockPos original = new BlockPos(-424, 62, -218);
        assertEquals(original, SelectionPersistence.decode(SelectionPersistence.encode(original)));
    }

    @Test
    void rejectsMalformedCoordinates() {
        assertNull(SelectionPersistence.decode("1,2"));
        assertNull(SelectionPersistence.decode("1,two,3"));
        assertNull(SelectionPersistence.decode("1,2,3,4"));
    }

    @Test
    void restoresOnlyForMatchingScopeAndDimension() {
        String scope = SelectionPersistence.scopeKey("multiplayer", "Example.COM:25565");
        SelectionPersistence.Restored restored = SelectionPersistence.restore(
                scope, "minecraft:overworld", "-368,62,-170", "-424,62,-218",
                SelectionPersistence.scopeKey("MULTIPLAYER", " example.com:25565 "),
                "minecraft:overworld");
        assertNotNull(restored);
        assertEquals(new BlockPos(-368, 62, -170), restored.first());
        assertEquals(new BlockPos(-424, 62, -218), restored.second());
        assertNull(SelectionPersistence.restore(scope, "minecraft:overworld", "1,2,3", "4,5,6",
                SelectionPersistence.scopeKey("multiplayer", "other.example"), "minecraft:overworld"));
        assertNull(SelectionPersistence.restore(scope, "minecraft:overworld", "1,2,3", "4,5,6",
                scope, "minecraft:the_nether"));
    }

    @Test
    void incompleteSelectionRestoresFirstWithoutAStaleSecond() {
        String scope = SelectionPersistence.scopeKey("multiplayer", "server");
        SelectionPersistence.Restored restored = SelectionPersistence.restore(
                scope, "minecraft:overworld", "1,2,3", null, scope, "minecraft:overworld");
        assertNotNull(restored);
        assertEquals(new BlockPos(1, 2, 3), restored.first());
        assertNull(restored.second());
    }

    @Test
    void invalidFirstCornerPreventsRestoration() {
        String scope = SelectionPersistence.scopeKey("singleplayer", "world");
        assertNull(SelectionPersistence.restore(
                scope, "minecraft:overworld", "broken", "4,5,6", scope, "minecraft:overworld"));
    }

    @Test
    void matchesContextWithoutRequiringCorners() {
        String scope = SelectionPersistence.scopeKey("singleplayer", "island world");
        assertTrue(SelectionPersistence.matchesContext(
                scope, "minecraft:overworld", scope, "minecraft:overworld"));
        assertFalse(SelectionPersistence.matchesContext(
                scope, "minecraft:overworld", scope, "minecraft:the_nether"));
    }
}
