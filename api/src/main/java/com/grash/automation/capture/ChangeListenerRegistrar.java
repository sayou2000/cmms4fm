package com.grash.automation.capture;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.internal.SessionFactoryImpl;
import org.springframework.stereotype.Component;

/**
 * Registers {@link AutomationChangeListener} with Hibernate.
 *
 * <p>Done here, from Spring, rather than through Hibernate's own {@code Integrator} and a
 * {@code META-INF/services} file. The listener needs an injected collaborator, and an integrator
 * is instantiated by Hibernate outside the Spring context, so it would have to reach back into
 * the container through a static holder. One {@code @PostConstruct} that unwraps the session
 * factory is the smaller price.
 *
 * <p>{@code appendListeners} rather than {@code setListeners}: Envers is enabled in this
 * application and registers its own listeners on the same event types. Replacing the list would
 * silently switch off the audit trail.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChangeListenerRegistrar {

    private final EntityManagerFactory entityManagerFactory;
    private final AutomationChangeListener listener;

    @PostConstruct
    void register() {
        EventListenerRegistry registry = entityManagerFactory
                .unwrap(SessionFactoryImpl.class)
                .getServiceRegistry()
                .requireService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_UPDATE, listener);
        registry.appendListeners(EventType.POST_INSERT, listener);
        log.info("Automation change capture registered for {} entity types",
                TrackedEntities.all().size());
    }
}
