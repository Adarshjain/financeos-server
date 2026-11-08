package com.financeos.domain.ingestion;

import com.financeos.core.observability.Ops;
import com.financeos.core.observability.Timing;
import com.financeos.domain.ingestion.held.HeldUpload;
import com.financeos.domain.ingestion.held.HeldUploadService;
import com.financeos.domain.job.JobArtifact;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.statement.parser.StatementIdentity;
import com.financeos.statement.parser.StatementParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Statement upload without an account. Per file: read who it is from (no password, then the typed one, then every
 * saved one), find the user's account with the same bank id and the same last 4, and ingest as usual when exactly one
 * matches; otherwise hold the file (no match, several matches, or locked) for the user to assign. Never logs
 * passwords, numbers or file text.
 */
@Service
public class AutoIngestService {

    private static final Logger log = LoggerFactory.getLogger(AutoIngestService.class);

    private final StatementIdentifier identifier;
    private final StatementPasswordVault passwordVault;
    private final StatementAccountMatcher accountMatcher;
    private final FileIngestionService ingestionService;
    private final HeldUploadService heldUploadService;

    public AutoIngestService(StatementIdentifier identifier,
                             StatementPasswordVault passwordVault,
                             StatementAccountMatcher accountMatcher,
                             FileIngestionService ingestionService,
                             HeldUploadService heldUploadService) {
        this.identifier = identifier;
        this.passwordVault = passwordVault;
        this.accountMatcher = accountMatcher;
        this.ingestionService = ingestionService;
        this.heldUploadService = heldUploadService;
    }

    public FileIngestionResult run(JobExecutionContext ctx, List<JobArtifact> artifacts, String typedPassword) {
        UUID userId = ctx.getUserId();
        int n = artifacts.size();
        FileIngestionResult.FileSummary[] summaries = new FileIngestionResult.FileSummary[n];
        List<StatementAccountMatcher.AccountKey> keys = accountMatcher.keysOf(userId);
        List<String> saved = null; // loaded once, only when a file is locked
        Map<UUID, List<Integer>> byAccount = new LinkedHashMap<>();

        for (int i = 0; i < n; i++) {
            ctx.checkCancelled();
            JobArtifact artifact = artifacts.get(i);
            String filename = artifact.getFilename();
            ctx.progress(i, n, filename);
            byte[] bytes = artifact.getData();
            if (bytes == null || bytes.length == 0) {
                summaries[i] = FileIngestionResult.FileSummary.failed(filename, "File is empty", FileIngestionResult.ErrorCode.UNREADABLE);
                continue;
            }

            StatementIdentity identity = null;
            StatementParseException failure = null;
            boolean locked = false;
            try {
                identity = Timing.call(Ops.STATEMENT_IDENTIFY, t -> t instanceof StatementParseException, () -> identifier.identify(bytes, blankToNull(typedPassword)));
            } catch (StatementParseException e) {
                failure = e;
                locked = e.isPasswordProblem();
            } catch (RuntimeException e) {
                failure = new StatementParseException("ERROR: failed to read file.", e);
            }
            if (locked) {
                if (saved == null) {
                    saved = passwordVault.savedPasswords(userId);
                }
                LinkedHashSet<String> candidates = new LinkedHashSet<>(saved);
                candidates.remove(typedPassword);
                for (String pwd : candidates) {
                    try {
                        identity = Timing.call(Ops.STATEMENT_UNLOCK_SAVED, t -> t instanceof StatementParseException, () -> identifier.identify(bytes, pwd));
                        failure = null;
                        locked = false;
                        break;
                    } catch (RuntimeException ignored) {
                        // next saved password
                    }
                }
            }

            if (locked) {
                HeldUpload held = heldUploadService.hold(userId, artifact, i, null, null, HeldUpload.Reason.LOCKED, List.of());
                summaries[i] = FileIngestionResult.FileSummary.held(filename, "Locked: needs a password and an account.", held.getId());
                continue;
            }
            if (failure != null) {
                summaries[i] = FileIngestionResult.FileSummary.failed(filename, failure.getMessage(),
                        FileIngestionResult.ErrorCode.classify(failure.getMessage(), false));
                continue;
            }

            String last4 = StatementAccountMatcher.lastFour(identity.accountNumber());
            List<UUID> matches = StatementAccountMatcher.match(keys, identity.bankId(), last4);
            if (matches.size() == 1) {
                byAccount.computeIfAbsent(matches.get(0), k -> new ArrayList<>()).add(i);
            } else {
                HeldUpload.Reason reason = matches.isEmpty() ? HeldUpload.Reason.NO_MATCH : HeldUpload.Reason.AMBIGUOUS;
                HeldUpload held = heldUploadService.hold(userId, artifact, i, identity.bankId(), last4, reason, matches);
                String message = matches.isEmpty() ? "No account matches this statement." : "More than one account matches this statement.";
                summaries[i] = FileIngestionResult.FileSummary.held(filename, message, held.getId());
            }
        }

        int processed = 0;
        int created = 0;
        int duplicates = 0;
        int truncated = 0;
        List<FileIngestionResult.DuplicateDetail> duplicateDetails = new ArrayList<>();
        for (Map.Entry<UUID, List<Integer>> group : byAccount.entrySet()) {
            List<UploadedFile> files = new ArrayList<>();
            for (int idx : group.getValue()) {
                JobArtifact a = artifacts.get(idx);
                files.add(new UploadedFile(a.getFilename(), a.getContentType(), a.getData()));
            }
            FileIngestionResult r = Timing.call(Ops.STATEMENT_INGEST_ACCOUNT, () -> ingestionService.ingest(group.getKey(), files, ctx, blankToNull(typedPassword)));
            processed += r.filesProcessed();
            created += r.totalCreated();
            duplicates += r.totalDuplicatesFound();
            truncated += r.duplicatesTruncated();
            if (r.duplicateDetails() != null) {
                duplicateDetails.addAll(r.duplicateDetails());
            }
            for (int k = 0; k < group.getValue().size() && k < r.fileDetails().size(); k++) {
                summaries[group.getValue().get(k)] = r.fileDetails().get(k);
            }
        }
        log.info("Auto-detect upload: files={}, matched={}, held={}", n, byAccount.values().stream().mapToInt(List::size).sum(),
                java.util.Arrays.stream(summaries).filter(s -> s != null && "HELD".equals(s.status())).count());
        ctx.progress(n, n, "Completed");
        List<FileIngestionResult.FileSummary> details = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            details.add(summaries[i] != null ? summaries[i]
                    : FileIngestionResult.FileSummary.failed(artifacts.get(i).getFilename(), "File was not processed",
                            FileIngestionResult.ErrorCode.OTHER));
        }
        return new FileIngestionResult(processed, created, duplicates, details, duplicateDetails, truncated);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
