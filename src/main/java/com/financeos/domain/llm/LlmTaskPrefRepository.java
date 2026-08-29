package com.financeos.domain.llm;

import com.financeos.llm.LlmTaskGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LlmTaskPrefRepository extends JpaRepository<LlmTaskPref, UUID> {

    List<LlmTaskPref> findByUserIdAndTaskGroupOrderByPositionAsc(UUID userId, LlmTaskGroup taskGroup);

    List<LlmTaskPref> findByUserIdOrderByTaskGroupAscPositionAsc(UUID userId);

    void deleteByUserIdAndTaskGroup(UUID userId, LlmTaskGroup taskGroup);
}
