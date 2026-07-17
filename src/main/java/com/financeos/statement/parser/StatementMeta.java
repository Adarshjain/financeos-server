package com.financeos.statement.parser;

import java.time.LocalDate;

public class StatementMeta {
    public String bank;
    public String accountNumber;
    public String ifsc;
    public String holderName;
    public LocalDate periodStart;
    public LocalDate periodEnd;
    public Double openingBalance;
    public Double closingBalance;
}
