package com.financeos.api.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.api.inbox.dto.InboxResponse;
import com.financeos.api.inbox.dto.InboxSummaryResponse;
import com.financeos.core.exception.GlobalExceptionHandler;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.inbox.InboxService;
import com.financeos.domain.inbox.collect.InboxRows;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InboxControllerTest {

    private static final LocalDate UNTIL = LocalDate.of(2026, 10, 12);

    private final UUID userId = UUID.randomUUID();
    private final UUID loanId = UUID.randomUUID();
    private InboxService service;
    private MockMvc mvc;
    private InboxResponse inbox;

    @BeforeEach
    void setUp() {
        service = mock(InboxService.class);
        mvc = MockMvcBuilders.standaloneSetup(new InboxController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        UserContext.setCurrentUserId(userId);
        InboxItemResponse row = InboxRows.item(InboxKinds.emiKey(loanId, 3), InboxKinds.EMI, InboxItemResponse.SEVERITY_WARNING,
                InboxItemResponse.SECTION_ACT_NOW, "EMI due", null, "/loans/" + loanId, null, LocalDate.of(2026, 10, 10),
                List.of(), InboxRefsResponse.ofLoan(loanId));
        inbox = new InboxResponse(List.of(row), new InboxSummaryResponse(1, 0, 0, 1), Instant.parse("2026-10-08T05:00:00Z"));
        when(service.list(userId)).thenReturn(inbox);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private String emiKey() {
        return "emi:" + loanId + ":3";
    }

    // ---------------------------------------------------------------- reads

    @Test
    void listReturnsTheUsersInbox() throws Exception {
        mvc.perform(get("/api/v1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].key").value(emiKey()))
                .andExpect(jsonPath("$.items[0].rowType").value("item"))
                .andExpect(jsonPath("$.items[0].section").value("act_now"))
                .andExpect(jsonPath("$.items[0].summary").doesNotExist())
                .andExpect(jsonPath("$.summary.badge").value(1));
        verify(service).list(userId);
    }

    @Test
    void summaryReturnsTheBadgeCounts() throws Exception {
        when(service.summaryOnly(userId)).thenReturn(new InboxSummaryResponse(2, 3, 4, 5));
        mvc.perform(get("/api/v1/inbox/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actNow").value(2))
                .andExpect(jsonPath("$.needsLook").value(3))
                .andExpect(jsonPath("$.info").value(4))
                .andExpect(jsonPath("$.badge").value(5));
        verify(service).summaryOnly(userId);
    }

    // ---------------------------------------------------------------- snooze

    @Test
    void snoozeMatchesAKeyWithColonsAndReturnsTheFreshInbox() throws Exception {
        mvc.perform(post("/api/v1/inbox/" + emiKey() + "/snooze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"until\":\"2026-10-12\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].key").value(emiKey()));
        InOrder order = inOrder(service);
        order.verify(service).snooze(userId, emiKey(), UNTIL);
        order.verify(service).list(userId);
    }

    /** The client URL-encodes keys once (URI form, so MockMvc does not encode the % again); Spring decodes before matching. */
    @Test
    void snoozeDecodesAPercentEncodedKey() throws Exception {
        mvc.perform(post(URI.create("/api/v1/inbox/emi%3A" + loanId + "%3A3/snooze"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"until\":\"2026-10-12\"}"))
                .andExpect(status().isOk());
        verify(service).snooze(userId, emiKey(), UNTIL);
    }

    @Test
    void snoozeWithoutAnUntilDateIs400AndNeverReachesTheService() throws Exception {
        mvc.perform(post("/api/v1/inbox/" + emiKey() + "/snooze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @Test
    void aSnoozeTheServiceRejectsIs400() throws Exception {
        doThrow(new ValidationException("This row can be dismissed but not snoozed"))
                .when(service).snooze(userId, InboxKinds.KEY_REVIEW, UNTIL);
        mvc.perform(post("/api/v1/inbox/review/snooze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"until\":\"2026-10-12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("This row can be dismissed but not snoozed"));
    }

    // ---------------------------------------------------------------- dismiss and undo

    @Test
    void dismissMatchesAKeyWithColonsAndReturnsTheFreshInbox() throws Exception {
        mvc.perform(post("/api/v1/inbox/" + emiKey() + "/dismiss"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.actNow").value(1));
        InOrder order = inOrder(service);
        order.verify(service).dismiss(userId, emiKey());
        order.verify(service).list(userId);
    }

    @Test
    void dismissWorksOnASummaryKey() throws Exception {
        mvc.perform(post("/api/v1/inbox/gmail-attention/dismiss")).andExpect(status().isOk());
        verify(service).dismiss(userId, InboxKinds.KEY_GMAIL_ATTENTION);
    }

    @Test
    void aDismissTheServiceRejectsIs400() throws Exception {
        String longKey = "k".repeat(201);
        doThrow(new ValidationException("Inbox item key is too long")).when(service).dismiss(userId, longKey);
        mvc.perform(post("/api/v1/inbox/" + longKey + "/dismiss"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Inbox item key is too long"));
    }

    @Test
    void clearStateMatchesAKeyWithColonsAndReturnsTheFreshInbox() throws Exception {
        mvc.perform(delete("/api/v1/inbox/" + emiKey() + "/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        InOrder order = inOrder(service);
        order.verify(service).clearState(userId, emiKey());
        order.verify(service).list(userId);
    }

    @Test
    void clearStateDecodesAPercentEncodedKey() throws Exception {
        mvc.perform(delete(URI.create("/api/v1/inbox/statement-expected%3A" + loanId + "%3A2026-09-30/state")))
                .andExpect(status().isOk());
        verify(service).clearState(userId, "statement-expected:" + loanId + ":2026-09-30");
    }

    // ---------------------------------------------------------------- auth

    @Test
    void everyEndpointIs401WithoutAUser() throws Exception {
        UserContext.clear();
        mvc.perform(get("/api/v1/inbox")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/inbox/summary")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/inbox/" + emiKey() + "/snooze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"until\":\"2026-10-12\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/inbox/" + emiKey() + "/dismiss")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/inbox/" + emiKey() + "/state")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
