package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.core.security.UserContext;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.HoldingPosition;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps one pending {@code oversold} suggestion per stock the user sold more of than the lots they hold (the
 * engine records the excess and the date of the first such sell): a split, bonus or earlier buy may be missing.
 * Rows of any status are kept so a dismissal sticks; a pending row of an instrument that is no longer oversold
 * (or whose first date moved) is removed. Positions are computed once per call.
 */
@Service
public class OversoldSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(OversoldSuggestionService.class);

    private record Oversold(Instrument instrument, LocalDate firstDate) {
    }

    private final InvestmentService investmentService;
    private final CorporateActionSuggestionRepository suggestions;
    private final UserRepository users;

    public OversoldSuggestionService(InvestmentService investmentService, CorporateActionSuggestionRepository suggestions,
                                     UserRepository users) {
        this.investmentService = investmentService;
        this.suggestions = suggestions;
        this.users = users;
    }

    /** Upserts and self-clears {@code userId}'s oversold suggestions; never throws. */
    public void sync(UUID userId) {
        UUID previous = UserContext.getCurrentUserId();
        try {
            if (!userId.equals(previous)) {
                UserContext.setCurrentUserId(userId);
            }
            List<HoldingPosition> positions = new ArrayList<>();
            for (InvestmentService.HoldingDetail d : investmentService.getHoldingDetails(null)) {
                positions.add(d.position());
            }
            apply(userId, oversoldOf(positions));
        } catch (Exception e) {
            log.warn("Oversold check failed for user {}: {}", userId, e.toString());
        } finally {
            if (!userId.equals(previous)) {
                if (previous == null) {
                    UserContext.clear();
                } else {
                    UserContext.setCurrentUserId(previous);
                }
            }
        }
    }

    /** Per stock instrument: the earliest first-oversold date over its oversold positions. */
    static Map<UUID, Oversold> oversoldOf(List<HoldingPosition> positions) {
        Map<UUID, Oversold> out = new HashMap<>();
        for (HoldingPosition p : positions) {
            Instrument instrument = p.holding().getInstrument();
            if (instrument.getType() != InstrumentType.stock || p.oversoldQty() == null || p.oversoldQty().signum() <= 0
                    || p.oversoldDate() == null) {
                continue;
            }
            out.merge(instrument.getId(), new Oversold(instrument, p.oversoldDate()),
                    (a, b) -> a.firstDate().isAfter(b.firstDate()) ? b : a);
        }
        return out;
    }

    private void apply(UUID userId, Map<UUID, Oversold> oversold) {
        Set<String> keys = new HashSet<>();
        List<CorporateActionSuggestion> stale = new ArrayList<>();
        for (CorporateActionSuggestion s : suggestions.findAllOwned(userId)) {
            if (s.getKind() != SuggestionKind.oversold) {
                continue;
            }
            Oversold now = oversold.get(s.getInstrument().getId());
            boolean current = now != null && now.firstDate().equals(s.getExDate());
            if (!current && s.getStatus() == SuggestionStatus.pending) {
                stale.add(s);
            } else {
                keys.add(s.getInstrument().getId() + "|" + s.getExDate());
            }
        }
        List<CorporateActionSuggestion> toSave = new ArrayList<>();
        for (Oversold o : oversold.values()) {
            if (!keys.add(o.instrument().getId() + "|" + o.firstDate())) {
                continue;
            }
            CorporateActionSuggestion s = new CorporateActionSuggestion();
            s.setUser(users.getReferenceById(userId));
            s.setInstrument(o.instrument());
            s.setKind(SuggestionKind.oversold);
            s.setExDate(o.firstDate());
            s.setStatus(SuggestionStatus.pending);
            s.setCreatedAt(Instant.now());
            toSave.add(s);
        }
        if (!stale.isEmpty()) {
            suggestions.deleteAll(stale);
        }
        if (!toSave.isEmpty()) {
            suggestions.saveAll(toSave);
        }
    }
}
