package com.grash.automation.model;

/**
 * How a condition compares its operand to its value.
 *
 * <p>This enum lists exactly what the evaluator implements, and nothing else. The old engine did
 * the opposite: {@code TITLE_CONTAINS} sat in its enums and in its settings form for years while
 * no switch handled it, so it fell through to {@code default: return false} and silently
 * disabled every rule that used it. An operator that exists here is one that works.
 */
public enum ConditionOperator {
    /** Equal, compared as text after both sides are stringified. */
    IS,
    IS_NOT,
    /** Case-sensitive substring. */
    CONTAINS,
    /**
     * The operand changed <em>to</em> this value in the change that triggered the rule. Needs
     * the event's field diff, so it only means anything for {@link com.grash.automation.event.ChangeType#UPDATED}.
     */
    CHANGED_TO,

    /**
     * Numeric comparisons. They exist because the generic resolver offers every numeric column
     * of an entity, and "quantity is exactly 5" is almost never the question worth asking —
     * "below the minimum" is.
     *
     * <p>Both sides are compared as numbers, not as text. A value that is not a number at all is
     * an error rather than a false: comparing "abc" to 5 is a broken rule, and answering "does
     * not hold" would hide it for as long as the rule exists. A value that is simply
     * <em>missing</em> is a different case and satisfies no comparison.
     */
    LT,
    LTE,
    GT,
    GTE,

    /**
     * Whether the operand has a value at all. Needed as its own operator because {@code IS} with
     * an empty expected value is ambiguous — an empty text field and an unset field are
     * different things, and only one of them is usually meant.
     */
    IS_SET,
    IS_NOT_SET
}
