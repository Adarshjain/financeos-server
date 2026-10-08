package com.financeos.domain.inbox;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InboxItemStateRepository extends JpaRepository<InboxItemState, UUID> {

    List<InboxItemState> findByUserId(UUID userId);

    Optional<InboxItemState> findByUserIdAndItemKey(UUID userId, String itemKey);
}
