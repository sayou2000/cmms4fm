package com.grash.automation;

import com.grash.automation.action.ActionDescriptor;
import com.grash.automation.action.ActionHandler;
import com.grash.automation.capture.TrackedEntities;
import com.grash.automation.dto.AutomationMetaDTO;
import com.grash.automation.event.ChangeType;
import com.grash.automation.event.EntityType;
import com.grash.automation.eval.ExecutionContext;
import com.grash.automation.eval.OperandDescriptor;
import com.grash.automation.eval.OperandResolver;
import com.grash.automation.model.ActionType;
import com.grash.automation.model.AutomationActionStep;
import com.grash.automation.model.AutomationCondition;
import com.grash.automation.model.ConditionOperator;
import com.grash.model.Company;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's metadata is not a document someone maintains — it is the registered resolvers and
 * handlers, read back. These tests pin that property, because the moment the endpoint starts
 * carrying a list of its own it has reintroduced the defect it was built to remove.
 */
class AutomationMetaServiceTest {

    private static class OneSubjectResolver implements OperandResolver {
        @Override
        public boolean supports(String subject) {
            return "asset.status".equals(subject);
        }

        @Override
        public List<OperandDescriptor> describe(Company company) {
            return List.of(OperandDescriptor.native_(EntityType.ASSET, "asset.status", "ENUM",
                    List.of(ConditionOperator.IS), List.of("DOWN")));
        }

        @Override
        public Object resolve(AutomationCondition condition, ExecutionContext context) {
            return null;
        }
    }

    private static class OneActionHandler implements ActionHandler {
        @Override
        public ActionType getType() {
            return ActionType.NOTIFY;
        }

        @Override
        public ActionDescriptor descriptor() {
            return new ActionDescriptor(ActionType.NOTIFY, "automation_action_notify",
                    List.of(ActionDescriptor.Parameter.text("message", true)));
        }

        @Override
        public void execute(AutomationActionStep step, ExecutionContext context) {
        }
    }

    private AutomationMetaDTO describe(boolean engineEnabled) {
        AutomationMetaService service = new AutomationMetaService(
                List.of(new OneSubjectResolver()), List.of(new OneActionHandler()));
        ReflectionTestUtils.setField(service, "engineEnabled", engineEnabled);
        return service.describe(new Company());
    }

    @Nested
    @DisplayName("the vocabulary")
    class Vocabulary {

        @Test
        @DisplayName("is exactly what the registered components report, and nothing besides")
        void comesFromTheComponents() {
            AutomationMetaDTO meta = describe(true);

            assertEquals(List.of("asset.status"), meta.subjects().stream()
                    .map(OperandDescriptor::subject).toList());
            assertEquals(List.of(ActionType.NOTIFY), meta.actions().stream()
                    .map(ActionDescriptor::type).toList());
            // CREATE_WORK_ORDER exists as an enum value but has no handler in this setup, which
            // is what a half-deployed engine looks like. It must not be offered.
            assertFalse(meta.actions().stream().anyMatch(action -> action.type() == ActionType.CREATE_WORK_ORDER));
        }

        @Test
        @DisplayName("names every placeholder a text parameter may use")
        void listsPlaceholders() {
            assertTrue(describe(true).placeholders().contains("${trigger.asset.id}"),
                    "the one placeholder every asset rule needs");
        }
    }

    @Nested
    @DisplayName("triggers")
    class Triggers {

        @Test
        @DisplayName("cover every combination, so the editor never silently omits one")
        void areComplete() {
            assertEquals(EntityType.values().length * ChangeType.values().length,
                    describe(true).triggers().size());
        }

        @Test
        @DisplayName("are live for every watched entity, without a list saying so")
        void areDerivedFromTheWatchedEntities() {
            List<AutomationMetaDTO.Trigger> live = describe(true).triggers().stream()
                    .filter(AutomationMetaDTO.Trigger::live).toList();

            // Creation and field change for each watched class, and nothing else — Hibernate
            // reports every insert and every update, so no per-entity wiring decides this.
            // If this number moves, TrackedEntities changed, which is the intended coupling.
            assertEquals(TrackedEntities.all().size() * 2, live.size());
            assertTrue(live.stream().allMatch(trigger ->
                            trigger.changeType() == ChangeType.CREATED
                                    || trigger.changeType() == ChangeType.UPDATED),
                    "only the two change types a column diff can reveal are automatic");
            assertTrue(live.stream().anyMatch(trigger -> trigger.entityType() == EntityType.ASSET
                    && trigger.changeType() == ChangeType.UPDATED));
        }

        @Test
        @DisplayName("a semantic change type stays unavailable until something publishes it")
        void semanticChangeTypesAreNotAutomatic() {
            // "Approved" is not visible in a column diff — only the service performing it knows
            // that is what the update meant. Reported as not live rather than hidden, so a rule
            // is refused rather than silently never firing.
            assertTrue(describe(true).triggers().stream()
                    .filter(trigger -> trigger.changeType() == ChangeType.APPROVED)
                    .noneMatch(AutomationMetaDTO.Trigger::live));
        }

        @Test
        @DisplayName("an update trigger offers only field names its diff can report")
        void fieldFilterMatchesTheDiff() {
            AutomationMetaDTO.Trigger assetUpdated = describe(true).triggers().stream()
                    .filter(trigger -> trigger.entityType() == EntityType.ASSET
                            && trigger.changeType() == ChangeType.UPDATED)
                    .findFirst()
                    .orElseThrow();

            // The stub resolver describes exactly one asset field, so that is the only name the
            // filter may offer. A name the diff never produces would be a filter that matches
            // nothing — the failure this endpoint exists to prevent.
            assertEquals(List.of("status"), assetUpdated.changedFields());
        }

        @Test
        @DisplayName("a creation trigger offers no field filter at all")
        void creationHasNoFieldFilter() {
            assertTrue(describe(true).triggers().stream()
                    .filter(trigger -> trigger.changeType() == ChangeType.CREATED)
                    .allMatch(trigger -> trigger.changedFields().isEmpty()),
                    "every field of a new record is new, so filtering on one says nothing");
        }
    }

    @Test
    @DisplayName("the engine's on/off state travels with the metadata")
    void reportsWhetherTheEngineIsOn() {
        // Without this the editor cannot tell a rule that never matched from a rule that was
        // never reached, and AUTOMATION_ENABLED defaults to false.
        assertFalse(describe(false).engineEnabled());
        assertTrue(describe(true).engineEnabled());
    }
}
