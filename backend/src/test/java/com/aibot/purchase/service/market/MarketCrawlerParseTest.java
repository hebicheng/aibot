package com.aibot.purchase.service.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

/**
 * gtgqw 文章制解析逻辑验证（jsoup 表格提取 + 工具方法）。
 * 与站点真实结构一致：表头 [品名|规格（mm）|材质|产地|价格（元/吨）|涨跌]，
 * 一篇「螺纹钢」文章含多类品名行（螺纹钢/盘螺/高线）。
 */
class MarketCrawlerParseTest {

  /** 与 gtgqw 杭州螺纹钢文章真实结构一致的片段（首行为标题行，次行为表头）。 */
  private static final String SAMPLE =
      "<table>"
      + "<tr><td colspan='6'>2026年8月21日杭州螺纹钢价格今日报价表</td></tr>"
      + "<tr><td>品名</td><td>规格（mm）</td><td>材质</td><td>产地</td><td>价格（元/吨）</td><td>涨跌</td></tr>"
      + "<tr><td>螺纹钢</td><td>Φ10</td><td>HRB400E</td><td>安徽富鑫</td><td>3190</td><td>+20</td></tr>"
      + "<tr><td>螺纹钢</td><td>Φ28-32</td><td>HRB400E</td><td>宝武鄂钢</td><td>3100</td><td>-20</td></tr>"
      + "<tr><td>盘螺</td><td>Φ8-10</td><td>HRB400</td><td>富钢</td><td>3110</td><td>-</td></tr>"
      + "<tr><td>高线</td><td>Φ6</td><td>HPB300</td><td>中天</td><td>3150</td><td>+10</td></tr>"
      + "</table>";

  @Test
  void recognizesPriceTableByHeader() {
    Document doc = Jsoup.parse(SAMPLE);
    Element table = doc.selectFirst("table");
    var rows = table.select("tr");
    // 首行是标题行，不是价格表头
    assertEquals(false, MarketCrawlerService.isPriceTable(rows.get(0)));
    // 第二行才是表头
    assertEquals(true, MarketCrawlerService.isPriceTable(rows.get(1)));
  }

  @Test
  void extractsRowsWithSixColumns() {
    Document doc = Jsoup.parse(SAMPLE);
    Element table = doc.selectFirst("table");
    var dataRows = table.select("tr").stream()
        .filter(tr -> tr.select("td").size() >= 6)
        .skip(1) // 跳过表头
        .toList();
    assertEquals(4, dataRows.size());

    var first = dataRows.get(0).select("td");
    assertEquals("螺纹钢", first.get(0).text().trim());
    assertEquals("Φ10", first.get(1).text().trim());
    assertEquals("HRB400E", first.get(2).text().trim());
    assertEquals("安徽富鑫", first.get(3).text().trim());
    assertEquals("3190", first.get(4).text().trim());
    assertEquals("+20", first.get(5).text().trim());
  }

  @Test
  void rangeSpecRowIsParsedAsIs() {
    Document doc = Jsoup.parse(SAMPLE);
    Element table = doc.selectFirst("table");
    var dataRows = table.select("tr").stream()
        .filter(tr -> tr.select("td").size() >= 6)
        .skip(1)
        .toList();
    var rangeRow = dataRows.get(1).select("td");
    assertEquals("Φ28-32", rangeRow.get(1).text().trim());
    // spec_name 组装：「品名 规格 材质」
    String specName = String.join(" ",
        rangeRow.get(0).text().trim(), rangeRow.get(1).text().trim(), rangeRow.get(2).text().trim());
    assertEquals("螺纹钢 Φ28-32 HRB400E", specName);
  }

  @Test
  void parsesNumberAndChange() {
    assertEquals(new BigDecimal("3190"), MarketCrawlerService.parseNumber("3190"));
    assertEquals(new BigDecimal("3100"), MarketCrawlerService.parseNumber("3100"));
    assertNull(MarketCrawlerService.parseNumber(""));

    assertEquals(new BigDecimal("20"), MarketCrawlerService.parseChange("+20"));
    assertEquals(new BigDecimal("-20"), MarketCrawlerService.parseChange("-20"));
    assertNull(MarketCrawlerService.parseChange("-"));
    assertNull(MarketCrawlerService.parseChange(""));
  }

  @Test
  void parsesDateFromTitle() {
    Document doc = Jsoup.parse(SAMPLE);
    LocalDate date = MarketCrawlerService.parseDate(
        doc, "8月21日杭州螺纹钢价格今日报价表");
    assertEquals(LocalDate.of(LocalDate.now().getYear(), 8, 21), date);
  }

  @Test
  void fallsBackToPublishDateWhenTitleHasNoDate() {
    String page = "<html><body>2026-08-21 09:50:12</body></html>";
    Document doc = Jsoup.parse(page);
    LocalDate date = MarketCrawlerService.parseDate(doc, "杭州螺纹钢价格今日报价表");
    assertEquals(LocalDate.of(2026, 8, 21), date);
  }
}
