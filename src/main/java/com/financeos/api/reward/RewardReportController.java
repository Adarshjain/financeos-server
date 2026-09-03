package com.financeos.api.reward;

import org.springdoc.core.annotations.ParameterObject;

import com.financeos.api.reward.dto.RewardLineResponse;
import com.financeos.api.reward.dto.RewardReportResponse;
import com.financeos.domain.reward.RewardCalculationService;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rewards")
public class RewardReportController {

    private final RewardCalculationService rewardCalculationService;

    public RewardReportController(RewardCalculationService rewardCalculationService) {
        this.rewardCalculationService = rewardCalculationService;
    }

    @GetMapping("/report")
    public ResponseEntity<RewardReportResponse> getReport(
            @RequestParam UUID accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(rewardCalculationService.report(accountId, from, to));
    }

    /**
     * Paginated per-transaction reward lines (the drill-down). The engine computes the
     * full window once per request and pages in memory — fine at personal scale.
     */
    @GetMapping("/lines")
    public ResponseEntity<Page<RewardLineResponse>> getLines(
            @RequestParam UUID accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID ruleId,
            @ParameterObject @PageableDefault(size = 50) Pageable pageable) {
        List<RewardLineResponse> lines = rewardCalculationService.lines(accountId, from, to, ruleId);
        int start = (int) Math.min(pageable.getOffset(), lines.size());
        int end = Math.min(start + pageable.getPageSize(), lines.size());
        Page<RewardLineResponse> page = new PageImpl<>(lines.subList(start, end), pageable, lines.size());
        return ResponseEntity.ok(page);
    }
}
