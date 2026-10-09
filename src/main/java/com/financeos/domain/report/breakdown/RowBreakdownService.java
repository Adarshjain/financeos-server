package com.financeos.domain.report.breakdown;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.engine.ReportData;
import com.financeos.domain.report.engine.RuntimeSort;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Row breakdowns for report datasources: routes a datasource's rows to its
 * {@link RowBreakdownProvider} bean and clamps paging. An unknown datasource is a 400 (registry),
 * as is a datasource without a provider; an unknown row or section is the provider's 404.
 */
@Service
public class RowBreakdownService {

    static final int DEFAULT_SIZE = 25;
    static final int MAX_SIZE = 200;

    private final DatasourceRegistry registry;
    private final Map<String, RowBreakdownProvider> providers = new LinkedHashMap<>();

    public RowBreakdownService(DatasourceRegistry registry, List<RowBreakdownProvider> providers) {
        this.registry = registry;
        for (RowBreakdownProvider provider : providers) {
            this.providers.put(provider.datasource(), provider);
        }
    }

    /** Whether rows of the datasource have a breakdown (they open one from the KPI underlying data). */
    public boolean supports(String datasource) {
        return providers.containsKey(datasource);
    }

    /** The row's breakdown, each section holding its first page of {@code size} rows. */
    public RowBreakdownResponse breakdown(String datasource, String rowId, Integer size) {
        return provider(datasource).breakdown(rowId, size(size));
    }

    /** One page of one section of a row's breakdown, in the section's default order. */
    public ReportData section(String datasource, String rowId, String section, Integer page, Integer size) {
        return section(datasource, rowId, section, page, size, null);
    }

    /**
     * One page of one section of a row's breakdown, ordered by {@code sort} ({@code <column>,<asc|desc>},
     * one of the section's columns) over the whole section; null or blank keeps the default order.
     *
     * @throws ValidationException when {@code sort} is malformed (before anything is loaded) or not a column of the section
     */
    public ReportData section(String datasource, String rowId, String section, Integer page, Integer size,
                              @Nullable String sort) {
        SortClause clause = RuntimeSort.parse(sort).orElse(null);
        RowBreakdownProvider provider = provider(datasource);
        int pageNumber = page == null ? 0 : Math.max(0, page);
        return clause == null
                ? provider.section(rowId, section, pageNumber, size(size))
                : provider.section(rowId, section, pageNumber, size(size), clause);
    }

    private RowBreakdownProvider provider(String datasource) {
        ReportDatasource ds = registry.byName(datasource);
        RowBreakdownProvider provider = providers.get(ds.name());
        if (provider == null) {
            throw new ValidationException("No breakdown for " + ds.label());
        }
        return provider;
    }

    private static int size(Integer size) {
        return size == null ? DEFAULT_SIZE : Math.max(1, Math.min(size, MAX_SIZE));
    }
}
