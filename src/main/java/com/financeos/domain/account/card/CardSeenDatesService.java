package com.financeos.domain.account.card;

import com.financeos.api.account.dto.CardSeenDatesResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionRepository.SeenSpan;
import com.financeos.gmail.attention.NumberGroupKey;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedMessageRepository.NumberedMail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * First and last day a number shows up for an account: in the user's Gmail mail (the extracted number, at the account's
 * bank) and in the account's transactions whose bank text contains it. Prefills the dates of a past card.
 */
@Service
public class CardSeenDatesService {

    private final AccountRepository accountRepository;
    private final GmailProcessedMessageRepository messageRepository;
    private final TransactionRepository transactionRepository;

    public CardSeenDatesService(AccountRepository accountRepository, GmailProcessedMessageRepository messageRepository,
                                TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.messageRepository = messageRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public CardSeenDatesResponse seenDates(UUID accountId, String last4) {
        if (last4 == null || !last4.matches("^[0-9]{4}$")) {
            throw new ValidationException("last4 must be exactly 4 digits");
        }
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ValidationException("You do not have permission to access this account.");
        }
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        if (!account.getUser().getId().equals(userId)) {
            throw new ValidationException("You do not have permission to access this account.");
        }
        UUID owner = account.getUser().getId();

        LocalDate first = null;
        LocalDate last = null;
        int mails = 0;
        for (NumberedMail m : messageRepository.findMentioning(owner, last4)) {
            String bank = NumberGroupKey.bankOf(m.getClassifiedBank(), m.getSenderAddress());
            if (!NumberGroupKey.attachable(bank, account.getBank())) {
                continue; // another bank's number (a partner brand's mail counts for its partner banks)
            }
            if (m.getInternalDate() == null) {
                continue;
            }
            LocalDate day = m.getInternalDate().atZone(AppTime.zone()).toLocalDate();
            mails++;
            first = min(first, day);
            last = max(last, day);
        }
        SeenSpan span = transactionRepository.findSeenSpan(owner, accountId, last4);
        int transactions = 0;
        if (span != null && span.getTotal() > 0) {
            transactions = (int) span.getTotal();
            first = min(first, span.getFirstDate());
            last = max(last, span.getLastDate());
        }
        return new CardSeenDatesResponse(first, last, mails, transactions);
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : (b.isBefore(a) ? b : a);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : (b.isAfter(a) ? b : a);
    }
}
