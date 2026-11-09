package com.financeos.domain.nps;

import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.instrument.price.NpsTrustFeedClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Keeps the NPS scheme catalog and its NAVs current from the NPS Trust daily file: every scheme in the file
 * becomes (or stays) an {@code nps_scheme} instrument with a row in {@code nps_scheme}, and its NAV is
 * upserted into the shared feed prices. The file lists all schemes, so this writes every one of them, not
 * only the ones a user holds.
 */
@Service
public class NpsCatalogService {

    private static final Logger log = LoggerFactory.getLogger(NpsCatalogService.class);
    private static final int INSTRUMENT_NAME_MAX = 255;

    private final NpsTrustFeedClient feedClient;
    private final NpsSchemeRepository schemeRepository;
    private final InstrumentRepository instrumentRepository;
    private final InstrumentPriceRepository priceRepository;
    private final TransactionTemplate tx;
    private final NpsEntryRepository entryRepository;
    private final NpsHistoryBackfill historyBackfill;

    public NpsCatalogService(NpsTrustFeedClient feedClient, NpsSchemeRepository schemeRepository,
                             InstrumentRepository instrumentRepository, InstrumentPriceRepository priceRepository,
                             PlatformTransactionManager transactionManager, NpsEntryRepository entryRepository,
                             NpsHistoryBackfill historyBackfill) {
        this.feedClient = feedClient;
        this.schemeRepository = schemeRepository;
        this.instrumentRepository = instrumentRepository;
        this.priceRepository = priceRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.entryRepository = entryRepository;
        this.historyBackfill = historyBackfill;
    }

    /** Downloads the daily file (no transaction open), then upserts the catalog and NAVs in one. Returns the NAV rows written. */
    public int sync() {
        feedClient.load();
        List<NpsTrustFeedClient.NavRow> rows = feedClient.all();
        if (rows.isEmpty()) {
            return 0;
        }
        Integer written = tx.execute(status -> apply(rows));
        requestHeldHistory();
        return written == null ? 0 : written;
    }

    /**
     * Asks for NAV history of every scheme held in any ledger, back to its earliest entry; schemes whose history
     * is already there are skipped. Runs outside a transaction, so the listener takes the event on
     * {@code fallbackExecution}. Repairs schemes imported before history was requested at import.
     */
    private void requestHeldHistory() {
        try {
            Map<String, LocalDate> coverFrom = new HashMap<>();
            for (Object[] row : entryRepository.findEarliestPostedDatesForAllLines()) {
                coverFrom.put((String) row[0], (LocalDate) row[1]);
            }
            int requested = historyBackfill.requestFor(coverFrom);
            log.info("NPS history backfill requested for {} of {} held schemes", requested, coverFrom.size());
        } catch (Exception e) {
            log.warn("NPS history backfill request failed: {}", e.getMessage());
        }
    }

    /** Whether no scheme is in the catalog yet (a fresh database). */
    public boolean isCatalogEmpty() {
        return schemeRepository.count() == 0;
    }

    /** Upserts the schemes and their NAVs of the given daily-file rows; returns the NAV rows written. */
    public int apply(List<NpsTrustFeedClient.NavRow> allRows) {
        Map<String, NpsTrustFeedClient.NavRow> byCode = new LinkedHashMap<>();
        for (NpsTrustFeedClient.NavRow row : allRows) {
            byCode.putIfAbsent(row.schemeCode(), row);
        }
        if (byCode.isEmpty()) {
            return 0;
        }
        Map<String, NpsScheme> schemes = schemeRepository.findAllById(byCode.keySet()).stream()
                .collect(Collectors.toMap(NpsScheme::getSchemeCode, s -> s));
        Map<UUID, Instrument> instruments = new HashMap<>();
        instrumentRepository.findAllById(schemes.values().stream().map(NpsScheme::getInstrumentId).toList())
                .forEach(i -> instruments.put(i.getId(), i));

        List<NpsTrustFeedClient.NavRow> fresh = new ArrayList<>();
        List<Instrument> newInstruments = new ArrayList<>();
        List<NpsScheme> changed = new ArrayList<>();
        for (NpsTrustFeedClient.NavRow row : byCode.values()) {
            NpsScheme scheme = schemes.get(row.schemeCode());
            String name = displayName(row);
            if (scheme == null) {
                Instrument instrument = new Instrument();
                instrument.setType(InstrumentType.nps_scheme);
                instrument.setName(name);
                instrument.setSymbol(row.schemeCode());
                instrument.setCurrency("INR");
                newInstruments.add(instrument);
                fresh.add(row);
                continue;
            }
            NpsSchemeNameParser.Meta meta = NpsSchemeNameParser.parse(row.schemeName());
            if (!matches(scheme, row, meta)) {
                fill(scheme, row, meta);
                changed.add(scheme);
            }
            Instrument instrument = instruments.get(scheme.getInstrumentId());
            if (instrument != null && !Objects.equals(instrument.getName(), name)) {
                instrument.setName(name);
                instrumentRepository.save(instrument);
            }
        }
        instrumentRepository.saveAll(newInstruments);
        List<NpsScheme> created = new ArrayList<>();
        for (int i = 0; i < fresh.size(); i++) {
            NpsTrustFeedClient.NavRow row = fresh.get(i);
            Instrument instrument = newInstruments.get(i);
            NpsScheme scheme = new NpsScheme();
            scheme.setSchemeCode(row.schemeCode());
            scheme.setInstrumentId(instrument.getId());
            fill(scheme, row, NpsSchemeNameParser.parse(row.schemeName()));
            created.add(scheme);
            schemes.put(scheme.getSchemeCode(), scheme);
            instruments.put(instrument.getId(), instrument);
        }
        schemeRepository.saveAll(changed);
        schemeRepository.saveAll(created);
        int written = storePrices(byCode, schemes, instruments);
        log.info("NPS catalog synced: {} schemes ({} new), {} NAVs written", byCode.size(), created.size(), written);
        return written;
    }

    private int storePrices(Map<String, NpsTrustFeedClient.NavRow> byCode, Map<String, NpsScheme> schemes,
                            Map<UUID, Instrument> instruments) {
        Set<LocalDate> dates = byCode.values().stream().map(NpsTrustFeedClient.NavRow::navDate).collect(Collectors.toSet());
        Map<String, InstrumentPrice> existing = new HashMap<>();
        for (InstrumentPrice p : priceRepository.findFeedRows(instruments.keySet(), dates)) {
            existing.put(p.getInstrument().getId() + "|" + p.getAsOf(), p);
        }
        int written = 0;
        for (NpsTrustFeedClient.NavRow row : byCode.values()) {
            UUID instrumentId = schemes.get(row.schemeCode()).getInstrumentId();
            InstrumentPrice price = existing.get(instrumentId + "|" + row.navDate());
            if (price == null) {
                price = new InstrumentPrice(instruments.get(instrumentId), row.navDate(), row.nav(), PriceSource.NPS_TRUST);
            } else if (price.getSource() == PriceSource.MANUAL) {
                continue;
            } else {
                price.setClose(row.nav());
                price.setSource(PriceSource.NPS_TRUST);
                price.setPriceTime(null);
            }
            priceRepository.save(price);
            written++;
        }
        return written;
    }

    private static String displayName(NpsTrustFeedClient.NavRow row) {
        String name = row.schemeName() == null || row.schemeName().isBlank() ? row.schemeCode() : row.schemeName().trim();
        return name.length() > INSTRUMENT_NAME_MAX ? name.substring(0, INSTRUMENT_NAME_MAX) : name;
    }

    private static boolean matches(NpsScheme s, NpsTrustFeedClient.NavRow row, NpsSchemeNameParser.Meta meta) {
        return Objects.equals(s.getPfmName(), row.pfmName())
                && Objects.equals(s.getPfmCode(), NpsSchemeNameParser.pfmCode(row.schemeCode()))
                && Objects.equals(s.getAssetClass(), meta.assetClass())
                && s.getTier() == meta.tier() && s.getVariant() == meta.variant();
    }

    private static void fill(NpsScheme s, NpsTrustFeedClient.NavRow row, NpsSchemeNameParser.Meta meta) {
        s.setPfmCode(NpsSchemeNameParser.pfmCode(row.schemeCode()));
        s.setPfmName(row.pfmName());
        s.setAssetClass(meta.assetClass());
        s.setTier(meta.tier());
        s.setVariant(meta.variant());
    }
}
