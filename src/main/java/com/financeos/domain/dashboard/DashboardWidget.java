package com.financeos.domain.dashboard;

import java.util.UUID;

/**
 * One widget on a dashboard: a reference to a saved report plus its grid placement.
 * Stored as part of the dashboard's {@code widgets} JSON array.
 */
public record DashboardWidget(String id, UUID reportId, String title, WidgetLayout layout) {
}
