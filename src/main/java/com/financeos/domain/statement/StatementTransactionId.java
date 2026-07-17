package com.financeos.domain.statement;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class StatementTransactionId implements Serializable {

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "statement_id", length = 36)
    private UUID statementId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "transaction_id", length = 36)
    private UUID transactionId;
}
