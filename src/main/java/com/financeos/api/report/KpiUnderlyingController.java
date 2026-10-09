package com.financeos.api.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.api.dashboard.dto.BuiltinDataRequest;
import com.financeos.api.report.dto.RunReportRequest;
import com.financeos.domain.dashboard.BuiltinWidgetRegistry;
import com.financeos.domain.report.underlying.KpiUnderlyingResponse;
import com.financeos.domain.report.underlying.KpiUnderlyingService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A KPI's underlying data ("View underlying data") for saved reports, ad-hoc definitions and KPI
 * built-in widgets: one page as JSON, or every row as a CSV download.
 *
 * <p>{@code period} is {@code current} (default) or {@code previous} (400 when the KPI has no
 * previous period); {@code sort=<column>,<asc|desc>} orders by a listed column. A non-KPI
 * definition is a 400. The CSV endpoints take no {@code produces} on purpose: their errors are
 * ordinary JSON error bodies, written before any CSV is.
 */
@RestController
public class KpiUnderlyingController {

    static final String CSV_CONTENT_TYPE = "text/csv; charset=UTF-8";
    static final String CSV_DISPOSITION = "attachment; filename=\"underlying.csv\"";

    private final KpiUnderlyingService underlying;
    private final BuiltinWidgetRegistry builtins;

    public KpiUnderlyingController(KpiUnderlyingService underlying, BuiltinWidgetRegistry builtins) {
        this.underlying = underlying;
        this.builtins = builtins;
    }

    /** One page of a saved KPI report's underlying rows (ownership as for running the report). */
    @PostMapping("/api/v1/reports/{id}/underlying")
    public ResponseEntity<KpiUnderlyingResponse> savedUnderlying(
            @PathVariable UUID id,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ResponseEntity.ok(underlying.saved(id, period, page, size, sort));
    }

    /** One page of an ad-hoc KPI definition's underlying rows. */
    @PostMapping("/api/v1/reports/underlying")
    public ResponseEntity<KpiUnderlyingResponse> adHocUnderlying(
            @Valid @RequestBody RunReportRequest request,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ResponseEntity.ok(underlying.adHoc(request.type(), request.datasource(), request.definition(),
                period, page, size, sort));
    }

    /** One page of a KPI built-in widget's underlying rows, with the widget's params applied. */
    @PostMapping("/api/v1/dashboards/builtins/{key}/underlying")
    public ResponseEntity<KpiUnderlyingResponse> builtinUnderlying(
            @PathVariable String key,
            @RequestBody(required = false) BuiltinDataRequest request,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        BuiltinWidgetRegistry.Entry entry = builtins.require(key);
        JsonNode definition = builtins.resolveDefinition(entry, request == null ? null : request.params());
        return ResponseEntity.ok(underlying.adHoc(entry.templateType(), entry.datasource(), definition,
                period, page, size, sort));
    }

    /** Every underlying row of a saved KPI report as CSV. */
    @PostMapping("/api/v1/reports/{id}/underlying/csv")
    @ApiResponse(responseCode = "200", description = "The underlying rows as CSV",
            content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")))
    public void savedUnderlyingCsv(
            @PathVariable UUID id,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String sort,
            HttpServletResponse response) {
        underlying.savedCsv(id, period, sort, csvAttachment(response));
    }

    /** Every underlying row of an ad-hoc KPI definition as CSV. */
    @PostMapping("/api/v1/reports/underlying/csv")
    @ApiResponse(responseCode = "200", description = "The underlying rows as CSV",
            content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")))
    public void adHocUnderlyingCsv(
            @Valid @RequestBody RunReportRequest request,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String sort,
            HttpServletResponse response) {
        underlying.adHocCsv(request.type(), request.datasource(), request.definition(), period, sort,
                csvAttachment(response));
    }

    /** Every underlying row of a KPI built-in widget as CSV. */
    @PostMapping("/api/v1/dashboards/builtins/{key}/underlying/csv")
    @ApiResponse(responseCode = "200", description = "The underlying rows as CSV",
            content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")))
    public void builtinUnderlyingCsv(
            @PathVariable String key,
            @RequestBody(required = false) BuiltinDataRequest request,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String sort,
            HttpServletResponse response) {
        BuiltinWidgetRegistry.Entry entry = builtins.require(key);
        JsonNode definition = builtins.resolveDefinition(entry, request == null ? null : request.params());
        underlying.adHocCsv(entry.templateType(), entry.datasource(), definition, period, sort,
                csvAttachment(response));
    }

    /** The response as a CSV attachment; headers are set only when the export starts writing. */
    private static KpiUnderlyingService.CsvTarget csvAttachment(HttpServletResponse response) {
        return () -> {
            response.setContentType(CSV_CONTENT_TYPE);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, CSV_DISPOSITION);
            return response.getOutputStream();
        };
    }
}
