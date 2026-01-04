package com.financeos.core.security;

import com.financeos.domain.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
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
                    userRepository.findByEmail(email)
                            .ifPresent(user -> UserContext.setCurrentUserId(user.getId()));
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}
