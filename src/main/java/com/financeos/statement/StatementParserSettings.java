package com.financeos.statement;

import com.financeos.statement.parser.ParserLimits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Applies the configured statement-parser limits to the static parser at startup. */
@Component
public class StatementParserSettings {

    public StatementParserSettings(
            @Value("${app.statement.max-pdf-pages:" + ParserLimits.DEFAULT_MAX_PDF_PAGES + "}") int maxPdfPages) {
        ParserLimits.setMaxPdfPages(maxPdfPages);
    }
}
