package cn.terraleveler;

final class SelectionInteractionRules {
    private SelectionInteractionRules() { }

    static boolean mayChangeSelection(boolean clientSide, boolean enabled, boolean appleHeld,
                                      ConstructionController.State controllerState) {
        return mayChangeSelection(clientSide, enabled, appleHeld,
                controllerState == ConstructionController.State.IDLE
                || controllerState == ConstructionController.State.PAUSED
                || controllerState == ConstructionController.State.COMPLETE);
    }

    static boolean mayChangeSelection(boolean clientSide, boolean enabled, boolean appleHeld,
                                      boolean controllerAllowsSelection) {
        return clientSide && enabled && appleHeld && controllerAllowsSelection;
    }

    /** Callbacks for one press arrive within this window; holding the button keeps extending it. */
    static final long REPEAT_WINDOW_MILLIS = 400L;

    /**
     * One left click reaches AttackBlockCallback twice in the same tick (start and
     * continue attack), and a held button repeats it every tick, so the corner was
     * reset and announced twice. A callback on the same block with the same button
     * inside the window is the same press.
     */
    static boolean isRepeatedPress(long lastPosition, boolean lastWasAttack, long lastMillis,
                                   long position, boolean attack, long nowMillis) {
        return lastPosition == position && lastWasAttack == attack
                && nowMillis >= lastMillis && nowMillis - lastMillis <= REPEAT_WINDOW_MILLIS;
    }
}
