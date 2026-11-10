package com.aibot.purchase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * 运输费用规则化提取：模型漏抽时的兜底，必须认得常见写法且不能误抓。
 */
class ChatAiServiceFreightTest {

  @Test
  void extractsFreightFromCommonFormats() {
    assertFee("150000", "运输费用150000");
    assertFee("150000", "运输费用：150000");
    assertFee("1200", "运费：1,200元");
    assertFee("1200", "运费 1200 元");
    assertFee("32000", "运费合计 3.2万");
    assertFee("20000", "配送费2万元");
    assertFee("800", "物流费=800");
  }

  @Test
  void extractsFreightFromRealDeliveryNote() {
    String text = """
        送货日期：2026-07-03
        到货明细：
        1. 三级抗震螺纹钢，规格 HRB400E Φ25，到货 12 件，合计重量 12.000 吨。
        2. 三级抗震盘螺，规格 HRB400E Φ10，到货 6 件，合计重量 7.500 吨。
        3. 槽钢，规格 Q235B 10#，到货 4 件，合计重量 4.000 吨。
        本次到货总重量：23.500 吨。
        运输费用150000
        """;
    assertEquals(0, bd("150000").compareTo(ChatAiService.extractTransportFee(text)),
        "应识别出文末的运输费用");
  }

  /** 金额只比较数值，不比较 BigDecimal 标度（3.2万 会算出 32000.0）。 */
  private static void assertFee(String expected, String sourceText) {
    BigDecimal actual = ChatAiService.extractTransportFee(sourceText);
    String actualText = actual == null ? "null" : actual.toPlainString();
    assertEquals(0, bd(expected).compareTo(actual),
        "原文「" + sourceText + "」期望运费 " + expected + "，实际 " + actualText);
  }

  @Test
  void ignoresNonFreightAmounts() {
    // 没有运费字样，不能把重量或货物金额当运费
    assertNull(ChatAiService.extractTransportFee("本次到货总重量：23.500 吨"));
    assertNull(ChatAiService.extractTransportFee("合计金额 125800 元"));
    // 关键词与数字之间隔着汉字，属于描述性文字，不能抓
    assertNull(ChatAiService.extractTransportFee("运费由甲方承担，材料款 150000 元"));
    // 没有金额的运费描述
    assertNull(ChatAiService.extractTransportFee("运费另议"));
    assertNull(ChatAiService.extractTransportFee(""));
    assertNull(ChatAiService.extractTransportFee(null));
  }

  @Test
  void returnsNullWhenAmountsConflict() {
    // 出现两个不同的运费金额时宁可交给用户确认，也不猜
    assertNull(ChatAiService.extractTransportFee("运费 1000 元，另计运输费用 2000 元"));
  }

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }
}
