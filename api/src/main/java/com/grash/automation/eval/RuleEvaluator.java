package com.grash.automation.eval;

import com.grash.automation.model.AutomationCondition;
import com.grash.automation.model.AutomationRule;
import com.grash.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Decides whether a rule's conditions hold. All of them have to: the walking skeleton has one
 * implicit AND group, which is exactly what the old engine could express with its
 * {@code allMatch}. OR groups and nesting come with the condition tree in the next phase, and
 * the flat list is the degenerate case of it.
 *
 * <p>No Spring dependencies beyond the resolver list, and no database access of its own — the
 * resolvers do the reading. That is what makes it testable as a function.
 */
@Component
@RequiredArgsConstructor
public class RuleEvaluator {

    private final List<OperandResolver> resolvers;

    /**
     * @return why the rule did not match, or null when it did. A string rather than a boolean
     * because the run log has to be able to say which condition failed — "condition not met" is
     * the answer that made the old engine impossible to support.
     */
    public String firstUnmetCondition(AutomationRule rule, ExecutionContext context) {
        for (AutomationCondition condition : rule.getConditions()) {
            if (!holds(condition, context)) {
                return describe(condition);
            }
        }
        return null;
    }

    private boolean holds(AutomationCondition condition, ExecutionContext context) {
        Object actual = resolverFor(condition).resolve(condition, context);
        String expected = condition.getExpectedValue();

        return switch (condition.getOperator()) {
            case IS -> Objects.equals(asText(actual), expected);
            case IS_NOT -> !Objects.equals(asText(actual), expected);
            case CONTAINS -> actual != null && expected != null && asText(actual).contains(expected);
            case CHANGED_TO -> changedTo(condition, context, actual, expected);
            case IS_SET -> actual != null && !asText(actual).isBlank();
            case IS_NOT_SET -> actual == null || asText(actual).isBlank();
            case LT, LTE, GT, GTE -> comparesNumerically(condition, actual, expected);
        };
    }

    /**
     * Numeric comparison, with the two ways it can be meaningless kept apart.
     *
     * <p>A <b>missing</b> value is an ordinary answer — an asset with no purchase price is not a
     * broken rule — and it satisfies no comparison, not even {@code LTE}. Treating null as zero
     * is the tempting shortcut and it is how "warranty expired" would fire for every asset that
     * has no warranty date at all.
     *
     * <p>A value that is <b>not a number</b> is different: the rule asks a question the data
     * cannot answer, which is a configuration error and raised as one, so the run is recorded as
     * FAILED with the reason.
     */
    private boolean comparesNumerically(AutomationCondition condition, Object actual, String expected) {
        if (actual == null || asText(actual).isBlank()) {
            return false;
        }
        int comparison = asNumber(actual, condition, "value")
                .compareTo(asNumber(expected, condition, "comparison value"));
        return switch (condition.getOperator()) {
            case LT -> comparison < 0;
            case LTE -> comparison <= 0;
            case GT -> comparison > 0;
            case GTE -> comparison >= 0;
            default -> throw new IllegalStateException("not a comparison: " + condition.getOperator());
        };
    }

    private java.math.BigDecimal asNumber(Object value, AutomationCondition condition, String what) {
        try {
            return new java.math.BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException | NullPointerException exception) {
            throw new CustomException("Condition on \"" + condition.getSubject() + "\" compares "
                    + "numerically, but the " + what + " \"" + value + "\" is not a number",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    /**
     * "Changed to X" needs no snapshot of the old value: the event already says which fields
     * differ, so a field that is in that set and now equals X has changed to X. That is the whole
     * reason the event carries a diff.
     */
    private boolean changedTo(AutomationCondition condition, ExecutionContext context,
                              Object actual, String expected) {
        String field = fieldNameOf(condition.getSubject());
        return context.getEvent().changedFields().contains(field)
                && Objects.equals(asText(actual), expected);
    }

    /** {@code asset.status} names the field {@code status}. */
    private String fieldNameOf(String subject) {
        int lastDot = subject.lastIndexOf('.');
        return lastDot < 0 ? subject : subject.substring(lastDot + 1);
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private OperandResolver resolverFor(AutomationCondition condition) {
        return resolvers.stream()
                .filter(resolver -> resolver.supports(condition.getSubject()))
                .findFirst()
                // Loudly, not as a false. A subject nothing can read is a broken rule, and the
                // old engine's habit of answering "false" to that question is defect D7.
                .orElseThrow(() -> new CustomException(
                        "No resolver for condition subject \"" + condition.getSubject() + "\"",
                        HttpStatus.UNPROCESSABLE_ENTITY));
    }

    private String describe(AutomationCondition condition) {
        String field = condition.getCustomField() == null
                ? condition.getSubject()
                : condition.getSubject() + "(" + condition.getCustomField().getId() + ")";
        return field + " " + condition.getOperator() + " " + condition.getExpectedValue();
    }
}
