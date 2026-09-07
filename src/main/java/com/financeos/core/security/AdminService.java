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
        List<String> adminEmails = appConfigProperties.getAdmin().getEmails();
        if (adminEmails == null || adminEmails.isEmpty()) {
            return false;
        }
        return adminEmails.stream()
                .filter(e -> e != null && !e.isBlank())
                .anyMatch(adminEmail -> adminEmail.trim().equalsIgnoreCase(email.trim()));
    }
}
