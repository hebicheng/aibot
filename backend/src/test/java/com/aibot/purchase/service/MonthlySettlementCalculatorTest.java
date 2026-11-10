package com.aibot.purchase.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 月结加价款规则单测（2026-08-01 与业务确认）：
 * - 起始日 T0=要求回款日期；7 天宽限期；第 8 天（T0+7）起计息；
 * - 计息天数 = 到账日 - T0 - 7（<=0 不计息）；日息 = 月息 / 30；
 * - 区间（对日）：<=1 月 0.5%，<=2 月 0.6%，>2 月 0.7%（整笔单一利率）；
 * - 未回款本金按当前日期区间滚动计息；已付不再加价；
 * - 风控：>3 个月 或 未回款 > 300 万 → 标记。
 */
class MonthlySettlementCalculatorTest {

  @Test
  void withinGracePeriod_noInterest() {
    // T0=2026-08-01，到账 2026-08-08（T0+7，第8天）：计息 0 天
    var result = MonthlySettlementCalculator.calculate(
        LocalDate.of(2026, 8, 1),
        new BigDecimal("10000"),
        new BigDecimal("10000"),
        List.of(new MonthlySettlementCalculator.ReceiptEntry(LocalDate.of(2026, 8, 8), new BigDecimal("10000"))),
        LocalDate.of(2026, 8, 8)
    );
    assertThat(result.totalInterest()).isEqualByComparingTo("0.00");
    assertThat(result.overdueFlag()).isFalse();
    assertThat(result.amountCapFlag()).isFalse();
  }

  @Test
  void oneMonthRate_050pct() {
    // T0=2026-08-01，到账 2026-08-20（19 天后，减去 7 宽限 = 12 天），1 个月内 → 0.5%
    // 利息 = 10000 * 0.005/30 * 12 = 20.00
    var result = MonthlySettlementCalculator.calculate(
        LocalDate.of(2026, 8, 1),
        new BigDecimal("10000"),
        new BigDecimal("10000"),
        List.of(new MonthlySettlementCalculator.ReceiptEntry(LocalDate.of(2026, 8, 20), new BigDecimal("10000"))),
        LocalDate.of(2026, 8, 20)
    );
    assertThat(result.totalInterest()).isEqualByComparingTo("20.00");
    assertThat(result.details()).hasSize(1);
  }

  @Test
  void twoMonthRate_060pct() {
    // T0=2026-08-01，到账 2026-09-20（50 天 - 7 = 43 天），>1月 <=2月 → 0.6%
    // 利息 = 10000 * 0.006/30 * 43 = 86.00
    var result = MonthlySettlementCalculator.calculate(
        LocalDate.of(2026, 8, 1),
        new BigDecimal("10000"),
        new BigDecimal("10000"),
        List.of(new MonthlySettlementCalculator.ReceiptEntry(LocalDate.of(2026, 9, 20), new BigDecimal("10000"))),
        LocalDate.of(2026, 9, 20)
    );
    assertThat(result.totalInterest()).isEqualByComparingTo("86.00");
  }

  @Test
  void overTwoMonthsRate_070pct() {
    // T0=2026-08-01，到账 2026-10-20（80 天 - 7 = 73 天），>2 个月 → 0.7%
    // 利息 = 10000 * 0.007/30 * 73 = 170.33
    var result = MonthlySettlementCalculator.calculate(
        LocalDate.of(2026, 8, 1),
        new BigDecimal("10000"),
        new BigDecimal("10000"),
        List.of(new MonthlySettlementCalculator.ReceiptEntry(LocalDate.of(2026, 10, 20), new BigDecimal("10000"))),
        LocalDate.of(2026, 10, 20)
    );
    assertThat(result.totalInterest()).isEqualByComparingTo("170.33");
  }

  @Test
  void partialReceipts_principalFirst() {
    // T0=2026-08-01，销售 20000：
    // 笔1：08-20 到账 10000（12 天，0.5%）→ 10000*0.005/30*12 = 20.00
    // 笔2：09-20 到账 5000（43 天，0.6%）→ 5000*0.006/30*43 = 43.00
    // 未回款本金 5000，asOf=10-05（65 天 - 7 = 58 天，>2月 0.7%）→ 5000*0.007/30*58 = 67.67
    // 合计 = 20.00 + 43.00 + 67.67 = 130.67
    var result = MonthlySettlementCalculator.calculate(
        LocalDate.of(2026, 8, 1),
        new BigDecimal("20000"),
        new BigDecimal("15000"),
        List.of(
            new MonthlySettlementCalculator.ReceiptEntry(LocalDate.of(2026, 8, 20), new BigDecimal("10000")),
            new MonthlySettlementCalculator.ReceiptEntry(LocalDate.of(2026, 9, 20), new BigDecimal("5000"))
        ),
        LocalDate.of(2026, 10, 5)
    );
    assertThat(result.totalInterest()).isEqualByComparingTo("130.67");
    assertThat(result.details()).hasSize(3); // 两笔回款 + 一笔未回款
  }

  @Test
  void overdueFlag_moreThanThreeMonths() {
    var result = MonthlySettlementCalculator.calculate(
        LocalDate.of(2026, 1, 1),
        new BigDecimal("10000"),
        BigDecimal.ZERO,
        List.of(),
        LocalDate.of(2026, 5, 1) // 4 个月后仍有未回款
    );
    assertThat(result.overdueFlag()).isTrue();
  }

  @Test
  void amountCapFlag_overThreeMillion() {
    var result = MonthlySettlementCalculator.calculate(
        LocalDate.of(2026, 8, 1),
        new BigDecimal("3500000"),
        BigDecimal.ZERO,
        List.of(),
        LocalDate.of(2026, 8, 20)
    );
    assertThat(result.amountCapFlag()).isTrue();
  }

  @Test
  void notMonthlySettlement_shouldNotBeCalled() {
    // 非月结批次由调用方跳过（LedgerService 中 isMonthlySettlement=false 时字段为 null）
    assertThat(MonthlySettlementCalculator.RATE_1M).isEqualByComparingTo("0.005");
    assertThat(MonthlySettlementCalculator.MAX_OUTSTANDING).isEqualByComparingTo("3000000");
  }
}
