package com.financeos.domain.account;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

@Repository
public class AccountRepositoryCustomImpl implements AccountRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Map<UUID, AccountBalanceBatch> findAccountBalanceBatches(List<UUID> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // The balance aggregate (b) plus, per account, the credit limit of its latest non-rejected
        // statement that carries one: the utilisation fallback for cards without their own limit.
        // The anchor's cutoff is BalanceMath.cutoff in SQL: the earlier of its period end and its
        // latest linked line's date (the period end when it has no lines); post_anchor_sum is over
        // the transactions dated after that cutoff. The anchor's closing balance falls back to a card
        // statement's total amount due, as StatementRepository.findEligibleAnchorStatements does.
        String sql = "SELECT b.account_id, b.anchor_date, b.anchor_closing_balance, b.total_sum, b.post_anchor_sum, " +
                "( SELECT d.credit_limit FROM statements sl " +
                "  JOIN statement_credit_card_details d ON d.statement_id = sl.id " +
                "  WHERE sl.account_id = b.account_id AND d.credit_limit IS NOT NULL " +
                "    AND (sl.verdict IS NULL OR sl.verdict <> 'REJECTED') " +
                "  ORDER BY sl.period_end DESC NULLS LAST, sl.created_at DESC " +
                "  FETCH FIRST 1 ROWS ONLY ) AS latest_statement_credit_limit, " +
                "b.anchor_cutoff " +
                "FROM ( SELECT " +
                "a.id AS account_id, " +
                "s.period_end AS anchor_date, " +
                "s.closing_balance AS anchor_closing_balance, " +
                "s.anchor_cutoff AS anchor_cutoff, " +
                "COALESCE(SUM(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END), 0) AS total_sum, " +
                "COALESCE(SUM(CASE WHEN s.anchor_cutoff IS NOT NULL AND t.transaction_date > s.anchor_cutoff THEN (CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END) ELSE 0 END), 0) AS post_anchor_sum " +
                "FROM accounts a " +
                "LEFT JOIN ( " +
                "    SELECT s1.id, s1.account_id, s1.period_end, " +
                "           COALESCE(s1.closing_balance, d1.total_amount_due) AS closing_balance, " +
                "           LEAST(s1.period_end, COALESCE(( " +
                "               SELECT MAX(tx.transaction_date) FROM statement_transactions st " +
                "               JOIN transactions tx ON tx.id = st.transaction_id " +
                "               WHERE st.statement_id = s1.id ), s1.period_end)) AS anchor_cutoff " +
                "    FROM statements s1 " +
                "    LEFT JOIN statement_credit_card_details d1 ON d1.statement_id = s1.id " +
                "    WHERE s1.period_end IS NOT NULL " +
                "      AND COALESCE(s1.closing_balance, d1.total_amount_due) IS NOT NULL " +
                "      AND s1.verdict != 'REJECTED' " +
                "      AND s1.id = ( " +
                "          SELECT s2.id FROM statements s2 " +
                "          LEFT JOIN statement_credit_card_details d2 ON d2.statement_id = s2.id " +
                "          WHERE s2.account_id = s1.account_id " +
                "            AND s2.period_end IS NOT NULL " +
                "            AND COALESCE(s2.closing_balance, d2.total_amount_due) IS NOT NULL " +
                "            AND s2.verdict != 'REJECTED' " +
                "          ORDER BY s2.period_end DESC, s2.created_at DESC " +
                "          FETCH FIRST 1 ROWS ONLY " +
                "      ) " +
                ") s ON s.account_id = a.id " +
                "LEFT JOIN transactions t ON t.account_id = a.id " +
                "WHERE a.id IN (:accountIds) " +
                "GROUP BY a.id, s.period_end, s.closing_balance, s.anchor_cutoff ) b";

        Query query = entityManager.createNativeQuery(sql);
        List<String> idStrings = accountIds.stream().map(UUID::toString).toList();
        query.setParameter("accountIds", idStrings);

        List<?> rows = query.getResultList();
        Map<UUID, AccountBalanceBatch> result = new HashMap<>();

        for (Object row : rows) {
            Object[] arr = (Object[]) row;
            UUID accId = parseUuid(arr[0]);
            LocalDate anchorDate = parseLocalDate(arr[1]);
            BigDecimal anchorClosingBalance = parseBigDecimal(arr[2]);
            BigDecimal totalSum = parseBigDecimal(arr[3]);
            BigDecimal postAnchorSum = parseBigDecimal(arr[4]);
            BigDecimal latestStatementCreditLimit = arr.length > 5 ? parseBigDecimal(arr[5]) : null;
            LocalDate anchorCutoff = arr.length > 6 ? parseLocalDate(arr[6]) : null;

            if (accId != null) {
                result.put(accId, new AccountBalanceBatch(
                        accId,
                        anchorDate,
                        anchorClosingBalance,
                        totalSum != null ? totalSum : BigDecimal.ZERO,
                        postAnchorSum != null ? postAnchorSum : BigDecimal.ZERO,
                        latestStatementCreditLimit,
                        anchorCutoff != null ? anchorCutoff : anchorDate
                ));
            }
        }

        return result;
    }

    private UUID parseUuid(Object obj) {
        if (obj == null) return null;
        if (obj instanceof UUID u) return u;
        return UUID.fromString(obj.toString());
    }

    private LocalDate parseLocalDate(Object obj) {
        if (obj == null) return null;
        if (obj instanceof LocalDate ld) return ld;
        if (obj instanceof Date d) return d.toLocalDate();
        if (obj instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        return LocalDate.parse(obj.toString().substring(0, 10));
    }

    private BigDecimal parseBigDecimal(Object obj) {
        if (obj == null) return null;
        if (obj instanceof BigDecimal bd) return bd;
        if (obj instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(obj.toString());
    }
}
