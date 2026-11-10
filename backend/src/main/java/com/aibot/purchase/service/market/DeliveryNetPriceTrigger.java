package com.aibot.purchase.service.market;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 送货单确认后的 AI 网价兜底触发器。
 *
 * <p>规则层未命中的明细行在确认事务内标记 ai_pending；事务提交后（afterCommit）
 * 异步执行 AI 匹配并重算台账，不阻塞确认接口。
 */
@Component
public class DeliveryNetPriceTrigger {
  private static final Logger log = LoggerFactory.getLogger(DeliveryNetPriceTrigger.class);

  private final DeliveryNetPriceService netPriceService;
  private final ExecutorService executor = Executors.newFixedThreadPool(1, runnable -> {
    Thread thread = new Thread(runnable, "delivery-netprice-worker");
    thread.setDaemon(true);
    return thread;
  });

  public DeliveryNetPriceTrigger(DeliveryNetPriceService netPriceService) {
    this.netPriceService = netPriceService;
  }

  /** 事务提交后异步执行 AI 兜底。 */
  public void afterCommitAiFallback(String documentId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          submit(documentId);
        }
      });
    } else {
      submit(documentId);
    }
  }

  private void submit(String documentId) {
    executor.submit(() -> {
      try {
        netPriceService.aiFallback(documentId);
        log.info("送货单 {} AI 网价兜底完成", documentId);
      } catch (Exception ex) {
        log.error("送货单 {} AI 网价兜底异常", documentId, ex);
      }
    });
  }
}
