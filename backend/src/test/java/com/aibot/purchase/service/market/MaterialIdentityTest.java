package com.aibot.purchase.service.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 材料身份归一化的单元测试（2026-10-30）。
 *
 * <p>这层是材料统一的根：采购单、送货单、提货单对同一种材料的三种写法必须收敛到同一个键，
 * 否则后面网价、采购价、毛利全部对不上。它又是纯函数、不碰库，最适合用测试把行为钉住——
 * 正则改一个字就可能让"HRB400 的 400 被当成直径"，线上表现为材料被拆成两种。
 */
class MaterialIdentityTest {

  @Test
  @DisplayName("三种单据写法收敛到同一个粗键（品类|直径|定尺）")
  void threeWriteupsShareBaseKey() {
    // 找钢网采购单：三级螺纹钢 12*9 中天
    MaterialIdentity.Identity purchase =
        MaterialIdentity.parse("三级螺纹钢", "12*9", "中天");
    // 送货单：螺纹钢HRB400 12*9 中天
    MaterialIdentity.Identity delivery =
        MaterialIdentity.parse("螺纹钢HRB400", "12*9", "中天");
    // 提货单：12螺纹钢 9m（不写牌号、不写产地）
    MaterialIdentity.Identity pickup =
        MaterialIdentity.parse("12螺纹钢 9m", null, null);

    assertEquals("螺纹钢|12|9", purchase.baseKey());
    assertEquals("螺纹钢|12|9", delivery.baseKey());
    assertEquals("螺纹钢|12|9", pickup.baseKey());
    // 采购单只写"三级"，不推断成 HRB400；这是"不猜"原则
    assertNull(purchase.grade());
    assertEquals("HRB400", delivery.grade());
    assertEquals("中天", delivery.origin());
  }

  @Test
  @DisplayName("组内回填牌号/产地后，三种写法归到同一细键")
  void backfillConvergesToSameIdentity() {
    MaterialIdentity.Identity purchase =
        MaterialIdentity.parse("三级螺纹钢", "12*9", "中天");
    MaterialIdentity.Identity delivery =
        MaterialIdentity.parse("螺纹钢HRB400", "12*9", "中天");

    // rebuild 的组内回填：粗键相同 → 牌号/产地取组内唯一非空值
    MaterialIdentity.Identity resolved = MaterialIdentity.withResolved(purchase, "HRB400", "中天");
    assertEquals(delivery.identityKey(), resolved.identityKey());
    // identityKey = baseKey(品类|直径|定尺) + |牌号 + |产地
    assertEquals("螺纹钢|12|9|HRB400|中天", resolved.identityKey());
  }

  @Test
  @DisplayName("牌号里的数字不会被当成直径（HRB400 的 400）")
  void gradeNumberIsNotDiameter() {
    MaterialIdentity.Identity identity = MaterialIdentity.parse("HRB400", "Φ12", null);
    assertEquals("HRB400", identity.grade());
    assertEquals("12", identity.diameter());
    assertNull(identity.lengthM());
  }

  @Test
  @DisplayName("盘螺/高线成盘供货：即使单据写了 9m 也不认定尺")
  void coiledSteelHasNoLength() {
    MaterialIdentity.Identity parsed = MaterialIdentity.parse("盘螺", "8*9", null);
    assertEquals("盘螺", parsed.category());
    assertNull(parsed.lengthM());

    // 即使绕过 parse 直接给了定尺，withResolved 也会清掉（否则同一材料会被拆成两行）
    MaterialIdentity.Identity forced =
        MaterialIdentity.withResolved("盘螺", null, "8", "9", null);
    assertNull(forced.lengthM());
    assertEquals("盘螺|8|", forced.baseKey());
  }

  @Test
  @DisplayName("写法键：符号与大小写归一，不同写法不能撞键")
  void rawKeyNormalizesSymbols() {
    assertEquals(
        MaterialIdentity.rawKey("螺纹钢hrb400", "12×9", "中天"),
        MaterialIdentity.rawKey("螺纹钢HRB400", "12*9", "中天"));
    // 产地写法不同就是两种写法（AI 判同要判的是这种情况，规则不能替它合并）
    assertFalse(MaterialIdentity.rawKey("螺纹钢HRB400", "12*9", "中天")
        .equals(MaterialIdentity.rawKey("螺纹钢HRB400", "12*9", "中天钢铁")));
  }

  @Test
  @DisplayName("直径/定尺只收纯数字，带符号的写法按\"没写\"处理")
  void numericFieldsRejectNoise() {
    assertEquals("12", MaterialIdentity.numericOrNull("12.00"));
    assertEquals("6.5", MaterialIdentity.numericOrNull("6.5"));
    assertNull(MaterialIdentity.numericOrNull("Φ12"));
    assertNull(MaterialIdentity.numericOrNull("12mm"));
    assertNull(MaterialIdentity.numericOrNull(" "));
  }

  @Test
  @DisplayName("牌号只认规范写法，\"三级钢\"这类描述不采信")
  void gradeMustLookLikeGrade() {
    assertTrue(MaterialIdentity.looksLikeGrade("HRB400E"));
    assertTrue(MaterialIdentity.looksLikeGrade("hpb300"));
    assertFalse(MaterialIdentity.looksLikeGrade("三级钢"));
    assertFalse(MaterialIdentity.looksLikeGrade(null));
  }

  @Test
  @DisplayName("材料名为空时归到「未标注材料」，不抛异常、不丢行")
  void blankMaterialNameFallsBack() {
    MaterialIdentity.Identity identity = MaterialIdentity.parse(null, null, null);
    assertEquals("未标注材料", identity.category());
    assertEquals("未标注材料||", identity.baseKey());
  }
}
