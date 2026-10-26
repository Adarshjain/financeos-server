package com.financeos.chat.history;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatConversationRepository extends JpaRepository<ChatConversation, UUID> {

    Optional<ChatConversation> findByIdAndUserId(UUID id, UUID userId);

    /** Rows of [id, title, createdAt, updatedAt, questionCount], newest updated first, counts in the same query. */
    @Query("select c.id, c.title, c.createdAt, c.updatedAt, "
            + "(select count(m) from ChatMessageRecord m where m.conversationId = c.id and m.role = 'USER') "
            + "from ChatConversation c where c.userId = :userId order by c.updatedAt desc, c.id")
    List<Object[]> findSummaries(@Param("userId") UUID userId, Pageable pageable);

    /** Row lock (select ... for update) serialising appends/retries of one conversation until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChatConversation c where c.id = :id")
    Optional<ChatConversation> lockById(@Param("id") UUID id);

    @Query("select c.id from ChatConversation c where c.userId = :userId order by c.updatedAt desc, c.id")
    List<UUID> findIdsNewestFirst(@Param("userId") UUID userId);

    @Modifying
    @Query("update ChatConversation c set c.updatedAt = :now where c.id = :id")
    int touch(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("update ChatConversation c set c.title = :title where c.id = :id and c.title is null")
    int setTitleIfNull(@Param("id") UUID id, @Param("title") String title);

    @Modifying
    @Query("delete from ChatConversation c where c.userId = :userId and c.id in :ids")
    int deleteByUserAndIds(@Param("userId") UUID userId, @Param("ids") List<UUID> ids);
}
