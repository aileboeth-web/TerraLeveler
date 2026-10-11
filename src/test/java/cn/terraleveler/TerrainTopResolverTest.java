package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TerrainTopResolverTest {
    @Test
    void heightmapFirstAirIsConvertedToHighestTerrainBlock() {
        assertEquals(142, TerrainTopResolver.surfaceBlockY(143));
        assertEquals(-64, TerrainTopResolver.surfaceBlockY(-63));
    }
}
