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
    public Page<TransactionRepository.TransactionBalanceProjection> findFiltered(
            UUID userId,
            TransactionSearchCriteria criteria,
            Pageable pageable) {

        queryBuilder.validate(criteria, pageable);

        // 1. Build and execute count query
        TransactionListQueryBuilder.QueryResult countResult = queryBuilder.buildCountQuery(userId, criteria);
        Query countQuery = entityManager.createNativeQuery(countResult.sql());
        countResult.params().forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        if (total == 0) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        // 2. Build and execute data query
        TransactionListQueryBuilder.QueryResult dataResult = queryBuilder.buildDataQuery(userId, criteria, pageable);
        Query dataQuery = entityManager.createNativeQuery(dataResult.sql());
        dataResult.params().forEach(dataQuery::setParameter);

        // Apply pagination limit/offset
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());

        List<?> rows = dataQuery.getResultList();
        List<TransactionRepository.TransactionBalanceProjection> projections = new ArrayList<>();
        for (Object row : rows) {
            Object[] array = (Object[]) row;

            UUID id;
            if (array[0] instanceof UUID) {
                id = (UUID) array[0];
            } else {
                id = UUID.fromString(array[0].toString());
            }

            BigDecimal balance = BigDecimal.ZERO;
            if (array[1] instanceof BigDecimal) {
                balance = (BigDecimal) array[1];
            } else if (array[1] != null) {
                balance = new BigDecimal(array[1].toString());
            }

            projections.add(new TransactionBalanceProjectionImpl(id, balance));
        }

        return new PageImpl<>(projections, pageable, total);
    }

    private static class TransactionBalanceProjectionImpl implements TransactionRepository.TransactionBalanceProjection {
        private final UUID id;
        private final BigDecimal balance;

        public TransactionBalanceProjectionImpl(UUID id, BigDecimal balance) {
            this.id = id;
            this.balance = balance;
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
