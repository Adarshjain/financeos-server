package com.financeos.statement.parser;

import java.util.List;

// droppedRows: dated rows discarded for want of a parseable date or any amount
record TxnBuild(List<TxnDraft> txns, Double openingFromMarker, int droppedRows) {
}
