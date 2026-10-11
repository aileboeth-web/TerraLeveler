package cn.terraleveler;

/**
 * How one cell looks to the fluid drain: a compact world snapshot classification
 * supplied by the controller (moved out of the removed trench FluidContainmentPlanner in 0.9.04).
 */
public enum FluidCellKind {
    UNLOADED,
    AIR,
    FALL_THROUGH,
    SOLID,
    SAND,
    WATER,
    WATERLOGGED_SOLID,
    LAVA,
    OTHER_FLUID
}
