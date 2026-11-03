package com.financeos.gmail.accountdetect.job;

import com.financeos.gmail.accountdetect.scan.ScannedMail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Reads and writes one user's account-detect state and facts; every call names the user. */
@Service
public class AccountDetectStore {

    private final AccountDetectStateRepository stateRepository;
    private final AccountDetectFactRepository factRepository;

    public AccountDetectStore(AccountDetectStateRepository stateRepository, AccountDetectFactRepository factRepository) {
        this.stateRepository = stateRepository;
        this.factRepository = factRepository;
    }

    /** A stored mail, decoded. */
    public record StoredFact(String messageId, java.time.Instant internalDate, FactsCodec.Facts facts) {}

    @Transactional(readOnly = true)
    public Optional<AccountDetectState> state(UUID userId) {
        return stateRepository.findById(userId);
    }

    @Transactional
    public AccountDetectState save(AccountDetectState state) {
        return stateRepository.save(state);
    }

    /** All of the user's facts, decoded, ordered by internal date then message id. */
    @Transactional(readOnly = true)
    public List<StoredFact> facts(UUID userId) {
        List<StoredFact> out = new ArrayList<>();
        for (AccountDetectFact f : factRepository.findAllForUser(userId)) {
            out.add(new StoredFact(f.getMessageId(), f.getInternalDate(), FactsCodec.decode(f.getFacts())));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Set<String> messageIds(UUID userId) {
        return new HashSet<>(factRepository.findMessageIdsForUser(userId));
    }

    /** Stores one chunk of newly scanned mails (derived facts and statement-PDF bookkeeping only). */
    @Transactional
    public void saveAll(UUID userId, List<ScannedMail> mails) {
        saveAll(userId, mails, Map.of());
    }

    /**
     * Stores one chunk of scanned mails. {@code incompleteRunsBefore} holds the stored partial mails being read again
     * (id to failed runs so far): their rows are replaced, and a mail still incomplete counts one more failed run.
     */
    @Transactional
    public void saveAll(UUID userId, List<ScannedMail> mails, Map<String, Integer> incompleteRunsBefore) {
        List<String> replaced = new ArrayList<>();
        for (ScannedMail m : mails) if (incompleteRunsBefore.containsKey(m.messageId())) replaced.add(m.messageId());
        if (!replaced.isEmpty()) factRepository.deleteForUserAndMessageIds(userId, replaced);
        List<AccountDetectFact> rows = new ArrayList<>(mails.size());
        for (ScannedMail m : mails) rows.add(toRow(userId, m, incompleteRuns(m, incompleteRunsBefore)));
        factRepository.saveAll(rows);
    }

    /** Failed runs in a row after this one: 0 once a read succeeds. */
    static int incompleteRuns(ScannedMail m, Map<String, Integer> before) {
        return m.incomplete() ? before.getOrDefault(m.messageId(), 0) + 1 : 0;
    }

    static AccountDetectFact toRow(UUID userId, ScannedMail m) {
        return toRow(userId, m, m.incomplete() ? 1 : 0);
    }

    static AccountDetectFact toRow(UUID userId, ScannedMail m, int incompleteRuns) {
        AccountDetectFact f = new AccountDetectFact();
        f.setUserId(userId);
        f.setMessageId(m.messageId());
        f.setInternalDate(m.internalDate());
        f.setSenderDomain(m.senderDomain());
        f.setRole(m.role() == null ? null : m.role().name());
        f.setFacts(FactsCodec.encode(m.derived(), m.statements(), m.bodyFetched(), m.headerVote(), incompleteRuns));
        f.setHasPdf(!m.pdfs().isEmpty());
        // a protected statement PDF is recorded for a later unlock step; no password is ever tried here
        ScannedMail.Pdf locked = m.pdfs().stream().filter(p -> "PROTECTED".equals(p.status())).findFirst().orElse(null);
        f.setPdfLocked(locked != null);
        String attachmentId = locked != null ? locked.attachmentId() : m.pdfs().isEmpty() ? null : m.pdfs().get(0).attachmentId();
        f.setPdfAttachmentId(attachmentId != null && attachmentId.length() <= 4000 ? attachmentId : null);
        f.setUnlockState(locked != null ? AccountDetectFact.UnlockState.LOCKED : AccountDetectFact.UnlockState.NONE);
        return f;
    }

    /** Deletes the user's state and facts (Gmail disconnect; account deletion also cascades in the database). */
    @Transactional
    public void deleteForUser(UUID userId) {
        factRepository.deleteForUser(userId);
        stateRepository.deleteForUser(userId);
    }
}
