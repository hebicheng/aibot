package com.aibot.purchase.service.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 采购价匹配的规格归一化（纯规则，无 Spring）。
 *
 * <p>这套 key 决定了送货单行能不能对上采购单行：对不上就是「待采购价」空着，
 * 所以范围规格（盘螺一档价）、件数规格（20*9）都必须归到同一个直径桶。
 */
class PurchaseCostResolverTest {

  @Test
  void specKeyOfPlainDiameter() {
    assertThat(PurchaseCostResolver.specKeys("Φ20")).containsExactly("20");
    assertThat(PurchaseCostResolver.specKeys("φ20")).containsExactly("20");
    assertThat(PurchaseCostResolver.specKeys("20mm")).containsExactly("20");
  }

  @Test
  void specKeyOfDiameterTimesLength() {
    // 采购单常写 20*9（直径*定尺），送货单写 Φ20 —— 必须落到同一个桶
    assertThat(PurchaseCostResolver.specKeys("20*9")).containsExactly("20");
    assertThat(PurchaseCostResolver.specKeys("12×9")).containsExactly("12");
  }

  @Test
  void specKeyOfRangeExpands() {
    // 盘螺「Φ6-10」是一档价：展开成 6..10，送货单写 Φ8 才能命中
    List<String> keys = PurchaseCostResolver.specKeys("6-10");
    assertThat(keys).containsExactly("6", "7", "8", "9", "10");
    assertThat(PurchaseCostResolver.specKeys("Φ28-32")).containsExactly("28", "29", "30", "31", "32");
  }

  @Test
  void specKeyOfEnumeration() {
    assertThat(PurchaseCostResolver.specKeys("12,14")).containsExactly("12", "14");
    assertThat(PurchaseCostResolver.specKeys("12，14")).containsExactly("12", "14");
  }

  @Test
  void specKeyOfEmptyOrTextOnly() {
    assertThat(PurchaseCostResolver.specKeys(null)).isEmpty();
    assertThat(PurchaseCostResolver.specKeys("  ")).isEmpty();
    assertThat(PurchaseCostResolver.specKeys("定尺")).isEmpty();
  }

  @Test
  void categoryKeysMatchAcrossNamingVariants() {
    // 「盘元 / 盘螺」两边都能解析出 143，且材料名归一化各自成类——双索引保证能对上
    MarketNameNormalizer.Parsed panYuan =
        MarketNameNormalizer.parse("盘元", "Φ8");
    assertThat(PurchaseCostResolver.categoryKeys(panYuan, "盘元"))
        .contains("P143")
        .contains("盘元");
    MarketNameNormalizer.Parsed panLuo =
        MarketNameNormalizer.parse("三级盘螺", "Φ8");
    assertThat(PurchaseCostResolver.categoryKeys(panLuo, "三级盘螺")).contains("P143");
  }
}
