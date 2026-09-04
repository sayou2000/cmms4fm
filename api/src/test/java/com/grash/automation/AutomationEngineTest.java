package com.grash.automation;

import com.grash.automation.event.ChangeType;
import com.grash.automation.event.EntityChangedEvent;
import com.grash.automation.event.EntityType;
import com.grash.automation.model.AutomationRule;
import com.grash.automation.repository.AutomationRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Which rules an event wakes. Reported as a bug: a rule with the field filter set to
 * {@code status} created its work order, the same rule with an empty filter did not — so the
 * suspicion was that "any change" is broken. These tests pin the engine's half of that question.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutomationEngineTest {

    @Mock
    private AutomationRuleRepository ruleRepository;
    @Mock
    private AutomationRuleRunner runner;
    @Mock
    private AutomationRunService runService;

    private AutomationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new AutomationEngine(ruleRepository, runner, runService);
    }

    private AutomationRule ruleFilteredOn(String... fields) {
        AutomationRule rule = new AutomationRule();
        rule.setId(1L);
        rule.setTriggerChangeType(ChangeType.UPDATED);
        rule.setTriggerEntityType(EntityType.ASSET);
        rule.setTriggerChangedFields(new HashSet<>(Set.of(fields)));
        return rule;
    }

    private EntityChangedEvent statusChange() {
        return EntityChangedEvent.root(ChangeType.UPDATED, EntityType.ASSET, 103L, 9L,
                Set.of("status"), null);
    }

    private void repositoryReturns(AutomationRule rule) {
        when(ruleRepository.findByCompany_IdAndTriggerChangeTypeAndTriggerEntityTypeAndEnabledTrue(
                any(), any(), any())).thenReturn(List.of(rule));
    }

    @Test
    @DisplayName("an empty field filter matches, so it really does mean 'any change'")
    void emptyFilterMatchesEverything() {
        repositoryReturns(ruleFilteredOn());

        assertEquals(1, engine.candidates(statusChange()).size(),
                "an empty filter must not narrow anything away");
    }

    @Test
    void aMatchingFieldMatches() {
        repositoryReturns(ruleFilteredOn("status"));

        assertEquals(1, engine.candidates(statusChange()).size());
    }

    @Test
    @DisplayName("a filter on another field does not match")
    void aDifferentFieldDoesNotMatch() {
        repositoryReturns(ruleFilteredOn("name"));

        assertEquals(0, engine.candidates(statusChange()).size());
    }

    @Test
    @DisplayName("an event without a company wakes nothing, whatever the filter says")
    void anEventWithoutACompanyIsIgnored() {
        repositoryReturns(ruleFilteredOn());

        assertEquals(0, engine.candidates(
                EntityChangedEvent.root(ChangeType.UPDATED, EntityType.ASSET, 103L, null,
                        Set.of("status"), null)).size());
    }
}
