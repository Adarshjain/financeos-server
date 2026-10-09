package com.financeos.domain.investment;

import com.financeos.domain.instrument.corporateaction.CorporateAction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A holding's position together with the FIFO engine's account of how it got there: every
 * timeline event in engine order and the lots left open at the end. Produced by
 * {@link InvestmentService#traceHoldingPosition} from the same loop that computes the position,
 * so the lots always sum to {@code position.openQty()} / {@code position.openCost()} (before
 * their final rounding).
 *
 * @param position the position exactly as {@link InvestmentService#calculateHoldingPosition} returns it
 * @param openLots the lots open after the last event, oldest first, with unrounded engine values
 * @param events   the timeline events in the order the engine applied them
 */
public record HoldingTrace(HoldingPosition position, List<OpenLot> openLots, List<Event> events) {

    /** Where an open lot came from. */
    public enum LotOrigin {
        /** A delivery buy as traded. */
        BUY,
        /** The delivery residual of a day whose intraday buys and sells were netted. */
        INTRADAY_NETTED_DELIVERY,
        /** Shares received from a demerger or merger of another instrument. */
        CORPORATE_ACTION
    }

    /**
     * The origin of a lot.
     *
     * @param action the demerger/merger the shares were received from; null unless
     *               {@code origin} is {@link LotOrigin#CORPORATE_ACTION}
     */
    public record LotSource(LotOrigin origin, CorporateAction action) {

        public static final LotSource BUY = new LotSource(LotOrigin.BUY, null);
        public static final LotSource INTRADAY_NETTED_DELIVERY = new LotSource(LotOrigin.INTRADAY_NETTED_DELIVERY, null);

        public static LotSource corporateAction(CorporateAction action) {
            return new LotSource(LotOrigin.CORPORATE_ACTION, action);
        }
    }

    /** A lot still open after FIFO matching. */
    public record OpenLot(LocalDate buyDate, LotSource source, BigDecimal quantity, BigDecimal costPerUnit) {

        /** The lot's cost, unrounded ({@code quantity × costPerUnit}). */
        public BigDecimal cost() {
            return quantity.multiply(costPerUnit);
        }
    }

    /** What a timeline event was. */
    public enum EventKind {
        BUY,
        SELL,
        /** A day whose intraday buys and sells were netted; it moves no lots itself. */
        INTRADAY_NETTED,
        /** The delivery buy left after netting a day's intraday trades. */
        DELIVERY_BUY,
        /** The delivery sell left after netting a day's intraday trades. */
        DELIVERY_SELL,
        /** A split, bonus, demerger or merger of this instrument ({@code action}). */
        CORPORATE_ACTION,
        /** Shares received from a demerger or merger of another instrument ({@code action}). */
        RECEIVED_FROM_CORPORATE_ACTION
    }

    /**
     * One applied timeline event.
     *
     * @param action         the corporate action for the two corporate-action kinds, else null
     * @param quantity       the event's own size: shares traded, squared off intraday, or received;
     *                       null for {@link EventKind#CORPORATE_ACTION}
     * @param price          the trade price (the netted average for delivery residuals); null otherwise
     * @param quantityChange the signed change the event made to the open quantity
     * @param quantityAfter  the open quantity after the event
     */
    public record Event(
            LocalDate date,
            EventKind kind,
            CorporateAction action,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal quantityChange,
            BigDecimal quantityAfter) {
    }
}
