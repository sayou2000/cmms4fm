package com.grash.automation.capture;

import com.grash.automation.event.EntityChangedEvent;

/**
 * Which cascade the current thread is executing a rule for, if any.
 *
 * <p>This is what stops the engine from hearing itself in a loop, and it became necessary the
 * moment change capture went generic. Before, the only event was hand-published from a status
 * change, and a rule writing an asset produced no event at all. Now every write is announced —
 * including the writes a rule's own actions perform. A rule "when an asset changes, set a
 * custom field" would otherwise write, be announced as a fresh change, match itself, write
 * again, and never stop: each event would carry a new {@code correlationId} and depth 0, so
 * neither the depth limit nor "already ran in this cascade" could recognise it.
 *
 * <p>The event itself carries {@code correlationId} and {@code depth} because a ThreadLocal
 * cannot survive the hop to the executor. This ThreadLocal is the other half of the same
 * mechanism: it carries them the short distance from the rule that is running to the flush its
 * actions cause, both of which are on the same thread.
 */
public final class CascadeContext {

    private static final ThreadLocal<EntityChangedEvent> CURRENT = new ThreadLocal<>();

    private CascadeContext() {
    }

    /**
     * Marks the thread as executing this event's cascade. Must be paired with {@link #exit()} in
     * a finally block — the automation executor pools its threads, and a leftover value would
     * attach the next unrelated change to this cascade and have it skipped as a repeat.
     */
    public static void enter(EntityChangedEvent event) {
        CURRENT.set(event);
    }

    public static void exit() {
        CURRENT.remove();
    }

    /** The event whose rule is currently running on this thread, or null outside a rule run. */
    public static EntityChangedEvent current() {
        return CURRENT.get();
    }
}
