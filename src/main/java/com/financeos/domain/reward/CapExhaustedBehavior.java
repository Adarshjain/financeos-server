package com.financeos.domain.reward;

/** What an EXCLUSIVE rule does when its period cap is exhausted mid-window. */
public enum CapExhaustedBehavior {
    FALL_THROUGH,
    STOP
}
