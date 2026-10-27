package com.financeos.domain.epf;

import com.financeos.api.epf.dto.EpfImportPreviewResponse;
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
import java.util.List;
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

    @Transactional(readOnly = true)
    public EpfImportPreviewResponse preview(byte[] file, @Nullable UUID employerId) {
        Plan plan = plan(file, employerId);
        return plan.toPreview();
    }

    public EpfImportResultResponse commit(byte[] file, @Nullable UUID employerId) {
        Plan plan = plan(file, employerId);
        UUID userId = currentUserId();
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

        Instant now = AppTime.now().atZone(AppTime.zone()).toInstant();
        List<EpfEntry> inserts = new ArrayList<>();
        int imported = 0;
        int replaced = 0;
        int skipped = 0;
        for (Planned p : plan.rows) {
            switch (p.status) {
                case EXISTS -> skipped++;
                case REPLACES -> {
                    fill(p.replaces, p.row, now);
                    replaced++;
                }
                case NEW -> {
                    EpfEntry entry = new EpfEntry();
                    entry.setUser(userRepository.getReferenceById(userId));
                    entry.setEmployer(employer);
                    fill(entry, p.row, now);
                    inserts.add(entry);
                    imported++;
                }
            }
        }
        entryRepository.saveAll(inserts);
        return new EpfImportResultResponse(employer.getId(), employer.getName(), imported, replaced, skipped);
    }

    // ------------------------------------------------------------------ planning

    private static final class Planned {
        final ParsedPassbookRow row;
        final Status status;
        final EpfEntry replaces;

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
                        p.replaces == null ? null : p.replaces.getId()));
            }
            EpfImportPreviewResponse.EpfImportEmployer emp = new EpfImportPreviewResponse.EpfImportEmployer(
                    employer == null ? null : employer.getId(),
                    employer == null ? newName : employer.getName(),
                    employer != null && employer.getMemberId() != null ? employer.getMemberId() : passbook.memberId(),
                    employer != null && employer.getEstablishmentId() != null ? employer.getEstablishmentId()
                            : passbook.establishmentId(),
                    last4(employer != null && employer.getUan() != null ? employer.getUan() : passbook.uan()));
            return new EpfImportPreviewResponse(emp, out, n, x, r, passbook.unrecognisedLines(), options);
        }
    }

    private Plan plan(byte[] file, @Nullable UUID employerId) {
        ParsedPassbook passbook;
        try {
            passbook = parser.parse(file);
        } catch (EpfPassbookFormatException e) {
            throw new ApiStatusException(HttpStatus.BAD_REQUEST, e.getReason().name(), e.getMessage());
        }
        UUID userId = currentUserId();
        List<EpfEmployer> employers = employerRepository.findAllByUser_Id(userId);

        if (passbook.uan() != null) {
            for (EpfEmployer e : employers) {
                if (e.getUan() != null && !e.getUan().equals(passbook.uan())) {
                    throw new ApiStatusException(HttpStatus.BAD_REQUEST, "UAN_MISMATCH",
                            "This passbook is for a different UAN (…" + last4(passbook.uan())
                                    + "). Your EPF uses UAN …" + last4(e.getUan()) + ".");
                }
            }
        }

        Plan plan = new Plan();
        plan.passbook = passbook;
        if (employerId != null) {
            EpfEmployer chosen = employers.stream().filter(e -> e.getId().equals(employerId)).findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("EpfEmployer", employerId));
            if (chosen.getMemberId() != null && passbook.memberId() != null
                    && !chosen.getMemberId().equals(passbook.memberId())) {
                throw new ValidationException("That employer already has a different member ID.");
            }
            plan.employer = chosen;
        } else if (passbook.memberId() != null) {
            plan.employer = employers.stream().filter(e -> passbook.memberId().equals(e.getMemberId()))
                    .findFirst().orElse(null);
        }
        if (plan.employer == null) {
            String name = passbook.establishmentName() == null ? "" : passbook.establishmentName().trim();
            plan.newName = name.isEmpty() ? UNKNOWN_EMPLOYER : name;
        }
        for (EpfEmployer e : employers) {
            if (e.getMemberId() == null) plan.options.add(new EpfEmployerOption(e.getId(), e.getName()));
        }

        List<EpfEntry> passbookEntries = new ArrayList<>();
        List<EpfEntry> manualEntries = new ArrayList<>();
        if (plan.employer != null) {
            for (EpfEntry e : entryRepository.findAllForUser(userId)) {
                if (!e.getEmployer().getId().equals(plan.employer.getId())) continue;
                (e.getSource() == EpfEntrySource.PASSBOOK ? passbookEntries : manualEntries).add(e);
            }
        }
        for (ParsedPassbookRow row : passbook.rows()) {
            EpfEntry match = take(passbookEntries, e -> sameRow(e, row));
            if (match != null) {
                plan.rows.add(new Planned(row, Status.EXISTS, null));
                continue;
            }
            match = take(manualEntries, e -> sameSlot(e, row));
            plan.rows.add(match != null ? new Planned(row, Status.REPLACES, match) : new Planned(row, Status.NEW, null));
        }
        return plan;
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
