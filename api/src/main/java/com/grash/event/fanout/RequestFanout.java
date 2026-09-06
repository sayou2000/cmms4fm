package com.grash.event.fanout;

import com.grash.factory.MailServiceFactory;
import com.grash.mapper.RequestMapper;
import com.grash.model.Notification;
import com.grash.model.Request;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.NotificationType;
import com.grash.model.enums.RoleCode;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.repository.RequestRepository;
import com.grash.service.NotificationService;
import com.grash.service.UserService;
import com.grash.service.WebhookDispatchService;
import com.grash.utils.Helper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Everything that used to happen inside {@code RequestService.approve} and {@code cancel} after
 * the decision itself: the webhook, the in-app notifications and the mails.
 *
 * <p>Why it moved. The old shape was one transaction carrying the whole fan-out — create the work
 * order, dispatch the webhook, write notifications, build and send mail. Three things were wrong
 * with that, and only the first is about tidiness:
 *
 * <ul>
 *   <li><b>The consumers ran too early.</b> {@code dispatchWebhook} and
 *       {@code NotificationService.createMultiple} are both {@code @Async}, so they left the
 *       transaction on another thread while it was still open. A webhook receiver that called
 *       back for the work order it had just been told about could get a 404 — intermittently,
 *       depending on timing.</li>
 *   <li><b>The audience was hardcoded.</b> Who hears about an approval was a
 *       {@code RoleCode.LIMITED_ADMIN} filter in the middle of a service method. It still is, one
 *       layer out, but now it is a consumer that can be replaced by a rule rather than a line
 *       that has to be edited.</li>
 *   <li><b>Every new reaction had to be added here.</b> An escalation, a report, an analysis —
 *       each one meant editing the approval transaction. Now it means another listener.</li>
 * </ul>
 *
 * <p>Read-only on purpose: this reacts, it does not decide. The notification rows and the mails
 * are written by {@code NotificationService} and the mail service in their own transactions, as
 * they always were.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestFanout {

    private final RequestRepository requestRepository;
    private final RequestMapper requestMapper;
    private final UserService userService;
    private final NotificationService notificationService;
    private final WebhookDispatchService webhookDispatchService;
    private final MailServiceFactory mailServiceFactory;
    private final MessageSource messageSource;

    @Value("${frontend.url}")
    private String frontendUrl;

    @Transactional(readOnly = true)
    public void onApproved(Long requestId, Long actorUserId) {
        Request request = requestRepository.findById(requestId).orElse(null);
        if (request == null) {
            log.warn("Request {} is gone; skipping the approval fan-out", requestId);
            return;
        }
        WorkOrder createdWorkOrder = request.getWorkOrder();
        if (createdWorkOrder == null) {
            // Approval always creates one. Reaching this means something cleared the link between
            // the commit and now, and half the messages below would name a work order that is not
            // there.
            log.warn("Request {} has no work order; skipping the approval fan-out", requestId);
            return;
        }
        User actor = findUser(actorUserId);
        Locale locale = localeOf(actor, request);

        Map<String, Object> webhookPayload = new HashMap<>();
        webhookPayload.put("requestId", request.getId());
        webhookPayload.put("requestTitle", request.getTitle());
        webhookPayload.put("previousStatus", "PENDING");
        webhookPayload.put("newStatus", "APPROVED");
        webhookPayload.put("workOrderId", createdWorkOrder.getId());
        webhookDispatchService.dispatchWebhook(request.getCompany(), WebhookEvent.WORK_REQUEST_STATUS_CHANGE,
                webhookPayload, "changedRequest", requestMapper.toShowDto(request),
                null, null, null, null, null);

        String title = messageSource.getMessage("request_approved", null, locale);
        List<User> usersToMail = emailedLimitedAdmins(request.getCompany().getId());

        if (request.getCreatedBy() != null) {
            Optional<User> requester = userService.findById(request.getCreatedBy());
            if (requester.isPresent()) {
                String message = messageSource.getMessage("request_approved_description",
                        new Object[]{request.getTitle()}, locale);
                notificationService.createMultiple(Collections.singletonList(
                        new Notification(message, requester.get(), NotificationType.WORK_ORDER,
                                createdWorkOrder.getId())), true, title);
                usersToMail.add(requester.get());
            }
        }

        String forLimitedAdmins = messageSource.getMessage("request_approved_description_limited_admin",
                new Object[]{actorName(actor, locale), request.getTitle()}, locale);
        notificationService.createMultiple(otherLimitedAdmins(request.getCompany().getId(), actorUserId).stream()
                .map(admin -> new Notification(forLimitedAdmins, admin, NotificationType.WORK_ORDER,
                        createdWorkOrder.getId()))
                .toList(), true, title);

        Map<String, Object> mailVariables = new HashMap<>();
        mailVariables.put("workOrderLink", frontendUrl + "/app/work-orders/" + createdWorkOrder.getId());
        mailVariables.put("workOrderTitle", createdWorkOrder.getTitle());
        mailServiceFactory.getMailService().sendMessageUsingThymeleafTemplate(
                usersToMail.stream().map(User::getEmail).toArray(String[]::new), title, mailVariables,
                "approved-request.html", locale, null);
    }

    @Transactional(readOnly = true)
    public void onRejected(Long requestId, Long actorUserId) {
        Request request = requestRepository.findById(requestId).orElse(null);
        if (request == null) {
            log.warn("Request {} is gone; skipping the rejection fan-out", requestId);
            return;
        }
        User actor = findUser(actorUserId);
        Locale locale = localeOf(actor, request);

        Map<String, Object> webhookPayload = new HashMap<>();
        webhookPayload.put("requestId", request.getId());
        webhookPayload.put("requestTitle", request.getTitle());
        webhookPayload.put("previousStatus", "PENDING");
        webhookPayload.put("newStatus", "CANCELLED");
        webhookPayload.put("cancellationReason", request.getCancellationReason());
        webhookDispatchService.dispatchWebhook(request.getCompany(), WebhookEvent.WORK_REQUEST_STATUS_CHANGE,
                webhookPayload, "changedRequest", requestMapper.toShowDto(request),
                null, null, null, null, null);

        String title = messageSource.getMessage("request_rejected", null, locale);
        List<User> usersToMail = emailedLimitedAdmins(request.getCompany().getId());

        if (request.getCreatedBy() != null) {
            Optional<User> requester = userService.findById(request.getCreatedBy());
            if (requester.isPresent()) {
                String message = messageSource.getMessage("request_rejected_description",
                        new Object[]{request.getTitle()}, locale);
                notificationService.createMultiple(Collections.singletonList(
                        new Notification(message, requester.get(), NotificationType.INFO, null)), true, title);
                usersToMail.add(requester.get());
            }
        }

        String forLimitedAdmins = messageSource.getMessage("request_rejected_description_limited_admin",
                new Object[]{actorName(actor, locale), request.getTitle()}, locale);
        notificationService.createMultiple(otherLimitedAdmins(request.getCompany().getId(), actorUserId).stream()
                .map(admin -> new Notification(forLimitedAdmins, admin, NotificationType.INFO, null))
                .toList(), true, title);

        Map<String, Object> mailVariables = new HashMap<>();
        mailVariables.put("requestLink", frontendUrl + "/app/requests/" + request.getId());
        mailVariables.put("requestTitle", request.getTitle());
        mailServiceFactory.getMailService().sendMessageUsingThymeleafTemplate(
                usersToMail.stream().map(User::getEmail).toArray(String[]::new), title, mailVariables,
                "rejected-request.html", locale, null);
    }

    /**
     * The mail audience, unchanged from what the service did: limited admins who are enabled and
     * have e-mail notifications on. Mutable, because the requester is appended to it.
     */
    private List<User> emailedLimitedAdmins(Long companyId) {
        return new ArrayList<>(userService.findByCompany(companyId).stream()
                .filter(candidate -> candidate.getRole().getCode().equals(RoleCode.LIMITED_ADMIN))
                .filter(candidate -> candidate.isEnabled() && candidate.getUserSettings().isEmailNotified())
                .toList());
    }

    /** Limited admins other than whoever decided — they already know. */
    private List<User> otherLimitedAdmins(Long companyId, Long actorUserId) {
        return userService.findByCompany(companyId).stream()
                .filter(candidate -> candidate.getRole().getCode().equals(RoleCode.LIMITED_ADMIN))
                .filter(candidate -> actorUserId == null || !candidate.getId().equals(actorUserId))
                .toList();
    }

    private User findUser(Long userId) {
        return userId == null ? null : userService.findById(userId).orElse(null);
    }

    /**
     * The acting user's language, falling back to the company's.
     *
     * <p>The service used to read this from the {@code User} it was handed. Here the actor may be
     * absent — a decision made by a job, or a user deleted between the commit and now — and the
     * company's language is the same answer the request portal already uses.
     */
    private Locale localeOf(User actor, Request request) {
        return actor == null ? Helper.getLocale(request.getCompany()) : Helper.getLocale(actor);
    }

    /**
     * Who to name in the message to the other admins. "Someone" is the wording the request portal
     * already uses for an unnamed actor, so the messages stay consistent.
     */
    private String actorName(User actor, Locale locale) {
        return actor == null ? messageSource.getMessage("someone", null, locale) : actor.getFullName();
    }
}
