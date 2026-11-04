package com.financeos.gmail.classify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One sender domain of a bank (global reference data, not per user). */
@Entity
@Table(name = "bank_sender_domains")
@Getter
@Setter
@NoArgsConstructor
public class BankSenderDomain {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "bank_id", nullable = false, length = 40)
    private String bankId;

    @Column(nullable = false)
    private String domain;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
