package com.financeos.domain.nps;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NpsAccountRepository extends JpaRepository<NpsAccount, UUID> {

    /** The user's accounts with their EPF employer, oldest first. */
    @Query("SELECT a FROM NpsAccount a LEFT JOIN FETCH a.employer WHERE a.user.id = :userId ORDER BY a.createdAt, a.id")
    List<NpsAccount> findAllByUser_Id(@Param("userId") UUID userId);

    Optional<NpsAccount> findByIdAndUser_Id(UUID id, UUID userId);
}
