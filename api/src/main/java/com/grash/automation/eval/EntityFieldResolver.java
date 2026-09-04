package com.grash.automation.eval;

import com.grash.automation.capture.TrackedEntities;
import com.grash.automation.event.EntityType;
import com.grash.automation.model.AutomationCondition;
import com.grash.automation.model.ConditionOperator;
import com.grash.exception.CustomException;
import com.grash.model.Company;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.SingularAttribute;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Reads any field of a watched entity, described from the JPA metamodel.
 *
 * <p>This replaces the hand-written {@code AssetResolver}, which offered five of the asset's
 * thirty-odd columns. Which five was an accident of what the first use case happened to need, and
 * every further field — serial number, warranty date, acquisition cost — would have been another
 * entry in a list plus another translation key, for every entity type separately. The metamodel
 * already knows the fields, their Java types and which of them are associations, so the editor
 * can be told all of it without anyone maintaining the answer.
 *
 * <p>The subject paths it produces are the same ones the old resolver used —
 * {@code asset.status}, {@code asset.name}, {@code asset.category} — so rules configured before
 * this existed keep working unchanged. That is not luck: {@link TrackedEntities#prefixOf} is
 * written to produce exactly {@code asset}, and the suffix is the JPA attribute name, which is
 * what those subjects were already spelled with.
 *
 * <p><b>What it deliberately does not offer:</b> to-many associations (a rule asking about "the
 * parts" needs a quantifier, which the condition model has no way to express), the audit columns,
 * the surrogate id, and the collections that hold attachments or custom field values. Custom
 * fields are {@link CustomFieldResolver}'s job because they are rows, not columns.
 */
@Slf4j
@Component
public class EntityFieldResolver implements OperandResolver {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Attribute names no rule should be built on.
     *
     * <p>The audit columns are excluded because they are the engine's own bookkeeping and would
     * invite rules that depend on it; {@code id} because a rule pinned to one row is what the
     * conditions on real fields are meant to replace; and the attachment and custom-field
     * collections because they are to-many and covered elsewhere.
     */
    private static final Set<String> EXCLUDED = Set.of(
            "id", "company", "createdAt", "updatedAt", "createdBy", "updatedBy",
            "customFieldValues", "files", "image", "isDemo");

    @Override
    public boolean supports(String subject) {
        return findAttribute(subject) != null;
    }

    @Override
    public List<OperandDescriptor> describe(Company company) {
        List<OperandDescriptor> operands = new ArrayList<>();
        TrackedEntities.ordered().forEach((entityClass, entityType) -> {
            String prefix = TrackedEntities.prefixOf(entityType);
            metamodelOf(entityClass).getSingularAttributes().stream()
                    // Alphabetical, because the metamodel's own order is unspecified and a
                    // dropdown that reshuffles between deployments is unusable.
                    .sorted(Comparator.comparing(Attribute::getName))
                    .filter(attribute -> !EXCLUDED.contains(attribute.getName()))
                    .map(attribute -> describeAttribute(entityType, prefix, attribute))
                    .filter(java.util.Objects::nonNull)
                    .forEach(operands::add);
        });
        return operands;
    }

    @Override
    public Object resolve(AutomationCondition condition, ExecutionContext context) {
        SingularAttribute<?, ?> attribute = findAttribute(condition.getSubject());
        if (attribute == null) {
            throw new CustomException("No field \"" + condition.getSubject() + "\" on any watched "
                    + "entity", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Object entity = context.getTriggerEntity();
        if (entity == null || !attribute.getDeclaringType().getJavaType()
                .isAssignableFrom(Hibernate.getClass(entity))) {
            // The rule names a field of another entity type than the one that triggered it. Not
            // an error: a rule may be edited to a different trigger and keep a stale condition,
            // and "no value" makes it not hold rather than break.
            return null;
        }
        return context.cached(condition.getSubject(), () -> readValue(entity, attribute));
    }

    /**
     * The value, as a comparable scalar.
     *
     * <p>An association yields its <b>id</b>, not the object. A rule points at a category, and
     * renaming that category must not change which assets the rule matches. A date yields its
     * epoch milliseconds so that the numeric operators work on it without a second code path.
     */
    private Object readValue(Object entity, SingularAttribute<?, ?> attribute) {
        Object value = readMember(entity, attribute);
        if (value == null) {
            return null;
        }
        if (attribute.isAssociation()) {
            return idOf(value);
        }
        if (value instanceof Date date) {
            return date.getTime();
        }
        if (value instanceof Enum<?> constant) {
            return constant.name();
        }
        return value;
    }

    private Object readMember(Object entity, SingularAttribute<?, ?> attribute) {
        Member member = attribute.getJavaMember();
        try {
            if (member instanceof Method method) {
                method.setAccessible(true);
                return method.invoke(entity);
            }
            Field field = (Field) member;
            field.setAccessible(true);
            return field.get(entity);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new CustomException("Could not read \"" + attribute.getName() + "\" from "
                    + Hibernate.getClass(entity).getSimpleName() + ": " + exception.getMessage(),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private Object idOf(Object associated) {
        try {
            Method getId = Hibernate.getClass(associated).getMethod("getId");
            return getId.invoke(associated);
        } catch (ReflectiveOperationException exception) {
            log.debug("Association {} has no getId()", associated.getClass());
            return null;
        }
    }

    /**
     * One operand, or null for an attribute the condition model cannot express.
     *
     * <p>Note what each value type gets for operators, because that is where the honesty of the
     * whole editor lives: an operator is offered only where it can actually decide something.
     * A date, in particular, gets only "is set" — comparing it to a literal timestamp would be a
     * question nobody asks, and "older than 3 days" is a time trigger, not a condition.
     */
    private OperandDescriptor describeAttribute(EntityType entityType, String prefix,
                                                SingularAttribute<?, ?> attribute) {
        String subject = prefix + "." + attribute.getName();
        Class<?> type = attribute.getJavaType();

        if (attribute.isAssociation()) {
            return OperandDescriptor.native_(entityType, subject, "ENTITY_" + entityValueType(type),
                    List.of(ConditionOperator.IS, ConditionOperator.IS_NOT, ConditionOperator.CHANGED_TO,
                            ConditionOperator.IS_SET, ConditionOperator.IS_NOT_SET),
                    List.of());
        }
        if (type.isEnum()) {
            return OperandDescriptor.native_(entityType, subject, "ENUM",
                    List.of(ConditionOperator.IS, ConditionOperator.IS_NOT, ConditionOperator.CHANGED_TO),
                    Arrays.stream(type.getEnumConstants()).map(constant -> ((Enum<?>) constant).name())
                            .toList());
        }
        if (type == boolean.class || type == Boolean.class) {
            return OperandDescriptor.native_(entityType, subject, "BOOLEAN",
                    List.of(ConditionOperator.IS, ConditionOperator.CHANGED_TO),
                    List.of("true", "false"));
        }
        if (Number.class.isAssignableFrom(type) || type.isPrimitive() && type != char.class) {
            return OperandDescriptor.native_(entityType, subject, "NUMBER",
                    List.of(ConditionOperator.IS, ConditionOperator.IS_NOT, ConditionOperator.LT,
                            ConditionOperator.LTE, ConditionOperator.GT, ConditionOperator.GTE,
                            ConditionOperator.IS_SET, ConditionOperator.IS_NOT_SET),
                    List.of());
        }
        if (Date.class.isAssignableFrom(type)) {
            return OperandDescriptor.native_(entityType, subject, "DATE",
                    List.of(ConditionOperator.IS_SET, ConditionOperator.IS_NOT_SET,
                            ConditionOperator.CHANGED_TO),
                    List.of());
        }
        if (CharSequence.class.isAssignableFrom(type)) {
            return OperandDescriptor.native_(entityType, subject, "TEXT",
                    List.of(ConditionOperator.IS, ConditionOperator.IS_NOT, ConditionOperator.CONTAINS,
                            ConditionOperator.IS_SET, ConditionOperator.IS_NOT_SET,
                            ConditionOperator.CHANGED_TO),
                    List.of());
        }
        // An embeddable, a byte array, something exotic. Silently skipped rather than offered as
        // text: a condition on it would compare a toString(), which is never what was meant.
        return null;
    }

    /**
     * The entity value type the editor uses to pick a picker, e.g. {@code AssetCategory} becomes
     * {@code ASSET_CATEGORY}. A type the frontend has no picker for falls back to a plain id
     * field, which is why an unrecognised name is not a problem here.
     */
    private String entityValueType(Class<?> type) {
        return type.getSimpleName()
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toUpperCase();
    }

    /** The attribute a subject names, or null when no watched entity has such a field. */
    private SingularAttribute<?, ?> findAttribute(String subject) {
        if (subject == null || !subject.contains(".")) {
            return null;
        }
        String prefix = subject.substring(0, subject.indexOf('.'));
        String name = subject.substring(subject.indexOf('.') + 1);
        for (var entry : TrackedEntities.ordered().entrySet()) {
            if (!TrackedEntities.prefixOf(entry.getValue()).equals(prefix)) {
                continue;
            }
            if (EXCLUDED.contains(name)) {
                return null;
            }
            try {
                return metamodelOf(entry.getKey()).getSingularAttribute(name);
            } catch (IllegalArgumentException notThere) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private <T> jakarta.persistence.metamodel.EntityType<T> metamodelOf(Class<?> entityClass) {
        return (jakarta.persistence.metamodel.EntityType<T>)
                entityManager.getMetamodel().entity(entityClass);
    }
}
