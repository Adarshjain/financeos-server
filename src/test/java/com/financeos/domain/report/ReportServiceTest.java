package com.financeos.domain.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@link ReportService#duplicate}. */
class ReportServiceTest {

    private ReportRepository reportRepository;
    private ReportService service;
    private final UUID userId = UUID.randomUUID();
    private User owner;

    @BeforeEach
    void setUp() {
        reportRepository = mock(ReportRepository.class);
        service = new ReportService(reportRepository, mock(ReportDefinitionValidator.class),
                mock(UserRepository.class), new ObjectMapper());
        owner = new User();
        owner.setId(userId);
        UserContext.setCurrentUserId(userId);
        when(reportRepository.save(any(Report.class))).thenAnswer(inv -> {
            Report r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Report source(User user, String description) {
        Report r = new Report(user, "Monthly spend", ReportType.TABLE, "transactions",
                "{\"mode\":\"raw\",\"columns\":[\"date\"],\"filters\":[]}");
        r.setId(UUID.randomUUID());
        r.setDescription(description);
        when(reportRepository.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void duplicateCopiesAnOwnedReportUnderACopyName() {
        Report src = source(owner, "By day");

        Report copy = service.duplicate(src.getId());

        ArgumentCaptor<Report> saved = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(saved.capture());
        assertSame(copy, saved.getValue());
        assertNotSame(src, copy);
        assertEquals("Monthly spend (copy)", copy.getName());
        assertEquals(ReportType.TABLE, copy.getType());
        assertEquals("transactions", copy.getDatasource());
        assertEquals(src.getDefinition(), copy.getDefinition());
        assertEquals("By day", copy.getDescription());
        assertSame(owner, copy.getUser());
        assertEquals("Monthly spend", src.getName());
    }

    @Test
    void duplicateKeepsAMissingDescriptionMissing() {
        Report copy = service.duplicate(source(owner, null).getId());
        assertNull(copy.getDescription());
    }

    @Test
    void duplicateOfAnotherUsersReportIsRejected() {
        User other = new User();
        other.setId(UUID.randomUUID());
        Report foreign = source(other, null);

        assertThrows(ValidationException.class, () -> service.duplicate(foreign.getId()));
        verify(reportRepository, never()).save(any());
    }

    @Test
    void duplicateOfAMissingReportIs404() {
        UUID missing = UUID.randomUUID();
        when(reportRepository.findById(missing)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.duplicate(missing));
        verify(reportRepository, never()).save(any());
    }
}
