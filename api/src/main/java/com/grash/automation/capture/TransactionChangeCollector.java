package com.grash.automation.capture;

import com.grash.automation.event.ChangeType;
import com.grash.automation.event.CommittedEntityChange;
import com.grash.automation.event.CurrentActor;
import com.grash.automation.event.EntityChangedEvent;
import com.grash.automation.event.EntityType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Collects the changes of one transaction and publishes them once, after it commits.
 *
 * <p>Collecting rather than publishing per statement is what makes the diff correct. A single
 * request can update the same row more than once — {@code AssetService.patch} writes the asset and
 * {@code triggerDownTime} then writes its status — and each UPDATE reports only its own columns.
 * Publishing per statement would produce two events, each with half the truth, and a rule
 * filtering on {@code status} would see one of them and a rule filtering on {@code name} the
 * other. Merged, the event says what the transaction as a whole changed.
 *
 * <p>It also gives the actor. {@link CurrentActor} reads the security context, which exists on
 * this thread during the flush and no longer exists on the executor that runs the rules.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionChangeCollector {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * A ceiling on how many changed rows one transaction may announce.
     *
     * <p>Not a performance tweak but a safety net with teeth: a CSV import of five thousand
     * assets, or a preventive-maintenance run generating work orders, would otherwise hand five
     * thousand events to the rule engine, each with its own query and its own async task. When
     * the limit is hit the rest of the transaction is dropped with a warning — deliberately
     * visible, because silently evaluating half a bulk import would be worse than not evaluating
     * it at all.
     */
    @Value("${automation.max-changes-per-transaction:200}")
    private int maxChangesPerTransaction;

    /**
     * The same off switch the listener has, applied one step earlier.
     *
     * <p>A default-off flag should mean the mechanism is not there at all. Without this the
     * capture would still collect every change of every request and publish it, only for the
     * listener to drop it — work done for nothing on an instance that has no rules.
     */
    @Value("${automation.enabled:false}")
    private boolean enabled;

    /** What one transaction has gathered. Cleared by the synchronization, in every outcome. */
    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

    /** A changed row: type, id and company, which together identify what to re-read later. */
    private record Key(EntityType entityType, Long entityId, Long companyId, ChangeType changeType) {
    }

    private static class Pending {
        private final Map<Key, Set<String>> changes = new LinkedHashMap<>();
        private final Long actorUserId;
        private boolean overflowed;

        Pending(Long actorUserId) {
            this.actorUserId = actorUserId;
        }
    }

    /**
     * Records one changed row. Called from the Hibernate flush, so it must be cheap and must not
     * touch the database — reading a lazy association here can reopen the very session that is
     * flushing.
     */
    public void record(EntityType entityType, Long entityId, Long companyId, ChangeType changeType,
                       Set<String> changedFields) {
        if (!enabled) {
            return;
        }
        if (entityId == null || companyId == null) {
            // No company means no tenant to look up rules for. The engine drops such an event
            // anyway; dropping it here keeps the log quiet for the rows that legitimately have
            // none, such as a company being created.
            return;
        }
        Pending pending = PENDING.get();
        if (pending == null) {
            pending = new Pending(CurrentActor.userIdOrNull());
            PENDING.set(pending);
            if (!registerFlush()) {
                // No Spring transaction is managing this write, so Hibernate has already
                // committed it. Publishing straight away is then correct rather than early.
                PENDING.remove();
                publish(new Key(entityType, entityId, companyId, changeType),
                        changedFields, pending.actorUserId);
                return;
            }
        }
        Key key = new Key(entityType, entityId, companyId, changeType);
        if (!pending.changes.containsKey(key) && pending.changes.size() >= maxChangesPerTransaction) {
            if (!pending.overflowed) {
                pending.overflowed = true;
                log.warn("More than {} changed entities in one transaction; the automation engine "
                                + "is skipping the rest. Raise automation.max-changes-per-transaction "
                                + "if this was a legitimate bulk operation that rules should see.",
                        maxChangesPerTransaction);
            }
            return;
        }
        pending.changes.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).addAll(changedFields);
    }

    /**
     * @return false when nothing is coordinating a commit, i.e. the write is already durable
     */
    private boolean registerFlush() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Pending pending = PENDING.get();
                if (pending == null) {
                    return;
                }
                pending.changes.forEach((key, fields) -> publish(key, fields, pending.actorUserId));
            }

            @Override
            public void afterCompletion(int status) {
                // In every outcome, including a rollback: the thread goes back into a pool, and a
                // leftover map would attach this transaction's changes to the next request.
                PENDING.remove();
            }
        });
        return true;
    }

    /**
     * Publishes one merged change, inside the cascade that caused it when there is one.
     *
     * <p>The {@code child} branch is what keeps the engine from hearing itself in a loop. A write
     * performed by a rule's own action is announced like any other, so without inheriting the
     * cascade it would arrive with a fresh {@code correlationId} and depth 0 — and both loop
     * guards would be blind to it. See {@link CascadeContext}.
     */
    private void publish(Key key, Set<String> changedFields, Long actorUserId) {
        EntityChangedEvent cascade = CascadeContext.current();
        EntityChangedEvent event = cascade == null
                ? EntityChangedEvent.root(key.changeType(), key.entityType(), key.entityId(),
                key.companyId(), Set.copyOf(changedFields), actorUserId)
                : cascade.child(key.changeType(), key.entityType(), key.entityId(),
                Set.copyOf(changedFields));
        eventPublisher.publishEvent(new CommittedEntityChange(event));
    }
}
