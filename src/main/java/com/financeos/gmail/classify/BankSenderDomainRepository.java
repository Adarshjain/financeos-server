package com.financeos.gmail.classify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BankSenderDomainRepository extends JpaRepository<BankSenderDomain, UUID> {
}
