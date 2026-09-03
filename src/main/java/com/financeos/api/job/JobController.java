package com.financeos.api.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.job.dto.JobResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.job.*;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobRepository jobRepository;
    private final JobService jobService;
    private final ObjectMapper objectMapper;

    public JobController(JobRepository jobRepository, JobService jobService, ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<Page<JobResponse>> getJobs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
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

        Page<Job> jobs;
        if (typeList != null && !typeList.isEmpty() && statusList != null && !statusList.isEmpty()) {
            jobs = jobRepository.findByUserIdAndTypeInAndStatusIn(userId, typeList, statusList, sortedPageable);
        } else if (typeList != null && !typeList.isEmpty()) {
            jobs = jobRepository.findByUserIdAndTypeIn(userId, typeList, sortedPageable);
        } else if (statusList != null && !statusList.isEmpty()) {
            jobs = jobRepository.findByUserIdAndStatusIn(userId, statusList, sortedPageable);
        } else {
            jobs = jobRepository.findByUserId(userId, sortedPageable);
        }

        return ResponseEntity.ok(jobs.map(job -> JobResponse.from(job, objectMapper)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> getJobById(@PathVariable UUID id) {
        UUID userId = UserContext.getCurrentUserId();
        Job job = jobRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Job", id));
        return ResponseEntity.ok(JobResponse.from(job, objectMapper));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<JobResponse> cancelJob(@PathVariable UUID id) {
        UUID userId = UserContext.getCurrentUserId();
        Job job = jobService.requestCancel(userId, id);
        return ResponseEntity.ok(JobResponse.from(job, objectMapper));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<JobResponse> retryJob(@PathVariable UUID id) {
        UUID userId = UserContext.getCurrentUserId();
        Job newJob = jobService.retry(userId, id);
        return ResponseEntity.ok(JobResponse.from(newJob, objectMapper));
    }
}
