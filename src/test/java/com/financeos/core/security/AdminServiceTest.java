package com.financeos.core.security;

import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock
    private UserRepository userRepository;

    private AppConfigProperties appConfigProperties;
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        appConfigProperties = new AppConfigProperties();
        adminService = new AdminService(appConfigProperties, userRepository);
    }

    @Test
    void testEmptyAdminListOpensAdminToEverySignedInUser() {
        appConfigProperties.getAdmin().setEmails(Collections.emptyList());

        assertTrue(adminService.isOpenToAll());
        assertTrue(adminService.isAdmin("anyone@example.test"));
        assertTrue(adminService.isAdmin(new User("anyone@example.test", "hash")));
        // identity is still required: no email, no admin
        assertFalse(adminService.isAdmin((String) null));
        assertFalse(adminService.isAdmin("   "));
        assertFalse(adminService.isAdmin(new User(null, "hash")));
        assertFalse(adminService.isAdmin((User) null));
        assertFalse(adminService.isAdmin((UUID) null));
    }

    @Test
    void testBlankOnlyEntriesCountAsEmptyAndOpen() {
        appConfigProperties.getAdmin().setEmails(List.of("", "   "));

        assertTrue(adminService.isOpenToAll());
        assertTrue(adminService.isAdmin("anyone@example.test"));
    }

    @Test
    void testConfiguredListClosesAccessToOthers() {
        appConfigProperties.getAdmin().setEmails(List.of("admin@example.test"));

        assertFalse(adminService.isOpenToAll());
        assertFalse(adminService.isAdmin("anyone@example.test"));
    }

    @Test
    void testExactDifferentCaseAndPaddedWhitespaceMatchesReturnTrue() {
        appConfigProperties.getAdmin().setEmails(List.of("  admin@example.test  ", "SuperAdmin@Financeos.com"));

        // Exact
        assertTrue(adminService.isAdmin("admin@example.test"));

        // Different case
        assertTrue(adminService.isAdmin("ADMIN@EXAMPLE.TEST"));
        assertTrue(adminService.isAdmin("superadmin@financeos.com"));

        // Padded whitespace in query
        assertTrue(adminService.isAdmin(" admin@example.test "));
    }

    @Test
    void testNonMemberReturnsFalse() {
        appConfigProperties.getAdmin().setEmails(List.of("admin@example.test"));

        assertFalse(adminService.isAdmin("regular-user@example.test"));
    }

    @Test
    void testNullOrBlankEmailReturnsFalse() {
        appConfigProperties.getAdmin().setEmails(List.of("admin@example.test"));

        assertFalse(adminService.isAdmin((String) null));
        assertFalse(adminService.isAdmin(""));
        assertFalse(adminService.isAdmin("   "));
        assertFalse(adminService.isAdmin(new User(null, "hash")));
        assertFalse(adminService.isAdmin((User) null));
    }

    @Test
    void testUnknownUserIdReturnsFalseInOpenMode() {
        appConfigProperties.getAdmin().setEmails(Collections.emptyList());
        UUID unknownOpen = UUID.randomUUID();
        when(userRepository.findById(unknownOpen)).thenReturn(Optional.empty());

        assertFalse(adminService.isAdmin(unknownOpen));
    }

    @Test
    void testUnknownUserIdReturnsFalse() {
        appConfigProperties.getAdmin().setEmails(List.of("admin@example.test"));
        UUID unknownId = UUID.randomUUID();
        when(userRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertFalse(adminService.isAdmin(unknownId));
        assertFalse(adminService.isAdmin((UUID) null));
    }

    @Test
    void testKnownUserIdResolvesAdminStatus() {
        appConfigProperties.getAdmin().setEmails(List.of("admin@example.test"));
        UUID adminId = UUID.randomUUID();
        User adminUser = new User("admin@example.test", "hash");
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));

        assertTrue(adminService.isAdmin(adminId));
    }
}
