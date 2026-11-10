package com.aibot.purchase.service.market;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 钢材材料名称归一化（L0 层，纯规则、零成本）。
 *
 * <p>目标：把业务侧与行情侧的不同表述统一成可比形式。
 * 例如「三级抗震螺纹钢 HRB400E Φ25」→ 产品「螺纹钢」、规格「Φ25」、材质「HRB400E」。
 */
public final class MarketNameNormalizer {

  private MarketNameNormalizer() {
  }

  /** 产品名 → 行情产品 code 词典（业务名常见写法 → 站点产品 code）。 */
  private static final Map<String, String> PRODUCT_ALIASES = new LinkedHashMap<>();

  static {
    PRODUCT_ALIASES.put("螺纹钢", "144");
    PRODUCT_ALIASES.put("盘螺", "143");
    PRODUCT_ALIASES.put("盘元", "143");
    PRODUCT_ALIASES.put("高线", "145");
    PRODUCT_ALIASES.put("线材", "145");
    PRODUCT_ALIASES.put("圆钢", "140");
    PRODUCT_ALIASES.put("涂镀", "180");
    PRODUCT_ALIASES.put("镀锌板", "180");
    PRODUCT_ALIASES.put("镀锌卷", "180");
    PRODUCT_ALIASES.put("中厚板", "203");
    PRODUCT_ALIASES.put("中板", "203");
    PRODUCT_ALIASES.put("花纹板", "160");
    PRODUCT_ALIASES.put("冷轧卷", "150");
    PRODUCT_ALIASES.put("冷轧板", "150");
    PRODUCT_ALIASES.put("不锈钢", "245");
    PRODUCT_ALIASES.put("钢坯", "345");
    PRODUCT_ALIASES.put("方坯", "338");
    PRODUCT_ALIASES.put("板坯", "339");
    PRODUCT_ALIASES.put("铁矿石", "372");
    PRODUCT_ALIASES.put("废钢", "296");
    PRODUCT_ALIASES.put("带钢", "210");
    PRODUCT_ALIASES.put("铁合金", "348");
    PRODUCT_ALIASES.put("结构钢", "211");
    PRODUCT_ALIASES.put("工字钢", "186");
    PRODUCT_ALIASES.put("H型钢", "183");
    PRODUCT_ALIASES.put("H钢", "183");
    PRODUCT_ALIASES.put("槽钢", "189");
    PRODUCT_ALIASES.put("角钢", "193");
    PRODUCT_ALIASES.put("焊管", "168");
    PRODUCT_ALIASES.put("焊接钢管", "168");
    PRODUCT_ALIASES.put("镀锌管", "173");
    PRODUCT_ALIASES.put("无缝管", "163");
    PRODUCT_ALIASES.put("螺旋管", "170");
    PRODUCT_ALIASES.put("方矩管", "162");
    PRODUCT_ALIASES.put("方管", "162");
    PRODUCT_ALIASES.put("矩形管", "162");
  }

  /**
   * 解析结果：产品 code（可能为 null）、规格、材质、原始归一化串。
   */
  public record Parsed(String productCode, String spec, String grade, String normalized) {
  }

  /**
   * 解析业务材料名（如「三级抗震螺纹钢 HRB400E Φ25」）。
   * 策略：先抽材质（HRB400E 等牌号，避免 400 被误当规格），再抽规格（Φ\d+ 或数字型号），剩余匹配产品词典。
   */
  public static Parsed parse(String name, String spec) {
    if (name == null) {
      name = "";
    }
    if (spec == null) {
      spec = "";
    }
    String combined = (name + " " + spec).toUpperCase();
    // 统一符号：φ/ø → Φ
    combined = combined.replace('φ', 'Φ').replace('ø', 'Φ').replace('∅', 'Φ');
    // 去全角
    combined = toHalfWidth(combined);

    // 1) 先抽材质（牌号），避免牌号里的数字被当规格
    java.util.regex.Matcher gradeMatcher =
        java.util.regex.Pattern.compile("(HRB[0-9A-Z]+|HPB[0-9A-Z]+|Q[0-9A-Z]+|20#|45#|16MN|20MN|SS[0-9]+|304L?|316L?|201|202|20G|T12|40CR|42CRMO|20CRMNTI|30CRMO|Q235B?|Q355B?)").matcher(combined);
    String gradePart = null;
    if (gradeMatcher.find()) {
      gradePart = gradeMatcher.group(1);
      combined = combined.replace(gradePart, " ");
    }

    // 2) 再抽规格：Φ\d+ 优先；其余匹配数字型号（100*100*6*8 等）。
    //    支持范围规格：Φ28-32 / Φ12,14 / Φ6-10（gtgqw 大量此类写法）
    String specPart = null;
    java.util.regex.Matcher phiMatcher =
        java.util.regex.Pattern.compile("Φ\\s*(\\d+(?:[.×*xX]\\d+){0,4}(?:[-–,]\\s*\\d+)?)").matcher(combined);
    java.util.regex.Matcher specMatcher =
        java.util.regex.Pattern.compile("(\\d+(?:[.×*xX]\\d+){0,4}(?:[-–,]\\s*\\d+)?)").matcher(combined);
    if (phiMatcher.find()) {
      specPart = phiMatcher.group(1).trim();
      combined = combined.replace(phiMatcher.group(0), " ");
    } else if (specMatcher.find()) {
      specPart = specMatcher.group(1).trim();
      combined = combined.replace(specMatcher.group(0), " ");
    }

    // 3) 剩余部分匹配产品词典
    String productCode = null;
    String remainingTrimmed = combined.replaceAll("[\\s·]+", "");
    for (Map.Entry<String, String> entry : PRODUCT_ALIASES.entrySet()) {
      if (remainingTrimmed.contains(entry.getKey().toUpperCase())
          || remainingTrimmed.contains(entry.getKey())) {
        productCode = entry.getValue();
        break;
      }
    }
    // 归一化完整串（规格在前材质在后，便于比较）
    StringBuilder normalized = new StringBuilder();
    if (productCode != null) {
      normalized.append(productCode).append('|');
    }
    normalized.append(specPart == null ? "" : specPart).append('|')
        .append(gradePart == null ? "" : gradePart);
    return new Parsed(productCode, specPart, gradePart, normalized.toString());
  }

  private static String toHalfWidth(String input) {
    StringBuilder sb = new StringBuilder(input.length());
    for (char c : input.toCharArray()) {
      if (c >= '\uFF01' && c <= '\uFF5E') {
        sb.append((char) (c - 0xFEE0));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
