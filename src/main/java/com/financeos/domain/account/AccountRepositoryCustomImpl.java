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

        String sql = "SELECT " +
                "a.id AS account_id, " +
                "s.period_end AS anchor_date, " +
                "s.closing_balance AS anchor_closing_balance, " +
                "COALESCE(SUM(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END), 0) AS total_sum, " +
                "COALESCE(SUM(CASE WHEN s.period_end IS NOT NULL AND t.transaction_date > s.period_end THEN (CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END) ELSE 0 END), 0) AS post_anchor_sum " +
                "FROM accounts a " +
                "LEFT JOIN ( " +
                "    SELECT s1.id, s1.account_id, s1.period_end, s1.closing_balance " +
                "    FROM statements s1 " +
                "    WHERE s1.period_end IS NOT NULL " +
                "      AND s1.closing_balance IS NOT NULL " +
                "      AND s1.verdict != 'REJECTED' " +
                "      AND s1.id = ( " +
                "          SELECT s2.id FROM statements s2 " +
                "          WHERE s2.account_id = s1.account_id " +
                "            AND s2.period_end IS NOT NULL " +
                "            AND s2.closing_balance IS NOT NULL " +
                "            AND s2.verdict != 'REJECTED' " +
                "          ORDER BY s2.period_end DESC, s2.created_at DESC " +
                "          FETCH FIRST 1 ROWS ONLY " +
                "      ) " +
                ") s ON s.account_id = a.id " +
                "LEFT JOIN transactions t ON t.account_id = a.id " +
                "WHERE a.id IN (:accountIds) " +
                "GROUP BY a.id, s.period_end, s.closing_balance";

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

            if (accId != null) {
                result.put(accId, new AccountBalanceBatch(
                        accId,
                        anchorDate,
                        anchorClosingBalance,
                        totalSum != null ? totalSum : BigDecimal.ZERO,
                        postAnchorSum != null ? postAnchorSum : BigDecimal.ZERO
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
