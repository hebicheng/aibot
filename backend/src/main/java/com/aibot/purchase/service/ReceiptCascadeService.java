package com.aibot.purchase.service;

import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.ProjectReceipt;
import com.aibot.purchase.entity.ProjectReceiptAllocation;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.mapper.ProjectReceiptAllocationMapper;
import com.aibot.purchase.mapper.ProjectReceiptMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 删除项目/批次时删除整笔相关收款；调用方须先校验归属，且在批次软删前调用。 */
@Service
public class ReceiptCascadeService {
  private final ProjectReceiptMapper receiptMapper;
  private final ProjectReceiptAllocationMapper allocationMapper;
  private final DocumentMapper documentMapper;
  private final DocumentService documentService;
  private final LedgerService ledgerService;

  public ReceiptCascadeService(ProjectReceiptMapper receiptMapper,
      ProjectReceiptAllocationMapper allocationMapper, DocumentMapper documentMapper,
      @Lazy DocumentService documentService, @Lazy LedgerService ledgerService) {
    this.receiptMapper = receiptMapper;
    this.allocationMapper = allocationMapper;
    this.documentMapper = documentMapper;
    this.documentService = documentService;
    this.ledgerService = ledgerService;
  }

  @Transactional
  public int deleteForBatch(String userId, String projectId, String batchId) {
    // 零元关联也属于本批次；同时覆盖源单据在本批次但分摊已移到其他批次的收款。
    Set<String> ids = new LinkedHashSet<>();
    allocationMapper.selectList(Wrappers.<ProjectReceiptAllocation>lambdaQuery()
        .eq(ProjectReceiptAllocation::getProjectId, projectId)
        .eq(ProjectReceiptAllocation::getBatchId, batchId))
        .forEach(a -> ids.add(a.getReceiptId()));
    List<ProjectReceipt> receipts = receiptMapper.selectList(Wrappers.<ProjectReceipt>lambdaQuery()
        .eq(ProjectReceipt::getProjectId, projectId)
        .and(q -> q.in(!ids.isEmpty(), ProjectReceipt::getId, ids)
            .or(!ids.isEmpty()).apply(
                "source_document_id IN (SELECT id FROM documents WHERE batch_id = {0})", batchId)));
    receipts.forEach(r -> ids.add(r.getId()));
    List<Document> documents = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getUserId, userId).eq(Document::getProjectId, projectId)
        .eq(Document::getBatchId, batchId).eq(Document::getDocType, "receipt"));
    return deleteWholeReceipts(userId, projectId, receipts, ids, documents);
  }

  @Transactional
  public int deleteForProject(String userId, String projectId) {
    List<ProjectReceipt> receipts = receiptMapper.selectList(Wrappers.<ProjectReceipt>lambdaQuery()
        .eq(ProjectReceipt::getProjectId, projectId));
    Set<String> ids = new LinkedHashSet<>();
    receipts.forEach(r -> ids.add(r.getId()));
    // 连旧的已软删收款残留分摊也清理，避免恢复/汇总时回流。
    allocationMapper.selectList(Wrappers.<ProjectReceiptAllocation>lambdaQuery()
        .eq(ProjectReceiptAllocation::getProjectId, projectId))
        .forEach(a -> ids.add(a.getReceiptId()));
    List<Document> documents = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getUserId, userId).eq(Document::getProjectId, projectId)
        .eq(Document::getDocType, "receipt"));
    return deleteWholeReceipts(userId, projectId, receipts, ids, documents);
  }

  private int deleteWholeReceipts(String userId, String projectId,
      List<ProjectReceipt> receipts, Set<String> receiptIds, List<Document> scopedDocuments) {
    Set<String> touchedBatches = new LinkedHashSet<>();
    if (!receiptIds.isEmpty()) {
      allocationMapper.selectList(Wrappers.<ProjectReceiptAllocation>lambdaQuery()
          .in(ProjectReceiptAllocation::getReceiptId, receiptIds))
          .forEach(a -> {
            if (!projectId.equals(a.getProjectId())) {
              throw new ApiException(HttpStatus.CONFLICT, "收款分摊跨项目关联异常，删除已取消，请核对数据");
            }
            touchedBatches.add(a.getBatchId());
          });
    }
    Set<String> documentIds = new LinkedHashSet<>();
    scopedDocuments.forEach(d -> documentIds.add(d.getId()));
    for (ProjectReceipt receipt : receipts) {
      if (StringUtils.hasText(receipt.getSourceDocumentId())) {
        documentIds.add(receipt.getSourceDocumentId());
      }
    }
    List<Document> activeDocuments = new ArrayList<>();
    for (String documentId : documentIds) {
      Document document = documentMapper.selectById(documentId);
      if (document == null) {
        continue; // 来源已软删时只清理残留收款和分摊。
      }
      if (!userId.equals(document.getUserId()) || !projectId.equals(document.getProjectId())
          || !"receipt".equals(document.getDocType())) {
        throw new ApiException(HttpStatus.CONFLICT, "收款来源单据关联异常，删除已取消，请核对数据");
      }
      // 旧删除流程按源单据 ID 删除收款：提前阻止异常的跨项目共用来源，不能越权回退别的项目。
      if (receiptMapper.exists(Wrappers.<ProjectReceipt>lambdaQuery()
          .eq(ProjectReceipt::getSourceDocumentId, documentId)
          .ne(ProjectReceipt::getProjectId, projectId))) {
        throw new ApiException(HttpStatus.CONFLICT, "收款来源跨项目共用异常，删除已取消，请核对数据");
      }
      activeDocuments.add(document);
    }
    // 所有来源先校验，再执行写入；现有流程回退财务投影并更新聊天卡片，保留原始附件。
    for (Document document : activeDocuments) {
      documentService.deleteDocument(userId, document.getId());
    }
    if (!receiptIds.isEmpty()) {
      allocationMapper.delete(Wrappers.<ProjectReceiptAllocation>lambdaQuery()
          .in(ProjectReceiptAllocation::getReceiptId, receiptIds));
      receiptMapper.delete(Wrappers.<ProjectReceipt>lambdaQuery()
          .eq(ProjectReceipt::getProjectId, projectId).in(ProjectReceipt::getId, receiptIds));
      // 不仅刷新待删批次，其他共享该笔收款的批次也必须同步减少已收款。
      touchedBatches.forEach(ledgerService::refreshReceived);
    }
    return receipts.size();
  }
}
