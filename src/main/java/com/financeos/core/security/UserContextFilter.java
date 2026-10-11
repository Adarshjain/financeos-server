package com.financeos.core.security;

import com.financeos.core.observability.Events;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Slf4j
@Component
@Order(0)
public class UserContextFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;

    public UserContextFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication != null && authentication.isAuthenticated()) {
                String email = authentication.getName();
                // We rely on email being the principal name as per current implementation
                if (email != null && !email.equals("anonymousUser")) {
                    Optional<User> user = userRepository.findByEmail(email);
                    if (user.isPresent()) {
                        UserContext.setCurrentUserId(user.get().getId());
                        MDC.put("userId", String.valueOf(user.get().getId()));
                    } else {
                        dropOrphanedSession(request);
                    }
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("userId");
            UserContext.clear();
        }
    }

    /**
     * A signed-in session whose user row is gone (the account was deleted but this session outlived
     * it). Carrying on would run the request with no current user, and the Hibernate userFilter is only
     * enabled for a known user — so filter-scoped reads would span every user. The session is
     * invalidated and the request continues signed out: protected routes then answer 401 through the
     * normal entry point, and the public login/signup routes still work.
     */
    private void dropOrphanedSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        log.warn("Signed-in session has no user row; invalidating it",
                StructuredArguments.keyValue("event", Events.AUTH_SESSION_ORPHANED),
                StructuredArguments.keyValue("sessionIdHash",
                        session != null ? SessionHashUtils.hashSessionId(session.getId()) : null));
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
