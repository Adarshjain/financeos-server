package com.financeos.domain.category;

import com.financeos.domain.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TagRepository extends JpaRepository<Tag, UUID> {
    Optional<Tag> findByNameAndUser(String name, User user);

    List<Tag> findByUserId(UUID userId);

    List<Tag> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    /** One page of the user's tags whose lower-cased name matches {@code pattern} (a LIKE pattern), by name. */
    @Query("SELECT t FROM Tag t WHERE t.user.id = :userId AND LOWER(t.name) LIKE :pattern ESCAPE '\\' " +
           "ORDER BY LOWER(t.name), t.id")
    Page<Tag> findPage(@Param("userId") UUID userId, @Param("pattern") String pattern, Pageable pageable);

    /** Number of transactions carrying each of the given tags, one row per tag that has any. */
    @Query("SELECT tt.tag.id AS tagId, COUNT(tt) AS total FROM TransactionTag tt "
            + "WHERE tt.tag.id IN :tagIds GROUP BY tt.tag.id")
    List<TagCount> countTransactionsByTagIds(@Param("tagIds") Collection<UUID> tagIds);

    interface TagCount {
        UUID getTagId();

        long getTotal();
    }
}
