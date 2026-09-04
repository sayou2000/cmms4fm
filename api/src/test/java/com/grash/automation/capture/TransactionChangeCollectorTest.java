package com.grash.automation.capture;

import com.grash.automation.event.ChangeType;
import com.grash.automation.event.CommittedEntityChange;
import com.grash.automation.event.EntityChangedEvent;
import com.grash.automation.event.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one transaction announces to the rule engine.
 *
 * <p>The three properties pinned here are the ones that were reasoned about rather than measured
 * when the change capture was written, and each of them would fail silently: a diff split across
 * two events, an event published before its transaction committed, and a rule's own write
 * starting a fresh cascade so that no loop guard can see it.
 */
class TransactionChangeCollectorTest {

    private final List<Object> published = new ArrayList<>();
    private TransactionChangeCollector collector;

    @BeforeEach
    void setUp() {
        ApplicationEventPublisher publisher = new ApplicationEventPublisher() {
            @Override
            public void publishEvent(Object event) {
                published.add(event);
            }

            @Override
            public void publishEvent(ApplicationEvent event) {
                published.add(event);
            }
        };
        collector = new TransactionChangeCollector(publisher);
        ReflectionTestUtils.setField(collector, "maxChangesPerTransaction", 3);
        ReflectionTestUtils.setField(collector, "enabled", true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_COMMITTED));
            TransactionSynchronizationManager.clearSynchronization();
        }
        CascadeContext.exit();
    }

    private void commit() {
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(synchronization -> synchronization.beforeCommit(false));
        synchronizations.forEach(TransactionSynchronization::afterCommit);
    }

    private List<EntityChangedEvent> events() {
        return published.stream()
                .filter(CommittedEntityChange.class::isInstance)
                .map(event -> ((CommittedEntityChange) event).event())
                .toList();
    }

    private void record(Long id, ChangeType changeType, String... fields) {
        collector.record(EntityType.ASSET, id, 9L, changeType, Set.of(fields));
    }

    @Test
    @DisplayName("nothing is announced before the transaction commits")
    void publishesOnlyAfterCommit() {
        record(103L, ChangeType.UPDATED, "status");

        assertTrue(events().isEmpty(), "a rule reading the entity now would find the old state");

        commit();

        assertEquals(1, events().size());
    }

    @Test
    @DisplayName("two updates of the same row become one event with the union of their fields")
    void mergesRepeatedUpdatesOfTheSameRow() {
        // The case this exists for: AssetService.patch writes the asset, then triggerDownTime
        // writes its status. Two UPDATE statements, each reporting only its own columns. Split
        // into two events, a rule filtering on "status" sees one and a rule filtering on "name"
        // the other, and neither sees the change as a whole.
        record(103L, ChangeType.UPDATED, "name");
        record(103L, ChangeType.UPDATED, "status");

        commit();

        assertEquals(1, events().size());
        assertEquals(Set.of("name", "status"), events().get(0).changedFields());
    }

    @Test
    @DisplayName("different rows stay different events")
    void keepsRowsApart() {
        record(103L, ChangeType.UPDATED, "status");
        record(104L, ChangeType.UPDATED, "status");

        commit();

        assertEquals(2, events().size());
    }

    @Test
    @DisplayName("a creation and an update of the same row are not merged")
    void keepsChangeTypesApart() {
        record(103L, ChangeType.CREATED);
        record(103L, ChangeType.UPDATED, "status");

        commit();

        assertEquals(2, events().size());
    }

    @Test
    @DisplayName("a row without a company is dropped rather than announced")
    void skipsRowsWithoutACompany() {
        collector.record(EntityType.ASSET, 103L, null, ChangeType.UPDATED, Set.of("status"));

        commit();

        assertTrue(events().isEmpty());
    }

    @Test
    @DisplayName("a rollback announces nothing and leaves no state on the thread")
    void announcesNothingOnRollback() {
        record(103L, ChangeType.UPDATED, "status");

        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertTrue(events().isEmpty());
    }

    @Nested
    @DisplayName("bulk protection")
    class Bulk {

        @Test
        @DisplayName("stops after the configured number of rows")
        void capsOneTransaction() {
            // A CSV import of five thousand assets would otherwise hand five thousand events to
            // the engine, each with its own query and its own async task.
            for (long id = 1; id <= 10; id++) {
                record(id, ChangeType.UPDATED, "status");
            }

            commit();

            assertEquals(3, events().size(), "the limit was set to three for this test");
        }

        @Test
        @DisplayName("a row already collected still accumulates fields past the limit")
        void doesNotDropFieldsOfRowsItAlreadyKnows() {
            for (long id = 1; id <= 3; id++) {
                record(id, ChangeType.UPDATED, "status");
            }
            record(1L, ChangeType.UPDATED, "name");

            commit();

            EntityChangedEvent first = events().stream()
                    .filter(event -> event.entityId() == 1L).findFirst().orElseThrow();
            assertEquals(Set.of("status", "name"), first.changedFields());
        }
    }

    @Nested
    @DisplayName("cascade")
    class Cascade {

        @Test
        @DisplayName("a write outside a rule run starts a fresh cascade")
        void aPersonsChangeIsARoot() {
            record(103L, ChangeType.UPDATED, "status");
            commit();

            assertEquals(0, events().get(0).depth());
        }

        @Test
        @DisplayName("a write caused by a rule stays in that rule's cascade, one level deeper")
        void aRulesOwnWriteIsAChild() {
            // Without this, a rule that writes what it also reacts to would announce its own
            // write as a brand new cascade at depth 0: the depth limit would never be reached
            // and "already ran in this cascade" would never recognise the repeat, so the rule
            // would trigger itself forever.
            EntityChangedEvent running = EntityChangedEvent.root(ChangeType.UPDATED,
                    EntityType.ASSET, 103L, 9L, Set.of("status"), 7L);
            CascadeContext.enter(running);

            record(103L, ChangeType.UPDATED, "name");
            commit();

            EntityChangedEvent caused = events().get(0);
            assertEquals(running.correlationId(), caused.correlationId(),
                    "the same cascade, so the loop guard can recognise a repeat");
            assertEquals(1, caused.depth());
            assertEquals(7L, caused.actorUserId(),
                    "the person who started the cascade stays the actor of what it causes");
        }

        @Test
        @DisplayName("the cascade is not inherited once the rule run is over")
        void doesNotLeakToTheNextChange() {
            EntityChangedEvent running = EntityChangedEvent.root(ChangeType.UPDATED,
                    EntityType.ASSET, 103L, 9L, Set.of("status"), 7L);
            CascadeContext.enter(running);
            CascadeContext.exit();

            record(103L, ChangeType.UPDATED, "name");
            commit();

            assertEquals(0, events().get(0).depth());
            assertNotEquals(running.correlationId(), events().get(0).correlationId());
        }
    }
}
