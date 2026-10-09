package com.financeos.domain.report.breakdown;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the reconciling steps of a row breakdown in the direction the row is listed.
 *
 * <p>Terms are given as signed contributions to the item's signed amount (a balance: +credits,
 * −debits; a loan's outstanding: −principal repaid). A row lists the magnitude of that amount, so
 * when the amount is negative every term is flipped; each term then becomes an {@code add} or
 * {@code subtract} of its magnitude and the chain closes on the listed value. A residual between
 * the terms and the listed value is never absorbed: it is logged and shown as an explicit
 * "Rounding difference" step.
 */
final class BreakdownChain {

    private static final Logger log = LoggerFactory.getLogger(BreakdownChain.class);

    static final String START = "start";
    static final String ADD = "add";
    static final String SUBTRACT = "subtract";
    static final String EQUALS = "equals";
    static final String INFO = "info";
    static final String CURRENCY = "currency";

    private final int direction;
    private final List<BreakdownStep> steps = new ArrayList<>();
    private BigDecimal running = BigDecimal.ZERO;

    /** @param signedAmount the item's signed amount; the row lists its magnitude */
    BreakdownChain(BigDecimal signedAmount) {
        this.direction = signedAmount.signum() < 0 ? -1 : 1;
    }

    /** The opening figure, shown signed in the listed direction. */
    BreakdownChain start(String label, BigDecimal signedAmount) {
        BigDecimal amount = oriented(signedAmount);
        steps.add(new BreakdownStep(START, label, null, amount, CURRENCY));
        running = running.add(amount);
        return this;
    }

    /** A movement: an add or subtract of its magnitude, depending on its sign in the listed direction. */
    BreakdownChain term(String label, BigDecimal signedAmount) {
        BigDecimal amount = oriented(signedAmount);
        steps.add(new BreakdownStep(amount.signum() < 0 ? SUBTRACT : ADD, label, null, amount.abs(), CURRENCY));
        running = running.add(amount);
        return this;
    }

    /** A standalone fact that takes no part in the sum. */
    BreakdownChain info(String label, String detail, BigDecimal amount, String format) {
        steps.add(new BreakdownStep(INFO, label, detail, amount, format));
        return this;
    }

    /**
     * Ends the chain on {@code total}, the row's listed value, inserting a "Rounding difference"
     * step (and logging it with {@code context}) when the terms do not add up to it exactly.
     */
    List<BreakdownStep> close(String equalsLabel, BigDecimal total, String context) {
        BigDecimal residual = total.subtract(running);
        if (residual.signum() != 0) {
            log.warn("Breakdown of {} misses its value {} by {}", context, total, residual);
            steps.add(new BreakdownStep(residual.signum() < 0 ? SUBTRACT : ADD, "Rounding difference", null,
                    residual.abs(), CURRENCY));
        }
        steps.add(new BreakdownStep(EQUALS, equalsLabel, null, total, CURRENCY));
        return List.copyOf(steps);
    }

    private BigDecimal oriented(BigDecimal signedAmount) {
        return direction < 0 ? signedAmount.negate() : signedAmount;
    }
}
