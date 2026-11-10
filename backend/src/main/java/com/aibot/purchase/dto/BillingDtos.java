package com.aibot.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class BillingDtos {

  private BillingDtos() {
  }

  public record CreditOverview(
      BigDecimal balance,
      BigDecimal monthlyQuota,
      BigDecimal totalUsed,
      String periodMonth,
      LocalDateTime periodStart,
      LocalDateTime periodEnd,
      BigDecimal usedThisMonth
  ) {
  }

  public record UsageLogItem(
      String id,
      String businessType,
      String model,
      long promptTokens,
      long completionTokens,
      long totalTokens,
      BigDecimal costCredits,
      LocalDateTime createdAt
  ) {
  }

  public record UsageLogPage(
      List<UsageLogItem> items,
      long total
  ) {
  }
}
