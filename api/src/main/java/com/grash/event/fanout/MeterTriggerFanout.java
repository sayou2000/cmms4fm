package com.grash.event.fanout;

import com.grash.dto.workOrder.WorkOrderPostDTO;
import com.grash.mapper.WorkOrderMapper;
import com.grash.model.Meter;
import com.grash.model.Notification;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.WorkOrderMeterTrigger;
import com.grash.model.enums.NotificationType;
import com.grash.model.enums.WorkOrderMeterTriggerCondition;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.service.MeterService;
import com.grash.service.NotificationService;
import com.grash.service.UserService;
import com.grash.service.WebhookDispatchService;
import com.grash.service.WorkOrderMeterTriggerService;
import com.grash.service.WorkOrderService;
import com.grash.utils.Helper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The meter alarm: a reading crossed a configured threshold, so raise a work order.
 *
 * <p>This was {@code ReadingController.processMeterTriggers} — threshold evaluation, work-order
 * creation, notification and webhook, all synchronous inside the HTTP request. Two problems came
 * with living there, beyond it being business logic in a controller:
 *
 * <ul>
 *   <li><b>It ran before the reading existed.</b> The create path called it and only then saved
 *       the reading, so the work order was raised first and survived a failing save. The alarm
 *       now reacts to a committed reading, which is the only reading it should react to.</li>
 *   <li><b>Whoever submitted the reading waited for it.</b> A device posting a value waited for a
 *       work order to be built and a webhook to be attempted.</li>
 * </ul>
 *
 * <p>Writing, not read-only: raising the work order is the point.
 *
 * <p>Not a rule in the automation engine, and that stays true after the move: {@code EntityType}
 * has no {@code METER}, and this path creates the work order itself rather than describing a
 * condition. It is a consumer of the same event stream, which is what E2 asked for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeterTriggerFanout {

    private final MeterService meterService;
    private final WorkOrderMeterTriggerService workOrderMeterTriggerService;
    private final WorkOrderService workOrderService;
    private final WorkOrderMapper workOrderMapper;
    private final NotificationService notificationService;
    private final WebhookDispatchService webhookDispatchService;
    private final UserService userService;
    private final MessageSource messageSource;

    @Transactional
    public void onReadingRecorded(ReadingRecorded event) {
        Meter meter = meterService.findById(event.meterId()).orElse(null);
        if (meter == null) {
            log.warn("Meter {} is gone; skipping the trigger check for reading {}", event.meterId(),
                    event.readingId());
            return;
        }
        Collection<WorkOrderMeterTrigger> triggers = workOrderMeterTriggerService.findByMeter(meter.getId());
        if (triggers.isEmpty()) {
            return;
        }
        User actor = event.actorUserId() == null
                ? null : userService.findById(event.actorUserId()).orElse(null);
        // The meter's company, not the actor's, decides the language and owns the work order
        // raised below: it is the right tenant, and it is still there when the reading came from
        // a job rather than a person.
        Locale locale = actor == null ? Helper.getLocale(meter.getCompany()) : Helper.getLocale(actor);

        for (WorkOrderMeterTrigger trigger : triggers) {
            String message = messageOnCrossing(trigger, meter, event.value(), locale);
            if (message == null) {
                continue;
            }
            notificationService.createMultiple(meter.getUsers().stream()
                            .map(user -> new Notification(message, user, NotificationType.METER, meter.getId()))
                            .toList(),
                    true, messageSource.getMessage("new_wo", null, locale));

            WorkOrderPostDTO workOrderRequest = workOrderService.getWorkOrderFromWorkOrderBase(trigger);
            WorkOrder createdWorkOrder = workOrderService.create(workOrderRequest, meter.getCompany());

            Map<String, Object> webhookPayload = new HashMap<>();
            webhookPayload.put("meterId", meter.getId());
            webhookPayload.put("meterName", meter.getName());
            webhookPayload.put("meterTriggerId", trigger.getId());
            webhookPayload.put("meterTriggerName", trigger.getName());
            webhookPayload.put("readingValue", event.value());
            webhookPayload.put("triggerValue", trigger.getValue());
            webhookPayload.put("triggerCondition", trigger.getTriggerCondition().name());
            webhookPayload.put("workOrderId", createdWorkOrder.getId());
            webhookDispatchService.dispatchWebhook(meter.getCompany(),
                    WebhookEvent.METER_TRIGGER_STATUS_CHANGE, webhookPayload,
                    "triggeredWorkOrder", workOrderMapper.toShowDto(createdWorkOrder),
                    null, null, null, null, null);
        }
    }

    /**
     * The message for a crossed threshold, or null when the reading is within bounds.
     *
     * @return null means "no alarm", which is the ordinary case
     */
    private String messageOnCrossing(WorkOrderMeterTrigger trigger, Meter meter, double readingValue,
                                     Locale locale) {
        Object[] arguments = new Object[]{meter.getName(), trigger.getValue(), meter.getUnit()};
        if (trigger.getTriggerCondition().equals(WorkOrderMeterTriggerCondition.LESS_THAN)) {
            return readingValue < trigger.getValue()
                    ? messageSource.getMessage("notification_reading_less_than", arguments, locale) : null;
        }
        return readingValue > trigger.getValue()
                ? messageSource.getMessage("notification_reading_more_than", arguments, locale) : null;
    }
}
