package com.financeos.api.reward.dto;

import com.financeos.domain.account.Account;
import com.financeos.domain.reward.RewardType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RewardAccountConfigResponse(
        UUID accountId,
        LocalDate rewardAnniversaryDate,
        RewardType defaultRewardType,
        BigDecimal pointValueInr) {

    public static RewardAccountConfigResponse from(Account account) {
        return new RewardAccountConfigResponse(
                account.getId(),
                account.getRewardAnniversaryDate(),
                account.getDefaultRewardType(),
                account.getPointValueInr());
    }
}

