package com.financeos.statement.parser;

import java.time.LocalDate;
import java.util.List;

class TxnDraft {
    LocalDate date;
    List<String> desc;
    List<AmountCell> amounts;
    int pos;
    int page;
    double top;
    List<Double> descX;
    String cardLast4;

    TxnDraft(LocalDate date, List<String> desc, List<AmountCell> amounts,
             int pos, int page, double top, List<Double> descX, String cardLast4) {
        this.date = date;
        this.desc = desc;
        this.amounts = amounts;
        this.pos = pos;
        this.page = page;
        this.top = top;
        this.descX = descX;
        this.cardLast4 = cardLast4;
    }

    TxnDraft(LocalDate date, List<String> desc, List<AmountCell> amounts,
             int pos, int page, double top, List<Double> descX) {
        this(date, desc, amounts, pos, page, top, descX, null);
    }
}
