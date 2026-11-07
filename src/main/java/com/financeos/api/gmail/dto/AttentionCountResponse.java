package com.financeos.api.gmail.dto;

import java.util.UUID;

/** {@code id} is the ignore rule (ignore) or null (restore); {@code count} is the mails archived / unarchived by the call. */
public record AttentionCountResponse(UUID id, int count) {}
