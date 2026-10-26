package com.financeos.chat.history;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessageRecord, UUID> {

    List<ChatMessageRecord> findByConversationIdOrderBySeqAsc(UUID conversationId);

    Optional<ChatMessageRecord> findByIdAndConversationId(UUID id, UUID conversationId);

    long countByConversationIdAndRole(UUID conversationId, String role);

    @Query("select coalesce(max(m.seq), 0) from ChatMessageRecord m where m.conversationId = :conversationId")
    int maxSeq(@Param("conversationId") UUID conversationId);

    @Modifying
    @Query("delete from ChatMessageRecord m where m.conversationId = :conversationId and m.seq > :seq")
    int deleteAfterSeq(@Param("conversationId") UUID conversationId, @Param("seq") int seq);
}
