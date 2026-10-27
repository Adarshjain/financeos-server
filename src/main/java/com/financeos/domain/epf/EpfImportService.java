package com.financeos.domain.epf;

import com.financeos.api.epf.dto.EpfImportBatchPreviewResponse;
import com.financeos.api.epf.dto.EpfImportBatchResultResponse;
import com.financeos.api.epf.dto.EpfImportPreviewResponse;
import com.financeos.api.epf.dto.EpfImportRefusedFile;
import com.financeos.api.epf.dto.EpfImportPreviewResponse.EpfEmployerOption;
import com.financeos.api.epf.dto.EpfImportPreviewResponse.Status;
import com.financeos.api.epf.dto.EpfImportResultResponse;
import com.financeos.core.exception.ApiStatusException;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.epf.passbook.EpfPassbookFormatException;
import com.financeos.domain.epf.passbook.EpfPassbookParser;
import com.financeos.domain.epf.passbook.ParsedPassbook;
import com.financeos.domain.epf.passbook.ParsedPassbookRow;
import com.financeos.domain.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * EPF passbook import. Preview and commit run the same plan: parse, resolve the employer, then compare each
 * parsed row with the employer's stored entries (loaded once). Nothing here logs file contents or values.
 */
@Service
@Transactional
public class EpfImportService {

    private static final int PARTICULARS_MAX = 300;
    private static final String UNKNOWN_EMPLOYER = "EPF employer";

    private final EpfPassbookParser parser = new EpfPassbookParser();
    private final EpfEmployerRepository employerRepository;
    private final EpfEntryRepository entryRepository;
    private final UserRepository userRepository;

    public EpfImportService(EpfEmployerRepository employerRepository, EpfEntryRepository entryRepository,
                            UserRepository userRepository) {
        this.employerRepository = employerRepository;
        this.entryRepository = entryRepository;
        this.userRepository = userRepository;
    }

    /** A file as uploaded: its display name and bytes. */
    public record BatchFile(String name, byte[] bytes) {
    }

    @Transactional(readOnly = true)
    public EpfImportPreviewResponse preview(byte[] file, @Nullable UUID employerId) {
        BatchPlan bp = planBatch(List.of(new BatchFile("passbook.pdf", file)), List.of(), employerId);
        throwIfRefused(bp);
        return bp.sections.get(0).plan.toPreview();
    }

    public EpfImportResultResponse commit(byte[] file, @Nullable UUID employerId) {
        BatchPlan bp = planBatch(List.of(new BatchFile("passbook.pdf", file)), List.of(), employerId);
        throwIfRefused(bp);
        return commitPlan(bp).results().get(0);
    }

    @Transactional(readOnly = true)
    public EpfImportBatchPreviewResponse previewBatch(List<BatchFile> files, List<String> assign) {
        BatchPlan bp = planBatch(files, assign, null);
        List<EpfImportBatchPreviewResponse.EpfImportSection> out = new ArrayList<>();
        for (SectionPlan s : bp.sections) {
            out.add(new EpfImportBatchPreviewResponse.EpfImportSection(s.key, s.fileNames, s.plan.toPreview()));
        }
        return new EpfImportBatchPreviewResponse(out, bp.refused);
    }

    public EpfImportBatchResultResponse commitBatch(List<BatchFile> files, List<String> assign) {
        BatchPlan bp = planBatch(files, assign, null);
        if (bp.sections.isEmpty()) throw new ValidationException("None of these files could be imported.");
        return commitPlan(bp);
    }

    private static void throwIfRefused(BatchPlan bp) {
        if (bp.sections.isEmpty()) {
            EpfImportRefusedFile r = bp.refused.get(0);
            throw new ApiStatusException(HttpStatus.BAD_REQUEST, r.code(), r.message());
        }
    }

    private EpfImportBatchResultResponse commitPlan(BatchPlan bp) {
        UUID userId = currentUserId();
        Instant now = AppTime.now().atZone(AppTime.zone()).toInstant();
        List<EpfImportResultResponse> results = new ArrayList<>();
        List<EpfEntry> inserts = new ArrayList<>();
        List<EpfEmployer> employersUsed = new ArrayList<>();
        int totalImported = 0;
        int totalReplaced = 0;
        int totalSkipped = 0;
        int[][] counts = new int[bp.sections.size()][3];
        for (int i = 0; i < bp.sections.size(); i++) {
            Plan plan = bp.sections.get(i).plan;
            ParsedPassbook passbook = plan.passbook;
            EpfEmployer employer = plan.employer;
            if (employer == null) {
                employer = new EpfEmployer();
                employer.setUser(userRepository.getReferenceById(userId));
                employer.setName(plan.newName);
            }
            if (employer.getMemberId() == null) employer.setMemberId(passbook.memberId());
            if (employer.getEstablishmentId() == null) employer.setEstablishmentId(passbook.establishmentId());
            if (employer.getUan() == null) employer.setUan(passbook.uan());
            employer = employerRepository.save(employer);
            employersUsed.add(employer);
            for (Planned p : plan.rows) {
                switch (p.status) {
                    case EXISTS -> counts[i][2]++;
                    case REPLACES -> {
                        fill(p.replaces, p.row, now);
                        counts[i][1]++;
                    }
                    case NEW -> {
                        EpfEntry entry = new EpfEntry();
                        entry.setUser(userRepository.getReferenceById(userId));
                        entry.setEmployer(employer);
                        fill(entry, p.row, now);
                        inserts.add(entry);
                        counts[i][0]++;
                    }
                }
            }
        }
        entryRepository.saveAll(inserts);
        List<EpfEntry> all = entryRepository.findAllForUser(userId);
        BigDecimal balance = EpfLedger.of(all).total().total();
        for (int i = 0; i < bp.sections.size(); i++) {
            EpfEmployer employer = employersUsed.get(i);
            UUID employerKey = employer.getId();
            LocalDate throughMonth = all.stream()
                    .filter(e -> e.getType() == EpfEntryType.CONTRIBUTION && e.getWageMonth() != null
                            && e.getEmployer().getId().equals(employerKey))
                    .map(EpfEntry::getWageMonth).max(LocalDate::compareTo).orElse(null);
            results.add(new EpfImportResultResponse(employerKey, employer.getName(), counts[i][0], counts[i][1],
                    counts[i][2], balance, throughMonth));
            totalImported += counts[i][0];
            totalReplaced += counts[i][1];
            totalSkipped += counts[i][2];
        }
        return new EpfImportBatchResultResponse(results, totalImported, totalReplaced, totalSkipped, balance,
                bp.refused);
    }

    // ------------------------------------------------------------------ planning

    private static final class Planned {
        final ParsedPassbookRow row;
        final Status status;
        final EpfEntry replaces;
        BigDecimal computedEmployee;
        BigDecimal computedEmployer;

        Planned(ParsedPassbookRow row, Status status, EpfEntry replaces) {
            this.row = row;
            this.status = status;
            this.replaces = replaces;
        }
    }

    private static final class Plan {
        ParsedPassbook passbook;
        EpfEmployer employer; // null = will be created
        String newName;
        List<Planned> rows = new ArrayList<>();
        int matchedOpeningCount;
        List<EpfEmployerOption> options = new ArrayList<>();

        EpfImportPreviewResponse toPreview() {
            int n = 0, x = 0, r = 0;
            List<EpfImportPreviewResponse.EpfImportRow> out = new ArrayList<>();
            for (Planned p : rows) {
                switch (p.status) {
                    case NEW -> n++;
                    case EXISTS -> x++;
                    case REPLACES -> r++;
                }
                ParsedPassbookRow row = p.row;
                out.add(new EpfImportPreviewResponse.EpfImportRow(row.index(), EpfEntryType.valueOf(row.type().name()),
                        row.withdrawalKind() == null ? null : EpfWithdrawalKind.valueOf(row.withdrawalKind().name()),
                        row.wageMonth() == null ? null : row.wageMonth().atDay(1), row.creditedOn(), row.interestFy(),
                        row.employee(), row.employer(), row.pension(), row.particulars(), p.status,
                        p.replaces == null ? null : p.replaces.getId(), p.computedEmployee, p.computedEmployer));
            }
            EpfImportPreviewResponse.EpfImportEmployer emp = new EpfImportPreviewResponse.EpfImportEmployer(
                    employer == null ? null : employer.getId(),
                    employer == null ? newName : employer.getName(),
                    employer != null && employer.getMemberId() != null ? employer.getMemberId() : passbook.memberId(),
                    employer != null && employer.getEstablishmentId() != null ? employer.getEstablishmentId()
                            : passbook.establishmentId(),
                    last4(employer != null && employer.getUan() != null ? employer.getUan() : passbook.uan()),
                    rows.stream().map(p -> p.row)
                            .filter(row -> EpfEntryType.valueOf(row.type().name()) == EpfEntryType.CONTRIBUTION
                                    && row.wageMonth() != null)
                            .map(row -> row.wageMonth().atDay(1)).max(LocalDate::compareTo).orElse(null));
            return new EpfImportPreviewResponse(emp, out, n, x, r, passbook.unrecognisedLines(), options,
                    matchedOpeningCount);
        }
    }

    private static final int MAX_FILES = 12;

    private static final class SectionPlan {
        String key;
        List<String> fileNames = new ArrayList<>();
        Plan plan;
    }

    private static final class BatchPlan {
        List<SectionPlan> sections = new ArrayList<>();
        List<EpfImportRefusedFile> refused = new ArrayList<>();
    }

    private static final class Group {
        String key;
        List<String> fileNames = new ArrayList<>();
        List<ParsedPassbook> passbooks = new ArrayList<>();
    }

    private BatchPlan planBatch(List<BatchFile> files, List<String> assignParams, @Nullable UUID singleEmployerId) {
        if (files.isEmpty()) throw new ValidationException("Upload at least one passbook.");
        if (files.size() > MAX_FILES) throw new ValidationException("Upload at most 12 passbooks at once.");
        Map<String, UUID> assign = parseAssign(assignParams);
        UUID userId = currentUserId();
        List<EpfEmployer> employers = employerRepository.findAllByUser_Id(userId);

        String refUan = null;
        for (EpfEmployer e : employers) {
            if (e.getUan() != null) {
                refUan = e.getUan();
                break;
            }
        }
        BatchPlan bp = new BatchPlan();
        Map<String, Group> groups = new LinkedHashMap<>();
        for (int i = 0; i < files.size(); i++) {
            BatchFile f = files.get(i);
            ParsedPassbook pb;
            try {
                pb = parser.parse(f.bytes());
            } catch (EpfPassbookFormatException e) {
                bp.refused.add(new EpfImportRefusedFile(f.name(), e.getReason().name(), e.getMessage()));
                continue;
            }
            if (pb.uan() != null) {
                if (refUan == null) {
                    refUan = pb.uan();
                } else if (!refUan.equals(pb.uan())) {
                    bp.refused.add(new EpfImportRefusedFile(f.name(), "UAN_MISMATCH",
                            "This passbook is for a different UAN (…" + last4(pb.uan())
                                    + "). Your EPF uses UAN …" + last4(refUan) + "."));
                    continue;
                }
            }
            String key = pb.memberId() != null ? "member:" + pb.memberId() : "file:" + i;
            Group g = groups.computeIfAbsent(key, k -> {
                Group ng = new Group();
                ng.key = k;
                return ng;
            });
            g.fileNames.add(f.name());
            g.passbooks.add(pb);
        }
        for (String key : assign.keySet()) {
            if (!groups.containsKey(key)) throw new ValidationException("Unknown passbook in the employer choices.");
        }

        List<EpfEmployerOption> options = new ArrayList<>();
        for (EpfEmployer e : employers) {
            if (e.getMemberId() == null) options.add(new EpfEmployerOption(e.getId(), e.getName()));
        }
        Map<UUID, List<EpfEntry>> entriesByEmployer = new HashMap<>();
        if (!groups.isEmpty()) {
            for (EpfEntry e : entryRepository.findAllForUser(userId)) {
                entriesByEmployer.computeIfAbsent(e.getEmployer().getId(), k -> new ArrayList<>()).add(e);
            }
        }
        Set<UUID> usedEmployers = new HashSet<>();
        for (Group g : groups.values()) {
            ParsedPassbook merged = merge(g.passbooks);
            UUID chosenId = assign.get(g.key);
            if (chosenId == null) chosenId = singleEmployerId;
            Plan plan = new Plan();
            plan.passbook = merged;
            if (chosenId != null) {
                UUID wanted = chosenId;
                EpfEmployer chosen = employers.stream().filter(e -> e.getId().equals(wanted)).findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("EpfEmployer", wanted));
                if (chosen.getMemberId() != null && merged.memberId() != null
                        && !chosen.getMemberId().equals(merged.memberId())) {
                    throw new ValidationException("That employer already has a different member ID.");
                }
                plan.employer = chosen;
            } else if (merged.memberId() != null) {
                plan.employer = employers.stream().filter(e -> merged.memberId().equals(e.getMemberId()))
                        .findFirst().orElse(null);
            }
            if (plan.employer != null && !usedEmployers.add(plan.employer.getId())) {
                throw new ValidationException("Two passbooks can't go under the same employer.");
            }
            if (plan.employer == null) {
                String name = merged.establishmentName() == null ? "" : merged.establishmentName().trim();
                plan.newName = name.isEmpty() ? UNKNOWN_EMPLOYER : name;
            }
            plan.options.addAll(options);

            List<EpfEntry> passbookEntries = new ArrayList<>();
            List<EpfEntry> manualEntries = new ArrayList<>();
            if (plan.employer != null) {
                for (EpfEntry e : entriesByEmployer.getOrDefault(plan.employer.getId(), List.of())) {
                    (e.getSource() == EpfEntrySource.PASSBOOK ? passbookEntries : manualEntries).add(e);
                }
            }
            for (ParsedPassbookRow row : merged.rows()) {
                EpfEntry match = take(passbookEntries, e -> sameRow(e, row));
                if (match != null) {
                    plan.rows.add(new Planned(row, Status.EXISTS, null));
                    continue;
                }
                match = take(manualEntries, e -> sameSlot(e, row));
                plan.rows.add(match != null ? new Planned(row, Status.REPLACES, match)
                        : new Planned(row, Status.NEW, null));
            }
            settleOpeningBalances(plan, plan.employer == null ? List.of()
                    : entriesByEmployer.getOrDefault(plan.employer.getId(), List.of()));
            SectionPlan sp = new SectionPlan();
            sp.key = g.key;
            sp.fileNames = g.fileNames;
            sp.plan = plan;
            bp.sections.add(sp);
        }
        return bp;
    }

    /**
     * Opening-balance rows are checks, not entries: the earliest one is kept (unless it is 0/0), a later new one is
     * dropped when the timeline before it already adds up to it, else kept with the amounts the entries come to.
     */
    private static void settleOpeningBalances(Plan plan, List<EpfEntry> existing) {
        Set<EpfEntry> replaced = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Planned p : plan.rows) if (p.status == Status.REPLACES) replaced.add(p.replaces);
        Instant now = Instant.EPOCH;
        List<EpfEntry> timeline = new ArrayList<>();
        List<Planned> owners = new ArrayList<>();
        for (EpfEntry e : existing) {
            if (replaced.contains(e)) continue;
            timeline.add(e);
            owners.add(null);
        }
        for (Planned p : plan.rows) {
            if (p.status == Status.EXISTS) continue;
            EpfEntry unsaved = new EpfEntry();
            fill(unsaved, p.row, now);
            timeline.add(unsaved);
            owners.add(p);
        }
        Integer[] order = new Integer[timeline.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.<Integer, LocalDate>comparing(i -> timeline.get(i).getCreditedOn())
                .thenComparing(i -> timeline.get(i).getType().ordinal()));
        EpfLedger.Balance balance = EpfLedger.Balance.ZERO;
        Set<Planned> dropped = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int k = 0; k < order.length; k++) {
            EpfEntry entry = timeline.get(order[k]);
            Planned p = owners.get(order[k]);
            if (p != null && p.status == Status.NEW && entry.getType() == EpfEntryType.OPENING_BALANCE) {
                boolean zero = entry.getEmployeeAmount().signum() == 0 && entry.getEmployerAmount().signum() == 0;
                if (k == 0) {
                    if (zero) dropped.add(p);
                } else if (balance.employee().compareTo(entry.getEmployeeAmount()) == 0
                        && balance.employer().compareTo(entry.getEmployerAmount()) == 0) {
                    dropped.add(p);
                    plan.matchedOpeningCount++;
                } else {
                    p.computedEmployee = balance.employee();
                    p.computedEmployer = balance.employer();
                }
            }
            balance = EpfLedger.apply(balance, entry);
        }
        plan.rows.removeAll(dropped);
    }

    private static Map<String, UUID> parseAssign(List<String> params) {
        Map<String, UUID> out = new LinkedHashMap<>();
        for (String p : params) {
            int eq = p == null ? -1 : p.indexOf('=');
            if (eq <= 0) throw new ValidationException("Invalid employer choice.");
            try {
                out.put(p.substring(0, eq), UUID.fromString(p.substring(eq + 1)));
            } catch (IllegalArgumentException ex) {
                throw new ValidationException("Invalid employer choice.");
            }
        }
        return out;
    }

    /** Merges the files of one member ID: overlapping rows are kept once, rows sorted by credit date and renumbered. */
    private static ParsedPassbook merge(List<ParsedPassbook> files) {
        if (files.size() == 1) return files.get(0);
        List<ParsedPassbookRow> merged = new ArrayList<>(files.get(0).rows());
        for (int i = 1; i < files.size(); i++) {
            List<ParsedPassbookRow> pool = new ArrayList<>(merged);
            for (ParsedPassbookRow row : files.get(i).rows()) {
                boolean dup = false;
                for (int k = 0; k < pool.size(); k++) {
                    if (sameParsed(pool.get(k), row)) {
                        pool.remove(k);
                        dup = true;
                        break;
                    }
                }
                if (!dup) merged.add(row);
            }
        }
        merged.sort(java.util.Comparator.comparing(ParsedPassbookRow::creditedOn));
        List<ParsedPassbookRow> rows = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            ParsedPassbookRow r = merged.get(i);
            rows.add(new ParsedPassbookRow(i, r.type(), r.withdrawalKind(), r.wageMonth(), r.creditedOn(),
                    r.interestFy(), r.employee(), r.employer(), r.pension(), r.particulars()));
        }
        String uan = null, memberId = null, establishmentId = null, establishmentName = null;
        int unrecognised = 0;
        for (ParsedPassbook f : files) {
            if (uan == null) uan = f.uan();
            if (memberId == null) memberId = f.memberId();
            if (establishmentId == null) establishmentId = f.establishmentId();
            if (establishmentName == null) establishmentName = f.establishmentName();
            unrecognised += f.unrecognisedLines();
        }
        return new ParsedPassbook(uan, memberId, establishmentId, establishmentName, rows, unrecognised,
                files.get(0).closing());
    }

    private static boolean sameParsed(ParsedPassbookRow a, ParsedPassbookRow b) {
        return a.type() == b.type()
                && Objects.equals(a.wageMonth(), b.wageMonth())
                && a.creditedOn().equals(b.creditedOn())
                && Objects.equals(a.interestFy(), b.interestFy())
                && a.withdrawalKind() == b.withdrawalKind()
                && a.employee().compareTo(b.employee()) == 0
                && a.employer().compareTo(b.employer()) == 0
                && a.pension().compareTo(b.pension()) == 0;
    }

    private static EpfEntry take(List<EpfEntry> pool, java.util.function.Predicate<EpfEntry> test) {
        for (int i = 0; i < pool.size(); i++) {
            if (test.test(pool.get(i))) return pool.remove(i);
        }
        return null;
    }

    private static boolean sameRow(EpfEntry e, ParsedPassbookRow r) {
        return e.getType().name().equals(r.type().name())
                && java.util.Objects.equals(e.getWageMonth(), r.wageMonth() == null ? null : r.wageMonth().atDay(1))
                && e.getCreditedOn().equals(r.creditedOn())
                && java.util.Objects.equals(e.getInterestFy(), r.interestFy())
                && java.util.Objects.equals(e.getWithdrawalKind() == null ? null : e.getWithdrawalKind().name(),
                        r.withdrawalKind() == null ? null : r.withdrawalKind().name())
                && e.getEmployeeAmount().compareTo(r.employee()) == 0
                && e.getEmployerAmount().compareTo(r.employer()) == 0
                && e.getPensionAmount().compareTo(r.pension()) == 0;
    }

    private static boolean sameSlot(EpfEntry e, ParsedPassbookRow r) {
        if (!e.getType().name().equals(r.type().name())) return false;
        return switch (e.getType()) {
            case CONTRIBUTION -> r.wageMonth() != null && e.getWageMonth() != null
                    && e.getWageMonth().equals(r.wageMonth().atDay(1));
            case INTEREST -> e.getInterestFy() != null && e.getInterestFy().equals(r.interestFy());
            case OPENING_BALANCE -> e.getCreditedOn().equals(r.creditedOn());
            default -> e.getCreditedOn().equals(r.creditedOn()) && gross(e).compareTo(gross(r)) == 0;
        };
    }

    private static BigDecimal gross(EpfEntry e) {
        return e.getWithdrawalKind() == EpfWithdrawalKind.PENSION ? e.getPensionAmount()
                : e.getEmployeeAmount().add(e.getEmployerAmount());
    }

    private static BigDecimal gross(ParsedPassbookRow r) {
        return r.withdrawalKind() != null && r.withdrawalKind().name().equals(EpfWithdrawalKind.PENSION.name())
                ? r.pension() : r.employee().add(r.employer());
    }

    private static void fill(EpfEntry e, ParsedPassbookRow r, Instant now) {
        e.setType(EpfEntryType.valueOf(r.type().name()));
        e.setWithdrawalKind(r.withdrawalKind() == null ? null : EpfWithdrawalKind.valueOf(r.withdrawalKind().name()));
        e.setWageMonth(r.wageMonth() == null ? null : r.wageMonth().atDay(1));
        e.setCreditedOn(r.creditedOn());
        e.setInterestFy(r.interestFy());
        e.setEmployeeAmount(r.employee());
        e.setEmployerAmount(r.employer());
        e.setPensionAmount(r.pension());
        String p = r.particulars();
        e.setParticulars(p == null ? null : p.length() > PARTICULARS_MAX ? p.substring(0, PARTICULARS_MAX) : p);
        e.setSource(EpfEntrySource.PASSBOOK);
        e.setImportedAt(now);
    }

    private static String last4(@Nullable String s) {
        return s == null ? null : s.length() <= 4 ? s : s.substring(s.length() - 4);
    }

    private static UUID currentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) throw new ResourceNotFoundException("User", "current");
        return userId;
    }
}
