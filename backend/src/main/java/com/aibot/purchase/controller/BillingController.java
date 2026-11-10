package com.aibot.purchase.controller;

import com.aibot.purchase.common.ApiResponse;
import com.aibot.purchase.common.CurrentUser;
import com.aibot.purchase.dto.BillingDtos.CreditOverview;
import com.aibot.purchase.dto.BillingDtos.UsageLogItem;
import com.aibot.purchase.dto.BillingDtos.UsageLogPage;
import com.aibot.purchase.entity.AiUsageLog;
import com.aibot.purchase.service.BillingService;
import com.aibot.purchase.service.BillingService.CreditSnapshot;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 积分 / 消费明细查询。
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {
  private final BillingService billingService;

  public BillingController(BillingService billingService) {
    this.billingService = billingService;
  }

  @GetMapping("/credits")
  public ApiResponse<CreditOverview> credits() {
    String userId = CurrentUser.get().id();
    CreditSnapshot snapshot = billingService.snapshot(userId);
    return ApiResponse.ok(new CreditOverview(
        snapshot.getBalance(),
        snapshot.getMonthlyQuota(),
        snapshot.getTotalUsed(),
        snapshot.getPeriodMonth(),
        snapshot.getPeriodStart(),
        snapshot.getPeriodEnd(),
        snapshot.getUsedThisMonth()
    ));
  }

  @GetMapping("/usage-logs")
  public ApiResponse<UsageLogPage> usageLogs(
      @RequestParam(defaultValue = "0") long page,
      @RequestParam(defaultValue = "20") long size
  ) {
    String userId = CurrentUser.get().id();
    long offset = page * size;
    List<AiUsageLog> logs = billingService.usageLogs(userId, size, offset);
    List<UsageLogItem> items = logs.stream().map(log -> new UsageLogItem(
        log.getId(),
        log.getBusinessType(),
        log.getModel(),
        log.getPromptTokens() == null ? 0L : log.getPromptTokens(),
        log.getCompletionTokens() == null ? 0L : log.getCompletionTokens(),
        log.getTotalTokens() == null ? 0L : log.getTotalTokens(),
        nullToZero(log.getCostCredits()),
        log.getCreatedAt()
    )).toList();
    long total = billingService.countUsageLogs(userId);
    return ApiResponse.ok(new UsageLogPage(items, total));
  }

  private static java.math.BigDecimal nullToZero(java.lang.Number value) {
    return value == null ? java.math.BigDecimal.ZERO : java.math.BigDecimal.valueOf(value.doubleValue());
  }
}
