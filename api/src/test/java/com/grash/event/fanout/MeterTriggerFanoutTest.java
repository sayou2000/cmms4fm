package com.grash.event.fanout;

import com.grash.dto.workOrder.WorkOrderPostDTO;
import com.grash.dto.workOrder.WorkOrderShowDTO;
import com.grash.mapper.WorkOrderMapper;
import com.grash.model.Company;
import com.grash.model.Meter;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.WorkOrderMeterTrigger;
import com.grash.model.enums.RoleCode;
import com.grash.model.enums.WorkOrderMeterTriggerCondition;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.service.MeterService;
import com.grash.service.NotificationService;
import com.grash.service.UserService;
import com.grash.service.WebhookDispatchService;
import com.grash.service.WorkOrderMeterTriggerService;
import com.grash.service.WorkOrderService;
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

import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * The meter alarm, now that it is a consumer rather than controller code.
 *
 * <p>The threshold arithmetic is transcribed unchanged, so the tests that matter are the ones
 * about the boundary — a value exactly on the threshold raises nothing, in both directions — and
 * about the alarm reacting to the value that arrived rather than to whatever the meter holds when
 * the handler happens to run.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MeterTriggerFanoutTest {

    @InjectMocks
    private MeterTriggerFanout meterTriggerFanout;

    @Mock
    private MeterService meterService;
    @Mock
    private WorkOrderMeterTriggerService workOrderMeterTriggerService;
    @Mock
    private WorkOrderService workOrderService;
    @Mock
    private WorkOrderMapper workOrderMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private WebhookDispatchService webhookDispatchService;
    @Mock
    private UserService userService;
    @Mock
    private MessageSource messageSource;

    private Company company;
    private User reader;
    private Meter meter;

    @BeforeEach
    void setUp() {
        company = FanoutTestFixtures.company(1L);
        reader = FanoutTestFixtures.user(1L, company, RoleCode.TECHNICIAN);
        meter = new Meter();
        meter.setId(5L);
        meter.setName("Boiler hours");
        meter.setUnit("h");
        meter.setCompany(company);
        meter.setUsers(List.of(reader));

        when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(meterService.findById(5L)).thenReturn(Optional.of(meter));
        when(userService.findById(1L)).thenReturn(Optional.of(reader));
        when(workOrderService.getWorkOrderFromWorkOrderBase(any())).thenReturn(new WorkOrderPostDTO());
        when(workOrderMapper.toShowDto(any())).thenReturn(new WorkOrderShowDTO());
        WorkOrder created = new WorkOrder();
        created.setId(42L);
        when(workOrderService.create(any(), any())).thenReturn(created);
    }

    private WorkOrderMeterTrigger trigger(WorkOrderMeterTriggerCondition condition, int value) {
        WorkOrderMeterTrigger trigger = new WorkOrderMeterTrigger();
        trigger.setId(3L);
        trigger.setName("Service due");
        trigger.setTriggerCondition(condition);
        trigger.setValue(value);
        return trigger;
    }

    @Test
    @DisplayName("a reading above the threshold raises the work order and reports it")
    void aboveThresholdRaisesWorkOrder() {
        when(workOrderMeterTriggerService.findByMeter(5L))
                .thenReturn(List.of(trigger(WorkOrderMeterTriggerCondition.MORE_THAN, 100)));

        meterTriggerFanout.onReadingRecorded(new ReadingRecorded(1L, 5L, 120, reader.getId()));

        verify(workOrderService).create(any(), eq(company));
        verify(notificationService).createMultiple(any(), anyBoolean(), anyString());

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(webhookDispatchService).dispatchWebhook(eq(company),
                eq(WebhookEvent.METER_TRIGGER_STATUS_CHANGE), payload.capture(), anyString(), any(),
                any(), any(), any(), any(), any());
        assertEquals(120.0, payload.getValue().get("readingValue"));
        assertEquals(42L, payload.getValue().get("workOrderId"));
    }

    @Test
    @DisplayName("a reading below the threshold raises nothing at all")
    void belowThresholdIsQuiet() {
        when(workOrderMeterTriggerService.findByMeter(5L))
                .thenReturn(List.of(trigger(WorkOrderMeterTriggerCondition.MORE_THAN, 100)));

        meterTriggerFanout.onReadingRecorded(new ReadingRecorded(1L, 5L, 80, reader.getId()));

        verify(workOrderService, never()).create(any(), any());
        verifyNoInteractions(notificationService, webhookDispatchService);
    }

    @Test
    @DisplayName("a value exactly on the threshold has not crossed it")
    void exactValueIsNotACrossing() {
        // Both comparisons are strict, in both directions. Written down because "reached the
        // limit" and "went past the limit" are the same sentence in German and a future reader
        // will wonder which one this is.
        when(workOrderMeterTriggerService.findByMeter(5L)).thenReturn(List.of(
                trigger(WorkOrderMeterTriggerCondition.MORE_THAN, 100),
                trigger(WorkOrderMeterTriggerCondition.LESS_THAN, 100)));

        meterTriggerFanout.onReadingRecorded(new ReadingRecorded(1L, 5L, 100, reader.getId()));

        verify(workOrderService, never()).create(any(), any());
    }

    @Test
    @DisplayName("a LESS_THAN trigger fires below its value")
    void lessThanFiresBelow() {
        when(workOrderMeterTriggerService.findByMeter(5L))
                .thenReturn(List.of(trigger(WorkOrderMeterTriggerCondition.LESS_THAN, 10)));

        meterTriggerFanout.onReadingRecorded(new ReadingRecorded(1L, 5L, 3, reader.getId()));

        verify(workOrderService).create(any(), eq(company));
    }

    @Test
    @DisplayName("a meter deleted before the handler runs is skipped, not thrown")
    void missingMeterIsSkipped() {
        when(meterService.findById(5L)).thenReturn(Optional.empty());

        meterTriggerFanout.onReadingRecorded(new ReadingRecorded(1L, 5L, 120, reader.getId()));

        verifyNoInteractions(workOrderMeterTriggerService, workOrderService, notificationService);
    }

    @Test
    @DisplayName("a reading with no known actor still raises the alarm")
    void unknownActorStillRaisesTheAlarm() {
        // A meter reading can arrive from a device or a job. The alarm belongs to the meter's
        // company, not to whoever happened to submit the value, so it must not depend on one.
        when(workOrderMeterTriggerService.findByMeter(5L))
                .thenReturn(List.of(trigger(WorkOrderMeterTriggerCondition.MORE_THAN, 100)));

        meterTriggerFanout.onReadingRecorded(new ReadingRecorded(1L, 5L, 120, null));

        verify(workOrderService).create(any(), eq(company));
    }
}
