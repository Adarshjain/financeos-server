package com.financeos.domain.nps.gmail;

import com.financeos.api.nps.dto.NpsImportResultResponse;
import com.financeos.domain.nps.NpsAccount;
import com.financeos.domain.nps.NpsAccountRepository;
import com.financeos.domain.nps.NpsImportService;
import com.financeos.domain.nps.NpsStatementImportService;
import com.financeos.domain.nps.NpsStatementSource;
import com.financeos.domain.nps.statement.NpsStatementFormatException;
import com.financeos.domain.nps.statement.NpsStatementLockedException;
import com.financeos.domain.nps.statement.NpsStatementParser;
import com.financeos.domain.nps.statement.ParsedNpsStatement;
import com.financeos.domain.user.UserRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An NPS statement PDF that came from Gmail: open it with the passwords we know, apply it to the account its PRAN
 * belongs to (auto-commit), or hold it. A PRAN that is no account is never made into one here: the statement is held
 * as a NEW_ACCOUNT row until the user says go (the import then re-fetches and applies it, see
 * {@link #importHeld}); a PRAN the user ignored is kept as an IGNORED NEW_ACCOUNT row (so adding the account later imports it); a locked PDF is held as a PASSWORD row. Never logs the
 * PRAN or the holder's name; never keeps the PDF.
 */
@Service
@Transactional
public class NpsGmailStatementService {

    /** The reason of an IGNORED outcome when the account's Gmail reading is switched off. */
    static final String READING_OFF = "Reading NPS emails is off for this account";

    private static final Pattern PRAN = Pattern.compile("\\d{12}");

    public enum Kind { IMPORTED, PASSWORD_NEEDED, ACCOUNT_NEEDED, IGNORED }

    /** What happened to one statement mail; {@code result} only when it was imported, {@code why} only when ignored. */
    public record Outcome(Kind kind, @Nullable NpsImportResultResponse result, @Nullable String why) {
        static Outcome of(Kind kind) {
            return new Outcome(kind, null, null);
        }
    }

    private final NpsStatementParser parser;
    private final NpsStatementImportService importService;
    private final NpsImportService npsImportService;
    private final NpsAccountRepository accountRepository;
    private final NpsGmailPendingRepository pendingRepository;
    private final UserRepository userRepository;

    public NpsGmailStatementService(NpsStatementParser parser, NpsStatementImportService importService,
                                    NpsImportService npsImportService, NpsAccountRepository accountRepository,
                                    NpsGmailPendingRepository pendingRepository, UserRepository userRepository) {
        this.parser = parser;
        this.importService = importService;
        this.npsImportService = npsImportService;
        this.accountRepository = accountRepository;
        this.pendingRepository = pendingRepository;
        this.userRepository = userRepository;
    }

    /**
     * Processes the PDF of one mail. {@code typedPassword} is what the user just typed (a password retry), or null.
     */
    public Outcome process(UUID userId, @Nullable UUID connectionId, String messageId, byte[] pdf,
                           @Nullable String typedPassword) {
        List<NpsAccount> accounts = accountRepository.findAllByUser_Id(userId);
        ParsedNpsStatement parsed;
        try {
            parsed = parser.parse(pdf, npsImportService.passwords(userId, typedPassword, null, accounts));
        } catch (NpsStatementLockedException e) {
            upsertPending(userId, connectionId, messageId, NpsGmailPendingReason.PASSWORD, null, null);
            return Outcome.of(Kind.PASSWORD_NEEDED);
        } catch (NpsStatementFormatException e) {
            return new Outcome(Kind.IGNORED, null, "Not a readable NPS statement");
        }
        if (parsed.pran() == null || !PRAN.matcher(parsed.pran()).matches()) {
            return new Outcome(Kind.IGNORED, null, "The statement has no PRAN");
        }

        NpsAccount account = accounts.stream().filter(a -> parsed.pran().equals(a.getPran())).findFirst().orElse(null);
        if (account != null) {
            if (!account.isReadGmail()) {
                return new Outcome(Kind.IGNORED, null, READING_OFF);
            }
            NpsImportResultResponse result = apply(userId, account, parsed, pdf, typedPassword, messageId);
            markDone(userId, messageId);
            return new Outcome(Kind.IMPORTED, result, null);
        }
        Held held = new Held(parsed.pran(), parsed.subscriberName(), parsed.periodStart(), parsed.periodEnd(),
                popOf(parsed), opener(userId, pdf, typedPassword, accounts));
        if (isIgnored(userId, parsed.pran())) {
            // kept as an IGNORED NEW_ACCOUNT row with the full data: adding the account later imports it
            upsertPending(userId, connectionId, messageId, NpsGmailPendingReason.NEW_ACCOUNT, held, null,
                    NpsGmailPendingStatus.IGNORED);
            return new Outcome(Kind.IGNORED, null, "This NPS account was ignored");
        }
        upsertPending(userId, connectionId, messageId, NpsGmailPendingReason.NEW_ACCOUNT, held, null);
        return Outcome.of(Kind.ACCOUNT_NEEDED);
    }

    /**
     * Imports a held NEW_ACCOUNT statement into the account its PRAN has by now: {@code pdf} is the mail's PDF fetched
     * again. A statement that cannot be read, or whose PRAN still has no account, comes back as IGNORED with the
     * reason and the row is left alone (the caller records the reason and keeps it PENDING).
     */
    public Outcome importHeld(UUID userId, NpsGmailPending row, byte[] pdf) {
        List<NpsAccount> accounts = accountRepository.findAllByUser_Id(userId);
        ParsedNpsStatement parsed;
        try {
            parsed = parser.parse(pdf, npsImportService.passwords(userId, row.getPassword(), null, accounts));
        } catch (NpsStatementLockedException e) {
            return new Outcome(Kind.IGNORED, null, "The statement password did not open it");
        } catch (NpsStatementFormatException e) {
            return new Outcome(Kind.IGNORED, null, "Not a readable NPS statement");
        }
        NpsAccount account = parsed.pran() == null ? null
                : accounts.stream().filter(a -> parsed.pran().equals(a.getPran())).findFirst().orElse(null);
        if (account == null) {
            return new Outcome(Kind.IGNORED, null, "The account for this statement is gone");
        }
        NpsImportResultResponse result = apply(userId, account, parsed, pdf, row.getPassword(), row.getGmailMessageId());
        NpsGmailPending fresh = pendingRepository.findByUserIdAndMessageId(userId, row.getGmailMessageId()).orElse(row);
        fresh.setStatus(NpsGmailPendingStatus.DONE);
        fresh.setLastError(null);
        pendingRepository.save(fresh);
        return new Outcome(Kind.IMPORTED, result, null);
    }

    private NpsImportResultResponse apply(UUID userId, NpsAccount account, ParsedNpsStatement parsed, byte[] pdf,
                                          @Nullable String typedPassword, String messageId) {
        // Save the typed password only when it is the one that opened this PDF (a saved password may have opened it instead).
        boolean typed = typedPassword != null && !typedPassword.isBlank();
        if (typed && !typedPassword.equals(account.getStatementPassword()) && parser.passwordOpens(pdf, typedPassword)) {
            account.setStatementPassword(typedPassword);
            accountRepository.save(account);
        }
        return importService.apply(userId, account.getId(), parsed, NpsStatementSource.GMAIL, messageId, false);
    }

    /** The password that opened a protected PDF (typed first, then saved ones, then the profile's), or null. */
    @Nullable
    private String opener(UUID userId, byte[] pdf, @Nullable String typedPassword, List<NpsAccount> accounts) {
        for (String candidate : npsImportService.passwords(userId, typedPassword, null, accounts)) {
            if (parser.passwordOpens(pdf, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** The POP that uploaded the statement's contributions: an eNPS one when there is one, else the first. */
    @Nullable
    private static String popOf(ParsedNpsStatement parsed) {
        String first = null;
        for (ParsedNpsStatement.ContributionRow c : parsed.contributions()) {
            if (c.uploadedBy() == null || c.uploadedBy().isBlank()) {
                continue;
            }
            if (NpsGmailDetections.isEnpsPop(c.uploadedBy())) {
                return c.uploadedBy();
            }
            first = first == null ? c.uploadedBy() : first;
        }
        return first;
    }

    /** Whether the user ignored this PRAN (their IGNORED rows, loaded once and compared decrypted). */
    private boolean isIgnored(UUID userId, String pran) {
        return pendingRepository.findHeld(userId, List.of(NpsGmailPendingStatus.IGNORED)).stream()
                .anyMatch(p -> pran.equals(p.getPran()));
    }

    /** The details a NEW_ACCOUNT row keeps so the statement can be imported later without guessing. */
    private record Held(String pran, @Nullable String holder, @Nullable LocalDate periodStart,
                        @Nullable LocalDate periodEnd, @Nullable String pop, @Nullable String password) {
    }

    private void upsertPending(UUID userId, @Nullable UUID connectionId, String messageId,
                               NpsGmailPendingReason reason, @Nullable Held held,
                               @Nullable String lastError) {
        upsertPending(userId, connectionId, messageId, reason, held, lastError, NpsGmailPendingStatus.PENDING);
    }

    private void upsertPending(UUID userId, @Nullable UUID connectionId, String messageId,
                               NpsGmailPendingReason reason, @Nullable Held held,
                               @Nullable String lastError, NpsGmailPendingStatus status) {
        NpsGmailPending pending = pendingRepository.findByUserIdAndMessageId(userId, messageId)
                .orElseGet(NpsGmailPending::new);
        if (pending.getId() == null) {
            pending.setUser(userRepository.getReferenceById(userId));
            pending.setGmailMessageId(messageId);
        }
        pending.setConnectionId(connectionId != null ? connectionId : pending.getConnectionId());
        pending.setReason(reason);
        pending.setStatus(status);
        pending.setLastError(lastError);
        if (held == null) {
            pending.setPran(null);
            pending.setPranLast4(null);
            pending.setHolderName(null);
            pending.setPeriodStart(null);
            pending.setPeriodEnd(null);
            pending.setPopName(null);
            pending.setPassword(null);
        } else {
            pending.setPran(held.pran());
            pending.setPranLast4(held.pran().substring(8));
            pending.setHolderName(held.holder() == null || held.holder().length() <= 200 ? held.holder()
                    : held.holder().substring(0, 200));
            pending.setPeriodStart(held.periodStart());
            pending.setPeriodEnd(held.periodEnd());
            pending.setPopName(held.pop() == null || held.pop().length() <= 200 ? held.pop()
                    : held.pop().substring(0, 200));
            pending.setPassword(held.password());
        }
        pendingRepository.save(pending);
    }

    private void markDone(UUID userId, String messageId) {
        pendingRepository.findByUserIdAndMessageId(userId, messageId).ifPresent(p -> {
            p.setStatus(NpsGmailPendingStatus.DONE);
            p.setLastError(null);
            pendingRepository.save(p);
        });
    }
}
