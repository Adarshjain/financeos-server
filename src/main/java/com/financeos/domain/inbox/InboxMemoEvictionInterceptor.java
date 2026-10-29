package com.financeos.domain.inbox;

import com.financeos.core.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

/** Drops the signed-in user's memoised inbox once any write of theirs has finished (its transaction is committed by then). */
@Component
public class InboxMemoEvictionInterceptor implements HandlerInterceptor {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    /** POSTs that only read (a filter body is too big for a query string); the shell calls some on every page. */
    static final List<String> READ_ONLY_POSTS = List.of(
            "/api/v1/transactions/search",
            "/api/v1/transactions/count",
            "/api/v1/rules/preview-matches",
            "/api/v1/reports/data",
            "/api/v1/reports/*/data",
            "/api/v1/reports/underlying/**",
            "/api/v1/reports/*/underlying/**",
            "/api/v1/dashboards/builtins/*/underlying/**");
    private static final AntPathMatcher PATHS = new AntPathMatcher();

    private final InboxMemo memo;

    public InboxMemoEvictionInterceptor(InboxMemo memo) {
        this.memo = memo;
    }

    /** Before the handler runs, so no entry built before the write can be served once its response is out. */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        evictForWrite(request);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        evictForWrite(request);
    }

    private void evictForWrite(HttpServletRequest request) {
        if (READ_METHODS.contains(request.getMethod().toUpperCase()) || isReadOnlyPost(request)) {
            return;
        }
        UUID userId = UserContext.getCurrentUserId();
        if (userId != null) {
            memo.evict(userId);
        }
    }

    private static boolean isReadOnlyPost(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return READ_ONLY_POSTS.stream().anyMatch(pattern -> PATHS.match(pattern, path));
    }
}
