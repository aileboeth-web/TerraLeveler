package cn.terraleveler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Up to 0.9.00 the mod was called WETA (mod id "weta") and kept its files under that
 * name. On the first start as TerraLeveler they are copied to the new names, so the
 * settings, scaffold ledgers and island survey carry over. The old files are left in
 * place as a backup and never written again; a new file that already exists is kept.
 */
final class LegacyFileMigration {
    /** Old and new names inside the config directory. */
    static final List<String[]> CONFIG_RENAMES = List.of(
            new String[] {"weta.json", "terraleveler.json"},
            new String[] {"weta-scaffold-ledgers.json", "terraleveler-scaffold-ledgers.json"},
            new String[] {"weta-island-survey", "terraleveler-island-survey"});

    private LegacyFileMigration() { }

    /** Copies every old file or directory whose new name does not exist yet; returns the old names copied. */
    static List<String> migrate(Path configDir) {
        List<String> copied = new ArrayList<>();
        for (String[] rename : CONFIG_RENAMES) {
            try {
                if (copyIfMissing(configDir.resolve(rename[0]), configDir.resolve(rename[1]))) copied.add(rename[0]);
            } catch (IOException exception) {
                TerraLeveler.LOGGER.error("Cannot copy old WETA file {} to {}", rename[0], rename[1], exception);
            }
        }
        return copied;
    }

    /**
     * Copies {@code legacy} (a file or a whole directory) to {@code current} unless
     * {@code current} exists. The copy is made under a temporary name and then moved
     * into place, so an interrupted copy never looks like a finished one.
     */
    static boolean copyIfMissing(Path legacy, Path current) throws IOException {
        if (Files.exists(current) || !Files.exists(legacy)) return false;
        Path temporary = current.resolveSibling(current.getFileName() + ".migrating");
        deleteRecursively(temporary);
        if (Files.isDirectory(legacy)) {
            try (Stream<Path> paths = Files.walk(legacy)) {
                for (Path source : (Iterable<Path>) paths::iterator) {
                    Path target = temporary.resolve(legacy.relativize(source).toString());
                    if (Files.isDirectory(source)) Files.createDirectories(target);
                    else Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        } else {
            Files.copy(legacy, temporary, StandardCopyOption.COPY_ATTRIBUTES);
        }
        Files.move(temporary, current);
        return true;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            List<Path> all = paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path each : all) Files.delete(each);
        }
    }
}
