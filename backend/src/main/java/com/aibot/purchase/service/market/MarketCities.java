package com.aibot.purchase.service.market;

import com.aibot.purchase.entity.MarketQuote;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 行情「城市」维度（2026-11-12）。
 *
 * <p>钢材行情的价格由三个维度共同决定：<b>品种/规格</b>（哪个货）、<b>城市</b>（哪个市场）、
 * <b>产地</b>（哪个钢厂）。三者都在 {@code market_quotes} 的唯一键里，但读取端必须各有明确行为：
 * <ul>
 *   <li>城市 = <b>范围过滤</b>：先把行情限定在「本批次的行情城市」，绝不跨城市平均；</li>
 *   <li>产地 = <b>优先命中</b>：同城市内优先同产地，没这个产地才回退同城市均价。</li>
 * </ul>
 *
 * <p>历史缺陷：城市只写不读——导入时把产地（如「中天」）写进了 city 列，取价又不看城市，
 * 同一规格同一产地于是出现两行（杭州 3666 / 中天 3480），取价把两行平均成 3573。
 * 本类负责两件事：白名单归一（挡住产地冒充城市）+ 取价时的城市过滤（挡住跨城市平均）。
 */
@Component
public class MarketCities {

  /**
   * 已知行情城市。既用于识别侧校验，也用于行情页筛选元数据的兜底
   * （库里还没有数据时也要能给出可选城市）。新增城市在这里加。
   */
  private static final List<String> KNOWN = List.of(
      "成都", "北京", "上海", "广州", "杭州", "武汉", "西安", "重庆", "天津", "南京",
      "沈阳", "福州", "长沙", "郑州", "昆明", "济南", "合肥", "南昌", "太原", "哈尔滨",
      "石家庄", "大连", "海口");

  /** 本批次的行情城市（当前只有一个市场，配置化；将来接项目/批次字段时改这里）。 */
  private final String defaultCity;

  public MarketCities(@Value("${app.market-city:杭州}") String defaultCity) {
    String normalized = normalize(defaultCity);
    this.defaultCity = normalized != null ? normalized : "杭州";
  }

  public static List<String> known() {
    return KNOWN;
  }

  /**
   * 城市归一：去空白、去「市」后缀；不在白名单返回 null（视为「未标注」）。
   *
   * <p>认不出来时宁可当未标注，也不要把「中天」这种产地当城市写进唯一键——那是重复行的根源。
   */
  public static String normalize(String city) {
    if (!StringUtils.hasText(city)) {
      return null;
    }
    String text = city.trim().replaceAll("\\s+", "");
    if (text.endsWith("市") && text.length() > 2) {
      text = text.substring(0, text.length() - 1);
    }
    return KNOWN.contains(text) ? text : null;
  }

  /** 本批次行情城市。 */
  public String defaultCity() {
    return defaultCity;
  }

  /** 归一 + 兜底：识别出的城市不合法（如把产地当城市）时落默认城市，避免写出一条脏维度值。 */
  public String resolve(String city) {
    String normalized = normalize(city);
    return normalized != null ? normalized : defaultCity;
  }

  /**
   * 取价用的城市过滤：本城市有行情就只用本城市，一行都没有才回退全集合。
   *
   * <p>回退是为了兼容「历史数据没标城市 / 别的城市才有这个规格」的情况，避免直接取不到价；
   * 但只要有本城市数据，就绝不跨城市平均——那是把两个市场的价揉成一个不存在的价。
   */
  public List<MarketQuote> preferCity(List<MarketQuote> rows) {
    if (rows == null || rows.isEmpty()) {
      return rows == null ? List.of() : rows;
    }
    List<MarketQuote> inCity = rows.stream()
        .filter(row -> defaultCity.equals(normalize(row.getCity())))
        .toList();
    return inCity.isEmpty() ? rows : inCity;
  }

  /** 城市是否命中（行情行没标城市时不算命中，交给回退逻辑）。 */
  public boolean matches(String city, String quoteCity) {
    return city != null && city.equals(normalize(quoteCity));
  }
}
