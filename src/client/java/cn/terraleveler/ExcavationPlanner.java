package cn.terraleveler;

/**
 * World-state classification of an excavation cell and what to do with it.
 *
 * <p>The key invariant is that dry air is already the desired result.  It never
 * creates a movement or placement task.  (Until 0.9.03 this class also held the
 * trench excavation geometry.)</p>
 */
public final class ExcavationPlanner {
    public enum CellKind { UNLOADED, CLEAR_AIR, BEDROCK, FLUID, SOLID }
    public enum Operation { NONE, WAIT_FOR_CHUNK, BREAK, CONTAIN_FLUID }

    private ExcavationPlanner() { }

    static CellKind classifySnapshot(boolean loaded, boolean air, boolean bedrock, boolean fluid) {
        if (!loaded) return CellKind.UNLOADED;
        if (fluid) return CellKind.FLUID;
        if (air) return CellKind.CLEAR_AIR;
        if (bedrock) return CellKind.BEDROCK;
        return CellKind.SOLID;
    }

    static boolean requiresWorldAction(CellKind kind) {
        return operationFor(kind) == Operation.BREAK || operationFor(kind) == Operation.CONTAIN_FLUID;
    }

    public static Operation operationFor(CellKind kind) {
        return switch (kind) {
            case CLEAR_AIR, BEDROCK -> Operation.NONE;
            case UNLOADED -> Operation.WAIT_FOR_CHUNK;
            case SOLID -> Operation.BREAK;
            case FLUID -> Operation.CONTAIN_FLUID;
        };
    }
}
