package com.financeos.domain.nps.link;

import com.financeos.api.nps.dto.NpsReviewResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.nps.NpsAccount;
import com.financeos.domain.nps.NpsAccountRepository;
import com.financeos.domain.nps.NpsAccountStatus;
import com.financeos.domain.nps.NpsEntry;
import com.financeos.domain.nps.NpsEntryRepository;
import com.financeos.domain.nps.NpsStatement;
import com.financeos.domain.nps.NpsStatementRepository;
import com.financeos.domain.nps.NpsTier;
import com.financeos.domain.nps.link.NpsLinkMatcher.Entry;
import com.financeos.domain.nps.link.NpsLinkMatcher.Pair;
import com.financeos.domain.nps.link.NpsLinkMatcher.Payment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What NPS needs the user to decide, found once for both the Inbox collectors and the NPS page. With no NPS account:
 * the payments behind "Set up NPS?". With accounts: contributions that fit several payments (or a payment fitting
 * several contributions), and NPS-looking debits older than a week that fit no contribution. "Not this one" and
 * "Not NPS" are honoured.
 */
@Service
@Transactional(readOnly = true)
public class NpsReviewService {

    /** Why the app did not link a contribution itself: it fits several payments. */
    public static final String WHY_MULTIPLE_CANDIDATES = "MULTIPLE_CANDIDATES";
    /** Why the app did not link a contribution itself: its only payment also fits another contribution. */
    public static final String WHY_SHARED_PAYMENT = "SHARED_PAYMENT";

    private static final int SAMPLE = 5;

    /**
     * {@code ambiguous}: every fitting pair that is not unique, newest payment first. {@code unrecorded}: payments
     * that fit no contribution and are older than the window, newest first. {@code setup}: the payments behind
     * "Set up NPS?" (only when the user has no account). {@code entries}: the contributions of the pairs, by id.
     */
    public record Detection(List<NpsAccount> accounts, List<Pair> ambiguous, List<Payment> unrecorded,
                            List<Payment> setup, Map<UUID, NpsEntry> entries) {
    }

    private final NpsAccountRepository accountRepository;
    private final NpsEntryRepository entryRepository;
    private final NpsStatementRepository statementRepository;
    private final NpsLinkStore store;

    public NpsReviewService(NpsAccountRepository accountRepository, NpsEntryRepository entryRepository,
                            NpsStatementRepository statementRepository, NpsLinkStore store) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.statementRepository = statementRepository;
        this.store = store;
    }

    public Detection detect(UUID userId, LocalDate today) {
        List<NpsAccount> accounts = accountRepository.findAllByUser_Id(userId);
        if (accounts.isEmpty()) {
            return new Detection(accounts, List.of(), List.of(), store.loadSetupPayments(userId, today), Map.of());
        }
        List<Payment> payments = store.loadPayments(userId);
        if (payments.isEmpty()) {
            return new Detection(accounts, List.of(), List.of(), List.of(), Map.of());
        }
        Map<UUID, NpsEntry> byId = new LinkedHashMap<>();
        List<Entry> entries = new ArrayList<>();
        for (NpsEntry e : entryRepository.findUnlinkedSelfContributions(userId)) {
            byId.put(e.getId(), e);
            entries.add(new Entry(e.getId(), e.getAccount().getId(), e.getTier(), e.getAmount(), e.getPostedDate()));
        }
        NpsLinkMatcher.Plan plan = NpsLinkMatcher.plan(entries, payments, store.loadDismissals(userId));
        List<Pair> ambiguous = plan.ambiguous().stream()
                .sorted(Comparator.comparing((Pair p) -> p.payment().date()).reversed()).toList();
        LocalDate cutoff = today.minusDays(NpsPaymentPatterns.WINDOW_DAYS);
        List<Payment> unrecorded = plan.unmatched().stream().filter(x -> x.date().isBefore(cutoff))
                .sorted(Comparator.comparing(Payment::date).reversed()).toList();
        return new Detection(accounts, ambiguous, unrecorded, List.of(), byId);
    }

    public NpsReviewResponse review() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResourceNotFoundException("User", "current");
        }
        LocalDate today = AppTime.today();
        Detection d = detect(userId, today);
        Map<UUID, NpsAccount> accountsById = d.accounts().stream()
                .collect(Collectors.toMap(NpsAccount::getId, a -> a));

        // Group the pairs by contribution; a contribution with one payment that is shared is the SHARED_PAYMENT case.
        Map<UUID, List<Payment>> candidates = new LinkedHashMap<>();
        for (Pair pair : d.ambiguous()) {
            candidates.computeIfAbsent(pair.entry().id(), k -> new ArrayList<>()).add(pair.payment());
        }
        Map<UUID, NpsStatement> statements = statementsFor(d.entries().values());
        List<NpsReviewResponse.Match> matches = new ArrayList<>();
        candidates.forEach((entryId, payments) -> {
            NpsEntry e = d.entries().get(entryId);
            NpsAccount account = accountsById.get(e.getAccount().getId());
            NpsStatement statement = e.getStatementId() == null ? null : statements.get(e.getStatementId());
            matches.add(new NpsReviewResponse.Match(
                    new NpsReviewResponse.Contribution(e.getId(), accountRef(account), e.getTier(), e.getPostedDate(),
                            e.getAmount(), e.getSource(), statement == null ? null : statement.getPeriodStart(),
                            statement == null ? null : statement.getPeriodEnd()),
                    payments.stream().map(NpsReviewService::payment).toList(),
                    payments.size() > 1 ? WHY_MULTIPLE_CANDIDATES : WHY_SHARED_PAYMENT));
        });

        List<NpsReviewResponse.Account> recordTo = d.accounts().stream()
                .filter(a -> a.getStatus() == NpsAccountStatus.ACTIVE).map(NpsReviewService::accountRef).toList();
        List<NpsReviewResponse.Unrecorded> unrecorded = d.unrecorded().stream()
                .map(p -> new NpsReviewResponse.Unrecorded(payment(p), NpsPaymentPatterns.matchedWord(p.description()),
                        ChronoUnit.DAYS.between(p.date(), today), recordTo))
                .toList();
        return new NpsReviewResponse(matches, unrecorded, setup(d.setup()));
    }

    private Map<UUID, NpsStatement> statementsFor(java.util.Collection<NpsEntry> entries) {
        List<UUID> ids = entries.stream().map(NpsEntry::getStatementId).filter(java.util.Objects::nonNull).distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return statementRepository.findAllById(ids).stream().collect(Collectors.toMap(NpsStatement::getId, s -> s));
    }

    private static NpsReviewResponse.Setup setup(List<Payment> payments) {
        if (payments.isEmpty()) {
            return null;
        }
        List<Payment> newestFirst = payments.stream().sorted(Comparator.comparing(Payment::date).reversed()).toList();
        BigDecimal total = payments.stream().map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new NpsReviewResponse.Setup(payments.size(), newestFirst.get(newestFirst.size() - 1).date(),
                newestFirst.get(0).date(), total,
                newestFirst.stream().limit(SAMPLE).map(NpsReviewService::payment).toList());
    }

    private static NpsReviewResponse.Payment payment(Payment p) {
        return new NpsReviewResponse.Payment(p.id(), p.date(), p.amount(), p.description(), p.accountName());
    }

    private static NpsReviewResponse.Account accountRef(NpsAccount a) {
        List<NpsTier> tiers = a.isTier2Active() ? List.of(NpsTier.I, NpsTier.II) : List.of(NpsTier.I);
        return new NpsReviewResponse.Account(a.getId(), a.getPranLast4(), a.getHolderName(), a.getRelation(), tiers);
    }
}
