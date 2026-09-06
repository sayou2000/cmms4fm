package com.grash.automation.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;

/**
 * The one place a service announces what a change <em>means</em>.
 *
 * <p>Column diffs come from Hibernate ({@code automation/capture}); this covers the other half —
 * approved, rejected, closed — which no diff can reveal because it is a reading of the change,
 * not a property of it. Only the service performing the change knows it, so only the service can
 * say it.
 *
 * <p>Two things this adds over calling {@code eventPublisher.publishEvent} directly, and both
 * exist because getting either wrong fails silently:
 *
 * <ul>
 *   <li><b>It insists on a transaction.</b> Every consumer of an
 *       {@link EntityChangedEvent} is a {@code @TransactionalEventListener(AFTER_COMMIT)}, and
 *       such a listener does not fire at all when no transaction is active — the event is
 *       published, accepted and dropped, with nothing in the log. That is the trap
 *       {@code PurchaseOrderController.respond} sat in before this existed: a controller method
 *       without {@code @Transactional}. Here it becomes an error in the log instead of a feature
 *       that quietly does not work.</li>
 *   <li><b>It takes the actor as an argument.</b> {@link CurrentActor} reads the security
 *       context, which is right for the capture pipeline running inside a flush, but a service
 *       that was handed the acting {@code User} already has the better answer. The overload
 *       without it falls back to the security context.</li>
 * </ul>
 *
 * <p>What it deliberately does <b>not</b> do is announce the follow-on changes an approval
 * causes. Approving a request creates a work order, and that creation is already announced by
 * the capture pipeline because {@code WorkOrder} is a tracked entity; publishing a
 * {@code child(CREATED, WORK_ORDER, …)} here as well would run every WORK_ORDER:CREATED rule
 * twice. Same reasoning as the {@code AssetService} note in {@code CLAUDE.md}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    /** Announces a semantic change caused by a known user. */
    public void publish(ChangeType changeType, EntityType entityType, Long entityId, Long companyId,
                        Long actorUserId) {
        if (entityId == null || companyId == null) {
            // The engine drops an event without a tenant anyway, and a consumer would have
            // nothing to reload. Saying so is more useful than publishing it.
            log.warn("Not publishing {} {}: entityId={} companyId={}", entityType, changeType,
                    entityId, companyId);
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.error("{} {} for {} was published outside a transaction, so no AFTER_COMMIT "
                            + "listener will see it. The publishing method needs @Transactional.",
                    entityType, changeType, entityId);
        }
        eventPublisher.publishEvent(EntityChangedEvent.root(changeType, entityType, entityId,
                companyId, Set.of(), actorUserId));
    }

    /** Announces a semantic change whose actor is whoever the security context names. */
    public void publish(ChangeType changeType, EntityType entityType, Long entityId, Long companyId) {
        publish(changeType, entityType, entityId, companyId, CurrentActor.userIdOrNull());
    }

    /**
     * Announces a domain event that is not one of the rule engine's change types.
     *
     * <p>{@code ChangeType} is the vocabulary a rule can be written against, and not every
     * meaningful change fits it — {@code com.grash.event.fanout.WorkOrderStatusChanged} is the
     * example: "the status became something" is not CREATED, UPDATED, CLOSED, APPROVED or
     * REJECTED, and borrowing UPDATED would double every WORK_ORDER:UPDATED rule.
     *
     * <p>It goes through here rather than straight to {@code ApplicationEventPublisher} for the
     * transaction check alone, which is the failure these events share regardless of their type.
     */
    public void publishDomainEvent(Object event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.error("{} was published outside a transaction, so no AFTER_COMMIT listener will "
                    + "see it. The publishing method needs @Transactional.", event.getClass().getSimpleName());
        }
        eventPublisher.publishEvent(event);
    }
}
