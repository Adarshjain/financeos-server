package com.financeos.domain.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.report.dto.CreateReportRequest;
import com.financeos.api.report.dto.UpdateReportRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.report.definition.ReportDefinition;
import com.financeos.domain.report.definition.ReportDefinitions;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
@Slf4j
public class ReportService {

    private final ReportRepository reportRepository;
    private final ReportDefinitionValidator validator;
    private final UserRepository userRepository;
    private final ObjectMapper mapper;

    public ReportService(ReportRepository reportRepository,
            ReportDefinitionValidator validator,
            UserRepository userRepository,
            ObjectMapper mapper) {
        this.reportRepository = reportRepository;
        this.validator = validator;
        this.userRepository = userRepository;
        this.mapper = mapper;
    }

    public Report create(CreateReportRequest req) {
        ReportDefinition definition = ReportDefinitions.parse(req.type(), req.definition(), mapper);

        if (!definition.type().equals(req.type())) {
            throw new ValidationException(
                    "Report definition type mismatch: expected " + req.type() + " but got " + definition.type());
        }

        validator.validate(req.datasource(), definition);

        String json = ReportDefinitions.toJson(definition, mapper);

        UUID currentSessionUserId = UserContext.getCurrentUserId();
        User currentUser = userRepository.getReferenceById(currentSessionUserId);

        Report report = new Report(currentUser, req.name(), req.type(), req.datasource(), json);
        report.setDescription(req.description());
        return reportRepository.save(report);
    }

    @Transactional(readOnly = true)
    public List<Report> list(ReportType type) {
        if (type == null) {
            return reportRepository.findAll();
        }
        return reportRepository.findAllByType(type);
    }

    @Transactional(readOnly = true)
    public Report get(UUID id) {
        Report report = reportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Report", id));

        UUID currentSessionUserId = UserContext.getCurrentUserId();
        if (!report.getUser().getId().equals(currentSessionUserId)) {
            log.error("Security Breach Attempt: User {} tried to access Report {} owned by User {}",
                    currentSessionUserId, id, report.getUser().getId());
            throw new ValidationException("You do not have permission to access this report.");
        }

        return report;
    }

    public Report update(UUID id, UpdateReportRequest req) {
        Report report = reportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Report", id));

        UUID currentSessionUserId = UserContext.getCurrentUserId();
        if (!report.getUser().getId().equals(currentSessionUserId)) {
            log.error("Security Breach Attempt: User {} tried to update Report {} owned by User {}",
                    currentSessionUserId, id, report.getUser().getId());
            throw new ValidationException("You do not have permission to update this report.");
        }

        ReportDefinition definition = ReportDefinitions.parse(report.getType(), req.definition(), mapper);
        validator.validate(report.getDatasource(), definition);

        String json = ReportDefinitions.toJson(definition, mapper);

        report.setName(req.name());
        report.setDescription(req.description());
        report.setDefinition(json);

        return reportRepository.save(report);
    }

    public void delete(UUID id) {
        Report report = reportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Report", id));

        UUID currentSessionUserId = UserContext.getCurrentUserId();
        if (!report.getUser().getId().equals(currentSessionUserId)) {
            log.error("Security Breach Attempt: User {} tried to delete Report {} owned by User {}",
                    currentSessionUserId, id, report.getUser().getId());
            throw new ValidationException("You do not have permission to delete this report.");
        }

        reportRepository.delete(report);
    }
}
