package com.aibot.purchase.service.market;

import com.aibot.purchase.entity.MarketCrawlLog;
import com.aibot.purchase.entity.MarketProduct;
import com.aibot.purchase.entity.MarketQuote;
import com.aibot.purchase.mapper.MarketCrawlLogMapper;
import com.aibot.purchase.mapper.MarketProductMapper;
import com.aibot.purchase.mapper.MarketQuoteMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 钢易网（gtgqw）行情爬虫。
 *
 * <p>站点结构（已实测 2026-08-21）：
 * <ul>
 *   <li>文章制：一篇 = 一城市 × 一产品，URL /j/showhz{id}.html，标题如「8月21日杭州螺纹钢价格今日报价表」。</li>
 *   <li>文章表格：表头 [品名 | 规格（mm）| 材质 | 产地 | 价格（元/吨）| 涨跌]，每行一条报价；
 *       一篇「螺纹钢」文章实际包含 螺纹钢/盘螺/高线 多类品名行（实测 200/59/11 行）。</li>
 *   <li>列表页（渠道页，如 /luowengang/）：列出各城市最新文章，可用于按城市发现。</li>
 *   <li>编码 gb2312，需显式按 GBK 解码。</li>
 * </ul>
 *
 * <p>发现：对每个启用产品（source='gtgqw'）按其 source_url 渠道页，找到标题同时含
 * 「城市名」与「渠道代表品名」的最新一篇（城市列表由 app.crawl-cities 配置，默认杭州）。
 * 解析：逐行按品名反查 market_products 得 product_code，一篇文章产出多个产品行。
 * 幂等：按 (quote_date, source, product_code, spec_name, city, origin) upsert。
 */
@Service
public class MarketCrawlerService {
  private static final Logger log = LoggerFactory.getLogger(MarketCrawlerService.class);

  private static final String SOURCE = "gtgqw";
  private static final String USER_AGENT =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";
  private static final int TIMEOUT_MS = 15000;
  private static final Pattern ARTICLE_PATTERN =
      Pattern.compile("href=\"(http://www\\.gtgqw\\.com/j/showhz\\d+\\.html)\"[^>]*>([^<]+)</a>");
  private static final Pattern PUBLISH_DATE_PATTERN = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");
  private static final Pattern TITLE_DATE_PATTERN = Pattern.compile("(\\d{1,2})月(\\d{1,2})日");
  private static final Charset GBK = Charset.forName("GBK");

  private final MarketProductMapper productMapper;
  private final MarketQuoteMapper quoteMapper;
  private final MarketCrawlLogMapper crawlLogMapper;

  /** 抓取城市列表，逗号分隔，默认杭州（当前业务只需杭州螺纹钢文章）。 */
  @Value("${app.crawl-cities:杭州}")
  private String crawlCities;

  public MarketCrawlerService(
      MarketProductMapper productMapper,
      MarketQuoteMapper quoteMapper,
      MarketCrawlLogMapper crawlLogMapper
  ) {
    this.productMapper = productMapper;
    this.quoteMapper = quoteMapper;
    this.crawlLogMapper = crawlLogMapper;
  }

  /**
   * 全量爬取：按渠道去重遍历启用产品。
   *
   * @return 汇总信息：{products, channels, rowsFound, rowsUpserted, rowsSkipped, failed}
   */
  public Map<String, Object> crawlAll() {
    List<MarketProduct> products = productMapper.selectList(Wrappers.<MarketProduct>lambdaQuery()
        .eq(MarketProduct::getEnabled, true)
        .eq(MarketProduct::getSource, SOURCE)
        .orderByAsc(MarketProduct::getSortOrder));
    Map<String, List<MarketProduct>> channels = new LinkedHashMap<>();
    for (MarketProduct product : products) {
      if (StringUtils.hasText(product.getSourceUrl())) {
        channels.computeIfAbsent(product.getSourceUrl(), k -> new ArrayList<>()).add(product);
      }
    }
    Map<String, String> nameToCode = new HashMap<>();
    for (MarketProduct product : products) {
      nameToCode.put(product.getName(), product.getCode());
    }

    LocalDateTime runStart = LocalDateTime.now();
    int found = 0, upserted = 0, skipped = 0, failed = 0;
    for (Map.Entry<String, List<MarketProduct>> entry : channels.entrySet()) {
      CrawlResult result = crawlChannel(entry.getKey(), entry.getValue(), nameToCode);
      found += result.rowsFound;
      upserted += result.rowsUpserted;
      skipped += result.rowsSkipped;
      if (result.error != null) {
        failed++;
      }
    }
    MarketCrawlLog summary = new MarketCrawlLog();
    summary.setRunStart(runStart);
    summary.setRunEnd(LocalDateTime.now());
    summary.setStatus(failed > 0 ? "partial" : "success");
    summary.setRowsFound(found);
    summary.setRowsUpserted(upserted);
    summary.setRowsSkipped(skipped);
    crawlLogMapper.insert(summary);

    Map<String, Object> result = new HashMap<>();
    result.put("products", products.size());
    result.put("channels", channels.size());
    result.put("rowsFound", found);
    result.put("rowsUpserted", upserted);
    result.put("rowsSkipped", skipped);
    result.put("failed", failed);
    return result;
  }

  /** 爬取单个产品所在渠道（兼容旧调用：crawlOne）。 */
  public CrawlResult crawlOne(MarketProduct product) {
    List<MarketProduct> group = productMapper.selectList(Wrappers.<MarketProduct>lambdaQuery()
        .eq(MarketProduct::getSource, SOURCE)
        .eq(MarketProduct::getSourceUrl, product.getSourceUrl()));
    Map<String, String> nameToCode = new HashMap<>();
    for (MarketProduct p : group) {
      nameToCode.put(p.getName(), p.getCode());
    }
    return crawlChannel(product.getSourceUrl(), group, nameToCode);
  }

  // ---------------------------------------------------------------------------
  // 渠道爬取
  // ---------------------------------------------------------------------------

  /** 爬取一个渠道页：按城市发现最新文章 → 解析入库。渠道页可能覆盖多个产品（同 URL）。 */
  private CrawlResult crawlChannel(String channelUrl, List<MarketProduct> products,
                                   Map<String, String> nameToCode) {
    CrawlResult result = new CrawlResult();
    LocalDateTime start = LocalDateTime.now();
    String channelName = products.stream().min((a, b) -> a.getSortOrder() - b.getSortOrder())
        .map(MarketProduct::getName).orElse(products.get(0).getName());
    try {
      // 1) 发现：渠道列表页 → 各城市最新文章
      List<ArticleLink> articles = listArticles(channelUrl);
      List<String> cities = splitCities();
      List<ArticleTarget> targets = new ArrayList<>();
      for (String city : cities) {
        ArticleLink target = articles.stream()
            .filter(a -> a.title.contains(city) && a.title.contains(channelName))
            .findFirst()
            .orElseGet(() -> articles.stream().filter(a -> a.title.contains(city)).findFirst().orElse(null));
        if (target != null) {
          targets.add(new ArticleTarget(target, city));
        } else {
          log.info("渠道 {} 未找到 {} 的最新文章", channelName, city);
        }
      }
      if (targets.isEmpty()) {
        throw new IllegalStateException("渠道列表页未发现任何目标城市文章");
      }
      // 2) 解析每篇文章并入库
      LocalDate quoteDate = null;
      for (ArticleTarget target : targets) {
        int before = result.rowsFound;
        LocalDate d = parseArticle(target.article(), target.city(), nameToCode, channelUrl, result);
        if (d != null) {
          quoteDate = d;
        }
        if (result.rowsFound == before) {
          log.info("文章 {} 无有效报价行", target.article().title());
        }
      }
      result.quoteDate = quoteDate;
      result.error = null;
    } catch (Exception ex) {
      result.error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
      log.error("渠道 {} 爬取失败：{}", channelName, result.error);
    }
    result.runEnd = LocalDateTime.now();
    MarketCrawlLog logRow = new MarketCrawlLog();
    logRow.setRunStart(start);
    logRow.setRunEnd(result.runEnd);
    logRow.setStatus(result.error == null ? "success" : "failed");
    logRow.setProductCode(products.get(0).getCode());
    logRow.setQuoteDate(result.quoteDate);
    logRow.setRowsFound(result.rowsFound);
    logRow.setRowsUpserted(result.rowsUpserted);
    logRow.setRowsSkipped(result.rowsSkipped);
    logRow.setErrorMsg(truncate(result.error, 500));
    crawlLogMapper.insert(logRow);
    log.info("渠道 {} 爬取完成：found={} upserted={} skipped={} err={}",
        channelName, result.rowsFound, result.rowsUpserted, result.rowsSkipped,
        result.error == null ? "-" : result.error);
    return result;
  }

  /** 列表页文章链接：按出现顺序（最新在前）。 */
  private List<ArticleLink> listArticles(String channelUrl) throws IOException {
    String html = fetch(channelUrl, "http://www.gtgqw.com/");
    List<ArticleLink> links = new ArrayList<>();
    Matcher matcher = ARTICLE_PATTERN.matcher(html);
    while (matcher.find()) {
      links.add(new ArticleLink(matcher.group(2).trim(), matcher.group(1)));
    }
    // 按 URL 去重
    List<ArticleLink> distinct = new ArrayList<>();
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (ArticleLink link : links) {
      if (seen.add(link.url)) {
        distinct.add(link);
      }
    }
    return distinct;
  }

  /**
   * 解析文章表格并入库。
   *
   * @return 行情归属日
   */
  private LocalDate parseArticle(ArticleLink article, String city, Map<String, String> nameToCode,
                                 String channelReferer, CrawlResult result) throws IOException {
    Document doc = parseHtml(fetch(article.url(), channelReferer));
    LocalDate quoteDate = parseDate(doc, article.title());
    result.quoteDate = quoteDate;

    for (Element table : doc.select("table")) {
      Elements trs = table.select("tr");
      // 表头可能不在首行（gtgqw 文章表格首行常为标题行「2026年8月21日…」），
      // 在前几行内定位含「品名+价格」的表头，跳过其前的标题行
      int headerSkip = -1;
      for (int i = 0; i < Math.min(trs.size(), 5); i++) {
        if (isPriceTable(trs.get(i))) {
          headerSkip = i + 1;
          break;
        }
      }
      if (headerSkip < 0) {
        continue;
      }
      for (int i = headerSkip; i < trs.size(); i++) {
        Elements tds = trs.get(i).select("td");
        if (tds.size() < 6) {
          continue;
        }
        String name = tds.get(0).text().trim();
        String spec = tds.get(1).text().trim();
        String grade = tds.get(2).text().trim();
        String origin = tds.get(3).text().trim();
        String priceText = tds.get(4).text().trim();
        String changeText = tds.get(5).text().trim();
        BigDecimal price = parseNumber(priceText);
        if (price == null) {
          continue;
        }
        String productCode = nameToCode.get(name);
        if (productCode == null) {
          // 渠道页未登记的产品行（如后续新增品名），跳过并计数，避免脏数据
          log.info("文章 {} 含未登记品名 {}，跳过", article.title(), name);
          continue;
        }
        String specName = String.join(" ",
            new String[]{name, spec, grade}).replaceAll("\\s+", " ").trim();
        upsert(quoteDate, productCode, specName, spec, grade, origin, city,
            price, parseChange(changeText), article.url(), result);
      }
    }
    return quoteDate;
  }

  static boolean isPriceTable(Element headRow) {
    String text = headRow.text();
    return text.contains("品名") && text.contains("价格");
  }

  private void upsert(LocalDate date, String productCode, String specName, String spec,
                      String grade, String origin, String city, BigDecimal price,
                      BigDecimal change, String detailUrl, CrawlResult result) {
    MarketQuote existing = quoteMapper.selectOne(Wrappers.<MarketQuote>lambdaQuery()
        .eq(MarketQuote::getQuoteDate, date)
        .eq(MarketQuote::getSource, SOURCE)
        .eq(MarketQuote::getProductCode, productCode)
        .eq(MarketQuote::getSpecName, specName)
        .eq(MarketQuote::getCity, city)
        .eq(MarketQuote::getOrigin, origin)
        .last("LIMIT 1"));
    if (existing == null) {
      MarketQuote quote = new MarketQuote();
      quote.setQuoteDate(date);
      quote.setSource(SOURCE);
      quote.setProductCode(productCode);
      quote.setProductName(productNameOf(productCode));
      quote.setSpecName(specName);
      quote.setSpec(StringUtils.hasText(spec) ? spec : null);
      quote.setGrade(StringUtils.hasText(grade) ? grade : null);
      quote.setOrigin(origin);
      quote.setCity(city);
      quote.setPrice(price);
      quote.setPriceChange(change);
      quote.setDetailUrl(detailUrl);
      quote.setCapturedAt(LocalDateTime.now());
      quoteMapper.insert(quote);
      result.rowsUpserted++;
    } else if (valuesChanged(existing, price, change, detailUrl)) {
      existing.setPrice(price);
      existing.setPriceChange(change);
      existing.setDetailUrl(detailUrl);
      existing.setCapturedAt(LocalDateTime.now());
      quoteMapper.updateById(existing);
      result.rowsUpserted++;
    } else {
      result.rowsSkipped++;
    }
    result.rowsFound++;
  }

  private String productNameOf(String productCode) {
    MarketProduct product = productMapper.selectOne(Wrappers.<MarketProduct>lambdaQuery()
        .eq(MarketProduct::getCode, productCode)
        .eq(MarketProduct::getSource, SOURCE)
        .last("LIMIT 1"));
    return product == null ? null : product.getName();
  }

  // ---------------------------------------------------------------------------
  // 解析工具
  // ---------------------------------------------------------------------------

  /** 抓取并显式按 GBK 解码（站点 gb2312，Jsoup 自动识别不可靠）。文章页需带渠道 Referer，否则 403。 */
  private String fetch(String url, String referer) throws IOException {
    byte[] bytes = Jsoup.connect(url)
        .userAgent(USER_AGENT)
        .timeout(TIMEOUT_MS)
        .header("Accept", "text/html,application/xhtml+xml")
        .header("Accept-Language", "zh-CN,zh;q=0.9")
        .header("Referer", referer)
        .execute()
        .bodyAsBytes();
    return new String(bytes, GBK);
  }

  private Document parseHtml(String html) {
    return Jsoup.parse(html);
  }

  /** 行情归属日：优先标题日期（如 8月21日）+ 当前年，其次页面发布时间。 */
  static LocalDate parseDate(Document doc, String title) {
    Matcher titleMatcher = TITLE_DATE_PATTERN.matcher(title);
    if (titleMatcher.find()) {
      int month = Integer.parseInt(titleMatcher.group(1));
      int day = Integer.parseInt(titleMatcher.group(2));
      LocalDate today = LocalDate.now();
      try {
        LocalDate date = LocalDate.of(today.getYear(), month, day);
        // 标题日期不应晚于今天（跨年/隔日报价兜底用发布时间）
        if (!date.isAfter(today)) {
          return date;
        }
      } catch (Exception ignored) {
        // fallthrough
      }
    }
    Matcher publishMatcher = PUBLISH_DATE_PATTERN.matcher(doc.html());
    if (publishMatcher.find()) {
      try {
        return LocalDate.parse(publishMatcher.group(1));
      } catch (Exception ignored) {
        // fallthrough
      }
    }
    return LocalDate.now();
  }

  static BigDecimal parseNumber(String text) {
    if (!StringUtils.hasText(text)) {
      return null;
    }
    String cleaned = text.replaceAll("[^\\d.\\-+]", "");
    if (cleaned.isEmpty()) {
      return null;
    }
    try {
      return new BigDecimal(cleaned);
    } catch (Exception ex) {
      return null;
    }
  }

  /** 涨跌："+20"/"-20"/"20" → 对应数值；"-"/空 → null。 */
  static BigDecimal parseChange(String text) {
    if (!StringUtils.hasText(text) || "-".equals(text.trim()) || "—".equals(text.trim())) {
      return null;
    }
    return parseNumber(text);
  }

  private List<String> splitCities() {
    List<String> cities = new ArrayList<>();
    for (String city : crawlCities.split("[,，]")) {
      if (StringUtils.hasText(city)) {
        cities.add(city.trim());
      }
    }
    return cities;
  }

  private boolean valuesChanged(MarketQuote existing, BigDecimal price, BigDecimal change,
                                String detailUrl) {
    return !bigDecimalEquals(existing.getPrice(), price)
        || !bigDecimalEquals(existing.getPriceChange(), change)
        || !Objects.equals(existing.getDetailUrl(), detailUrl);
  }

  /** BigDecimal 按数值比较（忽略 scale：3400 ≡ 3400.00）。 */
  private static boolean bigDecimalEquals(BigDecimal left, BigDecimal right) {
    if (left == null && right == null) {
      return true;
    }
    if (left == null || right == null) {
      return false;
    }
    return left.compareTo(right) == 0;
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  // ---------------------------------------------------------------------------
  // 内部记录
  // ---------------------------------------------------------------------------

  private record ArticleLink(String title, String url) {
  }

  private record ArticleTarget(ArticleLink article, String city) {
  }

  public static class CrawlResult {
    public LocalDate quoteDate;
    public int rowsFound;
    public int rowsUpserted;
    public int rowsSkipped;
    public String error;
    public LocalDateTime runEnd;

    public CrawlResult() {
      this.error = null;
    }
  }
}
