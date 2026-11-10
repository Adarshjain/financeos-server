package com.financeos.statement.parser;

import java.util.List;
import java.util.stream.Collectors;

public record Line(List<Word> words) {
    public String text() {
        return words.stream().map(Word::text).collect(Collectors.joining(" "));
    }

    public int page() {
        return words.get(0).page();
    }

    public double top() {
        return words.get(0).top();
    }
}
