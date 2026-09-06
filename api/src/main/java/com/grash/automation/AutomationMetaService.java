package com.grash.automation;

import com.grash.automation.action.ActionHandler;
import com.grash.automation.capture.TrackedEntities;
import com.grash.automation.action.ActionParameters;
import com.grash.automation.dto.AutomationMetaDTO;
import com.grash.automation.event.ChangeType;
import com.grash.automation.event.EntityType;
import com.grash.automation.eval.OperandDescriptor;
import com.grash.automation.eval.OperandResolver;
import com.grash.model.Company;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Assembles the editor's metadata by asking the components themselves.
 *
 * <p>Nothing here enumerates subjects or actions: the resolvers and handlers Spring found are the
 * answer. Adding a resolver therefore adds a condition to the editor, and adding a handler adds
 * an action, with no second list to update — which is the whole reason this layer exists.
 */
@Service
@RequiredArgsConstructor
public class AutomationMetaService {

    private final List<OperandResolver> resolvers;
    private final List<ActionHandler> handlers;

    @Value("${automation.enabled:false}")
    private boolean engineEnabled;

    /**
     * The change types a column diff can reveal by itself.
     *
     * <p>Every entity in {@link TrackedEntities} has both of these, because Hibernate reports
     * every insert and every update of it. What is left over is the semantic half — whether an
     * update <em>means</em> "approved" or "closed" — which only the service performing it knows
     * and which therefore still needs a hand-written publish point.
     *
     * <p>Those two constants are the whole of what used to be a hand-maintained list of every
     * live trigger combination. Forgetting to extend that list left a working trigger greyed out
     * in the editor with nothing to explain why.
     */
    private static final Set<ChangeType> DERIVED_FROM_DIFF =
            Set.of(ChangeType.CREATED, ChangeType.UPDATED);

    /**
     * Semantic triggers with a publish point today, as {@code ENTITY_TYPE:CHANGE_TYPE}.
     *
     * <p>These are the ones no column diff can produce, because they are a reading of a change
     * rather than a property of it. Each entry here has a matching
     * {@code SemanticEventPublisher.publish(...)} call in the service that performs it, and the
     * pair is what the editor's "not yet available" marker depends on — a trigger named here
     * without a publisher is a trigger the user can configure and never see fire.
     *
     * <p>Publish points, in the same order: {@code RequestService.approve} and {@code cancel},
     * {@code WorkOrderService.changeStatus} on the transition into COMPLETE, and
     * {@code PurchaseOrderController.respond}.
     */
    private static final Set<String> LIVE_SEMANTIC_TRIGGERS = Set.of(
            "REQUEST:APPROVED",
            "REQUEST:REJECTED",
            "WORK_ORDER:CLOSED",
            "PURCHASE_ORDER:APPROVED",
            "PURCHASE_ORDER:REJECTED");

    @Transactional(readOnly = true)
    public AutomationMetaDTO describe(Company company) {
        List<OperandDescriptor> subjects = new ArrayList<>();
        for (OperandResolver resolver : resolvers) {
            subjects.addAll(resolver.describe(company));
        }

        return new AutomationMetaDTO(
                engineEnabled,
                // Triggers after the subjects, and from them: the field filter of a trigger may
                // only offer names the diff can really report, and those are exactly the native
                // fields the resolvers just described.
                allTriggers(subjects),
                subjects,
                handlers.stream().map(ActionHandler::descriptor).toList(),
                ActionParameters.PLACEHOLDERS.keySet().stream().sorted()
                        .map(name -> "${" + name + "}").toList());
    }

    /**
     * Every combination, each marked live or not, and for a live update trigger the fields its
     * diff can report.
     *
     * <p>The dead combinations are reported rather than omitted so the editor can say "not yet
     * available" instead of leaving the user to guess why the trigger they expected is missing.
     */
    private List<AutomationMetaDTO.Trigger> allTriggers(List<OperandDescriptor> subjects) {
        Set<EntityType> watched = Set.copyOf(TrackedEntities.all().values());
        List<AutomationMetaDTO.Trigger> triggers = new ArrayList<>();
        for (EntityType entityType : EntityType.values()) {
            for (ChangeType changeType : ChangeType.values()) {
                // Both sources, not one or the other. Written as a ternary this read "a watched
                // entity has exactly the derived triggers", which quietly made every semantic
                // trigger dead: all five entity types are watched, so the second branch was
                // unreachable and the list below could never take effect.
                boolean live = (watched.contains(entityType) && DERIVED_FROM_DIFF.contains(changeType))
                        || LIVE_SEMANTIC_TRIGGERS.contains(entityType + ":" + changeType);
                triggers.add(new AutomationMetaDTO.Trigger(entityType, changeType, live,
                        live && changeType == ChangeType.UPDATED
                                ? changeableFieldsOf(entityType, subjects) : List.of()));
            }
        }
        return triggers;
    }

    /**
     * The field names an UPDATE of this entity can report.
     *
     * <p>Taken from the subjects, so the filter can only offer names the diff really produces —
     * offering one it does not is a filter that silently matches nothing, which is the failure
     * this endpoint exists to prevent. Custom fields are excluded for that very reason: they are
     * rows in another table, not columns of the entity, so no UPDATE of the entity ever names
     * one.
     */
    private List<String> changeableFieldsOf(EntityType entityType, List<OperandDescriptor> subjects) {
        String prefix = TrackedEntities.prefixOf(entityType) + ".";
        return subjects.stream()
                .filter(operand -> operand.customFieldId() == null)
                .map(OperandDescriptor::subject)
                .filter(subject -> subject.startsWith(prefix))
                .map(subject -> subject.substring(prefix.length()))
                .sorted()
                .toList();
    }
}
