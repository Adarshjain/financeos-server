package com.financeos.gmail.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface GmailBackfillDemandRepository extends JpaRepository<GmailBackfillDemand, UUID> {
}
