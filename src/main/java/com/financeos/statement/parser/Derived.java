package com.financeos.statement.parser;

import java.util.List;
import java.util.Map;

public class Derived {
    public int transactionCount;
    public int debitCount;
    public int creditCount;
    public int activeDays;
    public RowResult largestDebit;
    public RowResult largestCredit;
    public Double avgDebit;
    public Double avgDailySpend;
    public List<Map.Entry<String, Double>> topMerchants;
}
