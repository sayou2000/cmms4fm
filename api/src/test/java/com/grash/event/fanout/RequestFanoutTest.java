package com.grash.event.fanout;

import com.grash.dto.RequestShowDTO;
import com.grash.factory.MailServiceFactory;
import com.grash.mapper.RequestMapper;
import com.grash.model.Company;
import com.grash.model.Notification;
import com.grash.model.Request;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.RoleCode;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.repository.RequestRepository;
import com.grash.service.MailService;
import com.grash.service.NotificationService;
import com.grash.service.UserService;
import com.grash.service.WebhookDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What used to be the second half of {@code RequestService.approve} and {@code cancel}.
 *
 * <p>The tests are about the audience and the guards, not about the wording: who is told, who is
 * left out, and what happens when the record the event names is no longer what the event assumed.
 * The last of those is the point of moving this out of the transaction at all — the handler runs
 * later, on another thread, and has to survive the world having moved on.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RequestFanoutTest {

    @InjectMocks
    private RequestFanout requestFanout;

    @Mock
    private RequestRepository requestRepository;
    @Mock
    private RequestMapper requestMapper;
    @Mock
    private UserService userService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private WebhookDispatchService webhookDispatchService;
    @Mock
    private MailServiceFactory mailServiceFactory;
    @Mock
    private MailService mailService;
    @Mock
    private MessageSource messageSource;

    private Company company;
    private User approver;
    private User requester;
    private User otherAdmin;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(requestFanout, "frontendUrl", "http://localhost:3000");
        company = FanoutTestFixtures.company(1L);
        approver = FanoutTestFixtures.user(1L, company, RoleCode.LIMITED_ADMIN);
        requester = FanoutTestFixtures.user(2L, company, RoleCode.REQUESTER);
        otherAdmin = FanoutTestFixtures.user(3L, company, RoleCode.LIMITED_ADMIN);

        when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mailServiceFactory.getMailService()).thenReturn(mailService);
        when(requestMapper.toShowDto(any())).thenReturn(new RequestShowDTO());
    }

    private Request approvedRequest() {
        Request request = new Request();
        request.setId(10L);
        request.setTitle("Broken door");
        request.setCompany(company);
        request.setCreatedBy(requester.getId());
        WorkOrder workOrder = new WorkOrder();
        workOrder.setId(99L);
        workOrder.setTitle("Fix the door");
        request.setWorkOrder(workOrder);
        return request;
    }

    @Test
    @DisplayName("an approval reaches the requester, the other admins and the webhook")
    void approvalNotifiesEveryone() {
        when(requestRepository.findById(10L)).thenReturn(Optional.of(approvedRequest()));
        when(userService.findById(approver.getId())).thenReturn(Optional.of(approver));
        when(userService.findById(requester.getId())).thenReturn(Optional.of(requester));
        when(userService.findByCompany(1L)).thenReturn(List.of(approver, otherAdmin));

        requestFanout.onApproved(10L, approver.getId());

        verify(webhookDispatchService).dispatchWebhook(eq(company),
                eq(WebhookEvent.WORK_REQUEST_STATUS_CHANGE), any(), anyString(), any(),
                any(), any(), any(), any(), any());

        // Two calls: the requester alone, then the admins other than whoever approved.
        ArgumentCaptor<List<Notification>> batches = ArgumentCaptor.forClass(List.class);
        verify(notificationService, org.mockito.Mockito.times(2))
                .createMultiple(batches.capture(), anyBoolean(), anyString());
        assertEquals(List.of(requester), batches.getAllValues().get(0).stream()
                .map(Notification::getUser).toList());
        assertEquals(List.of(otherAdmin), batches.getAllValues().get(1).stream()
                .map(Notification::getUser).toList());
    }

    @Test
    @DisplayName("the approver is not told about their own approval")
    void approverIsNotNotified() {
        when(requestRepository.findById(10L)).thenReturn(Optional.of(approvedRequest()));
        when(userService.findById(approver.getId())).thenReturn(Optional.of(approver));
        when(userService.findById(requester.getId())).thenReturn(Optional.of(requester));
        when(userService.findByCompany(1L)).thenReturn(List.of(approver, otherAdmin));

        requestFanout.onApproved(10L, approver.getId());

        ArgumentCaptor<List<Notification>> batches = ArgumentCaptor.forClass(List.class);
        verify(notificationService, org.mockito.Mockito.times(2))
                .createMultiple(batches.capture(), anyBoolean(), anyString());
        assertTrue(batches.getAllValues().stream().flatMap(List::stream)
                .noneMatch(notification -> notification.getUser().equals(approver)));
    }

    @Test
    @DisplayName("the mail goes to the admins who want mail, plus the requester")
    void mailAudience() {
        User silentAdmin = FanoutTestFixtures.user(4L, company, RoleCode.LIMITED_ADMIN);
        silentAdmin.getUserSettings().setEmailNotified(false);
        when(requestRepository.findById(10L)).thenReturn(Optional.of(approvedRequest()));
        when(userService.findById(approver.getId())).thenReturn(Optional.of(approver));
        when(userService.findById(requester.getId())).thenReturn(Optional.of(requester));
        when(userService.findByCompany(1L)).thenReturn(List.of(approver, otherAdmin, silentAdmin));

        requestFanout.onApproved(10L, approver.getId());

        ArgumentCaptor<String[]> recipients = ArgumentCaptor.forClass(String[].class);
        verify(mailService).sendMessageUsingThymeleafTemplate(recipients.capture(), anyString(),
                any(), eq("approved-request.html"), any(), any());
        assertEquals(List.of(approver.getEmail(), otherAdmin.getEmail(), requester.getEmail()),
                List.of(recipients.getValue()));
    }

    @Test
    @DisplayName("a request deleted between the commit and the handler is skipped, not thrown")
    void missingRequestIsSkipped() {
        when(requestRepository.findById(10L)).thenReturn(Optional.empty());

        requestFanout.onApproved(10L, approver.getId());

        verifyNoInteractions(webhookDispatchService, notificationService);
    }

    @Test
    @DisplayName("an approval with no work order left is skipped rather than half reported")
    void missingWorkOrderIsSkipped() {
        Request request = approvedRequest();
        request.setWorkOrder(null);
        when(requestRepository.findById(10L)).thenReturn(Optional.of(request));

        requestFanout.onApproved(10L, approver.getId());

        // Every message here names the work order, so sending them without one would be worse
        // than sending nothing.
        verifyNoInteractions(webhookDispatchService, notificationService);
        verify(mailService, never()).sendMessageUsingThymeleafTemplate(any(), anyString(), any(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("a rejection carries the reason from the committed record")
    void rejectionCarriesTheReason() {
        Request request = approvedRequest();
        request.setWorkOrder(null);
        request.setCancelled(true);
        request.setCancellationReason("Duplicate");
        when(requestRepository.findById(10L)).thenReturn(Optional.of(request));
        when(userService.findById(approver.getId())).thenReturn(Optional.of(approver));
        when(userService.findById(requester.getId())).thenReturn(Optional.of(requester));
        when(userService.findByCompany(1L)).thenReturn(List.of(approver));

        requestFanout.onRejected(10L, approver.getId());

        ArgumentCaptor<java.util.Map<String, Object>> payload = ArgumentCaptor.forClass(java.util.Map.class);
        verify(webhookDispatchService).dispatchWebhook(eq(company),
                eq(WebhookEvent.WORK_REQUEST_STATUS_CHANGE), payload.capture(), anyString(), any(),
                any(), any(), any(), any(), any());
        assertEquals("Duplicate", payload.getValue().get("cancellationReason"));
        assertEquals("CANCELLED", payload.getValue().get("newStatus"));
    }

    @Test
    @DisplayName("without a known actor the company language is used and nobody is named")
    void unknownActorFallsBackToTheCompany() {
        // The event carries no actor when the change came from a job or a deleted user. Reading
        // the language off the actor would then be a null dereference in the handler.
        when(requestRepository.findById(10L)).thenReturn(Optional.of(approvedRequest()));
        when(userService.findById(requester.getId())).thenReturn(Optional.of(requester));
        when(userService.findByCompany(anyLong())).thenReturn(List.of(otherAdmin));

        requestFanout.onApproved(10L, null);

        // Locale.getDefault(), not Locale.ENGLISH: Language.EN falls through Helper.getLocale's
        // switch to the JVM default, so pinning English here would fail on a German workstation
        // and pass in CI — the trap CLAUDE.md records for the template tests.
        verify(messageSource).getMessage(eq("someone"), any(), any(Locale.class));
    }
}
