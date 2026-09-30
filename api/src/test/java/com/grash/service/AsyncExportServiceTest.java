package com.grash.service;

import com.grash.advancedsearch.SearchCriteria;
import com.grash.factory.StorageServiceFactory;
import com.grash.model.Asset;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.Language;
import com.grash.utils.CsvFileGenerator;
import com.grash.utils.csv.CsvColumnRegistries;
import com.grash.utils.csv.CsvColumnRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The filtered exports announce their result on the websocket, and the destination they use is
 * the whole feature: the client subscribes to {@code /user/{email}/exports/{uuid}} and waits
 * there with a spinner until something arrives.
 * <p>
 * Only {@code convertAndSendToUser} resolves to that destination. A plain
 * {@code convertAndSend("/exports/" + uuid, …)} goes to a broker destination nobody is
 * subscribed to — and because {@code /exports} is an enabled simple broker prefix, the broker
 * accepts the message and drops it silently. The export then completes, the file is uploaded,
 * and the client hangs forever with no error anywhere. That is the regression these tests hold
 * shut; they assert the destination, not the payload.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncExportServiceTest {

    private static final String UUID = "export-uuid-1";
    private static final String EMAIL = "someone@example.com";

    @InjectMocks
    private AsyncExportService asyncExportService;

    @Mock
    private WorkOrderService workOrderService;
    @Mock
    private AssetService assetService;
    @Mock
    private LocationService locationService;
    @Mock
    private PartService partService;
    @Mock
    private PartTransactionService partTransactionService;
    @Mock
    private MeterService meterService;
    @Mock
    private PreventiveMaintenanceService preventiveMaintenanceService;
    @Mock
    private CsvFileGenerator csvFileGenerator;
    @Mock
    private CsvColumnRegistries csvColumnRegistries;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private StorageServiceFactory storageServiceFactory;
    @Mock
    private SimpMessageSendingOperations messagingTemplate;
    @Mock
    private EntityManager entityManager;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private User user;
    @Mock
    private CsvColumnRegistry<Asset> assetRegistry;
    @Mock
    private CsvColumnRegistry<WorkOrder> workOrderRegistry;

    @BeforeEach
    void setUp() {
        when(user.getEmail()).thenReturn(EMAIL);
        when(user.getLanguage()).thenReturn(Language.DE);
        when(user.getCompany().getId()).thenReturn(1L);
        when(user.getCompany().getCompanySettings().getGeneralPreferences().getCsvSeparator())
                .thenReturn(";");

        when(csvColumnRegistries.assets(any())).thenReturn(assetRegistry);
        when(csvColumnRegistries.workOrders(any())).thenReturn(workOrderRegistry);
        when(assetRegistry.resolve(any())).thenReturn(Collections.emptyList());
        when(workOrderRegistry.resolve(any())).thenReturn(Collections.emptyList());
    }

    /** One page, so the paging loop runs exactly once. */
    private void givenOnePageOfAssets() {
        Page<Asset> page = new PageImpl<>(List.of(new Asset()));
        when(assetService.getSearchCriteria(any(), any())).thenReturn(new SearchCriteria());
        when(assetService.findForExport(any(), anyInt(), anyInt())).thenReturn(page);
    }

    private void givenOnePageOfWorkOrders() {
        Page<WorkOrder> page = new PageImpl<>(List.of(new WorkOrder()));
        when(workOrderService.getSearchCriteria(any(), any())).thenReturn(new SearchCriteria());
        when(workOrderService.findForExport(any(), anyInt(), anyInt())).thenReturn(page);
    }

    private void givenUploadReturns(String url) {
        StorageService storageService = storageServiceFactory.getStorageService();
        when(storageService.uploadAndSign(any(), anyString())).thenReturn(url);
    }

    @Nested
    class Assets {

        @Test
        void sendsTheSignedUrlToTheUserDestination() {
            givenOnePageOfAssets();
            givenUploadReturns("https://storage.example/assets.csv");

            asyncExportService.exportAssetsFiltered(user, UUID, new SearchCriteria(), null);

            verify(messagingTemplate).convertAndSendToUser(
                    EMAIL, "/exports/" + UUID, "https://storage.example/assets.csv");
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        void sendsTheFailureToTheUserDestinationToo() {
            // A failure that reaches no subscriber leaves the client spinning just as long as a
            // success that does — the error branch needs the user destination for the same reason.
            when(assetService.getSearchCriteria(any(), any()))
                    .thenThrow(new IllegalStateException("boom"));

            asyncExportService.exportAssetsFiltered(user, UUID, new SearchCriteria(), null);

            verify(messagingTemplate).convertAndSendToUser(EMAIL, "/exports/" + UUID, "error: boom");
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }
    }

    @Nested
    class WorkOrders {

        @Test
        void sendsTheSignedUrlToTheUserDestination() {
            givenOnePageOfWorkOrders();
            givenUploadReturns("https://storage.example/work-orders.csv");

            asyncExportService.exportWorkOrdersFiltered(user, UUID, new SearchCriteria(), null);

            verify(messagingTemplate).convertAndSendToUser(
                    EMAIL, "/exports/" + UUID, "https://storage.example/work-orders.csv");
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        void sendsTheFailureToTheUserDestinationToo() {
            when(workOrderService.getSearchCriteria(any(), any()))
                    .thenThrow(new IllegalStateException("boom"));

            asyncExportService.exportWorkOrdersFiltered(user, UUID, new SearchCriteria(), null);

            verify(messagingTemplate).convertAndSendToUser(EMAIL, "/exports/" + UUID, "error: boom");
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }
    }

    /**
     * The eight unfiltered dumps were never broken; this pins one of them so a later refactor
     * cannot "harmonise" the two families in the wrong direction.
     */
    @Test
    void theUnfilteredDumpAlsoUsesTheUserDestination() {
        when(assetService.findByCompanyForExport(any(), any()))
                .thenReturn(new PageImpl<>(List.of(new Asset())));
        givenUploadReturns("https://storage.example/all-assets.csv");

        asyncExportService.exportAssets(user, UUID);

        verify(messagingTemplate).convertAndSendToUser(
                eq(EMAIL), eq("/exports/" + UUID), any(Object.class));
    }

    /** Guards the writer contract the paging loop relies on: only page 0 writes the header. */
    @Test
    void writesTheHeaderOnlyForTheFirstPage() {
        givenOnePageOfAssets();
        givenUploadReturns("https://storage.example/assets.csv");

        asyncExportService.exportAssetsFiltered(user, UUID, new SearchCriteria(), null);

        verify(csvFileGenerator).writeToCsv(any(), any(), any(), eq(";"), eq(true));
        verify(csvFileGenerator, never()).writeToCsv(any(), any(), any(), anyString(), eq(false));
    }
}
