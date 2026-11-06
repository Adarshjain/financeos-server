package com.financeos.domain.transaction.match;

import com.financeos.api.transaction.dto.TransactionResponse;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.Transaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Loads, in one query per page, the periods of the statements the page's transactions are tagged missing from. */
@Service
public class NotOnStatementLookup {

    private static final int IN_CHUNK = 500;

    private final StatementRepository statementRepository;

    public NotOnStatementLookup(StatementRepository statementRepository) {
        this.statementRepository = statementRepository;
    }

    /** Statement id → {statementId, periodStart, periodEnd} for every tagged transaction among these. */
    @Transactional(readOnly = true)
    public Map<UUID, TransactionResponse.NotOnStatement> forTransactions(Collection<Transaction> transactions) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Transaction t : transactions) {
            if (t.getNotOnStatementId() != null) ids.add(t.getNotOnStatementId());
        }
        Map<UUID, TransactionResponse.NotOnStatement> out = new HashMap<>();
        List<UUID> all = new ArrayList<>(ids);
        for (int i = 0; i < all.size(); i += IN_CHUNK) {
            for (StatementRepository.StatementIdPeriod p : statementRepository.findPeriodsByIdIn(all.subList(i, Math.min(i + IN_CHUNK, all.size())))) {
                out.put(p.getId(), new TransactionResponse.NotOnStatement(p.getId(), p.getPeriodStart(), p.getPeriodEnd()));
            }
        }
        return out;
    }
}
