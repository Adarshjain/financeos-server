package com.financeos.statement.parser;

import java.util.List;
import java.util.NavigableMap;

record CollectedRows(List<Line> metaZone, List<RawRow> rows, Line headerWords,
                     NavigableMap<Integer, Line> pageHeaders,
                     String dateFmt, String dateFmt2, int maxTxnPage) {
}
