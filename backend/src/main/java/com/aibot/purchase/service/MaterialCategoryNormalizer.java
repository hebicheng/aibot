package com.aibot.purchase.service;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 材料大类归一化（仅用于对账台账的显示聚合，不影响识别与原始数据）：
 * 螺纹钢不分型号规格（三级/四级、HRB400/500）统一归「螺纹钢」；
 * 盘螺、高线、圆钢各自成类；其余材料剥离规格与牌号后按原始名兜底。
 */
public final class MaterialCategoryNormalizer {

  /** 单列的材料大类词表。 */
  public static final List<String> CATEGORIES = List.of("螺纹钢", "盘螺", "高线", "圆钢");

  /** 牌号：HRB400E / HRB500 / HPB300 / RRB400 / Q235B 等。 */
  private static final Pattern GRADE = Pattern.compile(
      "(?i)hrb\\d{3}e?|hpb\\d{3}|rrb\\d{3}|q\\d{3}[a-e]?");
  /** 规格：12*9、Φ12、12mm、9m、6.5、1.2米 等。 */
  private static final Pattern SPEC = Pattern.compile(
      "(?i)\\d+(\\.\\d+)?[*x×]\\d+|Φ\\s*\\d+(\\.\\d+)?|\\d+(\\.\\d+)?\\s*mm|\\d+(\\.\\d+)?\\s*[m米]"
          + "|\\d+(\\.\\d+)?");
  /** 级别前缀：三级/四级/二级/一级（含阿拉伯数字写法）。 */
  private static final Pattern LEVEL = Pattern.compile(
      "[一二三四1234]\\s*级|三级钢|四级钢|二级钢");

  private MaterialCategoryNormalizer() {
  }

  /**
   * 材料名称 → 材料大类。识别不出词表关键词时，剥离规格/牌号/级别后返回原始名；
   * 剥离结果为空时返回原名。
   */
  public static String category(String materialName) {
    if (!StringUtils.hasText(materialName)) {
      return "未标注材料";
    }
    String raw = materialName.trim();
    if (raw.contains("盘螺")) {
      return "盘螺";
    }
    if (raw.contains("螺纹")) {
      return "螺纹钢";
    }
    if (raw.contains("高线") || raw.contains("高速线材") || raw.contains("线材")) {
      return "高线";
    }
    if (raw.contains("圆钢")) {
      return "圆钢";
    }
    // 兜底：剥离牌号/规格/级别后按原始名显示
    String cleaned = GRADE.matcher(raw).replaceAll(" ");
    cleaned = LEVEL.matcher(cleaned).replaceAll(" ");
    cleaned = SPEC.matcher(cleaned).replaceAll(" ");
    cleaned = cleaned.replaceAll("[\\s（）()\\-—_/、,，。.]+", "").trim();
    return cleaned.isEmpty() ? raw : cleaned;
  }
}
