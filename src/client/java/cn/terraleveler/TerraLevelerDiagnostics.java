package cn.terraleveler;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class TerraLevelerDiagnostics {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Path LOG = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("terraleveler-diagnostic.log");
    private static final Path PREVIOUS = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("terraleveler-diagnostic.previous.log");
    /** Older files: terraleveler-diagnostic.previous.log (newest), then .previous.2.log ... .previous.N.log. */
    private static final int ARCHIVES = 5;
    private static final long ROTATE_BYTES = 8L * 1024 * 1024;
    /** Lines are buffered and flushed at most this often instead of once per line on the client thread. */
    private static final long FLUSH_INTERVAL_NANOS = 1_000_000_000L;
    private static BufferedWriter writer;
    private static boolean enabled;
    private static String version = "unknown";
    private static long writtenBytes;
    private static long lastFlushNanos;

    private TerraLevelerDiagnostics() { }

    /** Where the current log file is (0.9.10: shown and opened from the settings screen). */
    static Path logPath() {
        return LOG.toAbsolutePath().normalize();
    }

    /** Shows the log to the player: Explorer with the file selected on Windows, otherwise the logs folder. */
    static void revealLog() {
        Path log = logPath();
        try {
            if (net.minecraft.util.Util.getPlatform() == net.minecraft.util.Util.OS.WINDOWS && java.nio.file.Files.exists(log)) {
                new ProcessBuilder("explorer.exe", "/select," + log).start();
                return;
            }
        } catch (java.io.IOException ignored) {
            // fall back to opening the folder
        }
        com.mojang.blaze3d.Blaze3D.openPath(log.getParent());
    }

    public static synchronized void initialize(String version, boolean enabled) {
        TerraLevelerDiagnostics.version = version == null ? "unknown" : version;
        closeWriter();
        TerraLevelerDiagnostics.enabled = enabled;
        if (!enabled) return;
        openWriter();
        log("SESSION", "start version=" + TerraLevelerDiagnostics.version + " minecraft=26.1.2; privacy=world-state-only");
    }

    public static synchronized void initialize(String version) {
        initialize(version, true);
    }

    public static synchronized void setEnabled(boolean enabled) {
        if (TerraLevelerDiagnostics.enabled == enabled && (enabled ? writer != null : writer == null)) return;
        TerraLevelerDiagnostics.enabled = enabled;
        if (!enabled) {
            closeWriter();
            return;
        }
        openWriter();
        log("SESSION", "diagnostics-enabled version=" + version + " minecraft=26.1.2; privacy=world-state-only");
    }

    public static synchronized boolean isEnabled() {
        return enabled;
    }

    /** Flushes and closes the log; called when the client stops. */
    public static synchronized void close() {
        closeWriter();
    }

    private static void openWriter() {
        try {
            Files.createDirectories(LOG.getParent());
            if (Files.isRegularFile(LOG) && Files.size(LOG) > ROTATE_BYTES) {
                shiftArchives();
            }
            writer = Files.newBufferedWriter(LOG, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            writtenBytes = Files.size(LOG);
            lastFlushNanos = System.nanoTime();
        } catch (IOException exception) {
            writer = null;
            TerraLeveler.LOGGER.error("Cannot initialize TerraLeveler diagnostic log", exception);
        }
    }

    private static void closeWriter() {
        if (writer == null) return;
        try {
            writer.flush();
            writer.close();
        } catch (IOException exception) {
            TerraLeveler.LOGGER.error("Cannot close TerraLeveler diagnostic log", exception);
        } finally {
            writer = null;
        }
    }

    public static synchronized void log(String category, String message) {
        if (!enabled || writer == null) return;
        try {
            String line = TIME.format(LocalDateTime.now()) + " [" + category + "] " + message.replace('\n', ' ');
            writer.write(line);
            writer.newLine();
            writtenBytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
            long now = System.nanoTime();
            if (now - lastFlushNanos >= FLUSH_INTERVAL_NANOS) {
                writer.flush();
                lastFlushNanos = now;
            }
            if (writtenBytes > ROTATE_BYTES) {
                // Rotate during long sessions too, not only at start-up.
                closeWriter();
                openWriter();
                if (writer != null) {
                    writer.write(TIME.format(LocalDateTime.now()) + " [SESSION] continued version=" + version
                            + " after-rotation");
                    writer.newLine();
                }
            }
        } catch (IOException exception) {
            TerraLeveler.LOGGER.error("Cannot write TerraLeveler diagnostic log", exception);
        }
    }

    public static Path path() { return LOG; }

    static Path archive(int index) {
        return index <= 1 ? PREVIOUS : LOG.resolveSibling("terraleveler-diagnostic.previous." + index + ".log");
    }

    private static void shiftArchives() throws IOException {
        Files.deleteIfExists(archive(ARCHIVES));
        for (int index = ARCHIVES - 1; index >= 1; index--) {
            if (Files.isRegularFile(archive(index))) {
                Files.move(archive(index), archive(index + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(LOG, PREVIOUS, StandardCopyOption.REPLACE_EXISTING);
    }
}
