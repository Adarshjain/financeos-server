package com.financeos.domain.transaction.match;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentTest {

    @Test
    void picksTheMinimumCostPairing() {
        long[][] cost = {{4, 1, 3}, {2, 0, 5}, {3, 2, 2}};
        Map<Integer, Integer> pairs = Assignment.pairs(3, 3, (i, j) -> cost[i][j]);
        assertThat(pairs).containsExactlyInAnyOrderEntriesOf(Map.of(0, 1, 1, 0, 2, 2));
    }

    @Test
    void maximisesPairsBeforeCost() {
        // row 0 can take either column; row 1 only column 0. The cheap 0→0 pair would leave row 1 unpaired.
        Map<Integer, Integer> pairs = Assignment.pairs(2, 2, (i, j) -> i == 0 ? Long.valueOf(j == 0 ? 0L : 50L) : (j == 0 ? Long.valueOf(50L) : null));
        assertThat(pairs).containsExactlyInAnyOrderEntriesOf(Map.of(0, 1, 1, 0));
    }

    @Test
    void forbiddenPairsAreNeverChosenAndRectangularShapesWork() {
        Map<Integer, Integer> pairs = Assignment.pairs(3, 1, (i, j) -> i == 2 ? Long.valueOf(7L) : null);
        assertThat(pairs).containsExactlyEntriesOf(Map.of(2, 0));
        assertThat(Assignment.pairs(1, 3, (i, j) -> null)).isEmpty();
        assertThat(Assignment.pairs(0, 3, (i, j) -> 0L)).isEmpty();
    }
}
