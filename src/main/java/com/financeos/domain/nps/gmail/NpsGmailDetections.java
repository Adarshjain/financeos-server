package com.financeos.domain.nps.gmail;

import com.financeos.core.crypto.EncryptionService;
import com.financeos.domain.nps.NpsAccount;
import com.financeos.domain.nps.NpsAccountRepository;
import com.financeos.domain.nps.NpsSector;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The NPS accounts Gmail found that are not accounts yet: the user's held NEW_ACCOUNT statements grouped by PRAN,
 * one detection per PRAN. Two queries per call (held rows, accounts); the PRANs are compared decrypted, in memory.
 * A PRAN that has an account is no detection any more (its held statements are being, or were, imported).
 */
@Component
public class NpsGmailDetections {

    /** One detection as the summary and the Inbox show it; {@code key} is stable per user and PRAN. */
    public record Detection(UUID key, String pranLast4, @Nullable String holderName, int statementCount,
                            @Nullable LocalDate firstPeriodStart, @Nullable LocalDate lastPeriodEnd,
                            @Nullable NpsSector sectorGuess) {
    }

    /** A detection with the full PRAN and its held rows (never leaves the domain). */
    public record Group(Detection detection, String pran, List<NpsGmailPending> rows) {
    }

    private final NpsGmailPendingRepository pendingRepository;
    private final NpsAccountRepository accountRepository;
    private final EncryptionService encryption;

    public NpsGmailDetections(NpsGmailPendingRepository pendingRepository, NpsAccountRepository accountRepository,
                              EncryptionService encryption) {
        this.pendingRepository = pendingRepository;
        this.accountRepository = accountRepository;
        this.encryption = encryption;
    }

    /** A UUID derived (keyed HMAC) from the user and the PRAN: the PRAN cannot be guessed back from it. */
    public UUID keyOf(UUID userId, String pran) {
        ByteBuffer b = ByteBuffer.wrap(encryption.hmacSha256("nps-detection|" + userId + "|" + pran));
        return new UUID(b.getLong(), b.getLong());
    }

    public List<Detection> forUser(UUID userId) {
        return groups(userId).stream().map(Group::detection).toList();
    }

    /** The user's detection with this key, or null (also for another user's key: the key depends on the user). */
    @Nullable
    public Group find(UUID userId, UUID key) {
        return groups(userId).stream().filter(g -> g.detection().key().equals(key)).findFirst().orElse(null);
    }

    /** Password-locked statements waiting for a password. */
    public int lockedCount(UUID userId) {
        return pendingRepository.findByReasonAndStatus(userId, NpsGmailPendingReason.PASSWORD,
                NpsGmailPendingStatus.PENDING).size();
    }

    /**
     * Held statements of a PRAN the user owns whose import failed {@value NpsGmailPending#MAX_ATTEMPTS} times: they
     * wait for the user's retry. Two queries at most.
     */
    public int failedCount(UUID userId) {
        List<NpsGmailPending> held = pendingRepository.findByReasonAndStatus(userId, NpsGmailPendingReason.NEW_ACCOUNT,
                NpsGmailPendingStatus.PENDING).stream().filter(p -> p.getAttempts() >= NpsGmailPending.MAX_ATTEMPTS).toList();
        if (held.isEmpty()) {
            return 0;
        }
        Set<String> owned = new HashSet<>();
        for (NpsAccount a : accountRepository.findAllByUser_Id(userId)) {
            owned.add(a.getPran());
        }
        return (int) held.stream().filter(p -> p.getPran() != null && owned.contains(p.getPran())).count();
    }

    public List<Group> groups(UUID userId) {
        List<NpsGmailPending> held = pendingRepository.findByReasonAndStatus(userId, NpsGmailPendingReason.NEW_ACCOUNT,
                NpsGmailPendingStatus.PENDING);
        if (held.isEmpty()) {
            return List.of();
        }
        Set<String> owned = new HashSet<>();
        for (NpsAccount a : accountRepository.findAllByUser_Id(userId)) {
            owned.add(a.getPran());
        }
        Map<String, List<NpsGmailPending>> byPran = new LinkedHashMap<>();
        for (NpsGmailPending p : held) {
            if (p.getPran() != null && !owned.contains(p.getPran())) {
                byPran.computeIfAbsent(p.getPran(), k -> new ArrayList<>()).add(p);
            }
        }
        List<Group> out = new ArrayList<>();
        byPran.forEach((pran, rows) -> out.add(new Group(detection(userId, pran, rows), pran, rows)));
        out.sort(Comparator.comparing((Group g) -> g.detection().firstPeriodStart(),
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(g -> g.detection().key()));
        return out;
    }

    private Detection detection(UUID userId, String pran, List<NpsGmailPending> rows) {
        LocalDate first = null;
        LocalDate last = null;
        String holder = null;
        boolean enps = false;
        for (NpsGmailPending p : rows) {
            LocalDate start = p.getPeriodStart() != null ? p.getPeriodStart() : p.getPeriodEnd();
            if (start != null && (first == null || start.isBefore(first))) {
                first = start;
            }
            if (p.getPeriodEnd() != null && (last == null || p.getPeriodEnd().isAfter(last))) {
                last = p.getPeriodEnd();
                if (p.getHolderName() != null) {
                    holder = p.getHolderName();
                }
            } else if (holder == null) {
                holder = p.getHolderName();
            }
            enps |= isEnpsPop(p.getPopName());
        }
        return new Detection(keyOf(userId, pran), pran.substring(pran.length() - 4), holder, rows.size(), first, last,
                enps ? NpsSector.ALL_CITIZEN : null);
    }

    /** Whether a statement's uploading POP is eNPS (an All Citizen account). */
    public static boolean isEnpsPop(@Nullable String popName) {
        return popName != null && popName.toLowerCase(Locale.ROOT).contains("enps");
    }
}
