package com.financeos.domain.account.card;

import com.financeos.api.account.dto.CardholderStatsResponse;
import com.financeos.api.account.dto.CardholderStatsResponse.CardStats;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.core.time.FinancialYear;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.account.cycle.BillingCycles.Cycle;
import com.financeos.domain.transaction.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-cardholder usage numbers for the account's Cards tab, computed with grouped queries (one per measure, not per card). */
@Service
@Transactional(readOnly = true)
public class CardholderStatsService {

    private record CardSummary(long count, LocalDate first, LocalDate last) {}

    private final CardholderRepository cardholderRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final BillingCycleService billingCycleService;

    public CardholderStatsService(CardholderRepository cardholderRepository,
                                  AccountRepository accountRepository,
                                  TransactionRepository transactionRepository,
                                  BillingCycleService billingCycleService) {
        this.cardholderRepository = cardholderRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.billingCycleService = billingCycleService;
    }

    public List<CardholderStatsResponse> statsByAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        UUID currentUserId = UserContext.getCurrentUserId();
        if (currentUserId != null && !account.getUser().getId().equals(currentUserId)) {
            throw new ResourceNotFoundException("Account", accountId);
        }

        List<Cardholder> cardholders = cardholderRepository.findByAccountId(accountId);
        if (cardholders.isEmpty()) {
            return List.of();
        }

        LocalDate today = AppTime.today();
        FinancialYear year = FinancialYear.containing(today);
        Cycle cycle = billingCycleService.cyclesFor(account).containing(today);

        Map<UUID, CardSummary> summaries = new HashMap<>();
        for (Object[] row : transactionRepository.summarizeByCard(accountId)) {
            summaries.put((UUID) row[0], new CardSummary(((Number) row[1]).longValue(), (LocalDate) row[2], (LocalDate) row[3]));
        }
        Map<UUID, BigDecimal> yearSpend = spendByCard(accountId, year.start(), today);
        Map<UUID, BigDecimal> cycleSpend = spendByCard(accountId, cycle.start(), cycle.end());

        return cardholders.stream().map(ch -> {
            BigDecimal yearTotal = BigDecimal.ZERO;
            BigDecimal cycleTotal = BigDecimal.ZERO;
            long count = 0;
            LocalDate lastUsedOn = null;
            List<CardStats> cards = new ArrayList<>();
            for (Card card : ch.getCards()) {
                UUID id = card.getId();
                yearTotal = yearTotal.add(yearSpend.getOrDefault(id, BigDecimal.ZERO));
                cycleTotal = cycleTotal.add(cycleSpend.getOrDefault(id, BigDecimal.ZERO));
                CardSummary s = summaries.get(id);
                if (s == null) {
                    cards.add(new CardStats(id, 0, null, null));
                    continue;
                }
                count += s.count();
                if (lastUsedOn == null || s.last().isAfter(lastUsedOn)) {
                    lastUsedOn = s.last();
                }
                cards.add(new CardStats(id, s.count(), s.first(), s.last()));
            }
            return new CardholderStatsResponse(ch.getId(), yearTotal.max(BigDecimal.ZERO), year.start(), today, lastUsedOn,
                    cycleTotal.max(BigDecimal.ZERO), cycle.start(), cycle.end(), count, cards);
        }).toList();
    }

    private Map<UUID, BigDecimal> spendByCard(UUID accountId, LocalDate from, LocalDate to) {
        Map<UUID, BigDecimal> out = new HashMap<>();
        for (Object[] row : transactionRepository.netSpendByCard(accountId, from, to)) {
            out.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return out;
    }
}
