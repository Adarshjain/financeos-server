package com.financeos.api.nps.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** How many locked NPS statements the typed password opened, and how many are still locked. */
@Schema(name = "NpsGmailPasswordResult")
public record NpsGmailPasswordResponse(int opened, int stillLocked) {
}
