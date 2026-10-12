package com.financeos.gmail.ingest.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.llm.LlmClient;
import com.financeos.llm.LlmRequest;
import com.financeos.llm.LlmResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The email's sent day (in IST) goes into the extraction prompt, and a transaction date the model
 * reads as later than that day is capped to it and marked as corrected.
 */
class GeminiExtractorSentDateTest {

    /** 2026-09-21 15:34:54 IST — the Premier alert the model misread as 2026-09-26. */
    private static final Instant SENT_AFTERNOON_IST = Instant.parse("2026-09-21T10:04:54Z");
    /** 2026-09-21 01:30 IST, which is still 2026-09-20 in UTC. */
    private static final Instant SENT_AFTER_IST_MIDNIGHT = Instant.parse("2026-09-20T20:00:00Z");

    private LlmClient llmClient;
    private GeminiExtractor extractor;

    @BeforeEach
    void setUp() {
        AppTime.useZone(ZoneId.of(AppTime.DEFAULT_ZONE));
        llmClient = mock(LlmClient.class);
        extractor = new GeminiExtractor(llmClient, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private void modelReturnsDate(String date) {
        when(llmClient.complete(any())).thenReturn(new LlmResponse(
                "{\"isTransaction\":true,\"amount\":209.70,\"currency\":\"INR\",\"direction\":\"DEBIT\","
                        + "\"date\":\"" + date + "\",\"description\":\"MARITIME DATABASE SERV\",\"accountLast4\":\"7106\","
                        + "\"confidence\":0.9}",
                "openrouter", "z-ai/glm-5.3-flash"));
    }

    private static GmailMessage alert(Instant sentAt) {
        return new GmailMessage("msg-1", sentAt, "hsbc@hsbc.bank.in", "Credit Card Transaction Alert", "",
                "Your HSBC credit card ending 7106 was used for INR 209.70 at MARITIME DATABASE SERV on 21/09/26",
                null, List.of());
    }

    private String sentPrompt() {
        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmClient).complete(captor.capture());
        return captor.getValue().prompt();
    }

    @Test
    void promptCarriesTheSentDay() {
        modelReturnsDate("2026-09-21");

        extractor.extract(alert(SENT_AFTERNOON_IST));

        assertTrue(sentPrompt().contains("Email sent on: 2026-09-21 "));
    }

    @Test
    void promptUsesTheIstDayNotTheUtcDay() {
        modelReturnsDate("2026-09-21");

        extractor.extract(alert(SENT_AFTER_IST_MIDNIGHT));

        String prompt = sentPrompt();
        assertTrue(prompt.contains("Email sent on: 2026-09-21 "));
        assertFalse(prompt.contains("2026-09-20"));
    }

    @Test
    void promptOmitsTheSentDayWhenGmailGaveNoTimestamp() {
        modelReturnsDate("2026-09-21");

        extractor.extract(alert(null));

        assertFalse(sentPrompt().contains("Email sent on"));
    }

    @Test
    void dateAfterTheSentDayIsCappedAndMarkedCorrected() {
        modelReturnsDate("2026-09-26");

        GeminiExtractionResult result = extractor.extract(alert(SENT_AFTERNOON_IST));

        assertTrue(result.isSuccess());
        assertEquals(LocalDate.of(2026, 9, 21), result.date());
        assertTrue(result.dateCorrected());
        assertEquals(LocalDate.of(2026, 9, 26), result.dateCorrectedFrom());
    }

    @Test
    void dateOnTheSentDayIsKept() {
        modelReturnsDate("2026-09-21");

        GeminiExtractionResult result = extractor.extract(alert(SENT_AFTERNOON_IST));

        assertEquals(LocalDate.of(2026, 9, 21), result.date());
        assertFalse(result.dateCorrected());
        assertNull(result.dateCorrectedFrom());
    }

    @Test
    void dateBeforeTheSentDayIsKept() {
        modelReturnsDate("2026-09-19");

        GeminiExtractionResult result = extractor.extract(alert(SENT_AFTERNOON_IST));

        assertEquals(LocalDate.of(2026, 9, 19), result.date());
        assertFalse(result.dateCorrected());
    }

    @Test
    void sentDayIsJudgedInIst() {
        // Sent 01:30 IST on the 21st: a purchase on the 21st is not "after" the email,
        // even though the UTC date of the email is the 20th.
        modelReturnsDate("2026-09-21");

        GeminiExtractionResult result = extractor.extract(alert(SENT_AFTER_IST_MIDNIGHT));

        assertEquals(LocalDate.of(2026, 9, 21), result.date());
        assertFalse(result.dateCorrected());
    }

    @Test
    void noCapWhenGmailGaveNoTimestamp() {
        modelReturnsDate("2026-09-26");

        GeminiExtractionResult result = extractor.extract(alert(null));

        assertEquals(LocalDate.of(2026, 9, 26), result.date());
        assertFalse(result.dateCorrected());
    }
}
