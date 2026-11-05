package com.financeos.domain.nps;

import com.financeos.api.nps.dto.NpsAccountRequest;
import com.financeos.api.nps.dto.NpsImportCommitRequest;
import com.financeos.api.nps.dto.NpsImportPreviewResponse;
import com.financeos.api.nps.dto.NpsImportResultResponse;
import com.financeos.api.nps.dto.NpsStatementResponse;
import com.financeos.api.nps.dto.NpsSummaryResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.nps.statement.NpsStatementFormatException;
import com.financeos.domain.nps.statement.NpsStatementLockedException;
import com.financeos.domain.nps.statement.NpsStatementParser;
import com.financeos.domain.nps.statement.NpsStatementPassword;
import com.financeos.domain.nps.statement.ParsedNpsStatement;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The NPS statement upload flow: preview (open the PDF with the passwords we know, match or propose the
 * account, show what an import would do) and commit (apply it). The preview is not transactional itself: the
 * dry run inside {@link NpsStatementImportService} rolls its own transaction back. Never logs the PRAN or name.
 */
@Service
public class NpsImportService {

    private static final Pattern PARTIAL_BIRTH = Pattern.compile("^--(\\d{2})-(\\d{2})$");

    private final NpsStatementParser parser;
    private final NpsStatementImportService importService;
    private final NpsImportPreviewStore store;
    private final NpsAccountRepository accountRepository;
    private final UserRepository userRepository;
    private final NpsService npsService;

    public NpsImportService(NpsStatementParser parser, NpsStatementImportService importService,
                            NpsImportPreviewStore store, NpsAccountRepository accountRepository,
                            UserRepository userRepository, NpsService npsService) {
        this.parser = parser;
        this.importService = importService;
        this.store = store;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.npsService = npsService;
    }

    private static UUID currentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResourceNotFoundException("User", "current");
        }
        return userId;
    }

    // ------------------------------------------------------------------ preview

    public NpsImportPreviewResponse preview(byte[] file, String password, UUID accountId) {
        UUID userId = currentUserId();
        List<NpsAccount> accounts = accountRepository.findAllByUser_Id(userId);
        NpsAccount chosen = null;
        if (accountId != null) {
            chosen = accounts.stream().filter(a -> a.getId().equals(accountId)).findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("NPS account", accountId));
        }

        ParsedNpsStatement parsed;
        try {
            parsed = parser.parse(file, passwords(userId, password, chosen, accounts));
        } catch (NpsStatementLockedException e) {
            return new NpsImportPreviewResponse(null, true, null, null, null, List.of());
        } catch (NpsStatementFormatException e) {
            throw new ValidationException(e.getMessage());
        }

        NpsAccount account = chosen;
        if (account == null && parsed.pran() != null) {
            account = accounts.stream().filter(a -> parsed.pran().equals(a.getPran())).findFirst().orElse(null);
        } else if (account != null) {
            assertSamePran(account, parsed);
        }
        String sha = sha256(file);
        NpsStatementImportService.Outcome outcome = importService.applyWithRows(userId,
                account == null ? null : account.getId(), parsed, NpsStatementSource.UPLOAD, null, sha, true);
        UUID previewId = store.put(userId, parsed, sha, password == null || password.isBlank() ? null : password,
                account == null ? null : account.getId());
        return new NpsImportPreviewResponse(previewId, false,
                new NpsImportPreviewResponse.Statement(maskedPran(parsed.pran()), parsed.subscriberName(),
                        parsed.periodStart(), parsed.periodEnd(), parsed.layout() == null ? null : parsed.layout().name(),
                        parsed.schemeChoice()),
                account == null ? new NpsImportPreviewResponse.Account(null, null, null, null, proposal(parsed))
                        : new NpsImportPreviewResponse.Account(account.getId(), account.getHolderName(),
                                account.getRelation(), account.getPranLast4(), null),
                outcome.result(), outcome.rows());
    }

    /** The password the user typed, then the account's saved one (or every account's), then the profile's guess. */
    public List<String> passwords(UUID userId, String typed, NpsAccount chosen, List<NpsAccount> accounts) {
        Set<String> out = new LinkedHashSet<>();
        if (typed != null && !typed.isBlank()) {
            out.add(typed);
        }
        for (NpsAccount a : chosen != null ? List.of(chosen) : accounts) {
            if (a.getStatementPassword() != null && !a.getStatementPassword().isBlank()) {
                out.add(a.getStatementPassword());
            }
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user != null) {
            out.addAll(NpsStatementPassword.candidates(user.getDisplayName(), birthDateOf(user.getBirthDate())));
        }
        return new ArrayList<>(out);
    }

    /** The stored "yyyy-MM-dd" or "--MM-dd" birth date as a date (only its day and month matter), or null. */
    private static LocalDate birthDateOf(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            Matcher m = PARTIAL_BIRTH.matcher(stored.trim());
            if (m.matches()) {
                return LocalDate.of(2000, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            }
            return LocalDate.parse(stored.trim());
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static void assertSamePran(NpsAccount account, ParsedNpsStatement parsed) {
        if (parsed.pran() != null && !parsed.pran().equals(account.getPran())) {
            throw new ValidationException("This statement belongs to a different PRAN than the account you picked.");
        }
    }

    private static NpsImportPreviewResponse.Proposal proposal(ParsedNpsStatement parsed) {
        return new NpsImportPreviewResponse.Proposal(parsed.subscriberName(), maskedPran(parsed.pran()),
                NpsCra.PROTEAN, isEnps(parsed) ? NpsSector.ALL_CITIZEN : null, NpsRelation.SELF);
    }

    /** Whether a contribution of the statement was uploaded through eNPS (an All Citizen account). */
    public static boolean isEnps(ParsedNpsStatement parsed) {
        return parsed.contributions().stream()
                .anyMatch(c -> c.uploadedBy() != null && c.uploadedBy().toLowerCase().contains("enps"));
    }

    private static String maskedPran(String pran) {
        return pran == null || pran.length() < 4 ? "" : NpsService.mask(pran.substring(pran.length() - 4));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ commit

    @Transactional
    public NpsImportResultResponse commit(NpsImportCommitRequest request) {
        UUID userId = currentUserId();
        NpsImportPreviewStore.Stored stored = store.get(request.previewId(), userId);
        if (stored == null) {
            throw new ResourceNotFoundException("NPS import preview", request.previewId());
        }
        ParsedNpsStatement parsed = stored.parsed();
        UUID given = request.accountId() != null ? request.accountId() : stored.accountId();
        UUID accountId = given;
        if (given != null) {
            NpsAccount account = accountRepository.findByIdAndUser_Id(given, userId)
                    .orElseThrow(() -> new ResourceNotFoundException("NPS account", given));
            assertSamePran(account, parsed);
        } else {
            NpsAccount existing = parsed.pran() == null ? null : accountRepository.findAllByUser_Id(userId).stream()
                    .filter(a -> parsed.pran().equals(a.getPran())).findFirst().orElse(null);
            accountId = existing != null ? existing.getId() : createAccount(parsed, request.newAccount()).id();
        }
        NpsImportResultResponse result = importService.applyWithRows(userId, accountId, parsed,
                NpsStatementSource.UPLOAD, null, stored.fileSha256(), false).result();
        if (stored.providedPassword() != null) {
            NpsAccount account = accountRepository.findByIdAndUser_Id(accountId, userId).orElseThrow();
            if (!stored.providedPassword().equals(account.getStatementPassword())) {
                account.setStatementPassword(stored.providedPassword());
                accountRepository.saveAndFlush(account);
            }
        }
        store.remove(request.previewId());
        return result;
    }

    private NpsSummaryResponse.Account createAccount(ParsedNpsStatement parsed, NpsImportCommitRequest.NewAccount n) {
        if (parsed.pran() == null) {
            throw new ValidationException("The statement has no PRAN, so no account can be created from it.");
        }
        NpsImportCommitRequest.NewAccount given = n == null
                ? new NpsImportCommitRequest.NewAccount(null, null, null, null, null) : n;
        String holder = given.holderName() == null || given.holderName().isBlank()
                ? parsed.subscriberName() : given.holderName();
        return npsService.createAccount(new NpsAccountRequest(parsed.pran(), holder, given.relation(), given.sector(),
                given.cra(), given.employerId(), null, null, null, null, null));
    }

    // ------------------------------------------------------------------ statements

    public List<NpsStatementResponse> statements(UUID accountId) {
        return importService.listStatements(currentUserId(), accountId);
    }

    public void deleteStatement(UUID id) {
        importService.deleteStatement(currentUserId(), id);
    }
}
