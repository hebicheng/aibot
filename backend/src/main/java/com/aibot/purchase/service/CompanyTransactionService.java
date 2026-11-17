package com.aibot.purchase.service;

import com.aibot.purchase.entity.Batch;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.Project;
import com.aibot.purchase.mapper.BatchMapper;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.mapper.ProjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 公司往来（2026-11-17）：本公司与各供货商的进项发票往来。
 * <ul>
 *   <li>采购总额 = 已确认采购单金额（与台账采购金额同口径，不含运费）；</li>
 *   <li>已开票 = 已确认（入账）的进项发票金额；</li>
 *   <li>未开票 = 采购总额 − 已开票（可为负：多开票）。</li>
 * </ul>
 * 只统计当前用户名下、未删除项目 / 批次的单据；projectId 为空时统计全部项目。
 */
@Service
public class CompanyTransactionService {
  static final List<String> INVOICE_TYPES = List.of("purchase_invoice", "invoice");
  static final Set<String> CONFIRMED = Set.of("matched", "archived");
  static final String UNKNOWN_SUPPLIER = "未识别供货商";
  /** 公司往来页上传时写入的识别提示前缀，用于区分「手动上传」与智能助理对话中识别的发票 */
  public static final String MANUAL_UPLOAD_HINT = "公司往来上传";

  private final ProjectMapper projectMapper;
  private final BatchMapper batchMapper;
  private final DocumentMapper documentMapper;
  private final LedgerService ledgerService;
  private final ObjectMapper objectMapper;

  public CompanyTransactionService(
      ProjectMapper projectMapper,
      BatchMapper batchMapper,
      DocumentMapper documentMapper,
      LedgerService ledgerService,
      ObjectMapper objectMapper
  ) {
    this.projectMapper = projectMapper;
    this.batchMapper = batchMapper;
    this.documentMapper = documentMapper;
    this.ledgerService = ledgerService;
    this.objectMapper = objectMapper;
  }

  public record SupplierSummary(
      String supplierName,
      BigDecimal purchaseAmount,
      BigDecimal invoicedAmount,
      BigDecimal uninvoicedAmount,
      int invoiceCount
  ) {
  }

  public record InvoiceRow(
      String id,
      String invoiceNo,
      String supplierName,
      LocalDate invoiceDate,
      BigDecimal amount,
      BigDecimal taxAmount,
      String taxRate,
      String status,
      boolean confirmed,
      /** 疑似重复且尚未决定保留/跳过：需先处理才能入账 */
      boolean duplicatePending,
      String projectId,
      String projectName,
      String batchId,
      String batchName,
      String fileName,
      boolean hasFile,
      /** manual=公司往来页手动上传 / assistant=智能助理中识别 */
      String uploadSource,
      String remark
  ) {
  }

  public record Overview(
      BigDecimal purchaseAmount,
      BigDecimal invoicedAmount,
      BigDecimal uninvoicedAmount,
      int supplierCount,
      int invoiceCount,
      int pendingInvoiceCount,
      /** 已确认 / 待确认采购单张数（采购总额只计已确认） */
      int supplyCount,
      int pendingSupplyCount,
      List<SupplierSummary> suppliers,
      List<InvoiceRow> invoices
  ) {
  }

  public Overview overview(String userId, String projectId) {
    List<Project> projects = projectMapper.selectList(Wrappers.<Project>lambdaQuery()
        .eq(Project::getOwnerId, userId)
        .eq(StringUtils.hasText(projectId), Project::getId, projectId));
    if (projects.isEmpty()) {
      return new Overview(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, 0, List.of(), List.of());
    }
    Map<String, Project> projectById = projects.stream()
        .collect(Collectors.toMap(Project::getId, Function.identity()));
    List<Batch> batches = batchMapper.selectList(Wrappers.<Batch>lambdaQuery()
        .in(Batch::getProjectId, projectById.keySet()));
    if (batches.isEmpty()) {
      return new Overview(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, 0, List.of(), List.of());
    }
    Map<String, Batch> batchById = batches.stream()
        .collect(Collectors.toMap(Batch::getId, Function.identity()));
    List<Document> docs = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getUserId, userId)
        .in(Document::getBatchId, batchById.keySet())
        .in(Document::getDocType, List.of("supply_order", "purchase_invoice", "invoice")));
    return build(docs, projectById, batchById, ledgerService::supplyPurchase);
  }

  /** 纯计算（便于单测）：purchaseOf 返回单张采购单的采购金额。 */
  Overview build(
      List<Document> docs,
      Map<String, Project> projectById,
      Map<String, Batch> batchById,
      Function<Document, BigDecimal> purchaseOf
  ) {
    Map<String, BigDecimal[]> bySupplier = new LinkedHashMap<>();
    Map<String, Integer> invoiceCount = new LinkedHashMap<>();
    List<InvoiceRow> invoices = new ArrayList<>();
    int pending = 0;
    int supplyCount = 0;
    int pendingSupply = 0;
    for (Document doc : docs) {
      // 用户选择「跳过」的重复资料会被归档为 archived，不是有效单据，不计入也不展示
      if ("skip".equals(doc.getDuplicateDecision())) {
        continue;
      }
      boolean confirmed = CONFIRMED.contains(doc.getStatus());
      boolean duplicatePending = StringUtils.hasText(doc.getDuplicateOfDocumentId())
          && !StringUtils.hasText(doc.getDuplicateDecision());
      String supplier = supplierKey(doc.getSupplierName());
      if ("supply_order".equals(doc.getDocType())) {
        if (confirmed) {
          supplyCount++;
          BigDecimal[] sums = bySupplier.computeIfAbsent(supplier, k -> zeros());
          sums[0] = sums[0].add(nz(purchaseOf.apply(doc)));
        } else {
          pendingSupply++;
        }
        continue;
      }
      if (!INVOICE_TYPES.contains(doc.getDocType())) {
        continue;
      }
      Map<String, Object> data = structured(doc);
      Batch batch = batchById.get(doc.getBatchId());
      Project project = batch == null ? null : projectById.get(batch.getProjectId());
      invoices.add(new InvoiceRow(
          doc.getId(),
          StringUtils.hasText(doc.getDocCode()) ? doc.getDocCode() : text(data, "invoiceNo"),
          supplier,
          doc.getDocDate(),
          nz(doc.getAmount()),
          decimal(data.get("taxAmount")),
          text(data, "taxRate"),
          doc.getStatus(),
          confirmed,
          duplicatePending,
          project == null ? null : project.getId(),
          project == null ? null : project.getName(),
          doc.getBatchId(),
          batch == null ? null : batch.getName(),
          doc.getFileName(),
          StringUtils.hasText(doc.getFileObjectKey()) || StringUtils.hasText(doc.getFileUrl()),
          doc.getGuidanceText() != null && doc.getGuidanceText().startsWith(MANUAL_UPLOAD_HINT)
              ? "manual" : "assistant",
          doc.getRemark()
      ));
      if (confirmed) {
        BigDecimal[] sums = bySupplier.computeIfAbsent(supplier, k -> zeros());
        sums[1] = sums[1].add(nz(doc.getAmount()));
        invoiceCount.merge(supplier, 1, Integer::sum);
      } else {
        pending++;
      }
    }
    List<SupplierSummary> suppliers = bySupplier.entrySet().stream()
        .map(e -> new SupplierSummary(e.getKey(), e.getValue()[0], e.getValue()[1],
            e.getValue()[0].subtract(e.getValue()[1]), invoiceCount.getOrDefault(e.getKey(), 0)))
        .sorted(Comparator.comparing(SupplierSummary::purchaseAmount).reversed())
        .toList();
    BigDecimal purchase = suppliers.stream().map(SupplierSummary::purchaseAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal invoiced = suppliers.stream().map(SupplierSummary::invoicedAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    invoices.sort(Comparator.comparing(InvoiceRow::invoiceDate,
        Comparator.nullsLast(Comparator.reverseOrder())));
    int confirmedInvoices = invoices.size() - pending;
    return new Overview(purchase, invoiced, purchase.subtract(invoiced), suppliers.size(),
        confirmedInvoices, pending, supplyCount, pendingSupply, suppliers, invoices);
  }

  private static BigDecimal[] zeros() {
    return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};
  }

  /** 供货商名称归一：去空白；空名称归到「未识别供货商」。 */
  static String supplierKey(String name) {
    if (!StringUtils.hasText(name)) {
      return UNKNOWN_SUPPLIER;
    }
    return name.replaceAll("\\s+", "").replace('（', '(').replace('）', ')');
  }

  private Map<String, Object> structured(Document doc) {
    if (!StringUtils.hasText(doc.getStructuredData())) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(doc.getStructuredData(), new TypeReference<>() {
      });
    } catch (Exception ignored) {
      return Map.of();
    }
  }

  private static String text(Map<String, Object> data, String key) {
    Object value = data.get(key);
    return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
  }

  private static BigDecimal decimal(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return new BigDecimal(String.valueOf(value).replace(",", "").trim());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
