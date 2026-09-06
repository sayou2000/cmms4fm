package com.grash.event.fanout;

/**
 * A meter reading was written.
 *
 * <p>Exists so that the meter-trigger alarm — threshold check, work-order creation, notification,
 * webhook — can be a consumer instead of controller code. It used to run inside
 * {@code ReadingController}, synchronously in the HTTP request and, in the create path,
 * <em>before</em> the reading itself was saved: the work order referenced a reading that did not
 * exist yet, and would still exist if the save then failed.
 *
 * <p>The value travels with the event rather than being re-read, because a second reading of the
 * same meter may already have arrived by the time the consumer runs, and the alarm belongs to the
 * reading that caused it.
 */
public record ReadingRecorded(Long readingId, Long meterId, double value, Long actorUserId) {
}
