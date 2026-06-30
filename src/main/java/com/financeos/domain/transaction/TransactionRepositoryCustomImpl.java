package com.financeos.domain.transaction;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
