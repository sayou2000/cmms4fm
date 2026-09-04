package com.grash.automation.event;

/**
 * An {@link EntityChangedEvent} that is already committed.
 *
 * <p>A separate type, and the reason is worth stating because a single event class would look
 * simpler. {@link EntityChangedEvent} is published <em>inside</em> a transaction and picked up by
 * a {@code @TransactionalEventListener(AFTER_COMMIT)}. The change capture cannot do that: it
 * collects the field diff during the flush and publishes once from
 * {@code TransactionSynchronization.afterCommit}, where there is no longer a transaction for such
 * a listener to hang off — a transactional listener registered there never fires.
 *
 * <p>Publishing the same class from both places would therefore need one listener that fires
 * after commit and one that fires immediately, and the immediate one would run for a
 * hand-published event <b>before</b> its transaction committed: the rule would load the old state,
 * intermittently, depending on timing. Two types make the distinction impossible to get wrong.
 *
 * @param event what changed, with the diff Hibernate reported
 */
public record CommittedEntityChange(EntityChangedEvent event) {
}
