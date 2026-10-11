package cn.terraleveler;

final class FlattenExcavationPolicy {
    enum Category { IGNORE, PRIMARY, TREE_LOG, DEFERRED_FOLIAGE }

    private FlattenExcavationPolicy() { }

    static Category classify(boolean air, boolean bedrock, boolean leaves, boolean logs) {
        if (air || bedrock) return Category.IGNORE;
        if (leaves) return Category.DEFERRED_FOLIAGE;
        return logs ? Category.TREE_LOG : Category.PRIMARY;
    }
}
