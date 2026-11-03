package com.financeos.gmail.accountdetect.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Keyed by user id; every query names the user explicitly (no Hibernate user filter inside jobs). */
@Repository
public interface AccountDetectStateRepository extends JpaRepository<AccountDetectState, UUID> {

    @Modifying
    @Query("delete from AccountDetectState s where s.userId = :userId")
    int deleteForUser(@Param("userId") UUID userId);
}
