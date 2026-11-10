package com.aibot.purchase.service.market;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 采购单确认后的异步材料匹配触发器。
 *
 * <p>在事务提交后（afterCommit）异步执行，避免读到未提交的 materials；
 * 匹配结果落库 material_market_mappings，页面查询零 AI 调用。
 */
@Component
public class MaterialMatchTrigger {
  private static final Logger log = LoggerFactory.getLogger(MaterialMatchTrigger.class);

  private final MaterialMarketMatchService matchService;
  private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
    Thread thread = new Thread(runnable, "material-match-worker");
    thread.setDaemon(true);
    return thread;
  });

  public MaterialMatchTrigger(MaterialMarketMatchService matchService) {
    this.matchService = matchService;
  }

  /** 事务提交后异步匹配批次材料。 */
  public void afterCommitMatch(String batchId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          submit(batchId);
        }
      });
    } else {
      submit(batchId);
    }
  }

  private void submit(String batchId) {
    executor.submit(() -> {
      try {
        int matched = matchService.matchBatch(batchId);
        log.info("批次 {} 材料匹配完成，匹配 {} 条", batchId, matched);
      } catch (Exception ex) {
        log.error("批次 {} 材料匹配异常", batchId, ex);
      }
    });
  }
}
