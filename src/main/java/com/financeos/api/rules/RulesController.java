package com.financeos.api.rules;

import org.springdoc.core.annotations.ParameterObject;

import com.financeos.api.rules.dto.ApplyRuleRequest;
import com.financeos.api.rules.dto.ApplyRuleResponse;
import com.financeos.api.rules.dto.CreateRuleRequest;
import com.financeos.api.rules.dto.PreviewMatchesRequest;
import com.financeos.api.rules.dto.RuleMatchTransactionResponse;
import com.financeos.api.rules.dto.RuleResponse;
import com.financeos.api.rules.dto.UpdateRuleRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.categorization.CategoryRule;
import com.financeos.domain.categorization.CategoryRuleRepository;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.categorization.MatchType;
import com.financeos.domain.categorization.RuleMatchService;
import com.financeos.domain.categorization.RuleMatcher;
import com.financeos.domain.categorization.RuleMatchService.MatchedTransaction;
import com.financeos.domain.category.Category;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rules")
@Slf4j
public class RulesController {

    private final CategoryRuleRepository categoryRuleRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final CategorizationService categorizationService;
    private final RuleMatchService ruleMatchService;
    private final com.financeos.domain.job.JobService jobService;

    public RulesController(CategoryRuleRepository categoryRuleRepository,
                           CategoryRepository categoryRepository,
                           UserRepository userRepository,
                           CategorizationService categorizationService,
                           RuleMatchService ruleMatchService,
                           com.financeos.domain.job.JobService jobService) {
        this.categoryRuleRepository = categoryRuleRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
        this.categorizationService = categorizationService;
        this.ruleMatchService = ruleMatchService;
        this.jobService = jobService;
    }

    private static MatchType parseMatchType(String value) {
        if (value == null || value.isBlank()) {
            return MatchType.MERCHANT_KEY;
        }
        try {
            return MatchType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown match type: " + value);
        }
    }

    @GetMapping
    public ResponseEntity<Page<RuleResponse>> getRules(
            @RequestParam(required = false) Boolean verified,
            @RequestParam(required = false) String search,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {

        UUID currentSessionUserId = UserContext.getCurrentUserId();

        Pageable sortedPageable = pageable;
        if (pageable.getSort().isUnsorted()) {
            sortedPageable = PageRequest.of(
                    pageable.getPageNumber(),
                    pageable.getPageSize(),
                    Sort.by(Sort.Order.asc("verified"), Sort.Order.desc("lastAppliedAt"))
            );
        }

        Page<CategoryRule> rules = categoryRuleRepository.findRules(currentSessionUserId, verified, search, sortedPageable);
        Page<RuleResponse> response = rules.map(RuleResponse::from);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<RuleResponse> createRule(@Valid @RequestBody CreateRuleRequest request) {
        UUID currentSessionUserId = UserContext.getCurrentUserId();

        MatchType matchType = parseMatchType(request.matchType());
        String canonicalKey = RuleMatcher.canonicalizePattern(matchType, request.merchantKey());

        // Check for duplicate key
        if (categoryRuleRepository.findByUserIdAndMerchantKeyAndMatchType(currentSessionUserId, canonicalKey, matchType).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        // Validate categories exist and belong to user
        List<Category> categories = categoryRepository.findAllById(request.categoryIds());
        if (categories.size() != request.categoryIds().size()) {
            throw new ResourceNotFoundException("One or more categories not found");
        }

        for (Category category : categories) {
            if (!category.getUser().getId().equals(currentSessionUserId)) {
                throw new ValidationException("You do not have permission to use category: " + category.getName());
            }
        }

        CategoryRule rule = new CategoryRule();
        rule.setUser(userRepository.getReferenceById(currentSessionUserId));
        rule.setMerchantKey(canonicalKey);
        rule.setMatchType(matchType);
        rule.setDisplayName(request.displayName() != null && !request.displayName().isBlank() ? request.displayName() : request.merchantKey());
        rule.setVerified(true);
        rule.setSource("USER");
        rule.setCategories(new HashSet<>(categories));
        rule.setMcc(request.mcc());

        CategoryRule saved = categoryRuleRepository.save(rule);
        return ResponseEntity.status(HttpStatus.CREATED).body(RuleResponse.from(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RuleResponse> updateRule(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRuleRequest request) {

        UUID currentSessionUserId = UserContext.getCurrentUserId();

        CategoryRule rule = categoryRuleRepository.findWithCategoriesById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Rule", id));

        // SECURITY: verify ownership
        if (!rule.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to modify this rule.");
        }

        if (request.merchantKey() != null || request.matchType() != null) {
            MatchType newType = request.matchType() != null ? parseMatchType(request.matchType()) : rule.getMatchType();
            String rawPattern = request.merchantKey() != null ? request.merchantKey() : rule.getMerchantKey();
            String canonicalKey = RuleMatcher.canonicalizePattern(newType, rawPattern);

            if (newType != rule.getMatchType() || !canonicalKey.equals(rule.getMerchantKey())) {
                boolean duplicate = categoryRuleRepository
                        .findByUserIdAndMerchantKeyAndMatchType(currentSessionUserId, canonicalKey, newType)
                        .filter(other -> !other.getId().equals(rule.getId()))
                        .isPresent();
                if (duplicate) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).build();
                }
                rule.setMerchantKey(canonicalKey);
                rule.setMatchType(newType);
            }
        }

        if (request.displayName() != null) {
            rule.setDisplayName(request.displayName());
        }

        if (request.mcc() != null) {
            rule.setMcc(request.mcc().isBlank() ? null : request.mcc());
        }

        if (request.categoryIds() != null) {
            List<Category> categories = categoryRepository.findAllById(request.categoryIds());
            if (categories.size() != request.categoryIds().size()) {
                throw new ResourceNotFoundException("One or more categories not found");
            }
            for (Category category : categories) {
                if (!category.getUser().getId().equals(currentSessionUserId)) {
                    throw new ValidationException("You do not have permission to use category: " + category.getName());
                }
            }

            // Category change triggers retroactive re-apply
            categorizationService.updateRuleCategories(rule, new HashSet<>(categories));
        } else {
            categoryRuleRepository.save(rule);
        }

        return ResponseEntity.ok(RuleResponse.from(rule));
    }

    @PostMapping("/{id}/verify")
    public ResponseEntity<RuleResponse> verifyRule(@PathVariable UUID id) {
        UUID currentSessionUserId = UserContext.getCurrentUserId();

        CategoryRule rule = categoryRuleRepository.findWithCategoriesById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Rule", id));

        // SECURITY: verify ownership
        if (!rule.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to modify this rule.");
        }

        categorizationService.verifyRule(rule);
        return ResponseEntity.ok(RuleResponse.from(rule));
    }

    /**
     * Paginated preview of the transactions a rule definition would match — works for
     * unsaved definitions, so the create/edit dialog can test a pattern before saving.
     * POST because patterns (especially regex) don't travel well in query strings.
     */
    @PostMapping("/preview-matches")
    public ResponseEntity<Page<RuleMatchTransactionResponse>> previewMatches(
            @Valid @RequestBody PreviewMatchesRequest request,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {

        UUID currentSessionUserId = UserContext.getCurrentUserId();

        MatchType matchType = parseMatchType(request.matchType());
        String canonicalKey = RuleMatcher.canonicalizePattern(matchType, request.merchantKey());

        Page<MatchedTransaction> matches = ruleMatchService.findMatches(
                currentSessionUserId, matchType, canonicalKey, pageable);
        return ResponseEntity.ok(matches.map(RuleMatchTransactionResponse::from));
    }

    /**
     * Applies a rule to the selected transactions (or all current matches with all=true):
     * they get the rule's categories and stay linked to the rule, so later edits to the
     * rule's categories keep propagating to them.
     */
    @PostMapping("/{id}/apply")
    public ResponseEntity<com.financeos.api.job.dto.EnqueueResponse> applyRule(
            @PathVariable UUID id,
            @RequestBody ApplyRuleRequest request) {

        UUID currentSessionUserId = UserContext.getCurrentUserId();

        CategoryRule rule = categoryRuleRepository.findWithCategoriesById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Rule", id));

        // SECURITY: verify ownership
        if (!rule.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to modify this rule.");
        }

        boolean all = Boolean.TRUE.equals(request.all());
        if (!all && (request.transactionIds() == null || request.transactionIds().isEmpty())) {
            throw new ValidationException("Provide transactionIds or set all=true.");
        }

        com.financeos.domain.job.Job job = jobService.enqueue(
                currentSessionUserId,
                com.financeos.domain.job.JobType.RULE_APPLY,
                com.financeos.domain.job.JobTrigger.USER,
                new com.financeos.domain.job.handlers.RuleApplyPayload(id, all, request.transactionIds()),
                null,
                id.toString()
        );

        return ResponseEntity.accepted().body(new com.financeos.api.job.dto.EnqueueResponse(job.getId()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable UUID id) {
        UUID currentSessionUserId = UserContext.getCurrentUserId();

        CategoryRule rule = categoryRuleRepository.findWithCategoriesById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Rule", id));

        // SECURITY: verify ownership
        if (!rule.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to delete this rule.");
        }

        categoryRuleRepository.delete(rule);
        return ResponseEntity.noContent().build();
    }
}
