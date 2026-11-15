package com.aibot.purchase.service;

import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.dto.BusinessDtos.MaterialReceiptSummaryDto;
import com.aibot.purchase.dto.BusinessDtos.ProjectReceiptOverviewDto;
import com.aibot.purchase.dto.BusinessDtos.ReceiptAllocationDto;
import com.aibot.purchase.dto.BusinessDtos.ReceiptAllocationInput;
import com.aibot.purchase.dto.BusinessDtos.ReceiptBatchDto;
import com.aibot.purchase.dto.BusinessDtos.ReceiptDto;
import com.aibot.purchase.dto.BusinessDtos.ReceiptSaveRequest;
import com.aibot.purchase.dto.BusinessDtos.ReceiptSuggestionDto;
import com.aibot.purchase.entity.Batch;
import com.aibot.purchase.entity.Project;
import com.aibot.purchase.entity.ProjectReceipt;
import com.aibot.purchase.entity.ProjectReceiptAllocation;
import com.aibot.purchase.mapper.BatchMapper;
import com.aibot.purchase.mapper.ProjectMapper;
import com.aibot.purchase.mapper.ProjectReceiptAllocationMapper;
import com.aibot.purchase.mapper.ProjectReceiptMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 项目级收款（2026-11-15）。
 *
 * <p>业务规则（用户确认）：
 * <ul>
 *   <li>一笔收款关联同项目下的一个或多个批次；收款一般按销售顺序（批次业务日期）进行。</li>
 *   <li>自动分摊：按销售顺序依次把选中批次的「未收」补齐，钱不够时最后一个批次为「部分收款」。</li>
 *   <li>没被选中的批次不参与分摊：下一笔钱只选了批次 4，就只算批次 4，批次 3 之前没收齐的部分不管。</li>
 *   <li>选中批次都收齐后还有余额：余额记在最后一个选中批次上（多收，应收差额会出现负数）。</li>
 *   <li>手动分摊：前端可逐批次改金额，但 Σ 分摊必须等于收款金额。</li>
 *   <li>一个批次只有一种材料，材料取批次创建时填写的材料（batches.material_summary）。</li>
 * </ul>
 * 「已收款」唯一数据源是分摊表；每次变动后调用 {@link LedgerService#refreshReceived} 投影到台账并重算批次/项目。
 */
@Service
public class ReceiptService {
  public static final String STATUS_NONE = "未收款";
  public static final String STATUS_PARTIAL = "部分收款";
  public static final String STATUS_DONE = "已收款";

  private final ProjectMapper projectMapper;
  private final BatchMapper batchMapper;
  private final ProjectReceiptMapper receiptMapper;
  private final ProjectReceiptAllocationMapper allocationMapper;
  private final LedgerService ledgerService;
  private final BusinessQueryService businessQueryService;

  public ReceiptService(
      ProjectMapper projectMapper,
      BatchMapper batchMapper,
      ProjectReceiptMapper receiptMapper,
      ProjectReceiptAllocationMapper allocationMapper,
      LedgerService ledgerService,
      BusinessQueryService businessQueryService
  ) {
    this.projectMapper = projectMapper;
    this.batchMapper = batchMapper;
    this.receiptMapper = receiptMapper;
    this.allocationMapper = allocationMapper;
    this.ledgerService = ledgerService;
    this.businessQueryService = businessQueryService;
  }

  // ---------------------------------------------------------------------------
  // 查询
  // ---------------------------------------------------------------------------

  public ProjectReceiptOverviewDto overview(String userId, String projectId) {
    requireOwnedProject(userId, projectId);
    List<Batch> batches = orderedBatches(projectId);
    Map<String, Batch> batchMap = batches.stream()
        .collect(Collectors.toMap(Batch::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    List<ProjectReceipt> receipts = receiptMapper.selectList(Wrappers.<ProjectReceipt>lambdaQuery()
        .eq(ProjectReceipt::getProjectId, projectId)
        .orderByDesc(ProjectReceipt::getReceiptDate)
        .orderByDesc(ProjectReceipt::getCreatedAt));
    Map<String, List<ProjectReceiptAllocation>> allocationsByReceipt = allocationsOf(
        receipts.stream().map(ProjectReceipt::getId).toList()).stream()
        .filter(a -> batchMap.containsKey(a.getBatchId()))
        .collect(Collectors.groupingBy(ProjectReceiptAllocation::getReceiptId));

    Map<String, BigDecimal> receivedByBatch = new LinkedHashMap<>();
    allocationsByReceipt.values().stream().flatMap(List::stream)
        .forEach(a -> receivedByBatch.merge(a.getBatchId(), nz(a.getAmount()), BigDecimal::add));

    List<ReceiptDto> receiptDtos = receipts.stream()
        .map(r -> toDto(r, allocationsByReceipt.getOrDefault(r.getId(), List.of()), batchMap))
        .toList();
    List<ReceiptBatchDto> batchDtos = batches.stream()
        .map(b -> toBatchDto(b, receivedByBatch.getOrDefault(b.getId(), BigDecimal.ZERO)))
        .toList();

    // 应收 = 全部批次销售金额；收款 = 全部收款金额（含未落到有效批次上的部分，避免漏算）
    BigDecimal sales = batchDtos.stream().map(ReceiptBatchDto::salesAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal received = receipts.stream().map(ProjectReceipt::getAmount).map(ReceiptService::nz)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    return new ProjectReceiptOverviewDto(projectId, sales, received, sales.subtract(received),
        receiptDtos, batchDtos, materialSummaries(batchDtos));
  }

  /**
   * 自动分摊建议：给定收款金额与（可选）批次列表。
   * 不传批次时，按销售顺序从第一个未收齐的批次开始自动勾选，直到钱分完。
   */
  public ReceiptSuggestionDto suggest(
      String userId, String projectId, BigDecimal amount, List<String> batchIds, String excludeReceiptId) {
    requireOwnedProject(userId, projectId);
    BigDecimal total = nz(amount);
    List<Batch> batches = orderedBatches(projectId);
    Map<String, BigDecimal> received = receivedByBatch(projectId, excludeReceiptId);
    List<Batch> candidates;
    if (batchIds == null || batchIds.isEmpty()) {
      candidates = new ArrayList<>();
      BigDecimal left = total;
      for (Batch batch : batches) {
        if (left.signum() <= 0) {
          break;
        }
        BigDecimal remaining = remaining(batch, received);
        if (remaining.signum() <= 0) {
          continue;
        }
        candidates.add(batch);
        left = left.subtract(remaining);
      }
    } else {
      Set<String> wanted = new HashSet<>(batchIds);
      candidates = batches.stream().filter(b -> wanted.contains(b.getId())).toList();
    }
    Allocation result = allocateInOrder(total, candidates, received);
    Map<String, Batch> batchMap = batches.stream()
        .collect(Collectors.toMap(Batch::getId, Function.identity()));
    List<ReceiptAllocationDto> allocations = result.amounts().entrySet().stream()
        .map(e -> new ReceiptAllocationDto(e.getKey(), batchMap.get(e.getKey()).getName(),
            material(batchMap.get(e.getKey())), e.getValue()))
        .toList();
    return new ReceiptSuggestionDto(total, allocations, result.overflow());
  }

  // ---------------------------------------------------------------------------
  // 写入
  // ---------------------------------------------------------------------------

  @Transactional
  public ReceiptDto create(String userId, String projectId, ReceiptSaveRequest request) {
    requireOwnedProject(userId, projectId);
    ProjectReceipt receipt = new ProjectReceipt();
    receipt.setProjectId(projectId);
    fill(receipt, request);
    receiptMapper.insert(receipt);
    Set<String> touched = writeAllocations(receipt, request, null);
    touched.forEach(ledgerService::refreshReceived);
    businessQueryService.addLog(userId, projectId, null, "登记收款",
        "登记收款 " + receipt.getAmount().toPlainString() + " 元，关联 " + touched.size() + " 个批次");
    return single(receipt.getId());
  }

  @Transactional
  public ReceiptDto update(String userId, String projectId, String receiptId, ReceiptSaveRequest request) {
    ProjectReceipt receipt = requireReceipt(userId, projectId, receiptId);
    Set<String> touched = new LinkedHashSet<>(allocationsOf(List.of(receiptId)).stream()
        .map(ProjectReceiptAllocation::getBatchId).toList());
    fill(receipt, request);
    receipt.setUpdatedAt(LocalDateTime.now());
    receiptMapper.updateById(receipt);
    allocationMapper.delete(Wrappers.<ProjectReceiptAllocation>lambdaQuery()
        .eq(ProjectReceiptAllocation::getReceiptId, receiptId));
    touched.addAll(writeAllocations(receipt, request, receiptId));
    touched.forEach(ledgerService::refreshReceived);
    businessQueryService.addLog(userId, projectId, null, "修改收款",
        "修改收款为 " + receipt.getAmount().toPlainString() + " 元");
    return single(receiptId);
  }

  @Transactional
  public void delete(String userId, String projectId, String receiptId) {
    ProjectReceipt receipt = requireReceipt(userId, projectId, receiptId);
    if (StringUtils.hasText(receipt.getSourceDocumentId())) {
      throw new ApiException(HttpStatus.BAD_REQUEST,
          "这笔收款来自智能助理收款单，请到数据中心删除对应收款单");
    }
    List<String> batchIds = allocationsOf(List.of(receiptId)).stream()
        .map(ProjectReceiptAllocation::getBatchId).distinct().toList();
    allocationMapper.delete(Wrappers.<ProjectReceiptAllocation>lambdaQuery()
        .eq(ProjectReceiptAllocation::getReceiptId, receiptId));
    receiptMapper.deleteById(receiptId);
    batchIds.forEach(ledgerService::refreshReceived);
    businessQueryService.addLog(userId, projectId, null, "删除收款",
        "删除收款 " + nz(receipt.getAmount()).toPlainString() + " 元");
  }

  // ---------------------------------------------------------------------------
  // 分摊核心
  // ---------------------------------------------------------------------------

  /** 分摊结果：批次 → 金额（保持销售顺序），以及选中批次全部收齐后多出的钱。 */
  record Allocation(Map<String, BigDecimal> amounts, BigDecimal overflow) {
  }

  /**
   * 按销售顺序依次补齐每个批次的「未收」。钱不够 → 最后一个拿到钱的批次部分收款，其后批次为 0；
   * 全部补齐后还有余额 → 余额加到最后一个选中批次上（多收），overflow 返回该余额供前端提示。
   */
  static Allocation allocateInOrder(
      BigDecimal total, List<Batch> orderedCandidates, Map<String, BigDecimal> received) {
    Map<String, BigDecimal> amounts = new LinkedHashMap<>();
    BigDecimal left = nz(total);
    for (Batch batch : orderedCandidates) {
      BigDecimal take = left.min(remaining(batch, received)).max(BigDecimal.ZERO);
      amounts.put(batch.getId(), take);
      left = left.subtract(take);
    }
    if (left.signum() > 0 && !orderedCandidates.isEmpty()) {
      String last = orderedCandidates.get(orderedCandidates.size() - 1).getId();
      amounts.merge(last, left, BigDecimal::add);
    }
    return new Allocation(amounts, orderedCandidates.isEmpty() ? nz(total) : left.max(BigDecimal.ZERO));
  }

  private Set<String> writeAllocations(ProjectReceipt receipt, ReceiptSaveRequest request, String excludeReceiptId) {
    List<ReceiptAllocationInput> inputs = request.allocations() == null ? List.of() : request.allocations();
    if (inputs.isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "请至少关联一个批次");
    }
    List<Batch> projectBatches = orderedBatches(receipt.getProjectId());
    Map<String, Batch> batchMap = projectBatches.stream()
        .collect(Collectors.toMap(Batch::getId, Function.identity()));
    Set<String> selected = new LinkedHashSet<>();
    for (ReceiptAllocationInput input : inputs) {
      if (!batchMap.containsKey(input.batchId())) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "关联的批次不存在或不属于本项目");
      }
      if (!selected.add(input.batchId())) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "同一个批次不能重复关联");
      }
    }
    BigDecimal total = nz(receipt.getAmount());
    boolean manual = inputs.stream().allMatch(i -> i.amount() != null);
    Map<String, BigDecimal> amounts;
    if (manual) {
      amounts = new LinkedHashMap<>();
      for (ReceiptAllocationInput input : inputs) {
        if (input.amount().signum() < 0) {
          throw new ApiException(HttpStatus.BAD_REQUEST, "分摊金额不能为负数");
        }
        amounts.put(input.batchId(), input.amount());
      }
      BigDecimal sum = amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      if (sum.compareTo(total) != 0) {
        throw new ApiException(HttpStatus.BAD_REQUEST,
            "各批次分摊合计 " + sum.toPlainString() + " 元，与收款金额 " + total.toPlainString() + " 元不一致");
      }
    } else {
      List<Batch> ordered = projectBatches.stream().filter(b -> selected.contains(b.getId())).toList();
      amounts = allocateInOrder(total, ordered, receivedByBatch(receipt.getProjectId(), excludeReceiptId))
          .amounts();
    }
    int order = 0;
    for (Map.Entry<String, BigDecimal> entry : amounts.entrySet()) {
      ProjectReceiptAllocation allocation = new ProjectReceiptAllocation();
      allocation.setReceiptId(receipt.getId());
      allocation.setProjectId(receipt.getProjectId());
      allocation.setBatchId(entry.getKey());
      allocation.setAmount(entry.getValue());
      allocation.setSortOrder(order++);
      allocationMapper.insert(allocation);
    }
    return amounts.keySet();
  }

  // ---------------------------------------------------------------------------
  // 辅助
  // ---------------------------------------------------------------------------

  /** 销售顺序：批次业务日期 → 创建时间（日期为空的排最后）。 */
  private List<Batch> orderedBatches(String projectId) {
    return batchMapper.selectList(Wrappers.<Batch>lambdaQuery().eq(Batch::getProjectId, projectId))
        .stream()
        .sorted(Comparator
            .comparing(Batch::getBusinessDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Batch::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Batch::getName, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  private Map<String, BigDecimal> receivedByBatch(String projectId, String excludeReceiptId) {
    List<String> receiptIds = receiptMapper.selectList(Wrappers.<ProjectReceipt>lambdaQuery()
            .select(ProjectReceipt::getId)
            .eq(ProjectReceipt::getProjectId, projectId))
        .stream().map(ProjectReceipt::getId)
        .filter(id -> !Objects.equals(id, excludeReceiptId))
        .toList();
    Map<String, BigDecimal> result = new LinkedHashMap<>();
    allocationsOf(receiptIds).forEach(a -> result.merge(a.getBatchId(), nz(a.getAmount()), BigDecimal::add));
    return result;
  }

  private List<ProjectReceiptAllocation> allocationsOf(List<String> receiptIds) {
    if (receiptIds == null || receiptIds.isEmpty()) {
      return List.of();
    }
    return allocationMapper.selectList(Wrappers.<ProjectReceiptAllocation>lambdaQuery()
        .in(ProjectReceiptAllocation::getReceiptId, receiptIds)
        .orderByAsc(ProjectReceiptAllocation::getSortOrder));
  }

  private static BigDecimal remaining(Batch batch, Map<String, BigDecimal> received) {
    return nz(batch.getSalesAmount()).subtract(received.getOrDefault(batch.getId(), BigDecimal.ZERO))
        .max(BigDecimal.ZERO);
  }

  static String receiptStatus(BigDecimal sales, BigDecimal received) {
    if (nz(received).signum() <= 0) {
      return STATUS_NONE;
    }
    if (nz(sales).signum() > 0 && nz(received).compareTo(nz(sales)) < 0) {
      return STATUS_PARTIAL;
    }
    return STATUS_DONE;
  }

  private static String material(Batch batch) {
    return StringUtils.hasText(batch.getMaterialSummary()) ? batch.getMaterialSummary().trim() : "未填写材料";
  }

  private ReceiptBatchDto toBatchDto(Batch batch, BigDecimal received) {
    BigDecimal sales = nz(batch.getSalesAmount());
    return new ReceiptBatchDto(batch.getId(), batch.getName(), batch.getBusinessDate(), material(batch),
        sales, received, sales.subtract(received).max(BigDecimal.ZERO),
        receiptStatus(sales, received), Boolean.TRUE.equals(batch.getReconciled()));
  }

  private List<MaterialReceiptSummaryDto> materialSummaries(List<ReceiptBatchDto> batches) {
    Map<String, List<ReceiptBatchDto>> grouped = batches.stream()
        .collect(Collectors.groupingBy(ReceiptBatchDto::material, LinkedHashMap::new, Collectors.toList()));
    return grouped.entrySet().stream().map(e -> {
      BigDecimal sales = e.getValue().stream().map(ReceiptBatchDto::salesAmount)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal received = e.getValue().stream().map(ReceiptBatchDto::receivedAmount)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
      return new MaterialReceiptSummaryDto(e.getKey(), sales, received, sales.subtract(received),
          e.getValue().size());
    }).toList();
  }

  private ReceiptDto toDto(ProjectReceipt receipt, List<ProjectReceiptAllocation> allocations,
      Map<String, Batch> batchMap) {
    List<ReceiptAllocationDto> items = allocations.stream()
        .sorted(Comparator.comparing(ProjectReceiptAllocation::getSortOrder,
            Comparator.nullsLast(Comparator.naturalOrder())))
        .map(a -> {
          Batch batch = batchMap.get(a.getBatchId());
          return new ReceiptAllocationDto(a.getBatchId(),
              batch == null ? "已删除批次" : batch.getName(),
              batch == null ? "-" : material(batch), nz(a.getAmount()));
        })
        .toList();
    return new ReceiptDto(receipt.getId(), receipt.getReceiptDate(), nz(receipt.getAmount()),
        receipt.getPayer(), receipt.getRemark(),
        StringUtils.hasText(receipt.getSourceDocumentId()) ? "document" : "manual", items);
  }

  private ReceiptDto single(String receiptId) {
    ProjectReceipt receipt = receiptMapper.selectById(receiptId);
    Map<String, Batch> batchMap = orderedBatches(receipt.getProjectId()).stream()
        .collect(Collectors.toMap(Batch::getId, Function.identity()));
    return toDto(receipt, allocationsOf(List.of(receiptId)), batchMap);
  }

  private void fill(ProjectReceipt receipt, ReceiptSaveRequest request) {
    if (request.amount() == null || request.amount().signum() <= 0) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "收款金额必须大于 0");
    }
    receipt.setReceiptDate(request.receiptDate() != null ? request.receiptDate() : LocalDate.now());
    receipt.setAmount(request.amount());
    receipt.setPayer(StringUtils.hasText(request.payer()) ? request.payer().trim() : null);
    receipt.setRemark(StringUtils.hasText(request.remark()) ? request.remark().trim() : null);
  }

  private ProjectReceipt requireReceipt(String userId, String projectId, String receiptId) {
    requireOwnedProject(userId, projectId);
    ProjectReceipt receipt = receiptMapper.selectById(receiptId);
    if (receipt == null || !projectId.equals(receipt.getProjectId())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "收款记录不存在");
    }
    return receipt;
  }

  private void requireOwnedProject(String userId, String projectId) {
    Project project = projectMapper.selectOne(Wrappers.<Project>lambdaQuery()
        .eq(Project::getOwnerId, userId)
        .eq(Project::getId, projectId));
    if (project == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "项目不存在");
    }
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
