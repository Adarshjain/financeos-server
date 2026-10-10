package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.AssetClass;
import org.springframework.lang.Nullable;

/**
 * Body of {@code PATCH /instruments/{id}}: the asset class the current user pins for this instrument
 * (their own override — other users keep the global class), or null to remove that override and use
 * the AMFI / name-rule class again.
 */
public record UpdateInstrumentAssetClassRequest(@Nullable AssetClass assetClass) {
}
