package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_NEEDS_LOOK;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code stock_not_trading}: one row per instrument the user still holds whose price feed no longer knows it, until
 * they record a merger or mark it delisted. Generic dismiss is allowed.
 */
@Component
public class StockNotTradingInboxCollector implements InboxCollector {

    static final String HREF = "/investments/corporate-actions";
    static final List<CorporateActionType> ANSWERS = List.of(CorporateActionType.merger, CorporateActionType.delisted);

    private final InvestmentTransactionRepository transactions;
    private final CorporateActionRepository actions;

    public StockNotTradingInboxCollector(InvestmentTransactionRepository transactions, CorporateActionRepository actions) {
        this.transactions = transactions;
        this.actions = actions;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        List<Object[]> held = transactions.findHeldNotTradingInstruments(userId);
        if (held.isEmpty()) {
            return List.of();
        }
        Set<UUID> answered = new HashSet<>(actions.findInstrumentIdsWithTypes(userId, ANSWERS));
        return held.stream().filter(r -> !answered.contains((UUID) r[0])).map(r -> row((UUID) r[0], (String) r[1])).toList();
    }

    private static InboxItemResponse row(UUID instrumentId, String name) {
        return InboxRows.item(InboxKinds.stockNotTradingKey(instrumentId), InboxKinds.STOCK_NOT_TRADING,
                SEVERITY_WARNING, SECTION_NEEDS_LOOK, name + " no longer trades", "Record a merger or mark it delisted",
                HREF, null, null, List.of(InboxRows.open(HREF), InboxRows.dismiss()),
                InboxRefsResponse.ofInstrument(instrumentId));
    }
}
