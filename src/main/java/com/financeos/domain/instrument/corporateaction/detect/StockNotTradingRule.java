package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Which held stocks need an answer because the price feeds no longer know them: instruments marked
 * {@code not_trading} that the user still holds, until they record a merger or mark it delisted. Shared by the Inbox
 * row and the Corporate actions page so both apply one rule.
 */
@Component
public class StockNotTradingRule {

    public static final List<CorporateActionType> ANSWERS = List.of(CorporateActionType.merger, CorporateActionType.delisted);

    private final InvestmentTransactionRepository transactions;
    private final CorporateActionRepository actions;

    public StockNotTradingRule(InvestmentTransactionRepository transactions, CorporateActionRepository actions) {
        this.transactions = transactions;
        this.actions = actions;
    }

    /**
     * The unanswered held rows, name order: {@code [id, name, openQty, symbol, exchange, priceStatusAt]} (see
     * {@link InvestmentTransactionRepository#findHeldNotTradingInstruments}).
     */
    public List<Object[]> unanswered(UUID userId) {
        List<Object[]> held = transactions.findHeldNotTradingInstruments(userId);
        if (held.isEmpty()) {
            return List.of();
        }
        Set<UUID> answered = new HashSet<>(actions.findInstrumentIdsWithTypes(userId, ANSWERS));
        return held.stream().filter(r -> !answered.contains((UUID) r[0])).toList();
    }
}
