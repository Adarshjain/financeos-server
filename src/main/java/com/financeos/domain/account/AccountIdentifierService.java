package com.financeos.domain.account;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.job.handlers.GmailSyncPayload;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.ingest.GmailIngestProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class AccountIdentifierService {

    private static final Logger log = LoggerFactory.getLogger(AccountIdentifierService.class);

    public record CreationResult(AccountIdentifier identifier, int reactivatedCount, List<UUID> jobIds) {}

    private final AccountIdentifierRepository accountIdentifierRepository;
    private final GmailProcessedMessageRepository processedMessageRepository;
    private final JobService jobService;
    private final GmailIngestProperties ingestProperties;

    public AccountIdentifierService(AccountIdentifierRepository accountIdentifierRepository,
                                    GmailProcessedMessageRepository processedMessageRepository,
                                    JobService jobService,
                                    GmailIngestProperties ingestProperties) {
        this.accountIdentifierRepository = accountIdentifierRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.jobService = jobService;
        this.ingestProperties = ingestProperties;
    }

    @Transactional
    public CreationResult createOrUpdateIdentifier(User user, Account account, String rawValue, AccountIdentifierKind kind) {
        String normalized = AccountIdentifier.normalize(rawValue);
        AccountIdentifier.validateNormalized(normalized);

        AccountIdentifierKind effectiveKind = kind != null ? kind : AccountIdentifierKind.OTHER;

        Optional<AccountIdentifier> existingOpt = accountIdentifierRepository.findByUserIdAndValue(user.getId(), normalized);
        AccountIdentifier identifier;

        if (existingOpt.isPresent()) {
            AccountIdentifier existing = existingOpt.get();
            if (existing.getAccount().getId().equals(account.getId())) {
                // Idempotent no-op reuse
                identifier = existing;
            } else {
                throw new ValidationException(
                        "Identifier '" + normalized + "' is already assigned to account '" + existing.getAccount().getName() + "'."
                );
            }
        } else {
            identifier = new AccountIdentifier(
                    UUID.randomUUID(),
                    user,
                    account,
                    normalized,
                    effectiveKind,
                    Instant.now()
            );
            identifier = accountIdentifierRepository.save(identifier);
        }

        int reactivatedCount = 0;
        List<UUID> jobIds = new ArrayList<>();

        if (account.getIngestFromDate() != null) {
            Instant minInstant = account.getIngestFromDate()
                    .minusDays(ingestProperties.getDateWindowDays())
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();

            List<GmailProcessedMessage> parked = processedMessageRepository.findParkedForReactivation(
                    user.getId(),
                    normalized,
                    List.of(GmailProcessedStatus.UNRESOLVED_ACCOUNT),
                    minInstant
            );

            if (!parked.isEmpty()) {
                log.info("Re-activating {} parked rows for identifier {} on account {}", parked.size(), normalized, account.getId());
                for (GmailProcessedMessage gpm : parked) {
                    gpm.setStatus(GmailProcessedStatus.DISCOVERED);
                    gpm.setAttemptCount(0);
                    gpm.setNextRetryAt(null);
                    gpm.setError(null);
                }
                processedMessageRepository.saveAll(parked);
                reactivatedCount = parked.size();

                Set<UUID> connectionIds = new LinkedHashSet<>();
                for (GmailProcessedMessage gpm : parked) {
                    if (gpm.getConnection() != null) {
                        connectionIds.add(gpm.getConnection().getId());
                    }
                }

                for (UUID connId : connectionIds) {
                    Job job = jobService.enqueue(
                            user.getId(),
                            JobType.GMAIL_SYNC,
                            JobTrigger.USER,
                            new GmailSyncPayload(connId),
                            null,
                            connId.toString()
                    );
                    jobIds.add(job.getId());
                }
            }
        } else {
            log.info("Skipping reactivation for identifier {}: account {} has null ingestFromDate", normalized, account.getId());
        }

        return new CreationResult(identifier, reactivatedCount, jobIds);
    }

    @Transactional(readOnly = true)
    public List<AccountIdentifier> getIdentifiers(UUID userId, UUID accountId) {
        return accountIdentifierRepository.findByAccountIdOrderByCreatedAtAsc(accountId);
    }

    @Transactional
    public void deleteIdentifier(UUID userId, UUID accountId, UUID identifierId) {
        AccountIdentifier identifier = accountIdentifierRepository.findByIdAndAccountId(identifierId, accountId)
                .orElseThrow(() -> new ResourceNotFoundException("AccountIdentifier", identifierId));

        if (!identifier.getUser().getId().equals(userId)) {
            throw new ValidationException("You do not have permission to delete this identifier.");
        }

        accountIdentifierRepository.delete(identifier);
    }
}
