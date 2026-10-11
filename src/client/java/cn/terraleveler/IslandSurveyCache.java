package cn.terraleveler;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Record of island column classifications observed while chunks were loaded. Servers
 * with a small view distance unload terrain the player already walked past; the island
 * boundary scan may use these observations for columns the client no longer holds.
 * Construction never uses this cache: every block action is still planned from, and
 * revalidated against, live loaded terrain.
 *
 * <p>Entries are scoped to one server, dimension and target Y and are discarded whenever
 * that scope changes. Since 0.8.73 each scope is also saved to disk
 * ({@link IslandSurveyStore}); 0.8.69 testing lost the whole survey on every game restart.
 */
final class IslandSurveyCache {
    static final byte UNKNOWN = 0;
    static final byte LAND = 1;
    static final byte WATER = 2;
    private static final int FORMAT = 1;
    private static final int MAXIMUM_STORED_CHUNKS = 65536;

    private String scope;
    private final Map<Long, byte[]> chunks = new HashMap<>();
    private final Map<Long, Long> capturedTick = new HashMap<>();
    private boolean dirty;

    /** Switches to {@code scope}, clearing all observations if it differs. Returns true when cleared. */
    boolean ensureScope(String scope) {
        if (Objects.equals(this.scope, scope)) return false;
        boolean hadData = !chunks.isEmpty();
        this.scope = scope;
        chunks.clear();
        capturedTick.clear();
        dirty = false;
        return hadData;
    }

    String scope() { return scope; }

    void clear() {
        scope = null;
        chunks.clear();
        capturedTick.clear();
        dirty = false;
    }

    void put(int x, int z, byte kind) {
        if (kind != LAND && kind != WATER) return;
        byte[] chunk = chunks.computeIfAbsent(chunkKey(x >> 4, z >> 4), ignored -> new byte[256]);
        if (chunk[index(x, z)] != kind) {
            chunk[index(x, z)] = kind;
            dirty = true;
        }
    }

    byte get(int x, int z) {
        byte[] chunk = chunks.get(chunkKey(x >> 4, z >> 4));
        return chunk == null ? UNKNOWN : chunk[index(x, z)];
    }

    /** Stores a full 16x16 chunk observation; {@code kinds} is indexed by {@code localZ * 16 + localX}. */
    void putChunk(int chunkX, int chunkZ, byte[] kinds, long tick) {
        if (kinds.length != 256) throw new IllegalArgumentException("chunk observation must have 256 columns");
        chunks.put(chunkKey(chunkX, chunkZ), kinds.clone());
        capturedTick.put(chunkKey(chunkX, chunkZ), tick);
        dirty = true;
    }

    /** Tick of the last full-chunk capture, or {@code Long.MIN_VALUE} when never fully captured. */
    long capturedAt(int chunkX, int chunkZ) {
        return capturedTick.getOrDefault(chunkKey(chunkX, chunkZ), Long.MIN_VALUE);
    }

    int fullyCapturedChunks() { return capturedTick.size(); }

    int storedChunks() { return chunks.size(); }

    boolean dirty() { return dirty; }

    void markSaved() { dirty = false; }

    /**
     * Writes the current scope's observations. Capture ticks belong to one game session,
     * so they are not stored; loaded chunks count as captured long ago and are refreshed
     * from live terrain when the player passes by again.
     */
    void writeTo(OutputStream output) throws IOException {
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(FORMAT);
        data.writeUTF(scope == null ? "" : scope);
        int count = Math.min(chunks.size(), MAXIMUM_STORED_CHUNKS);
        data.writeInt(count);
        int written = 0;
        for (Map.Entry<Long, byte[]> entry : chunks.entrySet()) {
            if (written++ >= count) break;
            data.writeLong(entry.getKey());
            data.write(entry.getValue());
            data.writeBoolean(capturedTick.containsKey(entry.getKey()));
        }
        data.flush();
    }

    /** Replaces the observations with a stored copy of {@code expectedScope}; false (unchanged) on any mismatch. */
    boolean readFrom(InputStream input, String expectedScope) throws IOException {
        DataInputStream data = new DataInputStream(input);
        if (data.readInt() != FORMAT) return false;
        String storedScope = data.readUTF();
        if (!storedScope.equals(expectedScope)) return false;
        int count = data.readInt();
        if (count < 0 || count > MAXIMUM_STORED_CHUNKS) return false;
        Map<Long, byte[]> loaded = new LinkedHashMap<>();
        Map<Long, Long> captured = new HashMap<>();
        for (int index = 0; index < count; index++) {
            long key = data.readLong();
            byte[] kinds = new byte[256];
            data.readFully(kinds);
            for (byte kind : kinds) {
                if (kind != UNKNOWN && kind != LAND && kind != WATER) return false;
            }
            loaded.put(key, kinds);
            if (data.readBoolean()) captured.put(key, 0L);
        }
        this.scope = expectedScope;
        this.chunks.clear();
        this.chunks.putAll(loaded);
        this.capturedTick.clear();
        this.capturedTick.putAll(captured);
        this.dirty = false;
        return true;
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static int index(int x, int z) {
        return (z & 15) * 16 + (x & 15);
    }
}
