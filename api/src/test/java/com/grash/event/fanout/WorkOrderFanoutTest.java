package com.grash.event.fanout;

import com.grash.factory.MailServiceFactory;
import com.grash.model.Company;
import com.grash.model.Notification;
import com.grash.model.Request;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.RoleCode;
import com.grash.model.enums.Status;
import com.grash.repository.WorkOrderRepository;
import com.grash.service.MailService;
import com.grash.service.NotificationService;
import com.grash.service.UserService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The two audiences of a status change, and the conditions that used to be checked inside
 * {@code WorkOrderService.changeStatus}.
 *
 * <p>Those conditions are the interesting part: the company preference and the presence of a
 * parent request are now re-read from the committed record rather than travelling in the event,
 * so a test has to prove the handler still refuses when they say no.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkOrderFanoutTest {

    @InjectMocks
    private WorkOrderFanout workOrderFanout;

    @Mock
    private WorkOrderRepository workOrderRepository;
    @Mock
    private UserService userService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private MailServiceFactory mailServiceFactory;
    @Mock
    private MailService mailService;
    @Mock
    private MessageSource messageSource;

    private Company company;
    private User technician;
    private User admin;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(workOrderFanout, "frontendUrl", "http://localhost:3000");
        company = FanoutTestFixtures.company(1L);
        technician = FanoutTestFixtures.user(1L, company, RoleCode.TECHNICIAN);
        admin = FanoutTestFixtures.user(2L, company, RoleCode.ADMIN);
        admin.getRole().getViewPermissions().add(PermissionEntity.SETTINGS);

        when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mailServiceFactory.getMailService()).thenReturn(mailService);
    }

    private WorkOrder workOrder(Long id) {
        WorkOrder workOrder = new WorkOrder();
        workOrder.setId(id);
        workOrder.setTitle("Fix the door");
        workOrder.setCompany(company);
        workOrder.setStatus(Status.COMPLETE);
        return workOrder;
    }

    @Test
    @DisplayName("a completion reaches the admins who asked for work-order updates")
    void completionNotifiesAdmins() {
        User quietAdmin = FanoutTestFixtures.user(3L, company, RoleCode.ADMIN);
        quietAdmin.getRole().getViewPermissions().add(PermissionEntity.SETTINGS);
        quietAdmin.getUserSettings().setEmailUpdatesForWorkOrders(false);
        when(workOrderRepository.findById(7L)).thenReturn(Optional.of(workOrder(7L)));
        when(userService.findById(1L)).thenReturn(Optional.of(technician));
        when(userService.findWorkersByCompany(1L)).thenReturn(List.of(admin, quietAdmin, technician));

        workOrderFanout.onClosed(7L, technician.getId());

        ArgumentCaptor<List<Notification>> batch = ArgumentCaptor.forClass(List.class);
        verify(notificationService).createMultiple(batch.capture(), anyBoolean(), anyString());
        // The technician has no SETTINGS view permission and the quiet admin switched the
        // updates off, so exactly one of the three is left.
        assertEquals(List.of(admin), batch.getValue().stream().map(Notification::getUser).toList());
    }

    @Test
    @DisplayName("a work order deleted before the handler runs is skipped, not thrown")
    void missingWorkOrderIsSkipped() {
        when(workOrderRepository.findById(7L)).thenReturn(Optional.empty());

        workOrderFanout.onClosed(7L, technician.getId());

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("the requester is told, in the app and by mail")
    void requesterIsNotified() {
        User requester = FanoutTestFixtures.user(9L, company, RoleCode.REQUESTER);
        WorkOrder workOrder = workOrder(7L);
        Request parent = new Request();
        parent.setCreatedBy(requester.getId());
        workOrder.setParentRequest(parent);
        company.getCompanySettings().getGeneralPreferences().setWoUpdateForRequesters(true);
        when(workOrderRepository.findById(7L)).thenReturn(Optional.of(workOrder));
        when(userService.findById(1L)).thenReturn(Optional.of(technician));
        when(userService.findById(9L)).thenReturn(Optional.of(requester));

        workOrderFanout.onStatusChanged(new WorkOrderStatusChanged(7L, 1L, Status.OPEN,
                Status.COMPLETE, technician.getId()));

        verify(notificationService).create(any(Notification.class));
        verify(mailService).sendMessageUsingThymeleafTemplate(eq(new String[]{requester.getEmail()}),
                anyString(), any(), eq("requester-update.html"), any(), any());
    }

    @Test
    @DisplayName("a request left only as a contact address still gets the mail")
    void contactAddressFallback() {
        WorkOrder workOrder = workOrder(7L);
        Request parent = new Request();
        parent.setCreatedBy(null);
        parent.setContact("fallback@test.com");
        workOrder.setParentRequest(parent);
        company.getCompanySettings().getGeneralPreferences().setWoUpdateForRequesters(true);
        when(workOrderRepository.findById(7L)).thenReturn(Optional.of(workOrder));
        when(userService.findById(1L)).thenReturn(Optional.of(technician));

        workOrderFanout.onStatusChanged(new WorkOrderStatusChanged(7L, 1L, Status.OPEN,
                Status.COMPLETE, technician.getId()));

        // No account, so no in-app notification — only the mail.
        verify(notificationService, never()).create(any(Notification.class));
        verify(mailService).sendMessageUsingThymeleafTemplate(eq(new String[]{"fallback@test.com"}),
                anyString(), any(), eq("requester-update.html"), any(), any());
    }

    @Test
    @DisplayName("the company preference still decides, now read from the committed record")
    void preferenceOffMeansSilence() {
        WorkOrder workOrder = workOrder(7L);
        Request parent = new Request();
        parent.setContact("fallback@test.com");
        workOrder.setParentRequest(parent);
        company.getCompanySettings().getGeneralPreferences().setWoUpdateForRequesters(false);
        when(workOrderRepository.findById(7L)).thenReturn(Optional.of(workOrder));

        workOrderFanout.onStatusChanged(new WorkOrderStatusChanged(7L, 1L, Status.OPEN,
                Status.COMPLETE, technician.getId()));

        verifyNoInteractions(notificationService);
        verify(mailService, never()).sendMessageUsingThymeleafTemplate(any(), anyString(), any(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("a work order with no parent request has nobody to tell")
    void noParentRequestMeansSilence() {
        company.getCompanySettings().getGeneralPreferences().setWoUpdateForRequesters(true);
        when(workOrderRepository.findById(7L)).thenReturn(Optional.of(workOrder(7L)));

        workOrderFanout.onStatusChanged(new WorkOrderStatusChanged(7L, 1L, Status.OPEN,
                Status.COMPLETE, technician.getId()));

        verifyNoInteractions(notificationService);
    }
}
