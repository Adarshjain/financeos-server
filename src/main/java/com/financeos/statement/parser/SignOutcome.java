package com.financeos.statement.parser;

import java.util.List;

record SignOutcome(List<RowResult> results, int chainBreaks, Double openingUsed) {
}
