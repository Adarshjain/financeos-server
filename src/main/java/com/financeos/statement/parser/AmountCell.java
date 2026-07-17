package com.financeos.statement.parser;

class AmountCell {
    double value;
    int sign;
    double x1;
    String text;
    int col;

    AmountCell(double value, int sign, double x1, String text) {
        this.value = value;
        this.sign = sign;
        this.x1 = x1;
        this.text = text;
    }
}
