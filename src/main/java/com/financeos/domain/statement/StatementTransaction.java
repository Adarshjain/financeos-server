package com.financeos.domain.statement;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "statement_transactions")
@Getter
@Setter
@NoArgsConstructor
public class StatementTransaction {

    @EmbeddedId
    private StatementTransactionId id;

    @Column(name = "line_index")
    private Integer lineIndex;

    @Column(name = "balance_after", precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(name = "chain_valid")
    private Boolean chainValid;

    public StatementTransaction(UUID statementId, UUID transactionId, Integer lineIndex, BigDecimal balanceAfter,
            Boolean chainValid) {
        this.id = new StatementTransactionId(statementId, transactionId);
        this.lineIndex = lineIndex;
        this.balanceAfter = balanceAfter;
        this.chainValid = chainValid;
    }
}
