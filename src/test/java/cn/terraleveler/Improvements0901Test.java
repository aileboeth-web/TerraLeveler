package cn.terraleveler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Improvements0901Test {
    @Test
    void oldWetaFilesAreCopiedToTheNewNamesAndKept(@TempDir Path config) throws IOException {
        Files.writeString(config.resolve("weta.json"), "{\"scaffoldBudget\": 200}");
        Files.writeString(config.resolve("weta-scaffold-ledgers.json"), "{\"job\": {}}");
        Path survey = config.resolve("weta-island-survey");
        Files.createDirectories(survey.resolve("nested"));
        Files.writeString(survey.resolve("a.bin.gz"), "survey");
        Files.writeString(survey.resolve("nested").resolve("b.bin.gz"), "nested");

        List<String> copied = LegacyFileMigration.migrate(config);

        assertEquals(List.of("weta.json", "weta-scaffold-ledgers.json", "weta-island-survey"), copied);
        assertEquals("{\"scaffoldBudget\": 200}", Files.readString(config.resolve("terraleveler.json")));
        assertEquals("{\"job\": {}}", Files.readString(config.resolve("terraleveler-scaffold-ledgers.json")));
        assertEquals("survey", Files.readString(config.resolve("terraleveler-island-survey").resolve("a.bin.gz")));
        assertEquals("nested", Files.readString(
                config.resolve("terraleveler-island-survey").resolve("nested").resolve("b.bin.gz")));
        assertTrue(Files.exists(config.resolve("weta.json")), "the old file stays as a backup");
        assertFalse(Files.exists(config.resolve("terraleveler.json.migrating")));
    }

    @Test
    void anExistingTerraLevelerFileIsNeverOverwritten(@TempDir Path config) throws IOException {
        Files.writeString(config.resolve("weta.json"), "old");
        Files.writeString(config.resolve("terraleveler.json"), "new");

        assertEquals(List.of(), LegacyFileMigration.migrate(config));
        assertEquals("new", Files.readString(config.resolve("terraleveler.json")));
    }

    @Test
    void nothingHappensWithoutOldFiles(@TempDir Path config) throws IOException {
        assertEquals(List.of(), LegacyFileMigration.migrate(config));
        try (var files = Files.list(config)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void anInterruptedEarlierCopyIsRedone(@TempDir Path config) throws IOException {
        Files.writeString(config.resolve("weta.json"), "complete");
        Files.writeString(config.resolve("terraleveler.json.migrating"), "half");

        assertEquals(List.of("weta.json"), LegacyFileMigration.migrate(config));
        assertEquals("complete", Files.readString(config.resolve("terraleveler.json")));
    }
}
