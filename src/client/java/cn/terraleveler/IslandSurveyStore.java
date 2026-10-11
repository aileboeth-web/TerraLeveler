package cn.terraleveler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Island survey records on disk (0.8.73): one gzip file per server/dimension/target-Y
 * scope under {@code config/terraleveler-island-survey/}. Writes are atomic and happen on a
 * background thread; a damaged or foreign file is ignored (the survey starts empty),
 * never trusted. Only boundary detection reads these records.
 */
final class IslandSurveyStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("terraleveler");

    private final Supplier<Path> directorySupplier;
    private Path directory;
    private final Object fileLock = new Object();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "TerraLeveler island survey writer");
        thread.setDaemon(true);
        return thread;
    });

    IslandSurveyStore(Supplier<Path> directorySupplier) {
        this.directorySupplier = directorySupplier;
    }

    static IslandSurveyStore createDefault() {
        return new IslandSurveyStore(() -> FabricLoader.getInstance().getConfigDir().resolve("terraleveler-island-survey"));
    }

    private synchronized Path directory() {
        if (this.directory == null) this.directory = this.directorySupplier.get();
        return this.directory;
    }

    static String fileName(String scope) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16) + ".bin.gz";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Loads the stored survey of {@code scope} into {@code cache}; returns the number of chunks loaded. */
    int load(String scope, IslandSurveyCache cache) {
        Path file = this.directory().resolve(fileName(scope));
        if (!Files.isRegularFile(file)) return 0;
        synchronized (this.fileLock) {
            try (InputStream input = new GZIPInputStream(Files.newInputStream(file))) {
                if (!cache.readFrom(input, scope)) {
                    LOGGER.warn("Ignoring TerraLeveler island survey {} (format or scope mismatch)", file);
                    return 0;
                }
                return cache.storedChunks();
            } catch (IOException exception) {
                LOGGER.warn("Ignoring unreadable TerraLeveler island survey {}", file, exception);
                return 0;
            }
        }
    }

    /** Snapshots {@code cache} now and writes it on the background writer. */
    void saveAsync(IslandSurveyCache cache) {
        String scope = cache.scope();
        if (scope == null || scope.isEmpty()) return;
        byte[] snapshot;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            cache.writeTo(buffer);
            snapshot = buffer.toByteArray();
        } catch (IOException impossible) {
            LOGGER.error("Cannot snapshot TerraLeveler island survey", impossible);
            return;
        }
        cache.markSaved();
        Runnable write = () -> this.write(scope, snapshot);
        if (this.writer.isShutdown()) {
            write.run();
        } else {
            this.writer.execute(write);
        }
    }

    /** Drains pending writes, then writes {@code cache} synchronously if it changed; used when the client stops. */
    void saveBlocking(IslandSurveyCache cache) {
        if (cache.dirty()) this.saveAsync(cache);
        this.writer.shutdown();
        try {
            this.writer.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void write(String scope, byte[] snapshot) {
        synchronized (this.fileLock) {
            Path file = this.directory().resolve(fileName(scope));
            try {
                Files.createDirectories(file.getParent());
                Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
                try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(temporary))) {
                    new ByteArrayInputStream(snapshot).transferTo(output);
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                LOGGER.error("Cannot save TerraLeveler island survey {}", file, exception);
            }
        }
    }
}
