package com.financeos.domain.transaction;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Custom repository implementation executing dynamic transaction queries via EntityManager.
 */
@Repository
public class TransactionRepositoryCustomImpl implements TransactionRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    private final TransactionListQueryBuilder queryBuilder;

    public TransactionRepositoryCustomImpl(TransactionListQueryBuilder queryBuilder) {
        this.queryBuilder = queryBuilder;
    }

    @Override
    public Map<UUID, SumsAfter> sumsAfterCutoffs(Map<UUID, LocalDate> cutoffByAccountId) {
        Map<UUID, SumsAfter> result = new HashMap<>();
        if (cutoffByAccountId.isEmpty()) {
            return result;
        }
        StringBuilder jpql = new StringBuilder(
                "SELECT t.account.id, t.type, SUM(t.amount) FROM Transaction t WHERE ");
        List<Map.Entry<UUID, LocalDate>> entries = new ArrayList<>(cutoffByAccountId.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            jpql.append(i == 0 ? "" : " OR ").append("(t.account.id = :a").append(i).append(" AND t.date > :d").append(i).append(")");
        }
        jpql.append(" GROUP BY t.account.id, t.type");
        jakarta.persistence.Query query = entityManager.createQuery(jpql.toString());
        for (int i = 0; i < entries.size(); i++) {
            query.setParameter("a" + i, entries.get(i).getKey());
            query.setParameter("d" + i, entries.get(i).getValue());
        }
        Map<UUID, BigDecimal[]> sums = new HashMap<>();
        for (Object row : query.getResultList()) {
            Object[] cols = (Object[]) row;
            UUID accountId = (UUID) cols[0];
            BigDecimal sum = (BigDecimal) cols[2];
            BigDecimal[] pair = sums.computeIfAbsent(accountId, k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
            pair[cols[1] == TransactionType.CREDIT ? 0 : 1] = sum;
        }
        for (UUID accountId : cutoffByAccountId.keySet()) {
            BigDecimal[] pair = sums.getOrDefault(accountId, new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
            result.put(accountId, new SumsAfter(pair[0], pair[1]));
        }
        return result;
    }

    @Override
    public List<Transaction> findMatchCandidatesAny(TransactionType type, Collection<MatchWindow> windows) {
        List<MatchWindow> distinct = new ArrayList<>(new LinkedHashSet<>(windows));
        Map<UUID, Transaction> byId = new LinkedHashMap<>();
        for (int from = 0; from < distinct.size(); from += MATCH_WINDOW_CHUNK) {
            List<MatchWindow> chunk = distinct.subList(from, Math.min(from + MATCH_WINDOW_CHUNK, distinct.size()));
            StringBuilder jpql = new StringBuilder("SELECT t FROM Transaction t WHERE t.type = :type AND (");
            for (int i = 0; i < chunk.size(); i++) {
                jpql.append(i == 0 ? "" : " OR ")
                        .append("(t.amount BETWEEN :lo").append(i).append(" AND :hi").append(i)
                        .append(" AND t.date BETWEEN :from").append(i).append(" AND :to").append(i);
                if (chunk.get(i).accountId() != null) {
                    jpql.append(" AND t.account.id = :acct").append(i);
                }
                jpql.append(")");
            }
            jpql.append(")");
            jakarta.persistence.TypedQuery<Transaction> query = entityManager.createQuery(jpql.toString(), Transaction.class);
            query.setParameter("type", type);
            for (int i = 0; i < chunk.size(); i++) {
                MatchWindow w = chunk.get(i);
                query.setParameter("lo" + i, w.minAmount());
                query.setParameter("hi" + i, w.maxAmount());
                query.setParameter("from" + i, w.minDate());
                query.setParameter("to" + i, w.maxDate());
                if (w.accountId() != null) {
                    query.setParameter("acct" + i, w.accountId());
                }
            }
            for (Transaction t : query.getResultList()) {
                byId.putIfAbsent(t.getId(), t);
            }
        }
        return new ArrayList<>(byId.values());
    }

    @Override
    public List<DaySums> sumsByDayAfter(UUID accountId, LocalDate after) {
        jakarta.persistence.Query query = entityManager.createQuery(
                "SELECT t.date, t.type, SUM(t.amount) FROM Transaction t "
                        + "WHERE t.account.id = :a AND t.date > :d GROUP BY t.date, t.type ORDER BY t.date");
        query.setParameter("a", accountId);
        query.setParameter("d", after);
        Map<LocalDate, BigDecimal[]> byDay = new java.util.TreeMap<>();
        for (Object row : query.getResultList()) {
            Object[] cols = (Object[]) row;
            BigDecimal[] pair = byDay.computeIfAbsent((LocalDate) cols[0],
                    k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
            pair[cols[1] == TransactionType.CREDIT ? 0 : 1] = (BigDecimal) cols[2];
        }
        List<DaySums> result = new ArrayList<>();
        byDay.forEach((day, pair) -> result.add(new DaySums(day, pair[0], pair[1])));
        return result;
    }

    @Override
    public long countFiltered(UUID userId, TransactionSearchCriteria criteria) {
        queryBuilder.validate(criteria, Pageable.unpaged());
        TransactionListQueryBuilder.QueryResult countResult = queryBuilder.buildCountQuery(userId, criteria);
        Query countQuery = entityManager.createNativeQuery(countResult.sql());
        countResult.params().forEach(countQuery::setParameter);
        return ((Number) countQuery.getSingleResult()).longValue();
    }

    @Override
    public Page<TransactionRepository.TransactionBalanceProjection> findFiltered(
            UUID userId,
            TransactionSearchCriteria criteria,
            Pageable pageable) {

        queryBuilder.validate(criteria, pageable);

        // 1. The page itself (ids and accounts, no window work), in sort order.
        TransactionListQueryBuilder.QueryResult pageResult = queryBuilder.buildPageQuery(userId, criteria, pageable);
        Query pageQuery = entityManager.createNativeQuery(pageResult.sql());
        pageResult.params().forEach(pageQuery::setParameter);
        if (pageable.isPaged()) {
            pageQuery.setFirstResult((int) pageable.getOffset());
            pageQuery.setMaxResults(pageable.getPageSize());
        }
        List<?> pageRows = pageQuery.getResultList();
        if (pageRows.isEmpty() && pageable.getOffset() == 0) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        // 2. The total: a short first page already is the total, otherwise count.
        long total;
        if (pageable.isUnpaged() || (pageable.getOffset() == 0 && pageRows.size() < pageable.getPageSize())) {
            total = pageRows.size();
        } else {
            TransactionListQueryBuilder.QueryResult countResult = queryBuilder.buildCountQuery(userId, criteria);
            Query countQuery = entityManager.createNativeQuery(countResult.sql());
            countResult.params().forEach(countQuery::setParameter);
            total = ((Number) countQuery.getSingleResult()).longValue();
        }
        if (pageRows.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, total);
        }

        // 3. Running balances of just the page's rows: windowed over their accounts, up to the page's last date.
        List<String> ids = new ArrayList<>();
        java.util.Set<String> accountIds = new java.util.LinkedHashSet<>();
        LocalDate maxDate = null;
        for (Object row : pageRows) {
            Object[] array = (Object[]) row;
            ids.add(array[0].toString());
            if (array[1] != null) {
                accountIds.add(array[1].toString());
            }
            LocalDate date = toLocalDate(array[2]);
            if (maxDate == null || date.isAfter(maxDate)) {
                maxDate = date;
            }
        }
        Map<String, BigDecimal[]> balances = new java.util.HashMap<>();
        if (!accountIds.isEmpty()) {
            // Oracle caps an IN list at 1000 entries: a page beyond 900 rows is balanced in chunks.
            for (int from = 0; from < ids.size(); from += 900) {
                List<String> chunk = ids.subList(from, Math.min(ids.size(), from + 900));
                TransactionListQueryBuilder.QueryResult balanceResult = queryBuilder.buildBalanceQuery(userId, accountIds, maxDate, chunk);
                Query balanceQuery = entityManager.createNativeQuery(balanceResult.sql());
                balanceResult.params().forEach(balanceQuery::setParameter);
                for (Object row : balanceQuery.getResultList()) {
                    Object[] array = (Object[]) row;
                    balances.put(array[0].toString(), new BigDecimal[] { toDecimal(array[1]), toDecimal(array[2]) });
                }
            }
        }

        List<TransactionRepository.TransactionBalanceProjection> projections = new ArrayList<>();
        for (String id : ids) {
            BigDecimal[] pair = balances.get(id);
            BigDecimal balance = pair != null && pair[0] != null ? pair[0] : BigDecimal.ZERO;
            projections.add(new TransactionBalanceProjectionImpl(UUID.fromString(id), balance, pair != null ? pair[1] : null));
        }

        return new PageImpl<>(projections, pageable, total);
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        return LocalDate.parse(value.toString().substring(0, 10));
    }

    private static class TransactionBalanceProjectionImpl implements TransactionRepository.TransactionBalanceProjection {
        private final UUID id;
        private final BigDecimal balance;
        private final BigDecimal bankBalance;

        public TransactionBalanceProjectionImpl(UUID id, BigDecimal balance, BigDecimal bankBalance) {
            this.id = id;
            this.balance = balance;
            this.bankBalance = bankBalance;
        }

        @Override
        public BigDecimal getBankBalance() {
            return bankBalance;
        }

        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public BigDecimal getBalance() {
            return balance;
        }
    }
}
