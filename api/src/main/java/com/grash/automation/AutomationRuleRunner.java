package com.grash.automation;

import com.grash.automation.action.ActionHandler;
import com.grash.automation.capture.CascadeContext;
import com.grash.automation.capture.TrackedEntities;
import com.grash.automation.event.EntityChangedEvent;
import com.grash.automation.eval.ExecutionContext;
import com.grash.automation.eval.RuleEvaluator;
import com.grash.automation.model.ActionType;
import com.grash.automation.model.AutomationActionStep;
import com.grash.automation.model.AutomationRule;
import com.grash.automation.repository.AutomationRuleRepository;
import com.grash.exception.CustomException;
import com.grash.service.CompanyService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one rule against one event, in its own transaction.
 *
 * <p>Own transaction so that one rule cannot take another down with it, and so that a rolled back
 * action leaves the run log — written elsewhere, on purpose — intact.
 */
@Slf4j
@Service
public class AutomationRuleRunner {

    private final AutomationRuleRepository ruleRepository;
    private final AutomationRunService runService;
    private final RuleEvaluator evaluator;
    private final CompanyService companyService;
    private final Map<ActionType, ActionHandler> handlers = new EnumMap<>(ActionType.class);

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${automation.max-depth:3}")
    private int defaultMaxDepth;

    public AutomationRuleRunner(AutomationRuleRepository ruleRepository,
                                AutomationRunService runService,
                                RuleEvaluator evaluator,
                                CompanyService companyService,
                                List<ActionHandler> actionHandlers) {
        this.ruleRepository = ruleRepository;
        this.runService = runService;
        this.evaluator = evaluator;
        this.companyService = companyService;
        actionHandlers.forEach(handler -> this.handlers.put(handler.getType(), handler));
    }

    /**
     * Marks the thread as belonging to this event's cascade for as long as the rule runs, then
     * hands off to {@link #execute}.
     *
     * <p>Everything the rule's actions write is announced by the change capture, on this same
     * thread, and has to land in <em>this</em> cascade rather than starting a new one — otherwise
     * a rule that writes what it also reacts to never stops. The {@code finally} is not
     * decoration: this executor pools its threads.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunOutcome run(Long ruleId, EntityChangedEvent event) {
        CascadeContext.enter(event);
        try {
            return execute(ruleId, event);
        } finally {
            CascadeContext.exit();
        }
    }

    private RunOutcome execute(Long ruleId, EntityChangedEvent event) {
        AutomationRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new CustomException("Rule " + ruleId + " disappeared", HttpStatus.NOT_FOUND));

        int maxDepth = rule.getMaxDepth() == null ? defaultMaxDepth : rule.getMaxDepth();
        if (event.depth() >= maxDepth) {
            return RunOutcome.skipped("Cascade depth " + event.depth() + " reached the limit of " + maxDepth);
        }
        if (runService.alreadyRanInThisCascade(ruleId, event)) {
            return RunOutcome.skipped("Already ran for this entity in cascade " + event.correlationId());
        }

        ExecutionContext context = new ExecutionContext(
                event,
                companyService.findById(event.companyId())
                        .orElseThrow(() -> new CustomException("Company " + event.companyId() + " not found",
                                HttpStatus.NOT_FOUND)),
                loadTriggerEntity(event));

        String unmet = evaluator.firstUnmetCondition(rule, context);
        if (unmet != null) {
            return RunOutcome.skipped("Condition not met: " + unmet);
        }

        int executed = 0;
        for (AutomationActionStep step : rule.getActions()) {
            try {
                handlerFor(step).execute(step, context);
                executed++;
            } catch (Exception exception) {
                String message = step.getActionType() + " failed: " + exception.getMessage();
                if (step.isAbortOnFailure()) {
                    return RunOutcome.failed(message, executed);
                }
                log.warn("Rule {} step {} failed but is configured to continue", ruleId,
                        step.getActionType(), exception);
            }
        }
        return RunOutcome.success(executed);
    }

    /**
     * Loaded fresh, by id, inside this transaction. The event carries no entity for exactly this
     * reason: by the time a listener runs, anything it was handed would be detached and possibly
     * stale.
     *
     * <p>Through the entity manager and {@link TrackedEntities}, not through a switch over the
     * services. The switch had a {@code default} that threw "not wired up yet" for five of the
     * six types, and every new trigger meant another case with another service injected here.
     * Since the watched classes are already declared in one place, the lookup can be derived from
     * that declaration, and a type that is watched is loadable by construction.
     */
    private Object loadTriggerEntity(EntityChangedEvent event) {
        Class<?> entityClass = TrackedEntities.ordered().entrySet().stream()
                .filter(entry -> entry.getValue() == event.entityType())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new CustomException("Trigger entity type " + event.entityType()
                        + " is not watched, so nothing can read it", HttpStatus.NOT_IMPLEMENTED));

        Object entity = entityManager.find(entityClass, event.entityId());
        if (entity == null) {
            // Not a silent skip: the run is recorded as FAILED with this reason. An entity that
            // vanished between the commit and the rule run is rare but real — a delete right
            // after an update — and a rule quietly doing nothing would be indistinguishable from
            // a broken condition.
            throw new CustomException(event.entityType() + " " + event.entityId()
                    + " no longer exists", HttpStatus.NOT_FOUND);
        }
        return entity;
    }

    private ActionHandler handlerFor(AutomationActionStep step) {
        ActionHandler handler = handlers.get(step.getActionType());
        if (handler == null) {
            throw new CustomException("No handler for action " + step.getActionType(),
                    HttpStatus.NOT_IMPLEMENTED);
        }
        return handler;
    }
}
