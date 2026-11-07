package com.financeos.gmail.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GmailIgnoredNumberRepository extends JpaRepository<GmailIgnoredNumber, UUID> {

    @Query("SELECT i FROM GmailIgnoredNumber i WHERE i.userId = :userId ORDER BY i.createdAt DESC")
    List<GmailIgnoredNumber> findByUser(@Param("userId") UUID userId);

    @Query("SELECT i FROM GmailIgnoredNumber i WHERE i.userId = :userId AND i.bankId = :bankId AND i.last4 = :last4")
    Optional<GmailIgnoredNumber> findRule(@Param("userId") UUID userId, @Param("bankId") String bankId, @Param("last4") String last4);

    @Query("SELECT i FROM GmailIgnoredNumber i WHERE i.id = :id AND i.userId = :userId")
    Optional<GmailIgnoredNumber> findOwned(@Param("id") UUID id, @Param("userId") UUID userId);
}
