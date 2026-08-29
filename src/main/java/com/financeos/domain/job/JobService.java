package com.financeos.domain.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobRepository;
    private final JobArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;
    private JobWorker jobWorker;

    public JobService(JobRepository jobRepository,
                      JobArtifactRepository artifactRepository,
                      ObjectMapper objectMapper,
                      @Lazy JobWorker jobWorker) {
        this.jobRepository = jobRepository;
        this.artifactRepository = artifactRepository;
        this.objectMapper = objectMapper;
        this.jobWorker = jobWorker;
    }

    @Transactional
    public Job enqueue(UUID userId, JobType type, JobTrigger trigger, Object payload, List<StagedFile> files, String dedupKey) {
        if (dedupKey != null && !dedupKey.isBlank()) {
            Optional<Job> existing = jobRepository.findActiveDuplicate(
                    userId, type, dedupKey, List.of(JobStatus.PENDING, JobStatus.RUNNING));
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        Job job = new Job();
        job.setUserId(userId);
        job.setType(type);
        job.setStatus(JobStatus.PENDING);
        job.setTriggerSource(trigger);
        job.setDedupKey(dedupKey);
        job.setAttempt(0);
        job.setCancelRequested(false);

        if (payload != null) {
            try {
                job.setPayload(objectMapper.writeValueAsString(payload));
            } catch (Exception e) {
                throw new ValidationException("Failed to serialize job payload: " + e.getMessage());
            }
        }

        Job savedJob = jobRepository.save(job);

        if (files != null && !files.isEmpty()) {
            for (StagedFile file : files) {
                JobArtifact artifact = new JobArtifact();
                artifact.setJobId(savedJob.getId());
                artifact.setFilename(file.filename());
                artifact.setContentType(file.contentType());
                artifact.setSizeBytes(file.bytes() != null ? file.bytes().length : 0);
                artifact.setData(file.bytes() != null ? file.bytes() : new byte[0]);
                artifactRepository.save(artifact);
            }
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (jobWorker != null) {
                        jobWorker.poke();
                    }
                }
            });
        } else {
            if (jobWorker != null) {
                jobWorker.poke();
            }
        }

        return savedJob;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(UUID jobId) {
        return jobRepository.claim(jobId, Instant.now()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(UUID jobId, String resultJson) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        job.setStatus(JobStatus.SUCCEEDED);
        job.setResult(resultJson);
        job.setFinishedAt(Instant.now());
        jobRepository.save(job);
        artifactRepository.deleteByJobId(jobId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID jobId, String errorCode, String errorMessage) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        job.setStatus(JobStatus.FAILED);
        job.setErrorCode(errorCode);
        if (errorMessage != null && errorMessage.length() > 2000) {
            job.setErrorMessage(errorMessage.substring(0, 2000));
        } else {
            job.setErrorMessage(errorMessage);
        }
        job.setFinishedAt(Instant.now());
        jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCancelled(UUID jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        job.setStatus(JobStatus.CANCELLED);
        job.setFinishedAt(Instant.now());
        jobRepository.save(job);
        artifactRepository.deleteByJobId(jobId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(UUID jobId, int current, int total, String note) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        job.setProgressCurrent(current);
        job.setProgressTotal(total);
        job.setProgressNote(note);
        jobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public boolean isCancelRequested(UUID jobId) {
        return jobRepository.findById(jobId)
                .map(Job::isCancelRequested)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<JobArtifact> getArtifacts(UUID jobId) {
        return artifactRepository.findByJobId(jobId);
    }

    @Transactional
    public Job requestCancel(UUID userId, UUID jobId) {
        if (userId == null) {
            log.error("Security Breach Attempt: Anonymous user tried to cancel job {}", jobId);
            throw new ResourceNotFoundException("Job", jobId);
        }

        Job job = jobRepository.findByIdAndUserId(jobId, userId).orElse(null);

        if (job == null) {
            log.error("Security Breach Attempt: User {} tried to cancel non-existent or foreign job {}", userId, jobId);
            throw new ResourceNotFoundException("Job", jobId);
        }

        if (job.getStatus() == JobStatus.PENDING) {
            job.setStatus(JobStatus.CANCELLED);
            job.setFinishedAt(Instant.now());
            jobRepository.save(job);
            artifactRepository.deleteByJobId(jobId);
            return job;
        } else if (job.getStatus() == JobStatus.RUNNING) {
            job.setCancelRequested(true);
            return jobRepository.save(job);
        } else {
            throw new ValidationException("Cannot cancel job in terminal status: " + job.getStatus());
        }
    }

    @Transactional
    public Job retry(UUID userId, UUID jobId) {
        if (userId == null) {
            log.error("Security Breach Attempt: Anonymous user tried to retry job {}", jobId);
            throw new ResourceNotFoundException("Job", jobId);
        }

        Job orig = jobRepository.findByIdAndUserId(jobId, userId).orElse(null);

        if (orig == null) {
            log.error("Security Breach Attempt: User {} tried to retry non-existent or foreign job {}", userId, jobId);
            throw new ResourceNotFoundException("Job", jobId);
        }

        if (orig.getStatus() != JobStatus.FAILED && orig.getStatus() != JobStatus.CANCELLED) {
            throw new ValidationException("Only FAILED or CANCELLED jobs can be retried.");
        }

        List<JobArtifact> origArtifacts = artifactRepository.findByJobId(jobId);
        if (orig.getType() == JobType.STATEMENT_INGEST && origArtifacts.isEmpty()) {
            throw new ValidationException("Statement ingest artifacts expired, please re-upload.");
        }

        Job newJob = new Job();
        newJob.setUserId(orig.getUserId());
        newJob.setType(orig.getType());
        newJob.setStatus(JobStatus.PENDING);
        newJob.setTriggerSource(JobTrigger.USER);
        newJob.setPayload(orig.getPayload());
        newJob.setDedupKey(null);
        newJob.setAttempt(0);
        newJob.setCancelRequested(false);

        Job savedJob = jobRepository.save(newJob);

        for (JobArtifact a : origArtifacts) {
            JobArtifact newArtifact = new JobArtifact();
            newArtifact.setJobId(savedJob.getId());
            newArtifact.setFilename(a.getFilename());
            newArtifact.setContentType(a.getContentType());
            newArtifact.setSizeBytes(a.getSizeBytes());
            newArtifact.setData(a.getData());
            artifactRepository.save(newArtifact);
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (jobWorker != null) {
                        jobWorker.poke();
                    }
                }
            });
        } else {
            if (jobWorker != null) {
                jobWorker.poke();
            }
        }

        return savedJob;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requestCancelAllUserJobs(UUID userId) {
        if (userId != null) {
            jobRepository.requestCancelForUserJobs(userId, List.of(JobStatus.PENDING, JobStatus.RUNNING), Instant.now());
        }
    }

    @Transactional(readOnly = true)
    public long countRunningJobs(UUID userId) {
        if (userId == null) return 0;
        return jobRepository.countByUserIdAndStatus(userId, JobStatus.RUNNING);
    }
}
