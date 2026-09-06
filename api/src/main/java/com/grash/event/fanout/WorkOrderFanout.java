package com.grash.event.fanout;

import com.grash.factory.MailServiceFactory;
import com.grash.model.Notification;
import com.grash.model.Request;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.NotificationType;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.Status;
import com.grash.repository.WorkOrderRepository;
import com.grash.service.NotificationService;
import com.grash.service.UserService;
import com.grash.utils.Helper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The messages a work order sends when its status changes, moved out of
 * {@code WorkOrderService.changeStatus}.
 *
 * <p>Two audiences with two different rules, which is why there are two entry points:
 *
 * <ul>
 *   <li><b>Completion</b> tells the company's admins that a work order is done. It happens on the
 *       transition into {@code COMPLETE} and nowhere else, so it hangs off the semantic
 *       {@code WORK_ORDER:CLOSED} event the rule engine also hears.</li>
 *   <li><b>Any status change</b> tells the person who reported the underlying request where their
 *       report stands. That has no semantic change type — see {@link WorkOrderStatusChanged} for
 *       why it cannot borrow {@code UPDATED} — so it travels as its own event.</li>
 * </ul>
 *
 * <p>What stayed in the service, and deliberately: stopping the asset downtime, stopping logged
 * labour, rescheduling the parent preventive maintenance. Those are domain writes that belong to
 * the same unit of work as the status itself — a work order that is complete while its asset is
 * still recorded as down is a worse outcome than a slow request. Moving them out needs
 * at-least-once delivery first, which is what the outbox case (E1) is for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkOrderFanout {

    private final WorkOrderRepository workOrderRepository;
    private final UserService userService;
    private final NotificationService notificationService;
    private final MailServiceFactory mailServiceFactory;
    private final MessageSource messageSource;

    @Value("${frontend.url}")
    private String frontendUrl;

    /** Tells the admins who asked for work-order updates that this one is complete. */
    @Transactional(readOnly = true)
    public void onClosed(Long workOrderId, Long actorUserId) {
        WorkOrder workOrder = workOrderRepository.findById(workOrderId).orElse(null);
        if (workOrder == null) {
            log.warn("Work order {} is gone; skipping the completion fan-out", workOrderId);
            return;
        }
        User actor = findUser(actorUserId);
        Locale locale = actor == null ? Helper.getLocale(workOrder.getCompany()) : Helper.getLocale(actor);
        String actorName = actor == null
                ? messageSource.getMessage("someone", null, locale) : actor.getFullName();

        List<User> admins = userService.findWorkersByCompany(workOrder.getCompany().getId()).stream()
                .filter(candidate -> candidate.getRole().getViewPermissions().contains(PermissionEntity.SETTINGS))
                .filter(candidate -> candidate.isEnabled()
                        && candidate.getUserSettings().shouldEmailUpdatesForWorkOrders())
                .toList();

        notificationService.createMultiple(admins.stream()
                        .map(admin -> new Notification(
                                messageSource.getMessage("complete_work_order_content",
                                        new String[]{workOrder.getTitle(), actorName}, Helper.getLocale(admin)),
                                admin, NotificationType.WORK_ORDER, workOrderId))
                        .toList(),
                true, messageSource.getMessage("complete_work_order", null, locale));
    }

    /**
     * Tells the requester where their report stands, if the company has that switched on.
     *
     * <p>The requester may be a user of the system or only an e-mail address left in the portal;
     * both were already handled here and both still are. Only a real user gets an in-app
     * notification, because only a real user can read one.
     */
    @Transactional(readOnly = true)
    public void onStatusChanged(WorkOrderStatusChanged event) {
        WorkOrder workOrder = workOrderRepository.findById(event.workOrderId()).orElse(null);
        if (workOrder == null) {
            log.warn("Work order {} is gone; skipping the requester update", event.workOrderId());
            return;
        }
        Request parentRequest = workOrder.getParentRequest();
        if (parentRequest == null
                || !workOrder.getCompany().getCompanySettings().getGeneralPreferences().isWoUpdateForRequesters()) {
            return;
        }
        User actor = findUser(event.actorUserId());
        Locale locale = actor == null ? Helper.getLocale(workOrder.getCompany()) : Helper.getLocale(actor);

        User requester = parentRequest.getCreatedBy() == null
                ? null : userService.findById(parentRequest.getCreatedBy()).orElse(null);
        String requesterEmail = null;
        if (requester != null) {
            requesterEmail = requester.getEmail();
        } else if (parentRequest.getContact() != null && Helper.isValidEmailAddress(parentRequest.getContact())) {
            requesterEmail = parentRequest.getContact();
        }

        Status newStatus = event.newStatus() == null ? workOrder.getStatus() : event.newStatus();
        String message = messageSource.getMessage("notification_wo_request",
                new Object[]{workOrder.getTitle(), messageSource.getMessage(newStatus.toString(), null, locale)},
                locale);

        if (requester != null) {
            notificationService.create(new Notification(message, requester, NotificationType.WORK_ORDER,
                    event.workOrderId()));
        }
        // Transcribed from the service as it was, including the part that reads oddly: the second
        // arm makes the first redundant, so a requester who switched request e-mails off is
        // mailed anyway. That is upstream behaviour and changing it is a decision about
        // notification preferences, not about where this code lives — so it is written down here
        // rather than quietly corrected while moving.
        boolean mailWanted = (requester != null && requester.getUserSettings().shouldEmailUpdatesForRequests()
                && requester.isEnabled()) || requesterEmail != null;
        if (!mailWanted) {
            return;
        }
        Map<String, Object> mailVariables = new HashMap<>();
        mailVariables.put("workOrderLink", frontendUrl + "/app/work-orders/" + event.workOrderId());
        mailVariables.put("message", message);
        mailServiceFactory.getMailService().sendMessageUsingThymeleafTemplate(
                new String[]{requesterEmail}, messageSource.getMessage("request_update", null, locale),
                mailVariables, "requester-update.html", locale, null);
    }

    private User findUser(Long userId) {
        if (userId == null) {
            return null;
        }
        Optional<User> user = userService.findById(userId);
        return user.orElse(null);
    }
}
