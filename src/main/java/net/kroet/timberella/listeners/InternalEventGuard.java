package net.kroet.timberella.listeners;

import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Runs the creation and firing of an event whose constructor Paper marks as
 * internal. If a Paper update changed it (a LinkageError such as
 * NoSuchMethodError), the action is denied and one warning per run is logged.
 */
final class InternalEventGuard {
    private final Logger logger;
    private final String eventName;
    private final String action;
    private boolean warned;

    InternalEventGuard(Logger logger, String eventName, String action) {
        this.logger = logger;
        this.eventName = eventName;
        this.action = action;
    }

    // The fired event, or null if creating or firing it hit a LinkageError.
    <E> E fire(Supplier<E> fire) {
        try {
            return fire.get();
        } catch (LinkageError e) {
            if (!warned) {
                warned = true;
                logger.warning(action + " is denied: Paper's " + eventName + " could not be created or fired (" + e
                        + "). A Paper update probably changed this internal API; check for a plugin update."
                        + " This warning is shown once per server run.");
            }
            return null;
        }
    }
}
