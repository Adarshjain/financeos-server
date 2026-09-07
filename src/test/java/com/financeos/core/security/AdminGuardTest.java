package com.financeos.core.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminGuardTest {

    @Mock
    private AdminService adminService;

    private AdminGuard adminGuard;

    @BeforeEach
    void setUp() {
        adminGuard = new AdminGuard(adminService);
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testRequireThrowsWhenCurrentUserIsNull() {
        UserContext.setCurrentUserId(null);

        assertThrows(AccessDeniedException.class, () -> adminGuard.require());
        assertThrows(AccessDeniedException.class, () -> adminGuard.require(null));
    }

    @Test
    void testRequireThrowsWhenCurrentUserIsNotAdmin() {
        UUID nonAdminId = UUID.randomUUID();
        UserContext.setCurrentUserId(nonAdminId);
        when(adminService.isAdmin(nonAdminId)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> adminGuard.require());
        assertThrows(AccessDeniedException.class, () -> adminGuard.require(nonAdminId));
    }

    @Test
    void testRequirePassesWhenCurrentUserIsAdmin() {
        UUID adminId = UUID.randomUUID();
        UserContext.setCurrentUserId(adminId);
        when(adminService.isAdmin(adminId)).thenReturn(true);

        assertDoesNotThrow(() -> adminGuard.require());
        assertDoesNotThrow(() -> adminGuard.require(adminId));
    }
}
