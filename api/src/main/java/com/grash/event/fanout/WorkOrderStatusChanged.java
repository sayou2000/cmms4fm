package com.grash.event.fanout;

import com.grash.model.enums.Status;

/**
 * A work order moved from one status to another.
 *
 * <p>A purpose-built event rather than an {@code EntityChangedEvent}, and the reason is worth
 * stating because a second event type is a cost. {@code ChangeType} is the rule engine's trigger
 * vocabulary — {@code CREATED}, {@code UPDATED}, {@code CLOSED}, {@code APPROVED},
 * {@code REJECTED} — and "the status changed to something" is none of those. The one that comes
 * closest, {@code UPDATED}, is already published for every work order write by the capture
 * pipeline, so publishing it here as well would fire every WORK_ORDER:UPDATED rule twice for one
 * status change.
 *
 * <p>So the rule engine hears {@code WORK_ORDER:CLOSED} when a work order is completed, and the
 * requester notification — which owes the requester a mail on <em>any</em> status change — hears
 * this. Two audiences, two vocabularies, no duplicate rule executions.
 *
 * <p>Ids and primitives only, as with every event that crosses a commit: see
 * {@code com.grash.automation.event.EntityChangedEvent}.
 *
 * @param previousStatus the status before the change, which the consumer cannot recover
 *                       afterwards and which is the whole reason this is an event and not a
 *                       re-read
 */
public record WorkOrderStatusChanged(Long workOrderId, Long companyId, Status previousStatus,
                                     Status newStatus, Long actorUserId) {
}
