package com.financeos.domain.investment.imports;

import java.io.InputStream;
import java.util.List;

public interface ImportParser {

    ImportSource source();

    List<ParsedRow> parse(InputStream inputStream, ParseContext context);
}
