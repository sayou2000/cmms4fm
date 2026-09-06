package com.grash.event.fanout;

import com.grash.automation.event.ChangeType;
import com.grash.automation.event.EntityChangedEvent;
import com.grash.automation.event.EntityType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The one place that says which side effects follow which domain event.
 *
 * <p>Everything here used to sit inside the transaction that caused it. Four properties make this
 * a listener instead, and each of them was a real defect before:
 *
 * <ul>
 *   <li><b>After commit.</b> The side effects are {@code @Async} — {@code dispatchWebhook} and
 *       {@code NotificationService} both are — so they used to leave the caller's thread while
 *       its transaction was still open, and could read or announce a row that was not there yet.
 *       {@code AFTER_COMMIT} removes that race.</li>
 *   <li><b>Off the caller thread</b>, and on {@link FanoutAsyncConfig#EXECUTOR} rather than the
 *       shared pool, so an approval never queues behind a CSV export.</li>
 *   <li><b>Failure is contained.</b> Whatever happens here, the approval, rejection or completion
 *       has already been committed and stays committed. A failing mail template used to be able
 *       to roll back the decision it was reporting.</li>
 *   <li><b>Adding a reaction adds a listener.</b> The reason the case for this work called the
 *       old shape a structural weakness: every new escalation, report or analysis would otherwise
 *       have had to be edited into someone else's transaction.</li>
 * </ul>
 *
 * <p>The methods are thin on purpose. {@code @Async} and {@code @Transactional} on one method
 * leaves the order of the two advices to configuration, so the transaction belongs to the
 * handler being called, exactly as {@code AutomationListener} hands off to the engine.
 *
 * <p>This listens to the same {@link EntityChangedEvent} the rule engine does, and that is the
 * point of E2: one semantic event, several independent consumers. Note the asymmetry, because it
 * decides where new code goes — the rule engine is behind {@code automation.enabled} and off by
 * default, while these consumers replace behaviour that always ran and are therefore always on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FanoutListener {

    private final RequestFanout requestFanout;
    private final WorkOrderFanout workOrderFanout;
    private final MeterTriggerFanout meterTriggerFanout;

    @Async(FanoutAsyncConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEntityChanged(EntityChangedEvent event) {
        if (event.entityType() == EntityType.REQUEST && event.changeType() == ChangeType.APPROVED) {
            run(event, () -> requestFanout.onApproved(event.entityId(), event.actorUserId()));
        } else if (event.entityType() == EntityType.REQUEST && event.changeType() == ChangeType.REJECTED) {
            run(event, () -> requestFanout.onRejected(event.entityId(), event.actorUserId()));
        } else if (event.entityType() == EntityType.WORK_ORDER && event.changeType() == ChangeType.CLOSED) {
            run(event, () -> workOrderFanout.onClosed(event.entityId(), event.actorUserId()));
        }
        // Purchase order approvals and rejections are published for the rule engine and have no
        // fan-out of their own: the restocking they cause is a domain write and stays in the
        // transaction that decides it.
    }

    @Async(FanoutAsyncConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWorkOrderStatusChanged(WorkOrderStatusChanged event) {
        try {
            workOrderFanout.onStatusChanged(event);
        } catch (Exception exception) {
            log.warn("Requester update failed for work order {}; the status change itself is unaffected",
                    event.workOrderId(), exception);
        }
    }

    @Async(FanoutAsyncConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReadingRecorded(ReadingRecorded event) {
        try {
            meterTriggerFanout.onReadingRecorded(event);
        } catch (Exception exception) {
            log.warn("Meter trigger check failed for reading {}; the reading itself is unaffected",
                    event.readingId(), exception);
        }
    }

    private void run(EntityChangedEvent event, Runnable fanout) {
        try {
            fanout.run();
        } catch (Exception exception) {
            log.warn("Fan-out failed for {} {} {}; the change itself is unaffected",
                    event.entityType(), event.changeType(), event.entityId(), exception);
        }
    }
}
