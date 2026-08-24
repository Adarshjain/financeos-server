package com.financeos.chat.orchestrator;

import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.category.Category;
import com.financeos.domain.category.CategoryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatGroundingService {

    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;

    private final Map<UUID, CachedGrounding> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L; // 5 minutes

    public ChatGroundingService(AccountRepository accountRepository, CategoryRepository categoryRepository) {
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
    }

    public String buildGroundingBlock() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            return buildStaticDateBlock();
        }

        long now = System.currentTimeMillis();
        CachedGrounding cached = cache.get(userId);
        if (cached != null && (now - cached.timestamp()) < CACHE_TTL_MS) {
            return cached.content();
        }

        String content = generateGroundingBlock(userId);
        cache.put(userId, new CachedGrounding(content, now));
        return content;
    }

    private String generateGroundingBlock(UUID userId) {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        String activeFy = (today.getMonthValue() >= 4)
                ? "FY " + year + "-" + String.format("%02d", (year + 1) % 100)
                : "FY " + (year - 1) + "-" + String.format("%02d", year % 100);

        StringBuilder sb = new StringBuilder();
        sb.append("=== CURRENT CONTEXT GROUNDING ===\n");
        sb.append("Today's Date: ").append(today).append("\n");
        sb.append("Active Indian FY: ").append(activeFy).append("\n\n");

        List<Account> accounts = accountRepository.findByUserId(userId);
        sb.append("User Accounts:\n");
        for (Account acct : accounts) {
            sb.append("- ").append(acct.getName()).append(" (id: ").append(acct.getId())
              .append(", type: ").append(acct.getType()).append(")\n");
        }

        List<Category> categories = categoryRepository.findByUserId(userId);
        sb.append("\nUser Categories (max 60):\n");
        int count = 0;
        for (Category cat : categories) {
            count++;
            if (count > 60) {
                sb.append("... [truncated ").append(categories.size() - 60).append(" additional categories]\n");
                break;
            }
            sb.append("- ").append(cat.getName()).append(" (id: ").append(cat.getId()).append(")\n");
        }

        sb.append("=================================\n");
        return sb.toString();
    }

    private String buildStaticDateBlock() {
        LocalDate today = LocalDate.now();
        return "Today's Date: " + today + "\n";
    }

    private record CachedGrounding(String content, long timestamp) {}
}
