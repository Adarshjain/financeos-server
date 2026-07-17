package com.financeos.statement.parser;

import java.time.LocalDate;

public class RowResult {
    public LocalDate date;
    public String description;
    public Double amount;
    public Double balance;
    public boolean chainValid;
    public String signSource;

    RowResult(LocalDate date, String description, Double amount, Double balance, boolean chainValid) {
        this.date = date;
        this.description = description;
        this.amount = amount;
        this.balance = balance;
        this.chainValid = chainValid;
    }

    public RowResult copy() {
        RowResult r = new RowResult(date, description, amount, balance, chainValid);
        r.signSource = signSource;
        return r;
    }
}
