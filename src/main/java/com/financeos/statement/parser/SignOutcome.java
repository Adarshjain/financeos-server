package com.financeos.statement.parser;

import java.util.List;

// pruned: rows dropped as chain outliers (dated summary boxes/ads, or misread rows)
record SignOutcome(List<RowResult> results, int chainBreaks, Double openingUsed, int pruned) {
}
