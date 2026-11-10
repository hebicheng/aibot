package com.aibot.purchase.service.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aibot.purchase.entity.MarketQuote;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 材料 ↔ 行情匹配核心规则测试（纯静态逻辑，不依赖数据库）。
 * 覆盖：材质严格优先、范围包含、单数值规格识别。
 */
class MarketMatchLogicTest {

  private static MarketQuote quote(String spec, String grade) {
    MarketQuote q = new MarketQuote();
    q.setSpec(spec);
    q.setGrade(grade);
    return q;
  }

  @Test
  void gradeStrictRequiresExactMatch() {
    assertTrue(MaterialMarketMatchService.gradeStrict("HRB400E", "HRB400E"));
    assertTrue(MaterialMarketMatchService.gradeStrict("HRB400e", "HRB400E"));
    assertFalse(MaterialMarketMatchService.gradeStrict("HRB400E", "HRB400"));
    assertFalse(MaterialMarketMatchService.gradeStrict("HRB400", "HRB400E"));
    assertTrue(MaterialMarketMatchService.gradeStrict("Q235B", "Q235B"));
  }

  @Test
  void firstWithGradePrefersStrictGradeOverApproximate() {
    // HRB400 在前，但业务材质是 HRB400E，应命中后面的 HRB400E 行
    List<MarketQuote> rows = List.of(
        quote("Φ8-10", "HRB400"),
        quote("Φ8-10", "HRB400E"));
    MarketQuote picked = MaterialMarketMatchService.firstWithGrade(
        rows, row -> row.getSpec() != null && row.getSpec().contains("8-10"), "HRB400E");
    assertEquals("HRB400E", picked.getGrade());
  }

  @Test
  void firstWithGradeFallsBackToApproximateWhenNoStrict() {
    List<MarketQuote> rows = List.of(quote("Φ8-10", "HRB400"));
    MarketQuote picked = MaterialMarketMatchService.firstWithGrade(
        rows, row -> row.getSpec() != null && row.getSpec().contains("8-10"), "HRB400E");
    assertEquals("HRB400", picked.getGrade());
  }

  @Test
  void rangeContainmentWorks() {
    assertTrue(MaterialMarketMatchService.inRange(new BigDecimal("8"), "Φ8-10"));
    assertTrue(MaterialMarketMatchService.inRange(new BigDecimal("10"), "Φ8-10"));
    assertTrue(MaterialMarketMatchService.inRange(new BigDecimal("25"), "Φ16-25"));
    assertFalse(MaterialMarketMatchService.inRange(new BigDecimal("11"), "Φ8-10"));
    assertFalse(MaterialMarketMatchService.inRange(new BigDecimal("26"), "Φ16-25"));
  }

  @Test
  void singleSpecValueParsesOnlyPlainNumbers() {
    assertEquals(new BigDecimal("8"),
        MaterialMarketMatchService.singleSpecValue("Φ8"));
    // 乘号/定尺后缀应取主规格（与 specEqual/findExact 行为对齐），否则 12*10 会被误判为非数值退回 AI
    assertEquals(new BigDecimal("12"),
        MaterialMarketMatchService.singleSpecValue("12*10"));
    assertEquals(new BigDecimal("12"),
        MaterialMarketMatchService.singleSpecValue("Φ12×9"));
    assertNull(MaterialMarketMatchService.singleSpecValue("Φ28-32"));
    assertNull(MaterialMarketMatchService.singleSpecValue(null));
  }

  @Test
  void rangeMatchWithMultiplierSpecFallsInRangeNotAi() {
    // 业务规格 12*10（主规格 12）应落入市场范围规格 Φ12-14，走 L2.5 范围匹配而非 AI 兜底
    List<MarketQuote> rows = List.of(quote("Φ12-14", "HRB400E"));
    BigDecimal value = MaterialMarketMatchService.singleSpecValue("12*10");
    MarketQuote picked = MaterialMarketMatchService.firstWithGrade(
        rows,
        row -> MaterialMarketMatchService.isRangeSpec(row.getSpec())
            && MaterialMarketMatchService.inRange(value, row.getSpec()),
        "HRB400E");
    assertEquals("HRB400E", picked.getGrade());
  }

  @Test
  void rangeSpecDetection() {
    assertTrue(MaterialMarketMatchService.isRangeSpec("Φ16-25"));
    assertTrue(MaterialMarketMatchService.isRangeSpec("Φ12,14"));
    assertFalse(MaterialMarketMatchService.isRangeSpec("Φ25"));
    assertFalse(MaterialMarketMatchService.isRangeSpec(null));
  }
}
