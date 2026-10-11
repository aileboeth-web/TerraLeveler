package cn.terraleveler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent record of TerraLeveler-owned temporary scaffolds, kept apart from terraleveler.json.
 *
 * <p>The ledger changes with almost every scaffold placement. It used to live in
 * terraleveler.json, so the whole configuration was rewritten on the render thread every
 * second, non-atomically, and a corrupt file silently reset every setting. Here the
 * ledger is snapshotted on the client thread and written by a background thread to
 * a temporary file that atomically replaces the old one; an unreadable file is kept
 * aside instead of being overwritten.</p>
 */
final class ScaffoldLedgerStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LEDGER_TYPE = new TypeToken<LinkedHashMap<String, LinkedHashMap<String, String>>>() { }.getType();
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Logger LOGGER = LoggerFactory.getLogger("terraleveler");

    private final Supplier<Path> pathSupplier;
    private Path path;
    private final Object fileLock = new Object();
    private final Map<String, Map<String, String>> ledgers = new LinkedHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "TerraLeveler scaffold ledger writer");
        thread.setDaemon(true);
        return thread;
    });

    ScaffoldLedgerStore(Path path) {
        this(() -> path);
    }

    private ScaffoldLedgerStore(Supplier<Path> pathSupplier) {
        this.pathSupplier = pathSupplier;
    }

    /** The config directory is resolved on first use, not when the class loads. */
    static ScaffoldLedgerStore createDefault() {
        return new ScaffoldLedgerStore(() -> FabricLoader.getInstance().getConfigDir().resolve("terraleveler-scaffold-ledgers.json"));
    }

    private synchronized Path path() {
        if (this.path == null) this.path = this.pathSupplier.get();
        return this.path;
    }

    /** Loads the ledger file; an unreadable file is moved aside, never overwritten. */
    synchronized void load() {
        this.ledgers.clear();
        Path file = this.path();
        if (!Files.isRegularFile(file)) return;
        try {
            Map<String, Map<String, String>> loaded = GSON.fromJson(Files.readString(file), LEDGER_TYPE);
            if (loaded != null) {
                loaded.forEach((job, entries) -> {
                    if (job != null && entries != null && !entries.isEmpty()) {
                        this.ledgers.put(job, new LinkedHashMap<>(entries));
                    }
                });
            }
        } catch (Exception exception) {
            Path backup = file.resolveSibling(file.getFileName() + ".broken-"
                    + BACKUP_TIME.format(LocalDateTime.now()));
            try {
                Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailure) {
                exception.addSuppressed(moveFailure);
            }
            LOGGER.error("Cannot read TerraLeveler scaffold ledger; kept the file as {}", backup, exception);
        }
    }

    /** One-time migration of ledgers that older versions stored inside terraleveler.json. */
    synchronized boolean importLegacy(Map<String, Map<String, String>> legacy) {
        if (legacy == null || legacy.isEmpty()) return false;
        legacy.forEach((job, entries) -> {
            if (job == null || entries == null || entries.isEmpty()) return;
            this.ledgers.computeIfAbsent(job, ignored -> new LinkedHashMap<>()).putAll(entries);
        });
        return true;
    }

    synchronized Map<String, String> ledger(String jobKey) {
        Map<String, String> ledger = this.ledgers.get(jobKey);
        return ledger == null ? Map.of() : Map.copyOf(ledger);
    }

    synchronized void replace(String jobKey, Map<String, String> ledger) {
        if (jobKey == null) return;
        if (ledger == null || ledger.isEmpty()) {
            this.ledgers.remove(jobKey);
        } else {
            this.ledgers.put(jobKey, new LinkedHashMap<>(ledger));
        }
    }

    synchronized int jobCount() {
        return this.ledgers.size();
    }

    synchronized int entryCount() {
        int count = 0;
        for (Map<String, String> ledger : this.ledgers.values()) count += ledger.size();
        return count;
    }

    /**
     * Removes entries of jobs in {@code jobKey}'s server and dimension for which
     * {@code stale} holds (for example: loaded, but the recorded block is gone).
     */
    synchronized int pruneSameScope(String jobKey, BiPredicate<BlockPos, String> stale) {
        return pruneSameScope(this.ledgers, jobKey, stale);
    }

    /**
     * Moves scaffolds recorded by abandoned jobs in the same server and dimension
     * into {@code jobKey} when {@code owns} accepts their position, so a restarted
     * or re-selected job cleans up the scaffolds an earlier attempt left behind.
     */
    synchronized int adoptOrphans(String jobKey, Predicate<BlockPos> owns) {
        return adoptOrphans(this.ledgers, jobKey, owns);
    }

    static int pruneSameScope(Map<String, Map<String, String>> ledgers, String jobKey,
                              BiPredicate<BlockPos, String> stale) {
        int removed = 0;
        Iterator<Map.Entry<String, Map<String, String>>> jobs = ledgers.entrySet().iterator();
        while (jobs.hasNext()) {
            Map.Entry<String, Map<String, String>> job = jobs.next();
            if (!sameScope(jobKey, job.getKey())) continue;
            Iterator<Map.Entry<String, String>> entries = job.getValue().entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<String, String> entry = entries.next();
                BlockPos pos = SelectionPersistence.decode(entry.getKey());
                if (pos == null || stale.test(pos, entry.getValue())) {
                    entries.remove();
                    removed++;
                }
            }
            if (job.getValue().isEmpty()) jobs.remove();
        }
        return removed;
    }

    static int adoptOrphans(Map<String, Map<String, String>> ledgers, String jobKey, Predicate<BlockPos> owns) {
        if (jobKey == null) return 0;
        Map<String, String> adopted = new LinkedHashMap<>();
        Iterator<Map.Entry<String, Map<String, String>>> jobs = ledgers.entrySet().iterator();
        while (jobs.hasNext()) {
            Map.Entry<String, Map<String, String>> job = jobs.next();
            if (job.getKey().equals(jobKey) || !sameScope(jobKey, job.getKey())) continue;
            Iterator<Map.Entry<String, String>> entries = job.getValue().entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<String, String> entry = entries.next();
                BlockPos pos = SelectionPersistence.decode(entry.getKey());
                if (pos != null && owns.test(pos)) {
                    adopted.put(entry.getKey(), entry.getValue());
                    entries.remove();
                }
            }
            if (job.getValue().isEmpty()) jobs.remove();
        }
        if (!adopted.isEmpty()) {
            ledgers.computeIfAbsent(jobKey, ignored -> new LinkedHashMap<>()).putAll(adopted);
        }
        return adopted.size();
    }

    /** Job keys are MODE|scope|dimension|...; ledgers only interact within one server dimension. */
    static boolean sameScope(String firstJobKey, String secondJobKey) {
        if (firstJobKey == null || secondJobKey == null) return false;
        String[] first = firstJobKey.split("\\|", 4);
        String[] second = secondJobKey.split("\\|", 4);
        return first.length >= 3 && second.length >= 3
                && first[1].equals(second[1]) && first[2].equals(second[2]);
    }

    /** Snapshots now and writes atomically on the background writer. */
    void saveAsync() {
        String json = this.snapshotJson();
        if (this.writer.isShutdown()) {
            this.write(json);
            return;
        }
        this.writer.execute(() -> this.write(json));
    }

    /** Writes synchronously on the calling thread; returns whether the file was replaced. */
    boolean saveNow() {
        return this.write(this.snapshotJson());
    }

    /** Drains the background writer and writes a final snapshot; used when the client stops. */
    void saveBlocking() {
        String json = this.snapshotJson();
        this.writer.shutdown();
        try {
            this.writer.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        this.write(json);
    }

    private synchronized String snapshotJson() {
        return GSON.toJson(this.ledgers);
    }

    private boolean write(String json) {
        // A separate lock: file I/O must not block ledger updates on the client thread.
        synchronized (this.fileLock) {
            return this.writeLocked(json);
        }
    }

    private boolean writeLocked(String json) {
        Path file = this.path();
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, json);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            LOGGER.error("Cannot save TerraLeveler scaffold ledger", exception);
            return false;
        }
    }
}
