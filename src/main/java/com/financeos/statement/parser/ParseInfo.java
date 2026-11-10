package com.financeos.statement.parser;

import java.util.LinkedHashMap;
import java.util.Set;

public class ParseInfo {
    public String mode;
    public int rowsChainValidated;
    public double chainValidationPct;
    public double totalCredits;
    public double totalDebits;
    public Boolean checksumOk;
    public Boolean crossDebits;
    public Boolean crossCredits;
    public LinkedHashMap<String, Boolean> cardChecks;
    public String verdict;
    // dated rows the parser discarded (no date/amount, or chain outliers)
    public int rowsDropped;
    // summary fields the statement prints as none ("Payment Due Date: Nil"), so empty on purpose
    public Set<String> statedNone = Set.of();
}
