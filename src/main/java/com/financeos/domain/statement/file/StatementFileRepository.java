package com.financeos.domain.statement.file;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Deliberately not a JpaRepository (no findAll/findById): every query selects scalars only; the BLOB is loaded solely
 * by {@link #findByIdAndUserId}.
 */
public interface StatementFileRepository extends Repository<StatementFile, UUID> {

    <S extends StatementFile> S save(S file);

    <S extends StatementFile> S saveAndFlush(S file);

    long count();

    @Query("SELECT f.id FROM StatementFile f WHERE f.userId = :userId AND f.sha256 = :sha256")
    Optional<UUID> findIdByUserIdAndSha256(@Param("userId") UUID userId, @Param("sha256") String sha256);

    @Query("SELECT COALESCE(SUM(f.sizeBytes), 0) FROM StatementFile f WHERE f.userId = :userId")
    long sumSizeBytesByUserId(@Param("userId") UUID userId);

    @Query("SELECT COALESCE(SUM(f.sizeBytes), 0) FROM StatementFile f")
    long sumSizeBytes();

    Optional<StatementFile> findByIdAndUserId(UUID id, UUID userId);

    /** Deletes the user's unreferenced files created before {@code cutoff}. */
    @Modifying
    @Query("DELETE FROM StatementFile f WHERE f.userId = :userId AND f.createdAt < :cutoff "
            + "AND NOT EXISTS (SELECT 1 FROM Statement s WHERE s.fileId = f.id)")
    int deleteOrphansByUserId(@Param("userId") UUID userId, @Param("cutoff") Instant cutoff);

    @Modifying
    @Query("DELETE FROM StatementFile f WHERE f.id = :id AND f.userId = :userId AND f.createdAt < :cutoff "
            + "AND NOT EXISTS (SELECT 1 FROM Statement s WHERE s.fileId = f.id)")
    int deleteByIdIfUnreferenced(@Param("id") UUID id, @Param("userId") UUID userId, @Param("cutoff") Instant cutoff);
}
