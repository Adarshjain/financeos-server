package com.financeos.core.security;

import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AdminService {

    private final AppConfigProperties appConfigProperties;
    private final UserRepository userRepository;

    public AdminService(AppConfigProperties appConfigProperties, UserRepository userRepository) {
        this.appConfigProperties = appConfigProperties;
        this.userRepository = userRepository;
    }

    public boolean isAdmin(UUID userId) {
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId)
                .map(this::isAdmin)
                .orElse(false);
    }

    public boolean isAdmin(User user) {
        if (user == null || user.getEmail() == null) {
            return false;
        }
        return isAdmin(user.getEmail());
    }

    public boolean isAdmin(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        if (isOpenToAll()) {
            return true;
        }
        return configuredEmails().stream()
                .anyMatch(adminEmail -> adminEmail.equalsIgnoreCase(email.trim()));
    }

    /**
     * Admin access is open to every signed-in user when {@code app.admin.emails} (env {@code ADMIN_EMAILS})
     * is unset or blank; once at least one email is listed, only those emails are admins.
     * User decision 2026-09-07 — a single-tenant deployment prefers zero-config over fail-closed.
     */
    public boolean isOpenToAll() {
        return configuredEmails().isEmpty();
    }

    private List<String> configuredEmails() {
        List<String> adminEmails = appConfigProperties.getAdmin().getEmails();
        if (adminEmails == null) {
            return List.of();
        }
        return adminEmails.stream()
                .filter(e -> e != null && !e.isBlank())
                .map(String::trim)
                .toList();
    }
}
