package com.financeos.api.reward.dto;

import com.financeos.domain.account.Account;
import com.financeos.domain.reward.RewardType;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RewardAccountConfigResponse(
        UUID accountId,
        @Nullable LocalDate rewardAnniversaryDate,
        RewardType defaultRewardType,
        @Nullable BigDecimal pointValueInr) {

    public static RewardAccountConfigResponse from(Account account) {
        return new RewardAccountConfigResponse(
                account.getId(),
                account.getRewardAnniversaryDate(),
                account.getDefaultRewardType(),
                account.getPointValueInr());
    }
}

