package com.financeos.domain.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.imports.dto.ImportRecordResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.query.NativeQuery;
import org.hibernate.type.StandardBasicTypes;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The import history: one row per imported file or batch, unioned across statements (done, failed, held),
 * NPS statements, EPF passbook batches and broker import jobs. Paged in the database (UNION ALL, ORDER BY,
 * OFFSET/FETCH, plus a matching COUNT), plain ANSI SQL so the same text runs on Oracle and H2.
 * Every branch filters on the owning user.
 */
@Service
public class ImportHistoryService {

    public static final String KIND_STATEMENT = "statement";
    public static final String KIND_NPS = "nps_statement";
    public static final String KIND_EPF = "epf_passbook";
    public static final String KIND_BROKER = "broker";
    public static final List<String> KINDS = List.of(KIND_STATEMENT, KIND_NPS, KIND_EPF, KIND_BROKER);

    static final int MAX_SIZE = 100;

    private static final String COLUMNS = "rec_id, rec_kind, rec_source, rec_status, file_name, account_id, account_name, "
            + "period_start, period_end, item_count, error_message, created_at";

    private static final String NULL_TEXT = "CAST(NULL AS VARCHAR(255))";
    private static final String NULL_ID = "CAST(NULL AS VARCHAR(36))";
    private static final String NULL_DATE = "CAST(NULL AS DATE)";
    private static final String NULL_INT = "CAST(NULL AS INTEGER)";
    private static final String NULL_ERROR = "CAST(NULL AS VARCHAR(2000))";

    /** A statement that landed: one row per statements record. */
    private static final String STATEMENTS = "SELECT 'statement:' || CAST(s.id AS VARCHAR(36)) AS rec_id, 'statement' AS rec_kind, "
            + "CASE WHEN s.source = 'gmail' THEN 'gmail' ELSE 'upload' END AS rec_source, 'done' AS rec_status, "
            + "CASE WHEN s.source = 'file_upload' THEN s.source_ref ELSE " + NULL_TEXT + " END AS file_name, "
            + "CAST(s.account_id AS VARCHAR(36)) AS account_id, a.name AS account_name, "
            + "s.period_start AS period_start, s.period_end AS period_end, s.transaction_count AS item_count, "
            + NULL_ERROR + " AS error_message, s.created_at AS created_at "
            + "FROM statements s LEFT JOIN accounts a ON a.id = s.account_id WHERE s.user_id = :userId";

    /** A statement import that failed outright; the account (in the job payload) is added after the page is read. */
    private static final String FAILED_STATEMENTS = "SELECT 'job:' || CAST(j.id AS VARCHAR(36)) AS rec_id, 'statement' AS rec_kind, "
            + "'upload' AS rec_source, 'failed' AS rec_status, "
            + "(SELECT MIN(ja.filename) FROM job_artifacts ja WHERE ja.job_id = j.id) AS file_name, "
            + NULL_ID + " AS account_id, " + NULL_TEXT + " AS account_name, " + NULL_DATE + " AS period_start, "
            + NULL_DATE + " AS period_end, " + NULL_INT + " AS item_count, j.error_message AS error_message, "
            + "j.created_at AS created_at "
            + "FROM jobs j WHERE j.user_id = :userId AND j.type = 'STATEMENT_INGEST' AND j.status = 'FAILED'";

    /** A file held for the user to pick an account or password. */
    private static final String HELD = "SELECT 'held:' || CAST(h.id AS VARCHAR(36)) AS rec_id, 'statement' AS rec_kind, "
            + "'upload' AS rec_source, 'held' AS rec_status, h.file_name AS file_name, "
            + NULL_ID + " AS account_id, " + NULL_TEXT + " AS account_name, " + NULL_DATE + " AS period_start, "
            + NULL_DATE + " AS period_end, " + NULL_INT + " AS item_count, CAST(h.reason AS VARCHAR(2000)) AS error_message, "
            + "h.created_at AS created_at "
            + "FROM held_uploads h WHERE h.user_id = :userId AND h.resolved_at IS NULL";

    private static final String NPS = "SELECT 'nps:' || CAST(n.id AS VARCHAR(36)) AS rec_id, 'nps_statement' AS rec_kind, "
            + "CASE WHEN n.source = 'GMAIL' THEN 'gmail' ELSE 'upload' END AS rec_source, 'done' AS rec_status, "
            + NULL_TEXT + " AS file_name, CAST(n.account_id AS VARCHAR(36)) AS account_id, a.holder_name AS account_name, "
            + "n.period_start AS period_start, n.period_end AS period_end, n.contribution_count AS item_count, "
            + NULL_ERROR + " AS error_message, n.imported_at AS created_at "
            + "FROM nps_statement n LEFT JOIN nps_account a ON a.id = n.account_id WHERE n.user_id = :userId";

    /** A passbook upload: its entries all share the commit's imported_at stamp, so that is the batch key. */
    private static final String EPF = "SELECT 'epf:' || MIN(CAST(e.id AS VARCHAR(36))) AS rec_id, 'epf_passbook' AS rec_kind, "
            + "'upload' AS rec_source, 'done' AS rec_status, " + NULL_TEXT + " AS file_name, "
            + NULL_ID + " AS account_id, " + NULL_TEXT + " AS account_name, "
            + "MIN(e.credited_on) AS period_start, MAX(e.credited_on) AS period_end, COUNT(*) AS item_count, "
            + NULL_ERROR + " AS error_message, e.imported_at AS created_at "
            + "FROM epf_entry e WHERE e.user_id = :userId AND e.source = 'PASSBOOK' AND e.imported_at IS NOT NULL "
            + "GROUP BY e.imported_at";

    private static final String BROKER = "SELECT 'job:' || CAST(j.id AS VARCHAR(36)) AS rec_id, 'broker' AS rec_kind, "
            + "'upload' AS rec_source, CASE WHEN j.status = 'FAILED' THEN 'failed' ELSE 'done' END AS rec_status, "
            + "(SELECT MIN(ja.filename) FROM job_artifacts ja WHERE ja.job_id = j.id) AS file_name, "
            + NULL_ID + " AS account_id, " + NULL_TEXT + " AS account_name, " + NULL_DATE + " AS period_start, "
            + NULL_DATE + " AS period_end, " + NULL_INT + " AS item_count, j.error_message AS error_message, "
            + "j.created_at AS created_at "
            + "FROM jobs j WHERE j.user_id = :userId AND j.status IN ('SUCCEEDED', 'FAILED') "
            + "AND j.type IN ('INVESTMENT_IMPORT_COMMIT', 'BROKER_RECONCILE_COMMIT')";

    @PersistenceContext
    private EntityManager entityManager;

    private final JobRepository jobRepository;
    private final AccountRepository accountRepository;
    private final ObjectMapper objectMapper;

    public ImportHistoryService(JobRepository jobRepository, AccountRepository accountRepository, ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.accountRepository = accountRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Page<ImportRecordResponse> list(UUID userId, String kind, int page, int size) {
        if (kind != null && !kind.isBlank() && !KINDS.contains(kind)) {
            throw new ValidationException("kind must be one of " + KINDS);
        }
        int pageNo = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_SIZE);
        String union = union(kind == null || kind.isBlank() ? null : kind);

        Number total = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM (" + union + ") u")
                .setParameter("userId", userId.toString()).getSingleResult();
        if (total.longValue() == 0) {
            return new PageImpl<>(List.of(), PageRequest.of(pageNo, pageSize), 0);
        }

        // pageNo and pageSize are sanitised ints, so inlining them is safe and keeps the SQL portable.
        String sql = "SELECT " + COLUMNS + " FROM (" + union + ") u ORDER BY created_at DESC, rec_id DESC "
                + "OFFSET " + ((long) pageNo * pageSize) + " ROWS FETCH NEXT " + pageSize + " ROWS ONLY";
        NativeQuery<?> query = entityManager.createNativeQuery(sql).unwrap(NativeQuery.class);
        query.addScalar("rec_id", StandardBasicTypes.STRING).addScalar("rec_kind", StandardBasicTypes.STRING)
                .addScalar("rec_source", StandardBasicTypes.STRING).addScalar("rec_status", StandardBasicTypes.STRING)
                .addScalar("file_name", StandardBasicTypes.STRING).addScalar("account_id", StandardBasicTypes.STRING)
                .addScalar("account_name", StandardBasicTypes.STRING).addScalar("period_start", StandardBasicTypes.LOCAL_DATE)
                .addScalar("period_end", StandardBasicTypes.LOCAL_DATE).addScalar("item_count", StandardBasicTypes.INTEGER)
                .addScalar("error_message", StandardBasicTypes.STRING).addScalar("created_at", StandardBasicTypes.INSTANT);
        query.setParameter("userId", userId.toString());
        @SuppressWarnings("unchecked")
        List<Object[]> rows = (List<Object[]>) query.getResultList();

        List<ImportRecordResponse> records = new ArrayList<>();
        for (Object[] r : rows) {
            records.add(new ImportRecordResponse((String) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4],
                    r[5] == null ? null : UUID.fromString((String) r[5]), (String) r[6], (LocalDate) r[7], (LocalDate) r[8],
                    (Integer) r[9], "held".equals(r[3]) ? heldMessage((String) r[10]) : (String) r[10], (Instant) r[11]));
        }
        return new PageImpl<>(withFailedJobAccounts(userId, records), PageRequest.of(pageNo, pageSize), total.longValue());
    }

    private static String union(String kind) {
        List<String> branches = new ArrayList<>();
        if (kind == null || KIND_STATEMENT.equals(kind)) {
            branches.add(STATEMENTS);
            branches.add(FAILED_STATEMENTS);
            branches.add(HELD);
        }
        if (kind == null || KIND_NPS.equals(kind)) {
            branches.add(NPS);
        }
        if (kind == null || KIND_EPF.equals(kind)) {
            branches.add(EPF);
        }
        if (kind == null || KIND_BROKER.equals(kind)) {
            branches.add(BROKER);
        }
        return String.join(" UNION ALL ", branches);
    }

    private static String heldMessage(String reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case "NO_MATCH" -> "No account matched this file";
            case "AMBIGUOUS" -> "More than one account matched this file";
            case "LOCKED" -> "This file needs a password";
            default -> reason;
        };
    }

    /** Failed statement imports carry their account in the job payload: one batched job read and one account read. */
    private List<ImportRecordResponse> withFailedJobAccounts(UUID userId, List<ImportRecordResponse> records) {
        Set<UUID> jobIds = new HashSet<>();
        for (ImportRecordResponse r : records) {
            if (KIND_STATEMENT.equals(r.kind()) && "failed".equals(r.status()) && r.id().startsWith("job:")) {
                jobIds.add(UUID.fromString(r.id().substring(4)));
            }
        }
        if (jobIds.isEmpty()) {
            return records;
        }
        Map<UUID, UUID> accountByJob = new HashMap<>();
        for (Job job : jobRepository.findAllById(jobIds)) {
            UUID accountId = job.getUserId() != null && job.getUserId().equals(userId) ? accountIdOf(job) : null;
            if (accountId != null) {
                accountByJob.put(job.getId(), accountId);
            }
        }
        if (accountByJob.isEmpty()) {
            return records;
        }
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Account a : accountRepository.findByUserId(userId)) {
            names.put(a.getId(), a.getName());
        }
        List<ImportRecordResponse> out = new ArrayList<>();
        for (ImportRecordResponse r : records) {
            UUID accountId = r.id().startsWith("job:") ? accountByJob.get(UUID.fromString(r.id().substring(4))) : null;
            if (accountId == null || !names.containsKey(accountId)) {
                out.add(r);
            } else {
                out.add(new ImportRecordResponse(r.id(), r.kind(), r.source(), r.status(), r.fileName(), accountId,
                        names.get(accountId), r.periodStart(), r.periodEnd(), r.count(), r.errorMessage(), r.createdAt()));
            }
        }
        return out;
    }

    private UUID accountIdOf(Job job) {
        if (job.getPayload() == null || job.getPayload().isBlank()) {
            return null;
        }
        try {
            String id = objectMapper.readTree(job.getPayload()).path("accountId").asText(null);
            return id == null || id.isBlank() ? null : UUID.fromString(id);
        } catch (Exception e) {
            return null;
        }
    }
}
