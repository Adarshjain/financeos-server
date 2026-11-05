package com.financeos.domain.nps.link;

import com.financeos.api.nps.dto.NpsEntryResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.nps.NpsAccount;
import com.financeos.domain.nps.NpsAccountRepository;
import com.financeos.domain.nps.NpsAccountStatus;
import com.financeos.domain.nps.NpsEntry;
import com.financeos.domain.nps.NpsEntryRepository;
import com.financeos.domain.nps.NpsEntryType;
import com.financeos.domain.nps.NpsRelation;
import com.financeos.domain.nps.NpsService;
import com.financeos.domain.nps.NpsTier;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.AutoLinkCandidateLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Links an NPS contribution to the bank or card payment that funded it, records a payment as a contribution, and
 * remembers "not this one" / "not NPS". Every lookup names the user; another user's entry or transaction is a 404.
 */
@Service
@Transactional
public class NpsLinkService {

    private final NpsEntryRepository entryRepository;
    private final NpsAccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final AutoLinkCandidateLoader candidateLoader;
    private final NpsLinkStore store;
    private final NpsService npsService;

    public NpsLinkService(NpsEntryRepository entryRepository, NpsAccountRepository accountRepository,
                          TransactionRepository transactionRepository, AutoLinkCandidateLoader candidateLoader,
                          NpsLinkStore store, NpsService npsService) {
        this.entryRepository = entryRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.candidateLoader = candidateLoader;
        this.store = store;
        this.npsService = npsService;
    }

    private static UUID currentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResourceNotFoundException("User", "current");
        }
        return userId;
    }

    private NpsEntry ownedEntry(UUID userId, UUID id) {
        return entryRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("NPS entry", id));
    }

    private Transaction ownedTransaction(UUID userId, UUID id) {
        if (id == null) {
            throw new ValidationException("A transaction is required.");
        }
        return transactionRepository.findById(id)
                .filter(t -> t.getUser() != null && t.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
    }

    /** A DEBIT that no loan, lending, dividend, EPF, NPS row or transaction link holds. */
    private Transaction freeDebit(UUID userId, UUID transactionId) {
        Transaction txn = ownedTransaction(userId, transactionId);
        if (txn.getType() != TransactionType.DEBIT) {
            throw new ValidationException("An NPS contribution is paid by a debit.");
        }
        if (candidateLoader.freeIds(userId, List.of(txn.getId())).isEmpty()) {
            throw new ValidationException("This transaction is already linked to something else.");
        }
        return txn;
    }

    public NpsEntryResponse link(UUID entryId, UUID transactionId) {
        UUID userId = currentUserId();
        NpsEntry entry = ownedEntry(userId, entryId);
        requireOpen(entry);
        entry.setTransaction(freeDebit(userId, transactionId));
        entryRepository.saveAndFlush(entry);
        return npsService.getEntry(entryId);
    }

    /** Unlinks, and remembers the pair so the automatic linker does not put it straight back. */
    public void unlink(UUID entryId) {
        UUID userId = currentUserId();
        NpsEntry entry = ownedEntry(userId, entryId);
        if (entry.getTransaction() == null) {
            return;
        }
        store.dismiss(userId, entry.getTransaction().getId(), entry.getId());
        entry.setTransaction(null);
        entryRepository.saveAndFlush(entry);
    }

    /** "Not this one": this payment is not that contribution. */
    public void dismiss(UUID entryId, UUID transactionId) {
        UUID userId = currentUserId();
        NpsEntry entry = ownedEntry(userId, entryId);
        Transaction txn = ownedTransaction(userId, transactionId);
        store.dismiss(userId, txn.getId(), entry.getId());
    }

    /** "Not NPS": the payment stops being offered as a contribution to record. */
    public void notNps(UUID transactionId) {
        UUID userId = currentUserId();
        store.dismiss(userId, ownedTransaction(userId, transactionId).getId(), null);
    }

    /**
     * "Not NPS" on every payment the "Set up NPS?" suggestion counts, in one call (batched: no per-payment lookups).
     */
    public void notNpsSetup() {
        UUID userId = currentUserId();
        store.dismissNotNps(userId, store.loadSetupPayments(userId, AppTime.today()).stream()
                .map(NpsLinkMatcher.Payment::id).toList());
    }

    /**
     * Records a payment as an ESTIMATED own contribution linked to it. {@code accountId} may be omitted when the user
     * has exactly one active own account; {@code tier} defaults to I.
     */
    public NpsEntryResponse record(UUID transactionId, UUID accountId, NpsTier tier) {
        UUID userId = currentUserId();
        Transaction txn = freeDebit(userId, transactionId);
        NpsAccount account;
        if (accountId != null) {
            account = accountRepository.findByIdAndUser_Id(accountId, userId)
                    .orElseThrow(() -> new ResourceNotFoundException("NPS account", accountId));
        } else {
            List<NpsAccount> own = accountRepository.findAllByUser_Id(userId).stream()
                    .filter(a -> a.getRelation() == NpsRelation.SELF && a.getStatus() == NpsAccountStatus.ACTIVE)
                    .toList();
            if (own.size() != 1) {
                throw new ValidationException("Choose the NPS account this payment went to.");
            }
            account = own.get(0);
        }
        return npsService.createPaymentEntry(account, tier == null ? NpsTier.I : tier, txn.getAmount(), txn.getDate(), txn);
    }

    // ------------------------------------------------------------------ the automatic linker's path

    /** Still an unlinked contribution of this user. */
    public boolean isOpen(UUID userId, UUID entryId) {
        return entryRepository.findByIdAndUserId(entryId, userId)
                .map(e -> e.getTransaction() == null && e.getType() == NpsEntryType.CONTRIBUTION).orElse(false);
    }

    public void autoLink(UUID userId, UUID entryId, UUID transactionId) {
        NpsEntry entry = ownedEntry(userId, entryId);
        requireOpen(entry);
        entry.setTransaction(ownedTransaction(userId, transactionId));
        entryRepository.saveAndFlush(entry);
    }

    private static void requireOpen(NpsEntry entry) {
        if (entry.getType() != NpsEntryType.CONTRIBUTION) {
            throw new ValidationException("Only a contribution is paid by a bank payment.");
        }
        if (entry.getTransaction() != null) {
            throw new ValidationException("This contribution is already linked; unlink it first.");
        }
    }
}
