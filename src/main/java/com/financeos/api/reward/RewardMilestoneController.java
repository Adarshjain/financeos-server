package com.financeos.api.reward;

import com.financeos.api.reward.dto.RewardMilestoneRequest;
import com.financeos.api.reward.dto.RewardMilestoneResponse;
import com.financeos.domain.reward.RewardMilestoneService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reward-milestones")
public class RewardMilestoneController {

    private final RewardMilestoneService rewardMilestoneService;

    public RewardMilestoneController(RewardMilestoneService rewardMilestoneService) {
        this.rewardMilestoneService = rewardMilestoneService;
    }

    @GetMapping
    public ResponseEntity<List<RewardMilestoneResponse>> getMilestones(@RequestParam UUID accountId) {
        return ResponseEntity.ok(rewardMilestoneService.listForAccount(accountId));
    }

    @PostMapping
    public ResponseEntity<RewardMilestoneResponse> createMilestone(
            @Valid @RequestBody RewardMilestoneRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rewardMilestoneService.create(request));
    }

    /** Full overwrite; accountId in the body is ignored. */
    @PutMapping("/{id}")
    public ResponseEntity<RewardMilestoneResponse> updateMilestone(
            @PathVariable UUID id,
            @Valid @RequestBody RewardMilestoneRequest request) {
        return ResponseEntity.ok(rewardMilestoneService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMilestone(@PathVariable UUID id) {
        rewardMilestoneService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
