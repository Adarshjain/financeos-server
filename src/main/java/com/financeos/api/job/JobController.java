package com.financeos.api.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.job.dto.JobResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.job.*;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobRepository jobRepository;
    private final JobService jobService;
    private final ObjectMapper objectMapper;
    private final AccountRepository accountRepository;

    public JobController(JobRepository jobRepository, JobService jobService, ObjectMapper objectMapper,
                         AccountRepository accountRepository) {
        this.jobRepository = jobRepository;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
        this.accountRepository = accountRepository;
    }

    @GetMapping
    public ResponseEntity<Page<JobResponse>> getJobs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {

        UUID userId = UserContext.getCurrentUserId();

        List<JobStatus> statusList = null;
        if (status != null && !status.isBlank()) {
          if ("active".equalsIgnoreCase(status.trim())) {
            statusList = List.of(JobStatus.PENDING, JobStatus.RUNNING);
          } else {
            try {
              statusList = Arrays.stream(status.split(","))
                      .map(String::trim)
                      .filter(s -> !s.isEmpty())
                      .map(s -> JobStatus.valueOf(s.toUpperCase()))
                      .collect(Collectors.toList());
            } catch (IllegalArgumentException e) {
              throw new com.financeos.core.exception.ValidationException("Invalid status: " + status);
            }
          }
        }

        List<JobType> typeList = null;
        if (type != null && !type.isBlank()) {
          try {
            typeList = Arrays.stream(type.split(","))
                    .map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .map(t -> JobType.valueOf(t.toUpperCase()))
                    .collect(Collectors.toList());
          } catch (IllegalArgumentException e) {
            throw new com.financeos.core.exception.ValidationException("Invalid type: " + type);
          }
        }

        int pageNumber = pageable != null ? pageable.getPageNumber() : 0;
        int pageSize = pageable != null ? pageable.getPageSize() : 20;
        Pageable sortedPageable = PageRequest.of(
                pageNumber,
                pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        boolean hasType = typeList != null && !typeList.isEmpty();
        boolean hasStatus = statusList != null && !statusList.isEmpty();
        Page<Job> jobs;
        if (since == null) {
            if (hasType && hasStatus) {
                jobs = jobRepository.findByUserIdAndTypeInAndStatusIn(userId, typeList, statusList, sortedPageable);
            } else if (hasType) {
                jobs = jobRepository.findByUserIdAndTypeIn(userId, typeList, sortedPageable);
            } else if (hasStatus) {
                jobs = jobRepository.findByUserIdAndStatusIn(userId, statusList, sortedPageable);
            } else {
                jobs = jobRepository.findByUserId(userId, sortedPageable);
            }
        } else if (hasType && hasStatus) {
            jobs = jobRepository.findByUserIdAndTypeInAndStatusInAndCreatedAtGreaterThanEqual(userId, typeList, statusList, since, sortedPageable);
        } else if (hasType) {
            jobs = jobRepository.findByUserIdAndTypeInAndCreatedAtGreaterThanEqual(userId, typeList, since, sortedPageable);
        } else if (hasStatus) {
            jobs = jobRepository.findByUserIdAndStatusInAndCreatedAtGreaterThanEqual(userId, statusList, since, sortedPageable);
        } else {
            jobs = jobRepository.findByUserIdAndCreatedAtGreaterThanEqual(userId, since, sortedPageable);
        }

        Map<UUID, Account> accounts = loadAccounts(userId, jobs.getContent());
        return ResponseEntity.ok(jobs.map(job -> toResponse(job, accounts)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> getJobById(@PathVariable UUID id) {
        UUID userId = UserContext.getCurrentUserId();
        Job job = jobRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Job", id));
        return ResponseEntity.ok(toResponse(job, loadAccounts(userId, List.of(job))));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<JobResponse> cancelJob(@PathVariable UUID id) {
        UUID userId = UserContext.getCurrentUserId();
        Job job = jobService.requestCancel(userId, id);
        return ResponseEntity.ok(toResponse(job, loadAccounts(userId, List.of(job))));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<JobResponse> retryJob(@PathVariable UUID id) {
        UUID userId = UserContext.getCurrentUserId();
        Job newJob = jobService.retry(userId, id);
        return ResponseEntity.ok(toResponse(newJob, loadAccounts(userId, List.of(newJob))));
    }

    /** The ingest account id carried in a statement-ingest job's payload, or null for other jobs. */
    private UUID accountIdOf(Job job) {
        if (job.getType() != JobType.STATEMENT_INGEST || job.getPayload() == null || job.getPayload().isBlank()) {
            return null;
        }
        try {
            String id = objectMapper.readTree(job.getPayload()).path("accountId").asText(null);
            return id == null || id.isBlank() ? null : UUID.fromString(id);
        } catch (Exception e) {
            return null;
        }
    }

    /** One batched lookup of the user's accounts referenced by these jobs. */
    private Map<UUID, Account> loadAccounts(UUID userId, List<Job> jobs) {
        Set<UUID> ids = jobs.stream().map(this::accountIdOf).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return accountRepository.findAllById(ids).stream()
                .filter(a -> a.getUser() != null && userId.equals(a.getUser().getId()))
                .collect(Collectors.toMap(Account::getId, a -> a));
    }

    private JobResponse toResponse(Job job, Map<UUID, Account> accounts) {
        UUID accountId = accountIdOf(job);
        Account account = accountId == null ? null : accounts.get(accountId);
        return JobResponse.from(job, objectMapper, account == null ? null : accountId, account == null ? null : account.getName());
    }
}
