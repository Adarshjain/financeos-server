package com.financeos.statement.parser;

import java.util.List;

record CollectedRows(List<Line> metaZone, List<RawRow> rows, Line headerWords,
                     String dateFmt, String dateFmt2, int maxTxnPage) {
}
