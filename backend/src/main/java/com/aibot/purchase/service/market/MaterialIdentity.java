package com.aibot.purchase.service.market;

import com.aibot.purchase.service.MaterialCategoryNormalizer;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 材料身份归一化：把三张单里同一个材料的三种写法收敛成同一个键（2026-10-29）。
 *
 * <p>典型场景（同一批货）：
 * <pre>
 *   送货单   螺纹钢HRB400 / 12*9 / 品牌中天  → 螺纹钢|HRB400|12|9|中天
 *   找钢网   三级螺纹钢 / 12*9 / 产地中天    → 螺纹钢|HRB400|12|9|中天
 *   提货单   12螺纹钢 9m / 无规格 / 无产地   → 螺纹钢|?|12|9|?
 * </pre>
 *
 * <p>抽取原则：只认单据上写了的，<b>不猜</b>。牌号缺失（「三级螺纹钢」不推断成 HRB400）、
 * 产地缺失、定尺缺失，一律留空，由上层按「组内唯一值回填」决定能不能补（见
 * {@link #withResolved}）。定尺只对定尺类材料（螺纹钢/圆钢）有意义——盘螺、高线、盘圆
 * 成盘供货，本来就没有定尺，不能给默认值。
 */
public final class MaterialIdentity {

  /** 定尺类材料：只有它们有 9m / 12m 的概念。 */
  private static final java.util.Set<String> LENGTH_CATEGORIES = java.util.Set.of("螺纹钢", "圆钢");

  /** 牌号：HRB400E / HRB400 / HPB300 / RRB400 / Q235B。先抽掉，避免 400 被当成直径。 */
  private static final Pattern GRADE = Pattern.compile(
      "(?i)(HRBF?\\d{3}E?|HPB\\d{3}|RRB\\d{3}|Q\\d{3}[A-E]?)");

  /** 直径 × 定尺：12*9 / 12×9 / Φ12*9。第一段直径，第二段定尺（米）。 */
  private static final Pattern DIAMETER_X_LENGTH = Pattern.compile(
      "(\\d+(?:\\.\\d+)?)\\s*[*xX×]\\s*(\\d+(?:\\.\\d+)?)");

  /** Φ12 / φ12。 */
  private static final Pattern DIAMETER_PHI = Pattern.compile(
      "[ΦφØø]\\s*(\\d+(?:\\.\\d+)?)");

  /** 定尺写法：9m / 12米 / 9 M。 */
  private static final Pattern LENGTH_UNIT = Pattern.compile(
      "(\\d+(?:\\.\\d+)?)\\s*(?:[mM](?![a-zA-Z])|米)");

  private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

  private MaterialIdentity() {
  }

  /**
   * 归一化结果。
   *
   * @param baseKey     粗键 category|diameter|lengthM：先按规格归堆，组内再定牌号/产地
   * @param identityKey 细键 category|diameter|lengthM|grade|origin：落库唯一约束
   */
  public record Identity(
      String category,
      String grade,
      String diameter,
      String lengthM,
      String origin,
      String baseKey,
      String identityKey
  ) {
    public boolean hasGrade() {
      return StringUtils.hasText(grade);
    }

    public boolean hasOrigin() {
      return StringUtils.hasText(origin);
    }
  }

  /**
   * 单据<b>写法</b>的规范化文本：材料名|规格|产地（大写、统一符号、去空格）。
   *
   * <p>这是「这种写法」的键，用于查 {@code batch_material_aliases}——AI 判同按写法缓存，
   * 同样的写法不重复问 AI。注意它和 identityKey 不同：rawKey 保留原始用词（中天 vs 中天钢铁
   * 是两个 rawKey），identityKey 是归一后的身份。
   */
  public static String rawKey(String materialName, String spec, String origin) {
    String text = normalizeText((materialName == null ? "" : materialName)
        + "|" + (spec == null ? "" : spec)
        + "|" + (origin == null ? "" : origin));
    return text.replaceAll("\\s+", "").trim();
  }

  /**
   * 牌号是否可信：AI 可能编出「三级钢」「高强度」这类非规范写法，只接受正则认得的牌号。
   * 校验不过按"没写牌号"处理，不阻断判同。
   */
  public static boolean looksLikeGrade(String value) {
    return StringUtils.hasText(value) && GRADE.matcher(normalizeText(value).trim()).matches();
  }

  /**
   * 直径/定尺的清洗：只接受纯数字（12 / 6.5）。Φ12、12mm、12米 这类带符号的写法一律回 null，
   * 由上层按"没写"处理——AI 应该给规范值，给不出就别猜。
   */
  public static String numericOrNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.matches("\\d+(\\.\\d+)?") ? plain(trimmed) : null;
  }

  /** 从单据明细的材料名/规格/产地解析身份（不猜、缺就空）。 */
  public static Identity parse(String materialName, String spec, String origin) {
    String category = MaterialCategoryNormalizer.category(materialName);
    String text = normalizeText((materialName == null ? "" : materialName)
        + " " + (spec == null ? "" : spec));

    // 1) 先摘掉牌号：否则 HRB400 里的 400 会被当成直径/定尺
    String grade = null;
    Matcher gradeMatcher = GRADE.matcher(text);
    if (gradeMatcher.find()) {
      grade = gradeMatcher.group(1).toUpperCase();
      text = text.replace(gradeMatcher.group(0), " ");
    }

    String diameter = null;
    String lengthM = null;
    if (LENGTH_CATEGORIES.contains(category)) {
      Matcher pair = DIAMETER_X_LENGTH.matcher(text);
      if (pair.find()) {
        diameter = plain(pair.group(1));
        lengthM = plain(pair.group(2));
        text = text.replace(pair.group(0), " ");
      }
      if (lengthM == null) {
        Matcher unit = LENGTH_UNIT.matcher(text);
        if (unit.find()) {
          lengthM = plain(unit.group(1));
          text = text.replace(unit.group(0), " ");
        }
      }
    }
    if (diameter == null) {
      Matcher phi = DIAMETER_PHI.matcher(text);
      if (phi.find()) {
        diameter = plain(phi.group(1));
      }
    }
    if (diameter == null) {
      Matcher number = NUMBER.matcher(text);
      if (number.find()) {
        diameter = plain(number.group(1));
      }
    }

    return withResolved(category, grade, diameter, lengthM, origin);
  }

  /**
   * 用"已确定的"五维重建键：粗键始终由 category/diameter/lengthM 组成，
   * 细键再拼上 grade/origin。组内回填牌号或产地后走这里重新算键。
   */
  public static Identity withResolved(String category, String grade, String diameter,
                                      String lengthM, String origin) {
    String cleanCategory = StringUtils.hasText(category) ? category.trim() : "未标注材料";
    String cleanGrade = clean(grade);
    String cleanDiameter = clean(diameter);
    String cleanLength = clean(lengthM);
    String cleanOrigin = clean(origin);
    // 非定尺类材料即使单据上写了 9m 也不认（成盘供货），统一留空，避免同一材料被拆成两行
    if (!LENGTH_CATEGORIES.contains(cleanCategory)) {
      cleanLength = null;
    }
    String baseKey = cleanCategory + "|" + nz(cleanDiameter) + "|" + nz(cleanLength);
    String identityKey = baseKey + "|" + nz(cleanGrade) + "|" + nz(cleanOrigin);
    return new Identity(cleanCategory, cleanGrade, cleanDiameter, cleanLength, cleanOrigin,
        baseKey, identityKey);
  }

  /** 用同一个身份的粗细键重建（组内回填 grade/origin 时用）。 */
  public static Identity withResolved(Identity identity, String grade, String origin) {
    return withResolved(identity.category(), grade, identity.diameter(), identity.lengthM(), origin);
  }

  /** 统一符号与全角，便于正则识别。 */
  private static String normalizeText(String input) {
    if (input == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(input.length());
    for (char c : input.toCharArray()) {
      if (c == 'φ' || c == 'ø' || c == 'Ø') {
        sb.append('Φ');
      } else if (c == '×' || c == '✕') {
        sb.append('*');
      } else if (c == '＊') {
        sb.append('*');
      } else if (c >= '０' && c <= '９') {
        sb.append((char) (c - '０' + '0'));
      } else {
        sb.append(c);
      }
    }
    return sb.toString().toUpperCase();
  }

  /** 数字规范化：12.00 → 12，6.50 → 6.5。 */
  private static String plain(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    try {
      return new BigDecimal(value.trim()).stripTrailingZeros().toPlainString();
    } catch (NumberFormatException e) {
      return value.trim();
    }
  }

  /** 产地/牌号规范化：去首尾与内部空白（「中 天」与「中天」视为同一产地）。 */
  private static String clean(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String trimmed = value.replaceAll("\\s+", "").trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }
}
