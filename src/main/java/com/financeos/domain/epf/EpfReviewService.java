package com.financeos.domain.epf;

import com.financeos.api.epf.dto.EpfReviewResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.transaction.Transaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** The EPF page's "needs your decision" list: the same suggestions the Inbox shows, with their evidence. */
@Service
@Transactional(readOnly = true)
public class EpfReviewService {

    private final EpfSuggestionService suggestionService;

    public EpfReviewService(EpfSuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    public EpfReviewResponse review() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResourceNotFoundException("User", "current");
        }
        List<EpfReviewResponse.Match> matches = suggestionService.pending(userId).stream().map(s -> {
            EpfEntry e = s.entry();
            Transaction c = s.credit();
            String description = c.getDescription() != null && !c.getDescription().isBlank()
                    ? c.getDescription() : c.getSourcedDescription();
            java.math.BigDecimal received = EpfLedger.received(e);
            String text = e.getParticulars() != null && !e.getParticulars().isBlank() ? e.getParticulars() : e.getNote();
            return new EpfReviewResponse.Match(
                    new EpfReviewResponse.Entry(e.getId(), e.getCreditedOn(), e.getWithdrawalKind(), text,
                            e.getEmployer().getName(), EpfLedger.gross(e), received, e.getTdsAmount()),
                    new EpfReviewResponse.Credit(c.getId(), c.getDate(), c.getAmount(), description,
                            c.getAccount().getName()),
                    c.getAmount().subtract(received), ChronoUnit.DAYS.between(e.getCreditedOn(), c.getDate()), s.why());
        }).toList();
        return new EpfReviewResponse(matches);
    }
}
