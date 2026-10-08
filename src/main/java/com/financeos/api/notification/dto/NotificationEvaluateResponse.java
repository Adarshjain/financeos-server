package com.financeos.api.notification.dto;

/** What one on-demand pass of every producer did for the caller (same work as the hourly tick). */
public record NotificationEvaluateResponse(int evaluated, int recorded, int sent, int failed) {
}
