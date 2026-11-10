package com.financeos.domain.statement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StatementParseRecordRepository extends JpaRepository<StatementParseRecord, UUID> {
}
