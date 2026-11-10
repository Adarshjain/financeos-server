package com.financeos.gmail.accountdetect.job;

import com.financeos.gmail.accountdetect.StatementContent;
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

    /** The user's facts for these message ids, decoded, ordered like {@link #facts(UUID)}; ids are looked up in chunks of at most 900. */
    @Transactional(readOnly = true)
    public List<StoredFact> facts(UUID userId, java.util.Collection<String> messageIds) {
        List<AccountDetectFact> rows = new ArrayList<>();
        List<String> ids = new ArrayList<>(new java.util.LinkedHashSet<>(messageIds));
        for (int i = 0; i < ids.size(); i += ID_CHUNK) {
            rows.addAll(factRepository.findForUserAndMessageIds(userId, ids.subList(i, Math.min(ids.size(), i + ID_CHUNK))));
        }
        rows.sort(java.util.Comparator.comparing(AccountDetectFact::getInternalDate, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                .thenComparing(AccountDetectFact::getMessageId));
        List<StoredFact> out = new ArrayList<>(rows.size());
        for (AccountDetectFact f : rows) out.add(new StoredFact(f.getMessageId(), f.getInternalDate(), FactsCodec.decode(f.getFacts())));
        return out;
    }

    static final int ID_CHUNK = 900;

    /** A stored mail whose protected statement PDF is still closed, with what the retry needs to try it again. */
    public record LockedFact(String messageId, java.time.Instant internalDate, String attachmentId, String unlockHint, FactsCodec.Facts facts) {}

    /** The user's mails with a LOCKED or FAILED statement PDF not yet tried at unlock generation {@code gen}, newest first. */
    @Transactional(readOnly = true)
    public List<LockedFact> lockedFacts(UUID userId, int gen) {
        List<LockedFact> out = new ArrayList<>();
        for (AccountDetectFact f : factRepository.findStillLockedForUser(userId, gen)) {
            out.add(new LockedFact(f.getMessageId(), f.getInternalDate(), f.getPdfAttachmentId(), f.getUnlockHint(), FactsCodec.decode(f.getFacts())));
        }
        return out;
    }

    /** A stored mail whose statement PDF opened earlier (no password, or one a rule found). */
    public record OpenedFact(String messageId, java.time.Instant internalDate, String attachmentId, AccountDetectFact.UnlockState unlockState,
                             String unlockHint, FactsCodec.Facts facts) {}

    /**
     * The user's mails whose statement PDF opened and yielded statement content and whose holder flag was not yet recomputed
     * at holder generation {@code gen}, newest first. Mails with a PDF but no statement content have nothing to recompute and
     * are stamped as checked here, so they never count as pending.
     */
    @Transactional
    public List<OpenedFact> openedFacts(UUID userId, int gen) {
        List<OpenedFact> out = new ArrayList<>();
        for (AccountDetectFact f : factRepository.findOpenedPdfForUser(userId, gen)) {
            FactsCodec.Facts facts = FactsCodec.decode(f.getFacts());
            if (facts.statements().isEmpty()) {
                f.setHolderCheckedGen(gen);
                factRepository.save(f);
                continue;
            }
            out.add(new OpenedFact(f.getMessageId(), f.getInternalDate(), f.getPdfAttachmentId(), f.getUnlockState(), f.getUnlockHint(), facts));
        }
        return out;
    }

    /** The bookkeeping of a stored mail's statement PDF (no facts: nothing is decoded). */
    public record PdfRow(String messageId, java.time.Instant internalDate, String attachmentId, AccountDetectFact.UnlockState unlockState) {}

    /** The user's mails with an open (or earlier unlocked) statement PDF, newest first; their facts are not read. */
    @Transactional(readOnly = true)
    public List<PdfRow> pdfRows(UUID userId) {
        return factRepository.findPdfRowsForUser(userId);
    }

    /** The user's mails whose statement PDF is still closed (LOCKED or FAILED), newest first; their facts are not read. */
    @Transactional(readOnly = true)
    public List<PdfRow> closedPdfRows(UUID userId) {
        return factRepository.findClosedPdfRowsForUser(userId);
    }

    /**
     * Records a PDF's re-read: the mail's statements become {@code statements} (null keeps the stored ones: the PDF could not be
     * fetched or opened), the unlock state and rule are set when {@code state} is given, and the facts carry the current reader
     * version either way, so a PDF that cannot be re-read is not tried again until the reader changes again.
     */
    @Transactional
    public void applyReread(UUID userId, String messageId, AccountDetectFact.UnlockState state, String rule, List<StatementContent> statements) {
        AccountDetectFact f = factRepository.findByUserAndMessage(userId, messageId).orElse(null);
        if (f == null) return;
        FactsCodec.Facts facts = FactsCodec.decode(f.getFacts());
        if (state != null) {
            f.setUnlockState(state);
            f.setUnlockRule(rule);
        }
        f.setFacts(FactsCodec.encode(facts.derived(), statements == null ? facts.statements() : statements, facts.bodyFetched(), facts.headerVote(), facts.incompleteRuns()));
        factRepository.save(f);
    }

    /** Whether any retry work is left: a locked fact not tried at the current unlock generation, or an opened one not rechecked at the holder generation. */
    @Transactional(readOnly = true)
    public boolean retryPending(UUID userId) {
        return factRepository.countPendingForUser(userId) > 0;
    }

    /** Stamps a locked fact as tried at this unlock generation (whether or not it opened). */
    @Transactional
    public void markUnlockTried(UUID userId, String messageId, int gen) {
        factRepository.findByUserAndMessage(userId, messageId).ifPresent(f -> {
            f.setUnlockTriedGen(gen);
            factRepository.save(f);
        });
    }

    /** Stamps an opened fact as holder-checked at this holder generation. */
    @Transactional
    public void markHolderChecked(UUID userId, String messageId, int gen) {
        factRepository.findByUserAndMessage(userId, messageId).ifPresent(f -> {
            f.setHolderCheckedGen(gen);
            factRepository.save(f);
        });
    }

    /** A trigger: the user's name (nameChanged) or birth date / saved password changed. Adds one generation; a no-op when never scanned. */
    @Transactional
    public void requestUnlockRetry(UUID userId, boolean nameChanged) {
        stateRepository.bumpUnlockGen(userId);
        if (nameChanged) stateRepository.bumpHolderGen(userId);
    }

    /** Sets the holder flag (Y, N or null) on every statement of the mail; true when any changed. */
    @Transactional
    public boolean applyHolder(UUID userId, String messageId, String holder) {
        AccountDetectFact f = factRepository.findByUserAndMessage(userId, messageId).orElse(null);
        if (f == null) return false;
        FactsCodec.Facts facts = FactsCodec.decode(f.getFacts());
        boolean changed = false;
        List<StatementContent> statements = new ArrayList<>();
        for (StatementContent s : facts.statements()) {
            if (!java.util.Objects.equals(s.holder(), holder)) changed = true;
            statements.add(s.withHolder(holder));
        }
        if (!changed) return false;
        f.setFacts(FactsCodec.encode(facts.derived(), statements, facts.bodyFetched(), facts.headerVote(), facts.incompleteRuns(), facts.readerVersion()));
        factRepository.save(f);
        return true;
    }

    /**
     * Records a retry's result on one stored mail: its unlock state and rule and, when the PDF opened and was read, the
     * statement content added to the mail's facts (replacing an earlier one of the same origin, i.e. the same mail layout and date, from a previous retry).
     */
    @Transactional
    public void applyUnlock(UUID userId, String messageId, AccountDetectFact.UnlockState state, String rule, StatementContent content) {
        AccountDetectFact f = factRepository.findByUserAndMessage(userId, messageId).orElse(null);
        if (f == null) return;
        f.setUnlockState(state);
        f.setUnlockRule(rule);
        if (content != null) {
            FactsCodec.Facts facts = FactsCodec.decode(f.getFacts());
            List<StatementContent> statements = new ArrayList<>(facts.statements());
            if (content.originCluster() != null && content.originDate() != null) {
                statements.removeIf(x -> content.originCluster().equals(x.originCluster()) && content.originDate().equals(x.originDate())
                        && java.util.Objects.equals(content.last4(), x.last4()));
            }
            statements.add(content);
            f.setFacts(FactsCodec.encode(facts.derived(), statements, facts.bodyFetched(), facts.headerVote(), facts.incompleteRuns()));
        }
        factRepository.save(f);
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
        // a protected statement PDF is recorded; when a rule opened it, its state and the rule (never a password) are kept
        ScannedMail.Pdf locked = m.pdfs().stream()
                .filter(p -> "PROTECTED".equals(p.status()) || ScannedMail.UNLOCKED.equals(p.status()) || ScannedMail.UNLOCK_FAILED.equals(p.status()))
                .findFirst().orElse(null);
        f.setPdfLocked(locked != null);
        String attachmentId = locked != null ? locked.attachmentId() : m.pdfs().isEmpty() ? null : m.pdfs().get(0).attachmentId();
        f.setPdfAttachmentId(attachmentId != null && attachmentId.length() <= 4000 ? attachmentId : null);
        AccountDetectFact.UnlockState state = AccountDetectFact.UnlockState.NONE;
        if (locked != null) {
            state = ScannedMail.UNLOCKED.equals(locked.status()) ? AccountDetectFact.UnlockState.UNLOCKED
                    : ScannedMail.UNLOCK_FAILED.equals(locked.status()) ? AccountDetectFact.UnlockState.FAILED : AccountDetectFact.UnlockState.LOCKED;
            String hint = locked.unlockHint();
            f.setUnlockHint(hint != null && hint.length() <= 200 ? hint : null);
            String rule = locked.unlockRule();
            f.setUnlockRule(rule != null && rule.length() <= 100 ? rule : null);
        }
        f.setUnlockState(state);
        return f;
    }

    /** Deletes the user's state and facts (Gmail disconnect; account deletion also cascades in the database). */
    @Transactional
    public void deleteForUser(UUID userId) {
        factRepository.deleteForUser(userId);
        stateRepository.deleteForUser(userId);
    }
}
