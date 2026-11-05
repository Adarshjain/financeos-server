package com.financeos.api.nps.dto;

import com.financeos.domain.nps.NpsAccountStatus;
import com.financeos.domain.nps.NpsCra;
import com.financeos.domain.nps.NpsRelation;
import com.financeos.domain.nps.NpsSector;
import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * Create or update an NPS account. {@code pran} is the 12-digit number (spaces allowed); on update, null keeps
 * the stored one. Omitted optional fields take their defaults on create and stay unchanged on update.
 */
public record NpsAccountRequest(
        @Nullable String pran,
        @Nullable String holderName,
        @Nullable NpsRelation relation,
        @Nullable NpsSector sector,
        @Nullable NpsCra cra,
        @Nullable UUID employerId,
        @Nullable Boolean tier2Active,
        @Nullable String schemeChoice,
        @Nullable NpsAccountStatus status,
        @Nullable Boolean readGmail,
        @Nullable String statementPassword) {
}
