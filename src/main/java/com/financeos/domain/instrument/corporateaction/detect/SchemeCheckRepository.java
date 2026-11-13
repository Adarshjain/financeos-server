package com.financeos.domain.instrument.corporateaction.detect;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Every read names the owner: the worker runs without the request filter. */
@Repository
public interface SchemeCheckRepository extends JpaRepository<SchemeCheck, UUID> {

    @Query("SELECT c FROM SchemeCheck c JOIN FETCH c.instrument WHERE c.user.id = :userId")
    List<SchemeCheck> findOwned(@Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM SchemeCheck c WHERE c.user.id = :userId AND c.instrument.id = :instrumentId AND c.kind = :kind")
    void deleteFor(@Param("userId") UUID userId, @Param("instrumentId") UUID instrumentId, @Param("kind") String kind);
}
