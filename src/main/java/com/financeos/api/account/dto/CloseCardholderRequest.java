package com.financeos.api.account.dto;

import java.time.LocalDate;

public record CloseCardholderRequest(
        LocalDate closedOn
) {}
