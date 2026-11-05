package com.financeos.domain.nps.gmail;

import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.price.PriceRefreshEvent;
import com.financeos.domain.nps.NpsAccount;
import com.financeos.domain.nps.NpsAccountRepository;
import com.financeos.domain.nps.NpsAllocation;
import com.financeos.domain.nps.NpsAllocationRepository;
import com.financeos.domain.nps.NpsContributor;
import com.financeos.domain.nps.NpsEntry;
import com.financeos.domain.nps.NpsEntryLine;
import com.financeos.domain.nps.NpsEntryRepository;
import com.financeos.domain.nps.NpsEntrySource;
import com.financeos.domain.nps.NpsEntryStatus;
import com.financeos.domain.nps.NpsEntryType;
import com.financeos.domain.nps.NpsScheme;
import com.financeos.domain.nps.NpsSchemeRepository;
import com.financeos.domain.nps.NpsTier;
import com.financeos.domain.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns the two Protean alert mails into ledger entries: a contribution request is a PENDING entry (counts for
 * nothing), a credit intimation makes it (or a new entry) ESTIMATED with units from the account's allocation at the
 * credited NAV. The next statement upgrades either (see NpsStatementImportService). Order-independent: a request that
 * arrives after its credit, or a credit after the statement that confirmed it, writes nothing. Every query names the user.
 */
@Service
@Transactional
public class NpsAlertService {

    public enum Result { ENTRY, IGNORED }

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int CREDIT_WINDOW_DAYS = 10;
    private static final int CONFIRMED_WINDOW_DAYS = 5;
    private static final int NAV_LOOKBACK_DAYS = 7;

    private final NpsAccountRepository accountRepository;
    private final NpsEntryRepository entryRepository;
    private final NpsAllocationRepository allocationRepository;
    private final NpsSchemeRepository schemeRepository;
    private final InstrumentPriceRepository priceRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public NpsAlertService(NpsAccountRepository accountRepository, NpsEntryRepository entryRepository,
                           NpsAllocationRepository allocationRepository, NpsSchemeRepository schemeRepository,
                           InstrumentPriceRepository priceRepository, UserRepository userRepository,
                           ApplicationEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.allocationRepository = allocationRepository;
        this.schemeRepository = schemeRepository;
        this.priceRepository = priceRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    /** The one account of the user whose PRAN ends in the digits and that reads Gmail; null when none or several. */
    private NpsAccount uniqueAccount(UUID userId, String last4) {
        List<NpsAccount> matches = accountRepository.findAllByUser_Id(userId).stream()
                .filter(a -> a.isReadGmail() && last4.equals(a.getPranLast4())).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    // ------------------------------------------------------------------ request confirmation

    public Result applyRequest(UUID userId, NpsAlertParser.Request request, LocalDate mailDate) {
        NpsAccount account = uniqueAccount(userId, request.pranLast4());
        if (account == null) {
            return Result.IGNORED;
        }
        String key = "email|ack|" + request.ack();
        List<NpsEntry> existing = entryRepository.findForAccountTier(userId, account.getId(), request.tier());
        for (NpsEntry e : existing) {
            if (key.equals(e.getDedupeKey())) {
                return Result.ENTRY;
            }
        }
        // the credit mail (or the statement) of this contribution came first: nothing is pending any more
        for (NpsEntry e : existing) {
            if (e.getType() == NpsEntryType.CONTRIBUTION && e.getStatus() != NpsEntryStatus.PENDING
                    && sameAmount(e.getAmount(), request.amount())
                    && !e.getPostedDate().isBefore(mailDate.minusDays(1))
                    && !e.getPostedDate().isAfter(mailDate.plusDays(CREDIT_WINDOW_DAYS))) {
                return Result.ENTRY;
            }
        }
        NpsEntry entry = new NpsEntry();
        entry.setUser(userRepository.getReferenceById(userId));
        entry.setAccount(account);
        entry.setTier(request.tier());
        entry.setType(NpsEntryType.CONTRIBUTION);
        entry.setContributor(NpsContributor.SELF);
        entry.setAmount(request.amount());
        entry.setPostedDate(mailDate);
        entry.setSource(NpsEntrySource.EMAIL);
        entry.setStatus(NpsEntryStatus.PENDING);
        entry.setDescription("eNPS contribution under process");
        entry.setDedupeKey(key);
        entryRepository.saveAndFlush(entry);
        return Result.ENTRY;
    }

    // ------------------------------------------------------------------ credit intimation

    public Result applyCredit(UUID userId, NpsAlertParser.Credit credit) {
        NpsAccount account = uniqueAccount(userId, credit.pranLast4());
        if (account == null) {
            return Result.IGNORED;
        }
        String key = "email|credit|" + credit.pranLast4() + "|" + credit.tier() + "|" + credit.navDate() + "|"
                + credit.amount().toPlainString();
        List<NpsEntry> existing = entryRepository.findForAccountTier(userId, account.getId(), credit.tier());
        for (NpsEntry e : existing) {
            if (key.equals(e.getDedupeKey())) {
                return Result.ENTRY;
            }
        }
        NpsEntry target = null;
        long bestDays = Long.MAX_VALUE;
        for (NpsEntry e : existing) {
            if (e.getType() == NpsEntryType.CONTRIBUTION && e.getStatus() == NpsEntryStatus.PENDING
                    && sameAmount(e.getAmount(), credit.amount())) {
                long days = Math.abs(ChronoUnit.DAYS.between(e.getPostedDate(), credit.creditDate()));
                if (days <= CREDIT_WINDOW_DAYS && (days < bestDays
                        || (days == bestDays && target != null && e.getId().compareTo(target.getId()) < 0))) {
                    target = e;
                    bestDays = days;
                }
            }
        }
        if (target == null) {
            // a statement already confirmed this contribution: an estimate would count it twice
            for (NpsEntry e : existing) {
                if (e.getType() == NpsEntryType.CONTRIBUTION && e.getStatus() == NpsEntryStatus.CONFIRMED
                        && sameAmount(e.getAmount(), credit.amount())
                        && (near(e.getPostedDate(), credit.creditDate()) || near(e.getPostedDate(), credit.navDate()))) {
                    return Result.ENTRY;
                }
            }
            target = new NpsEntry();
            target.setUser(userRepository.getReferenceById(userId));
            target.setAccount(account);
            target.setTier(credit.tier());
            target.setType(NpsEntryType.CONTRIBUTION);
        } else {
            target.getLines().clear();
            entryRepository.saveAndFlush(target);
        }
        target.setContributor(credit.contributor());
        target.setAmount(credit.amount());
        target.setPostedDate(credit.creditDate());
        target.setNavDate(credit.navDate());
        target.setSource(NpsEntrySource.EMAIL);
        target.setStatus(NpsEntryStatus.ESTIMATED);
        target.setDescription("NPS contribution credited");
        target.setDedupeKey(key);
        fillLines(target, account, credit);
        entryRepository.saveAndFlush(target);
        return Result.ENTRY;
    }

    /**
     * The amount split by the account's current allocation for the tier (the last scheme takes the rounding
     * remainder), each part buying units at the scheme's NAV of the credited date. With no allocation, or a scheme
     * with no NAV yet (its history is then requested), the entry keeps no lines and stays ESTIMATED.
     */
    private void fillLines(NpsEntry entry, NpsAccount account, NpsAlertParser.Credit credit) {
        List<NpsAllocation> rows = allocationRepository.findForAccounts(List.of(account.getId())).stream()
                .filter(a -> a.getTier() == credit.tier())
                .sorted(Comparator.comparing(NpsAllocation::getSchemeCode)).toList();
        if (rows.isEmpty()) {
            return;
        }
        Map<String, NpsScheme> schemes = new HashMap<>();
        schemeRepository.findAllById(rows.stream().map(NpsAllocation::getSchemeCode).toList())
                .forEach(s -> schemes.put(s.getSchemeCode(), s));
        List<UUID> instrumentIds = schemes.values().stream().map(NpsScheme::getInstrumentId).toList();
        Map<UUID, InstrumentPrice> navs = new HashMap<>();
        for (InstrumentPrice p : priceRepository.findFeedRowsBetween(instrumentIds,
                credit.navDate().minusDays(NAV_LOOKBACK_DAYS), credit.navDate())) {
            navs.merge(p.getInstrument().getId(), p, (a, b) -> a.getAsOf().isAfter(b.getAsOf()) ? a : b);
        }
        BigDecimal left = credit.amount();
        List<NpsEntryLine> lines = new ArrayList<>();
        Set<UUID> missing = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            NpsAllocation row = rows.get(i);
            BigDecimal part = i == rows.size() - 1 ? left
                    : credit.amount().multiply(row.getPercent()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            left = left.subtract(part);
            NpsScheme scheme = schemes.get(row.getSchemeCode());
            if (scheme == null) {
                return;
            }
            InstrumentPrice price = navs.get(scheme.getInstrumentId());
            if (price == null) {
                missing.add(scheme.getInstrumentId());
                continue;
            }
            if (part.signum() == 0) {
                continue;
            }
            BigDecimal nav = price.getClose().setScale(4, RoundingMode.HALF_UP);
            lines.add(new NpsEntryLine(row.getSchemeCode(), part.divide(nav, 4, RoundingMode.HALF_UP), nav, part));
        }
        if (!missing.isEmpty()) {
            eventPublisher.publishEvent(new PriceRefreshEvent(missing));
            return;
        }
        lines.forEach(entry::addLine);
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a != null && a.subtract(b).abs().compareTo(TOLERANCE) <= 0;
    }

    private static boolean near(LocalDate a, LocalDate b) {
        return Math.abs(ChronoUnit.DAYS.between(a, b)) <= CONFIRMED_WINDOW_DAYS;
    }
}
