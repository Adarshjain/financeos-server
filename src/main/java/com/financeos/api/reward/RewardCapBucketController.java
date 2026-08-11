package com.financeos.api.reward;

import com.financeos.api.reward.dto.RewardCapBucketRequest;
import com.financeos.api.reward.dto.RewardCapBucketResponse;
import com.financeos.domain.reward.RewardCapBucketService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reward-cap-buckets")
public class RewardCapBucketController {

    private final RewardCapBucketService rewardCapBucketService;

    public RewardCapBucketController(RewardCapBucketService rewardCapBucketService) {
        this.rewardCapBucketService = rewardCapBucketService;
    }

    @GetMapping
    public ResponseEntity<List<RewardCapBucketResponse>> getBuckets(@RequestParam UUID accountId) {
        return ResponseEntity.ok(rewardCapBucketService.listForAccount(accountId));
    }

    @PostMapping
    public ResponseEntity<RewardCapBucketResponse> createBucket(
            @Valid @RequestBody RewardCapBucketRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rewardCapBucketService.create(request));
    }

    /** Full overwrite; accountId in the body is ignored. */
    @PutMapping("/{id}")
    public ResponseEntity<RewardCapBucketResponse> updateBucket(
            @PathVariable UUID id,
            @Valid @RequestBody RewardCapBucketRequest request) {
        return ResponseEntity.ok(rewardCapBucketService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBucket(@PathVariable UUID id) {
        rewardCapBucketService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
