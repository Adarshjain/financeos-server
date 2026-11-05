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

    /** Fail-closed variant: only an email listed in ADMIN_EMAILS passes; a blank list admits nobody. Returns the admin's email. */
    public String requireExplicit() {
        UUID userId = UserContext.getCurrentUserId();
        String email = adminService.explicitAdminEmail(userId);
        if (email == null) {
            throw new AccessDeniedException("Admin access required");
        }
        return email;
    }

    public void require(UUID userId) {
        if (userId == null || !adminService.isAdmin(userId)) {
            throw new AccessDeniedException("Admin access required");
        }
    }
}
