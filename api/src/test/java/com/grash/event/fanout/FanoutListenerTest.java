package com.grash.event.fanout;

import com.grash.automation.event.ChangeType;
import com.grash.automation.event.EntityChangedEvent;
import com.grash.automation.event.EntityType;
import com.grash.model.enums.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The routing table, and the promise that a broken consumer cannot undo a committed change.
 */
@ExtendWith(MockitoExtension.class)
class FanoutListenerTest {

    @InjectMocks
    private FanoutListener fanoutListener;

    @Mock
    private RequestFanout requestFanout;
    @Mock
    private WorkOrderFanout workOrderFanout;
    @Mock
    private MeterTriggerFanout meterTriggerFanout;

    private EntityChangedEvent event(EntityType entityType, ChangeType changeType) {
        return EntityChangedEvent.root(changeType, entityType, 10L, 1L, Set.of(), 2L);
    }

    @Test
    @DisplayName("an approved request reaches the request fan-out")
    void routesRequestApproved() {
        fanoutListener.onEntityChanged(event(EntityType.REQUEST, ChangeType.APPROVED));
        verify(requestFanout).onApproved(10L, 2L);
    }

    @Test
    @DisplayName("a rejected request reaches the request fan-out")
    void routesRequestRejected() {
        fanoutListener.onEntityChanged(event(EntityType.REQUEST, ChangeType.REJECTED));
        verify(requestFanout).onRejected(10L, 2L);
    }

    @Test
    @DisplayName("a closed work order reaches the work-order fan-out")
    void routesWorkOrderClosed() {
        fanoutListener.onEntityChanged(event(EntityType.WORK_ORDER, ChangeType.CLOSED));
        verify(workOrderFanout).onClosed(10L, 2L);
    }

    @Test
    @DisplayName("a purchase order approval is for the rule engine only")
    void purchaseOrderHasNoFanout() {
        // Published so a rule can react to it; the restocking it causes is a domain write that
        // stays in the transaction. If a consumer is ever added, this is the test that says so.
        fanoutListener.onEntityChanged(event(EntityType.PURCHASE_ORDER, ChangeType.APPROVED));
        verifyNoInteractions(requestFanout, workOrderFanout, meterTriggerFanout);
    }

    @Test
    @DisplayName("the change types the capture pipeline produces are not fanned out here")
    void capturedChangeTypesAreIgnored() {
        // Every insert and update of a tracked entity arrives as CommittedEntityChange for the
        // rule engine. Reacting to those here would send a mail on every save.
        fanoutListener.onEntityChanged(event(EntityType.REQUEST, ChangeType.UPDATED));
        fanoutListener.onEntityChanged(event(EntityType.WORK_ORDER, ChangeType.CREATED));
        verifyNoInteractions(requestFanout, workOrderFanout);
    }

    @Test
    @DisplayName("a failing consumer is logged, never rethrown")
    void aFailingConsumerIsContained() {
        // The change committed before this ran. Letting the exception out would surface a broken
        // mail template as a failed approval, which is the coupling the move was meant to break.
        doThrow(new RuntimeException("mail template broken"))
                .when(requestFanout).onApproved(anyLong(), any());

        assertDoesNotThrow(() -> fanoutListener.onEntityChanged(event(EntityType.REQUEST, ChangeType.APPROVED)));
    }

    @Test
    @DisplayName("a failing requester update does not escape either")
    void aFailingStatusUpdateIsContained() {
        WorkOrderStatusChanged statusChanged =
                new WorkOrderStatusChanged(10L, 1L, Status.OPEN, Status.COMPLETE, 2L);
        doThrow(new RuntimeException("no mail server")).when(workOrderFanout).onStatusChanged(any());

        assertDoesNotThrow(() -> fanoutListener.onWorkOrderStatusChanged(statusChanged));
    }

    @Test
    @DisplayName("a failing meter alarm does not escape either")
    void aFailingMeterAlarmIsContained() {
        ReadingRecorded recorded = new ReadingRecorded(1L, 5L, 120, 2L);
        doThrow(new RuntimeException("no meter")).when(meterTriggerFanout).onReadingRecorded(any());

        assertDoesNotThrow(() -> fanoutListener.onReadingRecorded(recorded));
    }
}
