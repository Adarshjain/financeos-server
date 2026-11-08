package com.financeos.domain.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The balance query's IN lists are padded to few distinct lengths without changing which values they hold. */
class PadInListTest {

    private static List<String> values(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add("v" + i);
        }
        return out;
    }

    @Test
    void padsToTheNextPowerOfTwoKeepingTheOriginalsFirst() {
        for (int[] c : new int[][] { { 1, 1 }, { 2, 2 }, { 3, 4 }, { 4, 4 }, { 5, 8 }, { 50, 64 }, { 64, 64 }, { 65, 128 } }) {
            List<String> in = values(c[0]);
            List<String> padded = TransactionRepositoryCustomImpl.padInList(in);
            assertEquals(c[1], padded.size(), "size for " + c[0]);
            assertEquals(in, padded.subList(0, c[0]));
            assertEquals(new LinkedHashSet<>(in), new LinkedHashSet<>(padded), "same distinct values for " + c[0]);
        }
    }

    @Test
    void neverExceedsOraclesThousandEntryCap() {
        assertEquals(1000, TransactionRepositoryCustomImpl.padInList(values(900)).size());
        assertEquals(1000, TransactionRepositoryCustomImpl.padInList(values(513)).size());
    }
}
