package com.financeos.domain.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AccountBrokerDetailsRepository extends JpaRepository<AccountBrokerDetails, UUID> {
}
