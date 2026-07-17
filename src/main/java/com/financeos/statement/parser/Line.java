package com.financeos.statement.parser;

import java.util.List;
import java.util.stream.Collectors;

record Line(List<Word> words) {
    String text() {
        return words.stream().map(Word::text).collect(Collectors.joining(" "));
    }

    int page() {
        return words.get(0).page();
    }

    double top() {
        return words.get(0).top();
    }
}
