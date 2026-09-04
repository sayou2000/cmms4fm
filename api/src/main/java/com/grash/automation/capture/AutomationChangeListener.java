package com.grash.automation.capture;

import com.grash.automation.event.ChangeType;
import com.grash.automation.event.EntityType;
import com.grash.model.abstracts.CompanyAudit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Turns every insert and update of a watched entity into a recorded change.
 *
 * <p>This is the piece that made the trigger matrix sustainable rather than a list of chores. The
 * previous design needed a hand-written publish point in each service, next to each of the
 * twenty-three webhook dispatches, each with its own hand-written field comparison — and the
 * comparisons disagreed with each other: work orders had two update paths with two different
 * diffs, and the asset diff could not see a status that {@code triggerDownTime} wrote afterwards.
 *
 * <p>Hibernate already knows the answer exactly. {@link PostUpdateEvent#getDirtyProperties()}
 * names the columns the UPDATE statement really touched, whatever route the code took to get
 * there. So there is one source of the diff, it is right by construction, and adding an entity to
 * {@link TrackedEntities} is the whole of the work.
 *
 * <p>Two constraints this must respect, both because it runs inside the flush:
 *
 * <ul>
 *   <li><b>It must not query.</b> Touching an unloaded association here can reopen the session
 *       that is currently flushing. Only the state Hibernate hands over is read, plus
 *       {@code company}, which is a {@code @ManyToOne} and therefore already loaded.</li>
 *   <li><b>It must not publish.</b> The transaction has not committed; a rule reading the entity
 *       on another thread would find the old state. Changes go to
 *       {@link TransactionChangeCollector}, which publishes after the commit.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutomationChangeListener implements PostUpdateEventListener, PostInsertEventListener {

    private final TransactionChangeCollector collector;

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        TrackedEntities.of(event.getEntity()).ifPresent(entityType ->
                collector.record(entityType, asLong(event.getId()), companyIdOf(event.getEntity()),
                        ChangeType.UPDATED, dirtyFieldNames(event)));
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        TrackedEntities.of(event.getEntity()).ifPresent(entityType ->
                // No diff on a creation: every field is new, so naming them would say nothing.
                // A rule on CREATED filters with conditions, not with a changed-field list.
                collector.record(entityType, asLong(event.getId()), companyIdOf(event.getEntity()),
                        ChangeType.CREATED, Set.of()));
    }

    /**
     * The names of the changed properties, as JPA attribute names — which is exactly the
     * vocabulary a condition subject uses, so {@code asset.status} and the diff entry
     * {@code status} match without a translation table.
     *
     * <p>Hibernate may report null instead of an index array when it did not compute a dirty
     * check (a forced version increment, a merge of a detached instance). That is treated as "the
     * change is unknown", not as "everything changed": a rule filtering on a field then does not
     * match, which loses an execution, whereas claiming every field changed would make
     * {@code CHANGED_TO} fire for fields that did not. Losing an execution is recoverable; a
     * false one is not.
     */
    private Set<String> dirtyFieldNames(PostUpdateEvent event) {
        int[] dirty = event.getDirtyProperties();
        if (dirty == null) {
            log.debug("No dirty properties for {} {}; the change is announced without a field diff",
                    event.getEntity().getClass().getSimpleName(), event.getId());
            return Set.of();
        }
        String[] names = event.getPersister().getPropertyNames();
        Set<String> changed = new LinkedHashSet<>();
        for (int index : dirty) {
            if (index >= 0 && index < names.length) {
                changed.add(names[index]);
            }
        }
        return changed;
    }

    private Long companyIdOf(Object entity) {
        if (!(entity instanceof CompanyAudit companyAudit) || companyAudit.getCompany() == null) {
            return null;
        }
        return companyAudit.getCompany().getId();
    }

    private Long asLong(Object id) {
        return id instanceof Number number ? number.longValue() : null;
    }

    /**
     * False, so these run during the flush rather than after the commit. The collector needs the
     * security context and the transaction synchronization, both of which are still in place
     * here; post-commit listeners would also fire for a rolled back transaction.
     */
    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }
}
