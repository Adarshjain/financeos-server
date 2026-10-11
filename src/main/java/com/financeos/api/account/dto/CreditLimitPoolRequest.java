package com.financeos.api.account.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The shared limit a credit card account draws on, sent with the account.
 * <ul>
 *   <li>{@code id} set: join that pool (it must be the caller's). A non-null {@code name} or
 *       {@code creditLimit} also renames it or changes its limit, for every account in it.</li>
 *   <li>{@code id} null: create a new pool from {@code name} and {@code creditLimit} (both required).</li>
 * </ul>
 * Leaving the whole object out (null) takes the account out of any pool.
 */
public record CreditLimitPoolRequest(
        @Nullable UUID id,
        @Nullable String name,
        @Nullable BigDecimal creditLimit
) {
}
