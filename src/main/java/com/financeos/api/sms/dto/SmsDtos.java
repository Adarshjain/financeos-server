package com.financeos.api.sms.dto;

import com.financeos.domain.account.AccountType;
import com.financeos.sms.line.SenderCatalogue;
import com.financeos.sms.line.SmsLineState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes of the SMS sync endpoints. */
public final class SmsDtos {

    private SmsDtos() {
    }

    public record PairingCodeResponse(String code, Instant expiresAt) {}

    public record PairRequest(@NotBlank @Size(max = 32) String code, @NotBlank @Size(max = 80) String deviceName) {}

    public record PairResponse(String token) {}

    public record SummaryLine(
            String sender,
            String ending,
            String family,
            int msgCount,
            int txnCount,
            Instant firstMsgAt,
            Instant lastMsgAt) {}

    public record Skipped(@Min(0) int personal, @Min(0) int otp, @Min(0) int promo, @Min(0) int unknownSender) {}

    public record SummaryRequest(@NotNull @Size(max = 1000) List<@Valid SummaryLine> lines, @Valid Skipped skipped) {}

    public record SummaryResponse(int lines, int newLines, int skippedLines) {}

    public record OnLine(UUID id, String sender, String ending, String family, LocalDate syncFrom) {}

    public record DeviceConfigResponse(SenderCatalogue.Data catalogue, List<OnLine> lines, boolean paused, String zone) {}

    public record DeviceView(UUID id, String name, Instant pairedAt, Instant lastSeenAt, Instant lastSummaryAt, boolean paused,
                             Instant revokedAt) {}

    public record LineView(UUID id, String sender, String provider, String kind, String ending, String family, int msgCount,
                           int txnCount, Instant firstMsgAt, Instant lastMsgAt, SmsLineState state, LocalDate syncFrom,
                           boolean isNew, UUID accountId, UUID suggestedAccountId) {}

    public record Last60Days(long statement, long emailAlert, long manual) {}

    public record AccountLines(UUID accountId, String name, AccountType type, LocalDate lastTransactionDate,
                               Last60Days last60Days, List<LineView> lines) {}

    public record LinesResponse(List<AccountLines> accounts, List<LineView> unassigned, List<LineView> notMine) {}

    public record OnRequest(@NotNull UUID accountId, LocalDate syncFrom) {}

    public record AccountRequest(@NotNull UUID accountId) {}

    public record IncomingMessage(String sender, String body, Instant receivedAt, Integer simSlot, String fingerprint) {}

    public record MessagesRequest(@NotNull @Size(max = 200) List<@NotNull IncomingMessage> messages) {}

    public record MessageResult(String fingerprint, String status, String reason) {}

    public record MessagesResponse(List<MessageResult> results) {}

    public record NotMineRequest(@NotNull @Size(min = 1, max = 500) List<@NotNull UUID> ids) {}

    public record MessageView(UUID id, String sender, String provider, String body, Instant receivedAt, UUID accountId,
                              String accountName, String readStatus, String kind, BigDecimal amount, String direction,
                              LocalDate txnDate, String counterparty, BigDecimal balanceAfter, String refNo, String outcome,
                              UUID transactionId) {}

    public record MessagePage(List<MessageView> items, int page, int size, long total) {}

    public record ConfirmRequest(BigDecimal amount, String direction, LocalDate txnDate, String counterparty) {}

    public record ReportTotals(long stored, long read, long needsConfirm, long couldNotRead, long ignored, long wouldCreate,
                               long wouldAttach, long created, long attached, long duplicates) {}

    public record ReportAlert(String kind, String message, LocalDate date) {}

    public record ReportAccount(UUID accountId, String name, Instant firstMessageAt, Instant lastMessageAt, long wouldCreate,
                                long wouldAttach, long created, long attached, long duplicates, long needsConfirm,
                                long couldNotRead, List<ReportAlert> alerts) {}

    public record ReportResponse(boolean postingEnabled, ReportTotals totals, List<ReportAccount> accounts) {}

    /** What a replay did: how many messages it re-decided, and the user's messages by outcome afterwards (every outcome listed). */
    public record ReplayResponse(int replayed, java.util.Map<String, Long> outcomes) {}
}
