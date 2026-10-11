package cn.terraleveler;

import java.util.HashMap;
import java.util.Map;

final class TimedEntityIgnoreSet {
    private final Map<Integer, Long> expirations = new HashMap<>();

    void ignore(int entityId, long untilTick) {
        if (entityId >= 0) expirations.merge(entityId, untilTick, Math::max);
    }

    boolean contains(int entityId, long currentTick) {
        Long expiration = expirations.get(entityId);
        if (expiration == null) return false;
        if (currentTick < expiration) return true;
        expirations.remove(entityId);
        return false;
    }

    void prune(long currentTick) {
        expirations.entrySet().removeIf(entry -> currentTick >= entry.getValue());
    }

    int size() {
        return expirations.size();
    }

    void clear() {
        expirations.clear();
    }
}
