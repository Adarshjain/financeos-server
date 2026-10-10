package com.financeos.domain.instrument;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.investment.InvestmentService.HoldingFigures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repoint's safety net ({@link InstrumentRepointService#verifyFiguresUnchanged}): the user's figures
 * after the move must equal those before, the source's holdings counted as the target's; only a holding
 * merged at one broker may change its cost and realised gains (reported, not refused).
 */
class InstrumentRepointFiguresCheckTest {

    private static final UUID USER = UUID.randomUUID();
    private final Instrument source = instrument("Source Co");
    private final Instrument target = instrument("Target Co");
    private final Instrument child = instrument("Child Co");
    private final UUID brokerZ = UUID.randomUUID();
    private final UUID brokerG = UUID.randomUUID();

    private static Instrument instrument(String name) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setName(name);
        return i;
    }

    private HoldingFigures fig(UUID broker, Instrument i, String qty, String cost, String realized) {
        return fig(broker, i, qty, cost, realized, null);
    }

    private HoldingFigures fig(UUID broker, Instrument i, String qty, String cost, String realized, String error) {
        return new HoldingFigures(UUID.randomUUID(), broker, broker.equals(brokerZ) ? "Z" : "G", i.getId(), i.getName(),
                new BigDecimal(qty), new BigDecimal(cost), new BigDecimal(realized), BigDecimal.ZERO, error);
    }

    private boolean check(List<HoldingFigures> before, List<HoldingFigures> after, Set<String> mergedBrokers) {
        return InstrumentRepointService.verifyFiguresUnchanged(USER, source, target, before, after, mergedBrokers);
    }

    @Test
    void aMovedHoldingWithTheSameFiguresPasses() {
        assertFalse(check(
                List.of(fig(brokerZ, source, "10", "1000", "0"), fig(brokerG, target, "4", "400", "0")),
                List.of(fig(brokerZ, target, "10", "1000", "0"), fig(brokerG, target, "4", "400", "0")),
                Set.of()));
    }

    @Test
    void aMovedHoldingWhoseQuantityChangedIsRefusedNamingIt() {
        ValidationException e = assertThrows(ValidationException.class, () -> check(
                List.of(fig(brokerZ, source, "10", "1000", "0")),
                List.of(fig(brokerZ, target, "20", "1000", "0")),
                Set.of()));
        assertTrue(e.getMessage().contains("Target Co at Z (quantity 10 → 20"), e.getMessage());
    }

    @Test
    void aCostChangeOnAnUnmergedHoldingIsRefused() {
        assertThrows(ValidationException.class, () -> check(
                List.of(fig(brokerG, target, "4", "400", "0")),
                List.of(fig(brokerG, target, "4", "300", "0")),
                Set.of()));
    }

    @Test
    void aRealisedChangeOnAnUnmergedHoldingIsRefused() {
        assertThrows(ValidationException.class, () -> check(
                List.of(fig(brokerG, target, "4", "400", "10")),
                List.of(fig(brokerG, target, "4", "400", "12")),
                Set.of()));
    }

    @Test
    void anotherInstrumentsChangeIsRefused() {
        // e.g. a demerger carried onto a target with more parent lots seeding more child shares.
        ValidationException e = assertThrows(ValidationException.class, () -> check(
                List.of(fig(brokerZ, source, "10", "1000", "0"), fig(brokerZ, child, "5", "250", "0")),
                List.of(fig(brokerZ, target, "10", "1000", "0"), fig(brokerZ, child, "7", "350", "0")),
                Set.of()));
        assertTrue(e.getMessage().contains("Child Co at Z"), e.getMessage());
    }

    @Test
    void aMergedHoldingMayChangeCostAndRealisedButReportsIt() {
        assertTrue(check(
                List.of(fig(brokerZ, source, "6", "600", "200"), fig(brokerZ, target, "10", "2000", "0")),
                List.of(fig(brokerZ, target, "16", "2200", "-200")),
                Set.of(brokerZ.toString())));
    }

    @Test
    void aMergedHoldingWithTheSameSummedFiguresReportsNothing() {
        assertFalse(check(
                List.of(fig(brokerZ, source, "6", "600", "200"), fig(brokerZ, target, "10", "2000", "0")),
                List.of(fig(brokerZ, target, "16", "2600", "200")),
                Set.of(brokerZ.toString())));
    }

    @Test
    void aMergedHoldingWhoseQuantityChangedIsRefused() {
        assertThrows(ValidationException.class, () -> check(
                List.of(fig(brokerZ, source, "6", "600", "0"), fig(brokerZ, target, "10", "2000", "0")),
                List.of(fig(brokerZ, target, "12", "2600", "0")),
                Set.of(brokerZ.toString())));
    }

    @Test
    void aHoldingTheEngineCanNoLongerComputeIsRefused() {
        assertThrows(ValidationException.class, () -> check(
                List.of(fig(brokerG, target, "0", "0", "0")),
                List.of(fig(brokerG, target, "0", "0", "0", "FIFO violation"))
                , Set.of()));
    }

    @Test
    void aHoldingThatAlreadyFailedBeforeDoesNotBlock() {
        assertFalse(check(
                List.of(fig(brokerG, target, "0", "0", "0", "FIFO violation")),
                List.of(fig(brokerG, target, "0", "0", "0", "FIFO violation")),
                Set.of()));
    }
}
