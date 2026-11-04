package com.financeos.api.gmail.dto;

import java.util.List;

/** The sender domains the app adds to Gmail senders for one catalog bank. */
public record BankDomainsResponse(String bankId, List<String> domains) {}
