package com.financeos.domain.notification.bill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Mapping of {@code bill_payments} (V169) so the test schema (ddl-auto) creates it. Rows are read and written
 * through {@link BillPaymentStore} in plain SQL; this class is the schema definition only.
 */
@Entity
@Table(name = "bill_payments", uniqueConstraints = {
        @UniqueConstraint(name = "idx_bill_pay_card", columnNames = "card_txn_id"),
        @UniqueConstraint(name = "idx_bill_pay_bank", columnNames = "bank_txn_id") })
@Getter
@Setter
@NoArgsConstructor
public class BillPaymentEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "statement_id", nullable = false, length = 36)
    private String statementId;

    @Column(name = "card_txn_id", nullable = false, length = 36)
    private String cardTxnId;

    @Column(name = "bank_txn_id", length = 36)
    private String bankTxnId;

    @Column(name = "bank_txn_created", nullable = false)
    private int bankTxnCreated;

    @Column(nullable = false, length = 10)
    private String origin;

    @Column(name = "link_id", length = 36)
    private String linkId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
