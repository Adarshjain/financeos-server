package com.financeos.statement.parser;

import java.util.List;

record TxnBuild(List<TxnDraft> txns, Double openingFromMarker) {
}
