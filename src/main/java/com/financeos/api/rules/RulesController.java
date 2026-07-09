package com.financeos.api.rules;

import com.financeos.api.rules.dto.CreateRuleRequest;
import com.financeos.api.rules.dto.RuleResponse;
import com.financeos.api.rules.dto.UpdateRuleRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.categorization.CategoryRule;
import com.financeos.domain.categorization.CategoryRuleRepository;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.categorization.DescriptionNormalizer;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rules")
@Slf4j
public class RulesController {

    private final CategoryRuleRepository categoryRuleRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final CategorizationService categorizationService;

    public RulesController(CategoryRuleRepository categoryRuleRepository,
                           CategoryRepository categoryRepository,
                           UserRepository userRepository,
                           CategorizationService categorizationService) {
        this.categoryRuleRepository = categoryRuleRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
        this.categorizationService = categorizationService;
    }

    @GetMapping
    public ResponseEntity<Page<RuleResponse>> getRules(
            @RequestParam(required = false) Boolean verified,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {

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

        String normalizedKey = DescriptionNormalizer.normalize(request.merchantKey());
        if (normalizedKey.length() < 3) {
            throw new ValidationException("Merchant key length must be at least 3 characters after normalization.");
        }

        // Check for duplicate key
        if (categoryRuleRepository.findByUserIdAndMerchantKey(currentSessionUserId, normalizedKey).isPresent()) {
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
        rule.setMerchantKey(normalizedKey);
        rule.setDisplayName(request.displayName() != null && !request.displayName().isBlank() ? request.displayName() : request.merchantKey());
        rule.setVerified(true);
        rule.setSource("USER");
        rule.setCategories(new HashSet<>(categories));

        CategoryRule saved = categoryRuleRepository.save(rule);
        return ResponseEntity.status(HttpStatus.CREATED).body(RuleResponse.from(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RuleResponse> updateRule(
            @PathVariable UUID id,
            @RequestBody UpdateRuleRequest request) {

        UUID currentSessionUserId = UserContext.getCurrentUserId();

        CategoryRule rule = categoryRuleRepository.findWithCategoriesById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Rule", id));

        // SECURITY: verify ownership
        if (!rule.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to modify this rule.");
        }

        if (request.merchantKey() != null) {
            String normalizedKey = DescriptionNormalizer.normalize(request.merchantKey());
            if (normalizedKey.length() < 3) {
                throw new ValidationException("Merchant key length must be at least 3 characters after normalization.");
            }
            if (!normalizedKey.equals(rule.getMerchantKey())) {
                if (categoryRuleRepository.findByUserIdAndMerchantKey(currentSessionUserId, normalizedKey).isPresent()) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).build();
                }
                rule.setMerchantKey(normalizedKey);
            }
        }

        if (request.displayName() != null) {
            rule.setDisplayName(request.displayName());
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
