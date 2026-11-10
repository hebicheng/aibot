package com.aibot.purchase.service.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class MarketNameNormalizerTest {

  @Test
  void parsesStandardMaterial() {
    MarketNameNormalizer.Parsed parsed = MarketNameNormalizer.parse("三级抗震螺纹钢 HRB400E Φ25", "");
    assertEquals("144", parsed.productCode());
    assertEquals("25", parsed.spec());
    assertEquals("HRB400E", parsed.grade());
  }

  @Test
  void parsesReversedOrder() {
    // 行情侧：螺纹钢 Φ25 HRB400E（规格在前材质在后）
    MarketNameNormalizer.Parsed parsed = MarketNameNormalizer.parse("螺纹钢", "Φ25 HRB400E");
    assertEquals("144", parsed.productCode());
    assertEquals("25", parsed.spec());
    assertEquals("HRB400E", parsed.grade());
  }

  @Test
  void parsesFullSymbol() {
    MarketNameNormalizer.Parsed parsed = MarketNameNormalizer.parse("盘螺 Φ10 HRB400E", "");
    assertEquals("143", parsed.productCode());
    assertEquals("10", parsed.spec());
    assertEquals("HRB400E", parsed.grade());
  }

  @Test
  void parsesPipeWithLength() {
    MarketNameNormalizer.Parsed parsed = MarketNameNormalizer.parse("螺纹钢", "Φ12×9 HRB400E");
    assertEquals("144", parsed.productCode());
    assertEquals("12×9", parsed.spec());
    assertEquals("HRB400E", parsed.grade());
  }

  @Test
  void parsesHBeam() {
    MarketNameNormalizer.Parsed parsed = MarketNameNormalizer.parse("H型钢", "100*100*6*8 Q235B");
    assertEquals("183", parsed.productCode());
    assertEquals("100*100*6*8", parsed.spec());
    assertEquals("Q235B", parsed.grade());
  }

  @Test
  void unknownProductReturnsNullCode() {
    MarketNameNormalizer.Parsed parsed = MarketNameNormalizer.parse("某特殊材料 X100", "");
    assertNull(parsed.productCode());
  }

  @Test
  void parsesRangeSpec() {
    // gtgqw 常见范围规格：Φ28-32 应整体解析，不再残留 "-32"
    MarketNameNormalizer.Parsed parsed = MarketNameNormalizer.parse("螺纹钢", "Φ28-32 HRB400E");
    assertEquals("144", parsed.productCode());
    assertEquals("28-32", parsed.spec());
    assertEquals("HRB400E", parsed.grade());
  }

  @Test
  void parsesCommaRangeSpec() {
    MarketNameNormalizer.Parsed parsed = MarketNameNormalizer.parse("螺纹钢", "Φ12,14 HRB400E");
    assertEquals("144", parsed.productCode());
    assertEquals("12,14", parsed.spec());
  }
}
