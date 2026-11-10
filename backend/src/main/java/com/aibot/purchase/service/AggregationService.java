package com.aibot.purchase.service;

import com.aibot.purchase.entity.Batch;
import com.aibot.purchase.entity.FinanceRecord;
import com.aibot.purchase.entity.FlowEvent;
import com.aibot.purchase.entity.Material;
import com.aibot.purchase.entity.Project;
import com.aibot.purchase.mapper.BatchMapper;
import com.aibot.purchase.mapper.FinanceRecordMapper;
import com.aibot.purchase.mapper.FlowEventMapper;
import com.aibot.purchase.mapper.MaterialMapper;
import com.aibot.purchase.mapper.ProjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 聚合重算：把物料/报价/财务的明细滚动汇总到 batch、再到 project 的金额列，替代之前"读死值"。
 * 每次业务写入（确认采购/选择供应商/归档收付款）后调用，保证看板与报表始终反映真实数据。
 *
 * 口径：
 * - 采购额 = Σ 物料数量 ×（已选报价 ?: 预算单价）
 * - 销售额 = Σ 收款类财务记录金额
 * - 毛利润 = 销售额 − 采购额；毛利率 = 毛利润 / 销售额 ×100
 * - 进度   = 已完成流程节点数 / 总节点数 ×100
 * 金额单位为元，前端按万展示。
 */
@Service
public class AggregationService {
  private final MaterialMapper materialMapper;
  private final FinanceRecordMapper financeRecordMapper;
  private final FlowEventMapper flowEventMapper;
  private final BatchMapper batchMapper;
  private final ProjectMapper projectMapper;

  public AggregationService(
      MaterialMapper materialMapper,
      FinanceRecordMapper financeRecordMapper,
      FlowEventMapper flowEventMapper,
      BatchMapper batchMapper,
      ProjectMapper projectMapper
  ) {
    this.materialMapper = materialMapper;
    this.financeRecordMapper = financeRecordMapper;
    this.flowEventMapper = flowEventMapper;
    this.batchMapper = batchMapper;
    this.projectMapper = projectMapper;
  }

  @Transactional
  public void recomputeBatch(String batchId) {
    Batch batch = batchMapper.selectById(batchId);
    if (batch == null) {
      return;
    }
    List<Material> materials = materialMapper.selectList(Wrappers.<Material>lambdaQuery()
        .eq(Material::getBatchId, batchId));
    BigDecimal purchase = BigDecimal.ZERO;
    for (Material material : materials) {
      BigDecimal qty = zero(material.getQuantity());
      BigDecimal unit = material.getSelectedQuote() != null ? material.getSelectedQuote() : zero(material.getBudgetUnitPrice());
      purchase = purchase.add(qty.multiply(unit));
    }

    List<FinanceRecord> finances = financeRecordMapper.selectList(Wrappers.<FinanceRecord>lambdaQuery()
        .eq(FinanceRecord::getBatchId, batchId));
    BigDecimal sales = BigDecimal.ZERO;
    for (FinanceRecord record : finances) {
      if (record.getType() != null && record.getType().contains("收")) {
        sales = sales.add(zero(record.getAmount()));
      }
    }

    BigDecimal profit = sales.subtract(purchase);
    BigDecimal margin = margin(sales, profit);
    int progress = flowProgress(batchId);

    batch.setPurchaseAmount(purchase);
    batch.setSalesAmount(sales);
    batch.setProfitAmount(profit);
    batch.setMarginRate(margin);
    batch.setProgress(progress);
    batch.setUpdatedAt(LocalDateTime.now());
    batchMapper.updateById(batch);

    recomputeProject(batch.getProjectId());
  }

  @Transactional
  public void recomputeProject(String projectId) {
    Project project = projectMapper.selectById(projectId);
    if (project == null) {
      return;
    }
    List<Batch> batches = batchMapper.selectList(Wrappers.<Batch>lambdaQuery()
        .eq(Batch::getProjectId, projectId));
    BigDecimal purchase = BigDecimal.ZERO;
    BigDecimal sales = BigDecimal.ZERO;
    BigDecimal profit = BigDecimal.ZERO;
    int progressSum = 0;
    for (Batch batch : batches) {
      purchase = purchase.add(zero(batch.getPurchaseAmount()));
      sales = sales.add(zero(batch.getSalesAmount()));
      profit = profit.add(zero(batch.getProfitAmount()));
      progressSum += batch.getProgress() == null ? 0 : batch.getProgress();
    }
    project.setPurchaseAmount(purchase);
    project.setSalesAmount(sales);
    project.setProfitAmount(profit);
    project.setMarginRate(margin(sales, profit));
    project.setProgress(batches.isEmpty() ? project.getProgress() : Math.round((float) progressSum / batches.size()));
    project.setUpdatedAt(LocalDateTime.now());
    projectMapper.updateById(project);
  }

  private int flowProgress(String batchId) {
    List<FlowEvent> flows = flowEventMapper.selectList(Wrappers.<FlowEvent>lambdaQuery()
        .eq(FlowEvent::getBatchId, batchId));
    if (flows.isEmpty()) {
      return 0;
    }
    long done = flows.stream().filter(f -> "已完成".equals(f.getStatus())).count();
    return (int) Math.round((double) done * 100 / flows.size());
  }

  private static BigDecimal margin(BigDecimal sales, BigDecimal profit) {
    if (sales == null || sales.compareTo(BigDecimal.ZERO) == 0) {
      return BigDecimal.ZERO;
    }
    return profit.multiply(BigDecimal.valueOf(100)).divide(sales, 1, RoundingMode.HALF_UP);
  }

  private static BigDecimal zero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
