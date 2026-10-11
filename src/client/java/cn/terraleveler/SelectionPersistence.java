package cn.terraleveler;

import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

final class SelectionPersistence {
    record Restored(BlockPos first, BlockPos second) { }

    private SelectionPersistence() { }

    static String encode(BlockPos pos) {
        return pos == null ? null : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    static BlockPos decode(String value) {
        if (value == null) return null;
        String[] parts = value.trim().split(",", -1);
        if (parts.length != 3) return null;
        try {
            return new BlockPos(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static Restored restore(String storedScope, String storedDimension,
                            String storedFirst, String storedSecond,
                            String currentScope, String currentDimension) {
        if (!matchesContext(storedScope, storedDimension, currentScope, currentDimension)) return null;
        BlockPos first = decode(storedFirst);
        if (first == null) return null;
        return new Restored(first, decode(storedSecond));
    }

    static boolean matchesContext(String storedScope, String storedDimension,
                                  String currentScope, String currentDimension) {
        return storedScope != null && storedDimension != null
                && storedScope.equals(currentScope) && storedDimension.equals(currentDimension);
    }

    static String scopeKey(String type, String identity) {
        if (type == null || identity == null || identity.isBlank()) return null;
        String normalized = type.trim().toLowerCase(Locale.ROOT) + "\n"
                + identity.trim().toLowerCase(Locale.ROOT);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
