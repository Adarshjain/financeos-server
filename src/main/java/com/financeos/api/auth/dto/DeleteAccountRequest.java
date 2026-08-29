package com.financeos.api.auth.dto;

public record DeleteAccountRequest(
        String password,
        String confirmEmail) {
}
