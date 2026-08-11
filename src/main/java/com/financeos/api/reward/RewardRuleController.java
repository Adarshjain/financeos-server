package com.financeos.api.reward;

import com.financeos.api.reward.dto.ReorderRewardRulesRequest;
import com.financeos.api.reward.dto.RewardRuleRequest;
import com.financeos.api.reward.dto.RewardRuleResponse;
import com.financeos.domain.reward.RewardRuleService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reward-rules")
public class RewardRuleController {

    private final RewardRuleService rewardRuleService;

    public RewardRuleController(RewardRuleService rewardRuleService) {
        this.rewardRuleService = rewardRuleService;
    }

    /** Rules of one account, in evaluation order (highest priority first). */
    @GetMapping
    public ResponseEntity<List<RewardRuleResponse>> getRules(@RequestParam UUID accountId) {
        return ResponseEntity.ok(rewardRuleService.listForAccount(accountId));
    }

    @PostMapping
    public ResponseEntity<RewardRuleResponse> createRule(@Valid @RequestBody RewardRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rewardRuleService.create(request));
    }

    /** Full overwrite of the rule definition; accountId in the body is ignored. */
    @PutMapping("/{id}")
    public ResponseEntity<RewardRuleResponse> updateRule(
            @PathVariable UUID id,
            @Valid @RequestBody RewardRuleRequest request) {
        return ResponseEntity.ok(rewardRuleService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable UUID id) {
        rewardRuleService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Reassigns priorities from a complete drag-ordered id list (first = evaluated first). */
    @PostMapping("/reorder")
    public ResponseEntity<List<RewardRuleResponse>> reorderRules(
            @Valid @RequestBody ReorderRewardRulesRequest request) {
        return ResponseEntity.ok(rewardRuleService.reorder(request.accountId(), request.orderedIds()));
    }
}
