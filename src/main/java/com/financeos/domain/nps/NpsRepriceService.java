package com.financeos.domain.nps;

import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * When a scheme's NAV history lands, ESTIMATED entries that were priced at a later NAV (no earlier one existed) are
 * re-priced at the nearest NAV on or before their posted date. Only units and NAV change; amounts stay. The
 * reconciliation and closing checks of the affected accounts with statements are then redone.
 */
@Service
public class NpsRepriceService {

    private final NpsEntryRepository entryRepository;
    private final NpsSchemeRepository schemeRepository;
    private final InstrumentPriceRepository priceRepository;
    private final NpsStatementRepository statementRepository;
    private final NpsStatementImportService statementImportService;

    public NpsRepriceService(NpsEntryRepository entryRepository, NpsSchemeRepository schemeRepository,
                             InstrumentPriceRepository priceRepository, NpsStatementRepository statementRepository,
                             NpsStatementImportService statementImportService) {
        this.entryRepository = entryRepository;
        this.schemeRepository = schemeRepository;
        this.priceRepository = priceRepository;
        this.statementRepository = statementRepository;
        this.statementImportService = statementImportService;
    }

    /** Returns the number of entries re-priced. */
    @Transactional
    public int repriceInstruments(Set<UUID> instrumentIds) {
        if (instrumentIds == null || instrumentIds.isEmpty()) {
            return 0;
        }
        Map<String, UUID> instrumentByCode = new HashMap<>();
        for (NpsScheme s : schemeRepository.findByInstrumentIdIn(instrumentIds)) {
            instrumentByCode.put(s.getSchemeCode(), s.getInstrumentId());
        }
        if (instrumentByCode.isEmpty()) {
            return 0;
        }
        List<NpsEntry> entries = entryRepository.findEstimatedWithSchemes(instrumentByCode.keySet());
        if (entries.isEmpty()) {
            return 0;
        }
        LocalDate earliest = entries.stream().map(NpsEntry::getPostedDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate latest = entries.stream().map(NpsEntry::getPostedDate).max(LocalDate::compareTo).orElseThrow();
        Map<UUID, TreeMap<LocalDate, BigDecimal>> navs = new HashMap<>();
        for (InstrumentPrice p : priceRepository.findFeedUpTo(new LinkedHashSet<>(instrumentByCode.values()),
                earliest.minusDays(10), latest)) {
            navs.computeIfAbsent(p.getInstrument().getId(), k -> new TreeMap<>()).put(p.getAsOf(), p.getClose());
        }

        Set<List<Object>> affected = new LinkedHashSet<>();
        int repriced = 0;
        for (NpsEntry entry : entries) {
            boolean changed = false;
            LocalDate navDate = null;
            for (NpsEntryLine line : entry.getLines()) {
                UUID instrumentId = instrumentByCode.get(line.getSchemeCode());
                TreeMap<LocalDate, BigDecimal> series = instrumentId == null ? null : navs.get(instrumentId);
                Map.Entry<LocalDate, BigDecimal> nearest = series == null ? null : series.floorEntry(entry.getPostedDate());
                if (nearest == null || line.getAmount() == null || line.getAmount().signum() == 0) {
                    continue;
                }
                BigDecimal nav = nearest.getValue().setScale(4, RoundingMode.HALF_UP);
                BigDecimal units = line.getAmount().divide(nav, 4, RoundingMode.HALF_UP);
                if (line.getNav() == null || nav.compareTo(line.getNav()) != 0 || units.compareTo(line.getUnits()) != 0) {
                    line.setNav(nav);
                    line.setUnits(units);
                    changed = true;
                }
                if (navDate == null || nearest.getKey().isAfter(navDate)) {
                    navDate = nearest.getKey();
                }
            }
            if (changed) {
                if (navDate != null) {
                    entry.setNavDate(navDate);
                }
                repriced++;
                affected.add(List.of(entry.getUser().getId(), entry.getAccount().getId(), entry.getTier()));
            }
        }
        if (repriced == 0) {
            return 0;
        }
        entryRepository.flush();
        for (List<Object> key : new ArrayList<>(affected)) {
            UUID userId = (UUID) key.get(0);
            UUID accountId = (UUID) key.get(1);
            NpsTier tier = (NpsTier) key.get(2);
            if (!statementRepository.findForAccountTier(userId, accountId, tier).isEmpty()) {
                statementImportService.rebuildReconciliation(userId, accountId, tier);
                statementImportService.checkClosing(userId, accountId, tier);
            }
        }
        return repriced;
    }
}
