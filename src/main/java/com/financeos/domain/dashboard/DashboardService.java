package com.financeos.domain.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.dashboard.dto.CreateDashboardRequest;
import com.financeos.api.dashboard.dto.DashboardResponse;
import com.financeos.api.dashboard.dto.DashboardSummaryResponse;
import com.financeos.api.dashboard.dto.ReportRef;
import com.financeos.api.dashboard.dto.UpdateDashboardRequest;
import com.financeos.api.dashboard.dto.WidgetResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.report.Report;
import com.financeos.domain.report.ReportRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@Slf4j
public class DashboardService {

    private final DashboardRepository dashboardRepository;
    private final ReportRepository reportRepository;
    private final UserRepository userRepository;
    private final DashboardValidator validator;
    private final ObjectMapper mapper;

    public DashboardService(DashboardRepository dashboardRepository, ReportRepository reportRepository,
            UserRepository userRepository, DashboardValidator validator, ObjectMapper mapper) {
        this.dashboardRepository = dashboardRepository;
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
        this.validator = validator;
        this.mapper = mapper;
    }

    public DashboardResponse create(CreateDashboardRequest req) {
        List<DashboardWidget> widgets = req.widgets() == null ? List.of() : req.widgets();
        validator.validate(req.name(), widgets);

        UUID userId = UserContext.getCurrentUserId();
        User user = userRepository.getReferenceById(userId);
        Dashboard dashboard = new Dashboard(user, req.name(), req.description(), serialize(widgets));
        applyDefault(dashboard, req.isDefault());
        return toResponse(dashboardRepository.save(dashboard));
    }

    @Transactional(readOnly = true)
    public List<DashboardResponse> list() {
        return dashboardRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public DashboardResponse get(UUID id) {
        return toResponse(loadOwned(id));
    }

    public DashboardResponse update(UUID id, UpdateDashboardRequest req) {
        Dashboard dashboard = loadOwned(id);
        List<DashboardWidget> widgets = req.widgets() == null ? List.of() : req.widgets();
        validator.validate(req.name(), widgets);

        dashboard.setName(req.name());
        dashboard.setDescription(req.description());
        dashboard.setWidgets(serialize(widgets));
        applyDefault(dashboard, req.isDefault());
        return toResponse(dashboardRepository.save(dashboard));
    }

    public void delete(UUID id) {
        dashboardRepository.delete(loadOwned(id));
    }

    /** Applies the default flag on save: when true, clears any previous default for the user first. */
    private void applyDefault(Dashboard dashboard, boolean isDefault) {
        if (isDefault) {
            dashboardRepository.clearDefaultForUser(UserContext.getCurrentUserId());
        }
        dashboard.setDefault(isDefault);
    }

    /** The current user's default dashboard, or 404 if none is set. */
    @Transactional(readOnly = true)
    public DashboardResponse getDefault() {
        Dashboard dashboard = dashboardRepository.findDefault()
                .orElseThrow(() -> new ResourceNotFoundException("No default dashboard set"));
        return toResponse(dashboard);
    }

    // ------------------------------------------------------------------ helpers

    private Dashboard loadOwned(UUID id) {
        // findById bypasses the Hibernate userFilter, so verify ownership explicitly.
        Dashboard dashboard = dashboardRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dashboard", id));
        UUID userId = UserContext.getCurrentUserId();
        if (!dashboard.getUser().getId().equals(userId)) {
            log.error("Security Breach Attempt: User {} tried to access Dashboard {} owned by User {}",
                    userId, id, dashboard.getUser().getId());
            throw new ValidationException("You do not have permission to access this dashboard.");
        }
        return dashboard;
    }

    private DashboardResponse toResponse(Dashboard dashboard) {
        List<DashboardWidget> widgets = parse(dashboard.getWidgets());

        // Batch-resolve referenced reports, scoped to the current user (foreign/deleted -> absent).
        Set<UUID> reportIds = widgets.stream()
                .map(DashboardWidget::reportId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, Report> reportsById = reportRepository.findAllById(reportIds).stream()
                .collect(Collectors.toMap(Report::getId, r -> r));

        List<WidgetResponse> widgetResponses = widgets.stream().map(widget -> {
            Report report = widget.reportId() == null ? null : reportsById.get(widget.reportId());
            ReportRef ref = report != null
                    ? new ReportRef(report.getName(), report.getType(), true)
                    : new ReportRef(null, null, false);
            return new WidgetResponse(widget.id(), widget.reportId(), widget.title(), widget.layout(), ref);
        }).toList();

        return new DashboardResponse(dashboard.getId(), dashboard.getName(), dashboard.getDescription(),
                dashboard.isDefault(), widgetResponses, dashboard.getCreatedAt(), dashboard.getUpdatedAt());
    }

    private DashboardSummaryResponse toSummary(Dashboard dashboard) {
        return new DashboardSummaryResponse(dashboard.getId(), dashboard.getName(), dashboard.getDescription(),
                dashboard.isDefault(), parse(dashboard.getWidgets()).size(), dashboard.getCreatedAt(),
                dashboard.getUpdatedAt());
    }

    private String serialize(List<DashboardWidget> widgets) {
        try {
            return mapper.writeValueAsString(widgets);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize dashboard widgets", e);
        }
    }

    private List<DashboardWidget> parse(String json) {
        try {
            return mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, DashboardWidget.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse stored dashboard widgets", e);
        }
    }
}
