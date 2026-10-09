package com.financeos.api.rules.dto;

/** verifiedCount counts only rules that were unverified before the call. */
public record BulkVerifyRulesResponse(int verifiedCount) {}
