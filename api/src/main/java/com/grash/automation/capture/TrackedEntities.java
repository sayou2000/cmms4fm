package com.grash.automation.capture;

import com.grash.automation.event.EntityType;
import com.grash.model.Asset;
import com.grash.model.Part;
import com.grash.model.PurchaseOrder;
import com.grash.model.Request;
import com.grash.model.WorkOrder;
import org.hibernate.Hibernate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which entity classes the engine watches, and under which {@link EntityType}.
 *
 * <p>This one map replaces what used to be a publish point per service. Adding an entity here
 * makes its creations and its field changes available as triggers, with a complete field diff,
 * because {@link AutomationChangeListener} asks Hibernate what actually changed instead of a
 * hand-written comparison. The concept document's trap F2 — a diff computed in
 * {@code AssetService.update} that cannot see a status written afterwards by
 * {@code triggerDownTime} — cannot occur through this path at all: the diff is the set of
 * columns the UPDATE statement really touched.
 *
 * <p>What is <b>not</b> covered, and stays hand-published: the semantic change types. Whether an
 * update means "approved" or "closed" is not visible in a column diff, so
 * {@code ChangeType.APPROVED} and friends still come from the service that knows.
 *
 * <p>Deletions are deliberately absent. The engine loads the trigger entity fresh by id, and for
 * a deleted row there is nothing to load.
 */
public final class TrackedEntities {

    /**
     * Ordered so that {@code /automation-rules/meta} lists subjects in a stable, sensible order
     * rather than whatever the map's hash gives.
     */
    private static final Map<Class<?>, EntityType> TRACKED = new LinkedHashMap<>();

    static {
        TRACKED.put(Asset.class, EntityType.ASSET);
        TRACKED.put(WorkOrder.class, EntityType.WORK_ORDER);
        TRACKED.put(Request.class, EntityType.REQUEST);
        TRACKED.put(Part.class, EntityType.PART);
        TRACKED.put(PurchaseOrder.class, EntityType.PURCHASE_ORDER);
    }

    private TrackedEntities() {
    }

    public static Map<Class<?>, EntityType> all() {
        return Map.copyOf(TRACKED);
    }

    /** Insertion order, which is the order the editor shows the subjects in. */
    public static Map<Class<?>, EntityType> ordered() {
        return java.util.Collections.unmodifiableMap(TRACKED);
    }

    /**
     * The type for an entity instance, or empty when it is not watched.
     *
     * <p>Goes through {@link Hibernate#getClass} because what a Hibernate event hands over may be
     * a proxy subclass, whose {@code getClass()} is not the mapped class and would never match.
     */
    public static Optional<EntityType> of(Object entity) {
        if (entity == null) {
            return Optional.empty();
        }
        Class<?> mapped = Hibernate.getClass(entity);
        return Optional.ofNullable(TRACKED.get(mapped));
    }

    /**
     * The prefix a condition subject uses for this type: {@code ASSET} → {@code asset},
     * {@code WORK_ORDER} → {@code workOrder}.
     *
     * <p>Derived rather than listed, so a new entity type cannot end up with a prefix in the
     * metadata that differs from the one the resolver accepts. {@code asset} keeps working for
     * every rule configured before this existed, which is why the conversion has to produce
     * exactly that.
     */
    public static String prefixOf(EntityType entityType) {
        String[] parts = entityType.name().toLowerCase().split("_");
        StringBuilder prefix = new StringBuilder(parts[0]);
        for (int index = 1; index < parts.length; index++) {
            prefix.append(Character.toUpperCase(parts[index].charAt(0)))
                    .append(parts[index].substring(1));
        }
        return prefix.toString();
    }
}
