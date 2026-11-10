package com.aibot.purchase.service.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 行情爬虫调度：启动时按需全量爬一次 + 定时周期爬取。
 * 幂等由 MarketCrawlerService 的唯一键 upsert 保证，同日期数据不重复存储。
 *
 * <p>两个开关相互独立：
 * <ul>
 *   <li>app.crawler-enabled-on-startup（APP_CRAWLER_ENABLED_ON_STARTUP）：启动后是否全量拉取一次</li>
 *   <li>app.crawler-enabled-scheduled（APP_CRAWLER_ENABLED_SCHEDULED）：是否按定时任务周期拉取</li>
 * </ul>
 * 两者均可独立关闭，关闭后爬虫服务仍可被手动接口按需触发。
 */
@Component
public class MarketCrawlerScheduler implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(MarketCrawlerScheduler.class);

  private final MarketCrawlerService crawlerService;

  @Value("${app.crawler-enabled-on-startup:true}")
  private boolean crawlerEnabledOnStartup;

  @Value("${app.crawler-enabled-scheduled:true}")
  private boolean crawlerEnabledScheduled;

  public MarketCrawlerScheduler(MarketCrawlerService crawlerService) {
    this.crawlerService = crawlerService;
  }

  /** 每 5 小时执行一次（整点触发：0 点、5 点、10 点……） */
  @Scheduled(cron = "0 0 */5 * * ?")
  public void crawlScheduled() {
    if (!crawlerEnabledScheduled) {
      log.info("行情爬虫定时拉取已关闭（app.crawler-enabled-scheduled=false），跳过");
      return;
    }
    runCrawl("定时爬取");
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!crawlerEnabledOnStartup) {
      log.info("行情爬虫启动拉取已关闭（app.crawler-enabled-on-startup=false），跳过");
      return;
    }
    // 启动后延时 5 秒执行，避免与其他初始化竞争
    Thread starter = new Thread(() -> {
      try {
        Thread.sleep(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      runCrawl("启动全量爬取");
    }, "market-crawl-starter");
    starter.setDaemon(true);
    starter.start();
  }

  private void runCrawl(String trigger) {
    try {
      log.info("[{}] 开始行情爬取", trigger);
      Object result = crawlerService.crawlAll();
      log.info("[{}] 行情爬取完成：{}", trigger, result);
    } catch (Exception ex) {
      log.error("[{}] 行情爬取异常", trigger, ex);
    }
  }
}
