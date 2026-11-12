package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.api.instrument.dto.CorporateActionReviewItemResponse;
import com.financeos.api.instrument.dto.CorporateActionReviewItemResponse.ReviewInstrument;
import com.financeos.api.instrument.dto.CorporateActionReviewResponse;
import com.financeos.api.instrument.dto.CorporateActionReviewResponse.NotTradingStock;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.PricePrecedence;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.InvestmentService.AsOfQuery;
import com.financeos.domain.investment.InvestmentService.OpenAsOf;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Corporate actions page's data: pending and automatic suggestions with the user's position before the ex-date,
 * and the held stocks that no longer trade. Position reads are batched for the whole list (one engine input load).
 */
@Service
public class CorporateActionReviewService {

    static final String SOURCE_NSE = "NSE";

    private final CorporateActionSuggestionRepository suggestions;
    private final InvestmentService investmentService;
    private final StockNotTradingRule notTradingRule;
    private final InstrumentPriceRepository prices;

    public CorporateActionReviewService(CorporateActionSuggestionRepository suggestions, InvestmentService investmentService,
                                        StockNotTradingRule notTradingRule, InstrumentPriceRepository prices) {
        this.suggestions = suggestions;
        this.investmentService = investmentService;
        this.notTradingRule = notTradingRule;
        this.prices = prices;
    }

    @Transactional(readOnly = true)
    public CorporateActionReviewResponse review() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ValidationException("The corporate actions page needs a signed-in user");
        }
        List<CorporateActionSuggestion> pending = suggestions.findPendingUnresolved(userId);
        List<CorporateActionSuggestion> applied = suggestions.findOwnedByStatus(userId, SuggestionStatus.auto_applied);

        Set<AsOfQuery> queries = new HashSet<>();
        boolean anyOversold = false;
        for (CorporateActionSuggestion s : concat(pending, applied)) {
            queries.add(new AsOfQuery(s.getInstrument().getId(), s.getExDate()));
            anyOversold |= s.getKind() == SuggestionKind.oversold;
        }
        Map<AsOfQuery, OpenAsOf> positions = investmentService.openPositionsBefore(queries);
        Map<UUID, BigDecimal> oversoldQty = anyOversold ? oversoldQtyByInstrument() : Map.of();

        return new CorporateActionReviewResponse(
                pending.stream().map(s -> item(s, positions, oversoldQty)).toList(),
                applied.stream().map(s -> item(s, positions, oversoldQty)).toList(),
                notTrading(userId));
    }

    private static List<CorporateActionSuggestion> concat(List<CorporateActionSuggestion> a, List<CorporateActionSuggestion> b) {
        List<CorporateActionSuggestion> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /** The excess sold per instrument over all the user's holdings of it. */
    private Map<UUID, BigDecimal> oversoldQtyByInstrument() {
        Map<UUID, BigDecimal> out = new HashMap<>();
        for (InvestmentService.HoldingDetail d : investmentService.getHoldingDetails(null)) {
            BigDecimal q = d.position().oversoldQty();
            if (q != null && q.signum() > 0) {
                out.merge(d.position().holding().getInstrument().getId(), q, BigDecimal::add);
            }
        }
        return out;
    }

    private CorporateActionReviewItemResponse item(CorporateActionSuggestion s, Map<AsOfQuery, OpenAsOf> positions,
                                                   Map<UUID, BigDecimal> oversoldQty) {
        OpenAsOf open = positions.get(new AsOfQuery(s.getInstrument().getId(), s.getExDate()));
        BigDecimal sharesBefore = open == null ? null : open.qty();
        BigDecimal avgBefore = open == null || open.qty().signum() <= 0 ? null
                : open.cost().divide(open.qty(), 4, RoundingMode.HALF_UP);
        BigDecimal factor = exchangeFactor(s);
        BigDecimal sharesAfter = factor == null || sharesBefore == null ? null
                : sharesBefore.multiply(factor).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        BigDecimal avgAfter = factor == null || avgBefore == null ? null
                : avgBefore.divide(factor, 4, RoundingMode.HALF_UP);
        boolean oversold = s.getKind() == SuggestionKind.oversold;
        return new CorporateActionReviewItemResponse(s.getId(), s.getKind(), s.getStatus(), s.getReason(), s.getExDate(),
                s.getRatioFrom(), s.getRatioTo(), s.getSubject(),
                new ReviewInstrument(s.getInstrument().getId(), s.getInstrument().getName(), s.getInstrument().getSymbol(),
                        s.getInstrument().getExchange()),
                s.getCorporateAction() == null ? null : s.getCorporateAction().getId(),
                s.getCreatedAt(), s.getResolvedAt(), oversold ? null : SOURCE_NSE, s.getFeedFactor(), factor,
                sharesBefore, sharesAfter, avgBefore, avgAfter,
                oversold ? oversoldQty.get(s.getInstrument().getId()) : null, oversold ? s.getExDate() : null);
    }

    /** The multiplier the notice implies: {@code ratioTo / ratioFrom} for a split or bonus (the rule the lot engine applies). */
    static BigDecimal exchangeFactor(CorporateActionSuggestion s) {
        if (s.getKind() != SuggestionKind.split && s.getKind() != SuggestionKind.bonus) {
            return null;
        }
        Integer from = s.getRatioFrom();
        Integer to = s.getRatioTo();
        if (from == null || from <= 0 || to == null || to <= 0) {
            return null;
        }
        return BigDecimal.valueOf(to).divide(BigDecimal.valueOf(from), 10, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private List<NotTradingStock> notTrading(UUID userId) {
        List<Object[]> rows = notTradingRule.unanswered(userId);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(r -> (UUID) r[0]).toList();
        Map<UUID, InstrumentPrice> latest = PricePrecedence.byInstrument(prices.findLatestByInstrumentIds(ids, userId));
        List<NotTradingStock> out = new ArrayList<>();
        for (Object[] r : rows) {
            UUID id = (UUID) r[0];
            InstrumentPrice p = latest.get(id);
            out.add(new NotTradingStock(new ReviewInstrument(id, (String) r[1], (String) r[3], (String) r[4]),
                    (BigDecimal) r[2], p == null ? null : p.getClose(), p == null ? null : p.getAsOf(), (Instant) r[5]));
        }
        return out;
    }
}
