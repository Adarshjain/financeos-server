package com.financeos.gmail.accountdetect.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Every query names the user explicitly (no Hibernate user filter inside jobs). */
@Repository
public interface AccountDetectFactRepository extends JpaRepository<AccountDetectFact, UUID> {

    /** All of one user's facts in a stable order: internal date, then message id. */
    @Query("select f from AccountDetectFact f where f.userId = :userId order by f.internalDate asc nulls first, f.messageId asc")
    List<AccountDetectFact> findAllForUser(@Param("userId") UUID userId);

    @Query("select f.messageId from AccountDetectFact f where f.userId = :userId")
    List<String> findMessageIdsForUser(@Param("userId") UUID userId);

    @Modifying
    @Query("delete from AccountDetectFact f where f.userId = :userId")
    int deleteForUser(@Param("userId") UUID userId);

    /** Drops these rows of the user (a partial mail about to be stored again). */
    @Modifying
    @Query("delete from AccountDetectFact f where f.userId = :userId and f.messageId in :messageIds")
    int deleteForUserAndMessageIds(@Param("userId") UUID userId, @Param("messageIds") java.util.Collection<String> messageIds);
}
