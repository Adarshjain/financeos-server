package com.financeos.statement.parser;

import java.util.LinkedHashMap;

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
}
