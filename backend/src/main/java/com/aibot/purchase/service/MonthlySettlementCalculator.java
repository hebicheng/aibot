package com.aibot.purchase.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 月结加价款计算（前后端同一规则，Web/Mini 各有一份等价实现）：
 *
 * 规则（2026-08-01 与业务确认）：
 * - 起始日 T0 = 要求回款日期（到货日）；
 * - 宽限期 7 个日历天（T0 起 7 天内不加价），第 8 天（T0+7）起计息；
 * - 每笔回款截止日 = 到账日前一天，计息天数 = 到账日 - T0 - 7（<=0 则不计息）；
 * - 区间按实际日历月（对日）：&lt;=1 个月 0.5%、&lt;=2 个月 0.6%、&gt;2 个月 0.7%（月息，日息 = 月息 / 30）；
 * - 整笔回款按「到账日所在区间」取单一利率；
 * - 未回款部分（未回款本金，不含利息）按「当前日期所在区间」滚动计息；
 * - 已付货款不再加价（每笔到账即锁定，天然满足）；
 * - 风控红线（单批次）：欠款时间 &gt; 3 个月（对日）或欠款金额（未回款本金）&gt; 300 万 → 标记提醒；
 * - 利息是时点值（随当前日期变化），不落库，每次现算。
 */
public final class MonthlySettlementCalculator {

  public static final BigDecimal RATE_1M = new BigDecimal("0.005");
  public static final BigDecimal RATE_2M = new BigDecimal("0.006");
  public static final BigDecimal RATE_OVER = new BigDecimal("0.007");
  public static final BigDecimal GRACE_DAYS = BigDecimal.valueOf(7);
  public static final BigDecimal DAYS_PER_MONTH = BigDecimal.valueOf(30);
  public static final BigDecimal MAX_OVERDUE_MONTHS = BigDecimal.valueOf(3);
  public static final BigDecimal MAX_OUTSTANDING = new BigDecimal("3000000");

  private MonthlySettlementCalculator() {
  }

  public record ReceiptEntry(LocalDate date, BigDecimal amount) {
  }

  public record InterestResult(
      BigDecimal totalInterest,
      List<Map<String, Object>> details,
      boolean overdueFlag,
      boolean amountCapFlag
  ) {
  }

  /**
   * 计算月结加价利息。
   *
   * @param requiredReceiptDate 要求回款日期（T0，为空则不计息）
   * @param salesAmount         销售金额
   * @param receivedAmount      已回款本金合计（用于推导未回款本金）
   * @param receipts            逐笔回款（按到账日升序）
   * @param asOfDate            计算基准日（通常为今天；未回款部分滚动计息用）
   */
  public static InterestResult calculate(
      LocalDate requiredReceiptDate,
      BigDecimal salesAmount,
      BigDecimal receivedAmount,
      List<ReceiptEntry> receipts,
      LocalDate asOfDate
  ) {
    if (requiredReceiptDate == null || asOfDate == null || salesAmount == null) {
      return new InterestResult(BigDecimal.ZERO, List.of(), false, false);
    }
    LocalDate start = requiredReceiptDate;
    List<Map<String, Object>> details = new ArrayList<>();
    BigDecimal totalInterest = BigDecimal.ZERO;

    // 1. 每笔已回款：按到账日所在区间取单一利率，利息锁定
    if (receipts != null) {
      for (ReceiptEntry receipt : receipts) {
        if (receipt.date() == null || receipt.amount() == null
            || receipt.amount().compareTo(BigDecimal.ZERO) <= 0) {
          continue;
        }
        if (!receipt.date().isAfter(start)) {
          continue; // 到账日不晚于起始日，不计息
        }
        long days = ChronoUnit.DAYS.between(start, receipt.date()) - GRACE_DAYS.longValue();
        if (days <= 0) {
          continue;
        }
        BigDecimal rate = rateFor(receipt.date(), start);
        BigDecimal interest = dailyInterest(receipt.amount(), rate, days);
        totalInterest = totalInterest.add(interest);
        details.add(entry("type", "receipt", "date", receipt.date(), "amount", receipt.amount(),
            "days", days, "rate", rate, "interest", interest,
            "note", "到账日所在区间月息 " + percent(rate) + "，计息 " + days + " 天"));
      }
    }

    // 2. 未回款本金：按当前日期所在区间滚动计息（货款先付原则）
    BigDecimal unpaid = salesAmount.subtract(nz(receivedAmount)).max(BigDecimal.ZERO);
    if (unpaid.compareTo(BigDecimal.ZERO) > 0 && asOfDate.isAfter(start)) {
      long days = ChronoUnit.DAYS.between(start, asOfDate) - GRACE_DAYS.longValue();
      if (days > 0) {
        BigDecimal rate = rateFor(asOfDate, start);
        BigDecimal interest = dailyInterest(unpaid, rate, days);
        totalInterest = totalInterest.add(interest);
        details.add(entry("type", "unpaid", "date", asOfDate, "amount", unpaid,
            "days", days, "rate", rate, "interest", interest,
            "note", "未回款本金按当前区间月息 " + percent(rate) + " 滚动计息 " + days + " 天"));
      }
    }

    // 3. 风控红线（单批次）：欠款时间 > 3 个月（对日）或未回款本金 > 300 万
    boolean overdueFlag = unpaid.compareTo(BigDecimal.ZERO) > 0
        && asOfDate.isAfter(start.plusMonths(MAX_OVERDUE_MONTHS.longValue()));
    boolean amountCapFlag = unpaid.compareTo(MAX_OUTSTANDING) > 0;

    return new InterestResult(totalInterest, details, overdueFlag, amountCapFlag);
  }

  /** 区间月息：<=1 个月 0.5%，<=2 个月 0.6%，>2 个月 0.7%（对日） */
  private static BigDecimal rateFor(LocalDate date, LocalDate start) {
    if (date.isBefore(start.plusMonths(1))) {
      return RATE_1M;
    }
    if (date.isBefore(start.plusMonths(2))) {
      return RATE_2M;
    }
    return RATE_OVER;
  }

  /** 日息 = 月息 / 30；利息 = 本金 × 日息 × 天数 */
  private static BigDecimal dailyInterest(BigDecimal principal, BigDecimal monthlyRate, long days) {
    return principal
        .multiply(monthlyRate)
        .divide(DAYS_PER_MONTH, 10, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(days))
        .setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String percent(BigDecimal rate) {
    return rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
  }

  private static Map<String, Object> entry(Object... kv) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      map.put(String.valueOf(kv[i]), kv[i + 1]);
    }
    return map;
  }
}
