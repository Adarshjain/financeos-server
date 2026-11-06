package com.financeos.domain.transaction.match;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimum-cost one-to-one assignment (Hungarian method). Groups are small (same amount on one account), so the
 * O(n³) solve is instant.
 */
public final class Assignment {

    private Assignment() {
    }

    /** Cost of a pair, or null when the two may never be paired. */
    @FunctionalInterface
    public interface PairCost {
        Long cost(int row, int col);
    }

    /**
     * Pairs rows with columns one-to-one: as many allowed pairs as possible first, then the smallest total cost.
     * Returns row → column for the paired rows only.
     */
    public static Map<Integer, Integer> pairs(int rows, int cols, PairCost pairCost) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        if (rows == 0 || cols == 0) return out;
        int n = Math.max(rows, cols);
        long[][] allowed = new long[rows][cols];
        boolean[][] ok = new boolean[rows][cols];
        long maxCost = 0;
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                Long c = pairCost.cost(i, j);
                if (c != null) {
                    if (c < 0) throw new IllegalArgumentException("Negative pair cost");
                    ok[i][j] = true;
                    allowed[i][j] = c;
                    maxCost = Math.max(maxCost, c);
                }
            }
        }
        // A forbidden or padding cell costs more than any full set of allowed pairs, so the pair count is maximised first.
        long none = (maxCost + 1) * (n + 1);
        long[][] cost = new long[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                cost[i][j] = i < rows && j < cols && ok[i][j] ? allowed[i][j] : none;
            }
        }
        int[] chosen = solve(cost);
        for (int i = 0; i < rows; i++) {
            int j = chosen[i];
            if (j < cols && ok[i][j]) out.put(i, j);
        }
        return out;
    }

    /** Minimum-cost assignment on a square matrix; returns the column chosen for each row. */
    public static int[] solve(long[][] cost) {
        int n = cost.length;
        long inf = Long.MAX_VALUE / 4;
        long[] u = new long[n + 1];
        long[] v = new long[n + 1];
        int[] p = new int[n + 1];
        int[] way = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            long[] minv = new long[n + 1];
            Arrays.fill(minv, inf);
            boolean[] used = new boolean[n + 1];
            do {
                used[j0] = true;
                int i0 = p[j0];
                long delta = inf;
                int j1 = 0;
                for (int j = 1; j <= n; j++) {
                    if (used[j]) continue;
                    long cur = cost[i0 - 1][j - 1] - u[i0] - v[j];
                    if (cur < minv[j]) {
                        minv[j] = cur;
                        way[j] = j0;
                    }
                    if (minv[j] < delta) {
                        delta = minv[j];
                        j1 = j;
                    }
                }
                for (int j = 0; j <= n; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] result = new int[n];
        for (int j = 1; j <= n; j++) result[p[j] - 1] = j - 1;
        return result;
    }
}
