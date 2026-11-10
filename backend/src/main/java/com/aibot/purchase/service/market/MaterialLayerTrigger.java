package com.aibot.purchase.service.market;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 材料层刷新触发器（2026-11-04，P1 接线）。
 *
 * <p>单据确认/修正后，事务提交后异步执行材料层刷新四步：
 * <ol>
 *   <li>{@link BatchMaterialService#rebuild}：规则归并（总是跑，无 AI，保证新单据的明细行
 *       立刻挂上材料；已登记的写法走 batch_material_aliases，不重复判）；</li>
 *   <li>{@link MaterialReconcileService#reconcileBatch}：AI 判同，只判未登记的新写法；</li>
 *   <li>{@link MaterialQuoteBindingService#bindBatch}：给没绑过网价的材料补绑定
 *       （refreshAuto=false：auto 绑好的不重复问模型，重跑批次才刷新）；</li>
 *   <li>重锁：批次内已确认采购单重锁行情价 + 送货单重算销售价——
 *       锁价链路优先消费材料级网价绑定，这一步让「确认时的按行匹配」
 *       升级成「按材料绑定取价」，台账随之修正。</li>
 * </ol>
 *
 * <p>任何一步失败都只记日志：材料层是增强层，绝不能把单据确认主流程拖下水；
 * 每一步自身幂等，下一次确认/重跑会自我修正。
 */
@Component
public class MaterialLayerTrigger {
  private static final Logger log = LoggerFactory.getLogger(MaterialLayerTrigger.class);

  private final BatchMaterialService batchMaterialService;
  private final MaterialReconcileService reconcileService;
  private final MaterialQuoteBindingService bindingService;
  private final DeliveryNetPriceService deliveryNetPriceService;
  private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "material-layer-worker");
    thread.setDaemon(true);
    return thread;
  });

  public MaterialLayerTrigger(
      BatchMaterialService batchMaterialService,
      MaterialReconcileService reconcileService,
      MaterialQuoteBindingService bindingService,
      DeliveryNetPriceService deliveryNetPriceService
  ) {
    this.batchMaterialService = batchMaterialService;
    this.reconcileService = reconcileService;
    this.bindingService = bindingService;
    this.deliveryNetPriceService = deliveryNetPriceService;
  }

  /** 事务提交后异步刷新材料层（确认/修正单据后调用）。 */
  public void afterCommitRefresh(String batchId) {
    submit(batchId, false);
  }

  /** 事务提交后异步刷新材料层；refreshAuto=true 时按最新行情重选 auto 绑定（重跑批次用）。 */
  public void afterCommitRefresh(String batchId, boolean refreshAuto) {
    submit(batchId, refreshAuto);
  }

  /** 同步重建材料层（删除单据后调用）：无 AI，事务内执行，保证重锁读到的材料层不含已删行。 */
  public void rebuildNow(String batchId) {
    if (!org.springframework.util.StringUtils.hasText(batchId)) {
      return;
    }
    try {
      batchMaterialService.rebuild(batchId);
    } catch (Exception ex) {
      log.warn("批次 {} 材料层同步重建失败（不影响删除主流程）：{}", batchId, ex.toString());
    }
  }

  private void submit(String batchId, boolean refreshAuto) {
    if (batchId == null || batchId.isBlank()) {
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          executor.submit(() -> refreshSafely(batchId, refreshAuto));
        }
      });
    } else {
      executor.submit(() -> refreshSafely(batchId, refreshAuto));
    }
  }

  private void refreshSafely(String batchId, boolean refreshAuto) {
    try {
      // ① 规则归并：新单据的明细行先挂上材料（无 AI，走已登记别名）
      batchMaterialService.rebuild(batchId);
      // ② AI 判同：只判未登记的新写法；判定后内部会再重建
      reconcileService.reconcileBatch(batchId, false);
      // ③ 材料级网价绑定：只补没绑过的（auto 不重复问模型）
      bindingService.bindBatch(batchId, refreshAuto);
      // ④ 重锁：采购单行情价 + 送货单销售价，按材料绑定取价，台账按差额修正
      relock(batchId);
    } catch (Exception ex) {
      log.error("批次 {} 材料层刷新异常（不影响主流程）", batchId, ex);
    }
  }

  private void relock(String batchId) {
    var supplies = deliveryNetPriceService.confirmedSupplies(batchId);
    for (var supply : supplies) {
      deliveryNetPriceService.lockSupplyPrices(supply);
    }
    deliveryNetPriceService.resyncDeliverySales(batchId, false);
    log.info("批次 {} 材料层刷新完成：采购单 {} 张重锁，送货单已重算", batchId, supplies.size());
  }
}
