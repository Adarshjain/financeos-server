package com.financeos.api.gmail.dto;

import com.financeos.gmail.domain.AttentionReason;

public record AttentionReasonCount(AttentionReason reason, long count) {}
