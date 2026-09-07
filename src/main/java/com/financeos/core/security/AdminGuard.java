package com.financeos.core.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AdminGuard {

    private final AdminService adminService;

    public AdminGuard(AdminService adminService) {
        this.adminService = adminService;
    }

    public void require() {
        UUID userId = UserContext.getCurrentUserId();
        require(userId);
    }

    public void require(UUID userId) {
        if (userId == null || !adminService.isAdmin(userId)) {
            throw new AccessDeniedException("Admin access required");
        }
    }
}
