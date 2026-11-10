package com.financeos.domain.statement.file;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/** {@code app.statement-files.*}: whether original statement files are kept, and the size limits. */
@Component
@ConfigurationProperties(prefix = "app.statement-files")
@Getter
@Setter
public class StatementFileProperties {

    private boolean enabled = true;
    private DataSize maxFileBytes = DataSize.ofMegabytes(25);
    private DataSize maxBytesPerUser = DataSize.ofGigabytes(2);
    private DataSize maxTotalBytes = DataSize.ofGigabytes(8);
}
