package com.financeos.gmail.attention;

import com.financeos.gmail.domain.GmailIgnoredNumber;
import com.financeos.gmail.domain.GmailIgnoredNumberRepository;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Mail that parks as unresolved with a number the user ignores is archived at once (still listed under Archived). */
@Component
public class IgnoredNumberRules {

    private static final Logger log = LoggerFactory.getLogger(IgnoredNumberRules.class);

    private final GmailIgnoredNumberRepository ignoredRepository;
    private final GmailProcessedMessageRepository messageRepository;

    public IgnoredNumberRules(GmailIgnoredNumberRepository ignoredRepository, GmailProcessedMessageRepository messageRepository) {
        this.ignoredRepository = ignoredRepository;
        this.messageRepository = messageRepository;
    }

    /** Stored key of a bank id (the sentinel when the mail names none). */
    static String bankKey(String bank) {
        return bank == null || bank.isBlank() ? GmailIgnoredNumber.UNKNOWN_BANK : bank;
    }

    /** Saves the rule in its own transaction so a unique-key clash from a double submit fails here, not the caller's transaction. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public GmailIgnoredNumber create(UUID userId, String bankKey, String last4) {
        return ignoredRepository.saveAndFlush(new GmailIgnoredNumber(userId, bankKey, last4));
    }

    /** True when this mail is an unresolved one whose (bank, last4) the user ignores. */
    static boolean covers(Set<String> rules, GmailProcessedMessage row) {
        return row.getStatus() == GmailProcessedStatus.UNRESOLVED_ACCOUNT
                && row.getExtractedLast4() != null
                && rules.contains(bankKey(NumberGroupKey.bankOf(row.getClassifiedBank(), row.getSenderAddress())) + ":" + row.getExtractedLast4());
    }

    /** Archives those of these rows (by id, one query for the rules and one for the rows) that are unresolved with an ignored number; returns how many. */
    @Transactional
    public int archiveIfIgnored(UUID userId, Collection<UUID> rowIds) {
        if (rowIds == null || rowIds.isEmpty()) {
            return 0;
        }
        List<GmailIgnoredNumber> rules = ignoredRepository.findByUser(userId);
        if (rules.isEmpty()) {
            return 0;
        }
        Set<String> keys = new HashSet<>();
        for (GmailIgnoredNumber r : rules) {
            keys.add(r.getBankId() + ":" + r.getLast4());
        }
        Instant now = Instant.now();
        int archived = 0;
        for (GmailProcessedMessage row : messageRepository.findByUserIdAndIdIn(userId, rowIds)) {
            if (row.getArchivedAt() == null && covers(keys, row)) {
                row.setArchivedAt(now);
                messageRepository.save(row);
                archived++;
            }
        }
        if (archived > 0) {
            log.info("Archived {} unresolved mail(s) for ignored numbers of user {}", archived, userId);
        }
        return archived;
    }
}
