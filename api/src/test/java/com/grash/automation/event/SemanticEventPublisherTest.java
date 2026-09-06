package com.grash.automation.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The publish point for the change types no column diff can produce.
 */
@ExtendWith(MockitoExtension.class)
class SemanticEventPublisherTest {

    @InjectMocks
    private SemanticEventPublisher semanticEventPublisher;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("a semantic change is published as a root event carrying the actor")
    void publishesARootEvent() {
        TransactionSynchronizationManager.initSynchronization();

        semanticEventPublisher.publish(ChangeType.APPROVED, EntityType.REQUEST, 10L, 1L, 5L);

        ArgumentCaptor<EntityChangedEvent> published = ArgumentCaptor.forClass(EntityChangedEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        EntityChangedEvent event = published.getValue();
        assertEquals(ChangeType.APPROVED, event.changeType());
        assertEquals(EntityType.REQUEST, event.entityType());
        assertEquals(10L, event.entityId());
        assertEquals(1L, event.companyId());
        // The actor is the whole reason the service passes it rather than the listener reading
        // it: there is no security context on the executor thread.
        assertEquals(5L, event.actorUserId());
        assertEquals(0, event.depth());
        assertNotNull(event.correlationId());
        assertTrue(event.changedFields().isEmpty(), "a semantic change is not a field diff");
    }

    @Test
    @DisplayName("an event without a tenant is dropped rather than published")
    void refusesAnEventWithoutACompany() {
        // The engine drops such an event and a consumer would have nothing to scope its work to.
        // Dropping it here at least leaves a warning that names the entity.
        TransactionSynchronizationManager.initSynchronization();

        semanticEventPublisher.publish(ChangeType.CLOSED, EntityType.WORK_ORDER, 10L, null, 5L);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("publishing outside a transaction still publishes, so the log is the only warning")
    void publishesOutsideATransactionAnyway() {
        // No transaction means no AFTER_COMMIT listener fires, which is a silent no-op. The
        // publisher logs an error and does not swallow the event on top of it: a listener with
        // fallbackExecution would still be entitled to it, and refusing here would hide a second
        // problem behind the first.
        semanticEventPublisher.publish(ChangeType.APPROVED, EntityType.REQUEST, 10L, 1L, 5L);

        verify(eventPublisher).publishEvent(any(EntityChangedEvent.class));
    }

    @Test
    @DisplayName("a domain event outside the rule vocabulary goes through the same check")
    void publishesAPlainDomainEvent() {
        TransactionSynchronizationManager.initSynchronization();
        Object domainEvent = new Object();

        semanticEventPublisher.publishDomainEvent(domainEvent);

        verify(eventPublisher).publishEvent(domainEvent);
    }
}
