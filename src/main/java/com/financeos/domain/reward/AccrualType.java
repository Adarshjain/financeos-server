package com.financeos.domain.reward;

/** PERCENT: cashback = basis x rate%. SLAB: points = floor(basis / slabSize) x pointsPerSlab. */
public enum AccrualType {
    PERCENT,
    SLAB
}
