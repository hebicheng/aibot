package com.aibot.purchase.service;

import com.aibot.purchase.common.InsufficientCreditsException;
import com.aibot.purchase.config.AiProperties;
import com.aibot.purchase.config.BillingProperties;
import com.aibot.purchase.entity.AiUsageLog;
import com.aibot.purchase.entity.Batch;
import com.aibot.purchase.entity.CreditAccount;
import com.aibot.purchase.entity.CreditTransaction;
import com.aibot.purchase.entity.Project;
import com.aibot.purchase.mapper.AiUsageLogMapper;
import com.aibot.purchase.mapper.BatchMapper;
import com.aibot.purchase.mapper.CreditAccountMapper;
import com.aibot.purchase.mapper.CreditTransactionMapper;
import com.aibot.purchase.mapper.ProjectMapper;
import com.aibot.purchase.service.ai.QwenClient.QwenUsage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计费 / 积分服务。
 *
 * <p>换算公式：costCredits = round(totalTokens / 1000 × yuanPerThousandTokens × priceMultiplier × creditsPerYuan)。
 * 默认（qwen-turbo 档）约 1 token = 0.003 积分（1000 token = 3 积分），量级已拉开，客户无感知。
 *
 * <p>余额不足策略（决策：拦截停用）：
 * - 聊天识别：charge 抛 {@link InsufficientCreditsException}，由 ChatService 拦截本次调用；
 * - 后台行情 AI 匹配：调用方自行 try-catch 降级（跳过 AI 分支，不影响已确认单据）。
 */
@Service
public class BillingService {
  private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

  private final BillingProperties properties;
  private final AiProperties aiProperties;
  private final AiUsageLogMapper usageLogMapper;
  private final CreditAccountMapper accountMapper;
  private final CreditTransactionMapper transactionMapper;
  private final BatchMapper batchMapper;
  private final ProjectMapper projectMapper;

  public BillingService(
      BillingProperties properties,
      AiProperties aiProperties,
      AiUsageLogMapper usageLogMapper,
      CreditAccountMapper accountMapper,
      CreditTransactionMapper transactionMapper,
      BatchMapper batchMapper,
      ProjectMapper projectMapper
  ) {
    this.properties = properties;
    this.aiProperties = aiProperties;
    this.usageLogMapper = usageLogMapper;
    this.accountMapper = accountMapper;
    this.transactionMapper = transactionMapper;
    this.batchMapper = batchMapper;
    this.projectMapper = projectMapper;
  }

  /**
   * 记一笔大模型调用并扣减积分。返回本次消耗的积分（0 表示未计费，如 mock 的空用量）。
   *
   * @throws InsufficientCreditsException 余额不足（聊天场景据此拦截）
   */
  @Transactional
  public BigDecimal charge(String userId, String projectId, String batchId,
                     String businessType, String model, QwenUsage usage, String refMessageId) {
    long totalTokens = usage == null ? 0 : usage.totalTokens();
    BigDecimal cost = computeCost(totalTokens);
    if (cost.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    CreditAccount account = getOrCreateAccount(userId);

    BigDecimal balance = account.getBalance() == null ? BigDecimal.ZERO : account.getBalance();
    if (balance.compareTo(cost) < 0) {
      throw new InsufficientCreditsException(
          String.format("积分余额不足，本次需 %s，剩余 %s", cost.toPlainString(), balance.toPlainString()));
    }

    account.setBalance(balance.subtract(cost));
    account.setTotalUsed((account.getTotalUsed() == null ? BigDecimal.ZERO : account.getTotalUsed()).add(cost));
    account.setUpdatedAt(LocalDateTime.now());
    accountMapper.updateById(account);

    String resolvedModel = (model != null && !model.isBlank()) ? model : aiProperties.qwen().model();
    AiUsageLog log = new AiUsageLog();
    log.setUserId(userId);
    log.setProjectId(projectId);
    log.setBatchId(batchId);
    log.setModel(resolvedModel);
    log.setBusinessType(businessType);
    log.setPromptTokens(usage.promptTokens());
    log.setCompletionTokens(usage.completionTokens());
    log.setTotalTokens(totalTokens);
    log.setBillTokens(cost);
    log.setCostCredits(cost);
    log.setRefMessageId(refMessageId);
    log.setCreatedAt(LocalDateTime.now());
    usageLogMapper.insert(log);

    CreditTransaction tx = new CreditTransaction();
    tx.setUserId(userId);
    tx.setDelta(cost.negate());
    tx.setType("consume");
    tx.setRefUsageLogId(log.getId());
    tx.setRemark(businessType);
    tx.setCreatedAt(LocalDateTime.now());
    transactionMapper.insert(tx);

    return cost;
  }

  /**
   * 市场行情 AI 匹配用的计费：根据 batchId 解析归属用户后记账。
   * 与 chat 不同，这里不抛异常——余额不足时仅记日志并跳过（降级）。
   */
  public BigDecimal chargeMarketMatch(String batchId, QwenUsage usage, String model) {
    if (usage == null || usage.totalTokens() <= 0) {
      return BigDecimal.ZERO;
    }
    String userId = resolveOwnerId(batchId);
    if (userId == null) {
      return BigDecimal.ZERO;
    }
    try {
      return charge(userId, null, batchId, "market_match", model, usage, null);
    } catch (InsufficientCreditsException ex) {
      org.slf4j.LoggerFactory.getLogger(BillingService.class)
          .warn("行情 AI 匹配积分不足，跳过计费：{}", ex.getMessage());
      return BigDecimal.ZERO;
    }
  }

  /** 材料判同（AI 归并）计费：余额不足时抛出，调用方不落库判定结果。 */
  @Transactional
  public BigDecimal chargeMaterialMatch(String batchId, QwenUsage usage, String model)
      throws InsufficientCreditsException {
    if (usage == null || usage.totalTokens() <= 0) {
      return BigDecimal.ZERO;
    }
    String userId = resolveOwnerId(batchId);
    if (userId == null) {
      return BigDecimal.ZERO;
    }
    return charge(userId, null, batchId, "material_match", model, usage, null);
  }

  /** 材料级网价绑定（AI 选行情）计费：余额不足时抛出，调用方不落库绑定。 */
  @Transactional
  public BigDecimal chargeMaterialQuote(String batchId, QwenUsage usage, String model)
      throws InsufficientCreditsException {
    if (usage == null || usage.totalTokens() <= 0) {
      return BigDecimal.ZERO;
    }
    String userId = resolveOwnerId(batchId);
    if (userId == null) {
      return BigDecimal.ZERO;
    }
    return charge(userId, null, batchId, "material_quote", model, usage, null);
  }

  /** 当前积分账户快照 + 本月已用 */
  public CreditSnapshot snapshot(String userId) {
    CreditAccount account = getOrCreateAccount(userId);
    BigDecimal usedThisMonth = usageLogMapper.sumCostCreditsSince(
        userId, account.getPeriodStart() == null ? LocalDateTime.now().minusMonths(1) : account.getPeriodStart());
    return new CreditSnapshot(
        account.getBalance(),
        account.getMonthlyQuota(),
        account.getTotalUsed(),
        account.getPeriodMonth(),
        account.getPeriodStart(),
        account.getPeriodEnd(),
        usedThisMonth
    );
  }

  /** 分页查询消费明细 */
  public List<AiUsageLog> usageLogs(String userId, long limit, long offset) {
    return usageLogMapper.selectList(Wrappers.<AiUsageLog>lambdaQuery()
        .eq(AiUsageLog::getUserId, userId)
        .orderByDesc(AiUsageLog::getCreatedAt)
        .last("LIMIT " + offset + ", " + limit));
  }

  /** 消费明细总数 */
  public long countUsageLogs(String userId) {
    return usageLogMapper.selectCount(Wrappers.<AiUsageLog>lambdaQuery()
        .eq(AiUsageLog::getUserId, userId));
  }

  // ---------------------------------------------------------------------------
  // 每月刷新：每月 1 号 0 点把余额重置为固定月额度（未用完作废）
  // ---------------------------------------------------------------------------
  @Transactional
  @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 0 1 * ?")
  public void monthlyRefresh() {
    List<CreditAccount> accounts = accountMapper.selectList(null);
    LocalDateTime now = LocalDateTime.now();
    String month = YearMonth.from(now.toLocalDate()).format(MONTH_FMT);
    for (CreditAccount account : accounts) {
      account.setBalance(properties.monthlyQuota());
      account.setTotalGranted((account.getTotalGranted() == null ? BigDecimal.ZERO : account.getTotalGranted())
          .add(properties.monthlyQuota()));
      account.setPeriodMonth(month);
      account.setPeriodStart(firstDayOfMonth(now));
      account.setPeriodEnd(firstDayOfMonth(now.plusMonths(1)));
      account.setUpdatedAt(now);
      accountMapper.updateById(account);

      CreditTransaction tx = new CreditTransaction();
      tx.setUserId(account.getUserId());
      tx.setDelta(properties.monthlyQuota());
      tx.setType("grant");
      tx.setRemark("monthly:" + month);
      tx.setCreatedAt(now);
      transactionMapper.insert(tx);
    }
  }

  // ---------------------------------------------------------------------------
  // 内部
  // ---------------------------------------------------------------------------

  private BigDecimal computeCost(long totalTokens) {
    BigDecimal raw = BigDecimal.valueOf(totalTokens)
        .divide(BigDecimal.valueOf(1000), 10, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(properties.yuanPerThousandTokens()))
        .multiply(BigDecimal.valueOf(properties.priceMultiplier()))
        .multiply(BigDecimal.valueOf(properties.creditsPerYuan()));
    return raw.setScale(2, RoundingMode.HALF_UP);
  }

  private CreditAccount getOrCreateAccount(String userId) {
    CreditAccount account = accountMapper.selectById(userId);
    if (account != null) {
      return account;
    }
    LocalDateTime now = LocalDateTime.now();
    String month = YearMonth.from(now.toLocalDate()).format(MONTH_FMT);
    account = new CreditAccount();
    account.setUserId(userId);
    account.setBalance(properties.monthlyQuota());
    account.setTotalGranted(properties.monthlyQuota());
    account.setTotalUsed(BigDecimal.ZERO);
    account.setMonthlyQuota(properties.monthlyQuota());
    account.setPeriodMonth(month);
    account.setPeriodStart(firstDayOfMonth(now));
    account.setPeriodEnd(firstDayOfMonth(now.plusMonths(1)));
    account.setCreatedAt(now);
    account.setUpdatedAt(now);
    accountMapper.insert(account);
    return account;
  }

  private String resolveOwnerId(String batchId) {
    if (batchId == null) {
      return null;
    }
    Batch batch = batchMapper.selectById(batchId);
    if (batch == null || batch.getProjectId() == null) {
      return null;
    }
    Project project = projectMapper.selectById(batch.getProjectId());
    return project == null ? null : project.getOwnerId();
  }

  private static LocalDateTime firstDayOfMonth(LocalDateTime time) {
    return time.toLocalDate().withDayOfMonth(1).atStartOfDay();
  }

  @Data
  public static class CreditSnapshot {
    private final BigDecimal balance;
    private final BigDecimal monthlyQuota;
    private final BigDecimal totalUsed;
    private final String periodMonth;
    private final LocalDateTime periodStart;
    private final LocalDateTime periodEnd;
    private final BigDecimal usedThisMonth;
  }
}
