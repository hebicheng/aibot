package com.aibot.purchase.service;

import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.dto.BusinessDtos.BatchCreateRequest;
import com.aibot.purchase.dto.BusinessDtos.BatchDetailDto;
import com.aibot.purchase.dto.BusinessDtos.BatchDto;
import com.aibot.purchase.dto.BusinessDtos.BatchUpdateRequest;
import com.aibot.purchase.dto.BusinessDtos.ChartPointDto;
import com.aibot.purchase.dto.BusinessDtos.DashboardOverviewDto;
import com.aibot.purchase.dto.BusinessDtos.FileRecordDto;
import com.aibot.purchase.dto.BusinessDtos.FinanceRecordDto;
import com.aibot.purchase.dto.BusinessDtos.FinanceSaveRequest;
import com.aibot.purchase.dto.BusinessDtos.FlowEventDto;
import com.aibot.purchase.dto.BusinessDtos.KpiDto;
import com.aibot.purchase.dto.BusinessDtos.MaterialDto;
import com.aibot.purchase.dto.BusinessDtos.MaterialSaveRequest;
import com.aibot.purchase.dto.BusinessDtos.OperationLogDto;
import com.aibot.purchase.dto.BusinessDtos.ProjectCreateRequest;
import com.aibot.purchase.dto.BusinessDtos.ProjectDto;
import com.aibot.purchase.dto.BusinessDtos.ProjectUpdateRequest;
import com.aibot.purchase.dto.BusinessDtos.QuoteDto;
import com.aibot.purchase.dto.BusinessDtos.ReportDto;
import com.aibot.purchase.dto.BusinessDtos.SupplierDto;
import com.aibot.purchase.dto.BusinessDtos.SupplierSummaryDto;
import com.aibot.purchase.entity.Batch;
import com.aibot.purchase.entity.FileRecord;
import com.aibot.purchase.entity.FinanceRecord;
import com.aibot.purchase.entity.FlowEvent;
import com.aibot.purchase.entity.LedgerRecord;
import com.aibot.purchase.entity.Material;
import com.aibot.purchase.entity.MaterialMarketMapping;
import com.aibot.purchase.entity.OperationLog;
import com.aibot.purchase.entity.Project;
import com.aibot.purchase.entity.ProjectReceiptAllocation;
import com.aibot.purchase.entity.Supplier;
import com.aibot.purchase.entity.SupplierQuote;
import com.aibot.purchase.mapper.BatchMapper;
import com.aibot.purchase.mapper.FileRecordMapper;
import com.aibot.purchase.mapper.FinanceRecordMapper;
import com.aibot.purchase.mapper.FlowEventMapper;
import com.aibot.purchase.mapper.LedgerRecordMapper;
import com.aibot.purchase.mapper.MaterialMapper;
import com.aibot.purchase.mapper.MaterialMarketMappingMapper;
import com.aibot.purchase.mapper.OperationLogMapper;
import com.aibot.purchase.mapper.ProjectMapper;
import com.aibot.purchase.mapper.ProjectReceiptAllocationMapper;
import com.aibot.purchase.mapper.SupplierMapper;
import com.aibot.purchase.mapper.SupplierQuoteMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class BusinessQueryService {
  private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);

  private final ProjectMapper projectMapper;
  private final BatchMapper batchMapper;
  private final MaterialMapper materialMapper;
  private final SupplierMapper supplierMapper;
  private final SupplierQuoteMapper supplierQuoteMapper;
  private final FlowEventMapper flowEventMapper;
  private final FinanceRecordMapper financeRecordMapper;
  private final FileRecordMapper fileRecordMapper;
  private final OperationLogMapper operationLogMapper;
  private final MaterialMarketMappingMapper materialMarketMappingMapper;
  private final LedgerRecordMapper ledgerRecordMapper;
  private final AggregationService aggregationService;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
  /** 批次口径变更后重算送货单销售价（毛利率口径的定价基数 = 采购价，口径一变锁定值要跟着变）。 */
  private final com.aibot.purchase.service.market.DeliveryNetPriceService deliveryNetPriceService;

  private final ProjectReceiptAllocationMapper allocationMapper;

  public BusinessQueryService(
      ProjectMapper projectMapper,
      BatchMapper batchMapper,
      MaterialMapper materialMapper,
      SupplierMapper supplierMapper,
      SupplierQuoteMapper supplierQuoteMapper,
      FlowEventMapper flowEventMapper,
      FinanceRecordMapper financeRecordMapper,
      FileRecordMapper fileRecordMapper,
      OperationLogMapper operationLogMapper,
      MaterialMarketMappingMapper materialMarketMappingMapper,
      LedgerRecordMapper ledgerRecordMapper,
      AggregationService aggregationService,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper,
      @org.springframework.context.annotation.Lazy
      com.aibot.purchase.service.market.DeliveryNetPriceService deliveryNetPriceService,
      ProjectReceiptAllocationMapper allocationMapper
  ) {
    this.allocationMapper = allocationMapper;
    this.projectMapper = projectMapper;
    this.batchMapper = batchMapper;
    this.materialMapper = materialMapper;
    this.supplierMapper = supplierMapper;
    this.supplierQuoteMapper = supplierQuoteMapper;
    this.flowEventMapper = flowEventMapper;
    this.financeRecordMapper = financeRecordMapper;
    this.fileRecordMapper = fileRecordMapper;
    this.operationLogMapper = operationLogMapper;
    this.materialMarketMappingMapper = materialMarketMappingMapper;
    this.ledgerRecordMapper = ledgerRecordMapper;
    this.aggregationService = aggregationService;
    this.objectMapper = objectMapper;
    this.deliveryNetPriceService = deliveryNetPriceService;
  }

  public DashboardOverviewDto dashboard(String ownerId) {
    List<ProjectDto> projects = listProjects(ownerId, null);
    List<BatchDto> batches = listBatches(ownerId, null);
    List<OperationLogDto> histories = operationLogs(ownerId, 20);
    BigDecimal purchase = projects.stream().map(ProjectDto::purchaseAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal sales = projects.stream().map(ProjectDto::salesAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal profit = projects.stream().map(ProjectDto::profitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    List<KpiDto> kpis = List.of(
        new KpiDto("项目总数", projects.size() + "个", "进行中 " + countStatus(projects, "进行中") + "个"),
        new KpiDto("采购总额", moneyWan(purchase), "较上月 +12.5%"),
        new KpiDto("销售总额", moneyWan(sales), "较上月 +8.3%"),
        new KpiDto("毛利润", moneyWan(profit), "综合毛利率 " + margin(sales, profit) + "%")
    );
    return new DashboardOverviewDto(kpis, projects, batches, histories);
  }

  public List<ProjectDto> listProjects(String ownerId, String query) {
    String keyword = query == null ? "" : query.trim();
    List<Project> projects = projectMapper.selectList(Wrappers.<Project>lambdaQuery()
        .eq(Project::getOwnerId, ownerId)
        .and(StringUtils.hasText(keyword), wrapper -> wrapper
            .like(Project::getName, keyword)
            .or()
            .like(Project::getCustomerName, keyword))
        .orderByDesc(Project::getUpdatedAt));
    if (projects.isEmpty()) {
      return List.of();
    }
    List<Batch> projectBatches = batchMapper.selectList(Wrappers.<Batch>lambdaQuery()
        .in(Batch::getProjectId, projects.stream().map(Project::getId).toList()));
    Map<String, Long> batchCounts = projectBatches.stream()
        .collect(Collectors.groupingBy(Batch::getProjectId, Collectors.counting()));
    Map<String, ProjectFinance> finance = projectFinance(projectBatches);
    return projects.stream()
        .map(project -> mapProject(
            project,
            batchCounts.getOrDefault(project.getId(), 0L).intValue(),
            finance.getOrDefault(project.getId(), ProjectFinance.ZERO)))
        .toList();
  }

  /**
   * 项目级资金 / 票据汇总：从台账行累加收付款与进销项开票金额。
   *
   * <p>台账行没有软删标记，批次删除后行记录仍在库中，因此这里只按<b>有效批次</b>过滤，
   * 避免已删批次的金额回流到项目统计。项目下没有批次时返回空表，调用方取零值。
   */
  private Map<String, ProjectFinance> projectFinance(List<Batch> batches) {
    if (batches.isEmpty()) {
      return Map.of();
    }
    Map<String, String> batchToProject = batches.stream()
        .collect(Collectors.toMap(Batch::getId, Batch::getProjectId, (first, second) -> first));
    // 只取聚合需要的四列，避免把台账全字段拉进内存
    List<LedgerRecord> rows = ledgerRecordMapper.selectList(Wrappers.<LedgerRecord>lambdaQuery()
        .select(
            LedgerRecord::getBatchId,
            LedgerRecord::getPaidAmount,
            LedgerRecord::getInvoiceAmount,
            LedgerRecord::getSalesInvoiceAmount,
            LedgerRecord::getReceivedAmount)
        .in(LedgerRecord::getBatchId, batchToProject.keySet()));
    Map<String, ProjectFinance> result = new LinkedHashMap<>();
    for (LedgerRecord row : rows) {
      String projectId = batchToProject.get(row.getBatchId());
      if (projectId == null) {
        continue;
      }
      ProjectFinance current = result.getOrDefault(projectId, ProjectFinance.ZERO);
      result.put(projectId, new ProjectFinance(
          current.paid().add(zero(row.getPaidAmount())),
          current.invoiced().add(zero(row.getInvoiceAmount())),
          current.salesInvoiced().add(zero(row.getSalesInvoiceAmount())),
          current.received()));
    }
    // 总收款取项目收款分摊（2026-11-15）：批次还没有台账行时收款也要计入，不能只看台账投影
    batchReceived(new ArrayList<>(batchToProject.keySet())).forEach((batchId, amount) -> {
      String projectId = batchToProject.get(batchId);
      ProjectFinance current = result.getOrDefault(projectId, ProjectFinance.ZERO);
      result.put(projectId, new ProjectFinance(current.paid(), current.invoiced(),
          current.salesInvoiced(), current.received().add(amount)));
    });
    return result;
  }

  public ProjectDto getProject(String ownerId, String id) {
    Project project = projectMapper.selectOne(Wrappers.<Project>lambdaQuery()
        .eq(Project::getOwnerId, ownerId)
        .eq(Project::getId, id));
    if (project == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "项目不存在");
    }
    List<Batch> batches = batchMapper.selectList(Wrappers.<Batch>lambdaQuery()
        .eq(Batch::getProjectId, id));
    return mapProject(project, batches.size(),
        projectFinance(batches).getOrDefault(id, ProjectFinance.ZERO));
  }

  @Transactional
  public ProjectDto createProject(String ownerId, ProjectCreateRequest request) {
    Project project = new Project();
    project.setOwnerId(ownerId);
    project.setName(request.name());
    project.setCustomerName(StringUtils.hasText(request.customerName()) ? request.customerName() : "客户公司名称");
    project.setStatus("进行中");
    project.setProgress(0);
    projectMapper.insert(project);
    addLog(ownerId, project.getId(), null, "新建项目", "新建项目：" + request.name());
    return getProject(ownerId, project.getId());
  }

  /**
   * 编辑项目基础信息（项目名称 / 建设-采购单位）。
   * 业务数据均按 id 关联、名称实时取值，改名无需回写任何单据。
   */
  @Transactional
  public ProjectDto updateProject(String ownerId, String id, ProjectUpdateRequest request) {
    Project project = projectMapper.selectOne(Wrappers.<Project>lambdaQuery()
        .eq(Project::getOwnerId, ownerId)
        .eq(Project::getId, id));
    if (project == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "项目不存在");
    }
    if (StringUtils.hasText(request.name()) && !request.name().trim().equals(project.getName())) {
      project.setName(request.name().trim());
    }
    if (request.customerName() != null) {
      project.setCustomerName(StringUtils.hasText(request.customerName())
          ? request.customerName().trim() : "客户公司名称");
    }
    projectMapper.updateById(project);
    addLog(ownerId, id, null, "编辑项目", "更新项目：" + project.getName());
    return getProject(ownerId, id);
  }

  /**
   * 软删除项目：级联逻辑删除其下所有批次，底层业务数据保留。
   * 删除后项目不再出现在任何列表/统计/报表中。
   */
  @Transactional
  public void deleteProject(String ownerId, String projectId) {
    getProject(ownerId, projectId);
    batchMapper.delete(Wrappers.<Batch>lambdaQuery().eq(Batch::getProjectId, projectId));
    projectMapper.deleteById(projectId);
    addLog(ownerId, projectId, null, "删除项目", "删除项目及其下全部批次");
  }

  /**
   * 软删除批次：批次置删除态，并重算所属项目金额快照（项目统计依赖批次汇总，必须重算）。
   */
  @Transactional
  public void deleteBatch(String ownerId, String batchId) {
    BatchDto batch = getBatch(ownerId, batchId);
    batchMapper.deleteById(batchId);
    aggregationService.recomputeProject(batch.projectId());
    addLog(ownerId, batch.projectId(), batchId, "删除批次", "删除批次：" + batch.name());
  }

  public List<BatchDto> listBatches(String ownerId, String projectId) {
    Map<String, Project> projects = ownedProjectMap(ownerId, projectId);
    if (projects.isEmpty()) {
      return List.of();
    }
    List<Batch> batches = batchMapper.selectList(Wrappers.<Batch>lambdaQuery()
        .in(Batch::getProjectId, projects.keySet())
        .orderByDesc(Batch::getUpdatedAt));
    Map<String, BigDecimal> received = batchReceived(batches.stream().map(Batch::getId).toList());
    return batches.stream()
        .map(batch -> mapBatch(batch, projects.get(batch.getProjectId()).getName(),
            received.getOrDefault(batch.getId(), BigDecimal.ZERO)))
        .toList();
  }

  public BatchDto getBatch(String ownerId, String id) {
    Batch batch = batchMapper.selectById(id);
    if (batch == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "批次不存在");
    }
    Project project = projectMapper.selectOne(Wrappers.<Project>lambdaQuery()
        .eq(Project::getOwnerId, ownerId)
        .eq(Project::getId, batch.getProjectId()));
    if (project == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "批次不存在");
    }
    return mapBatch(batch, project.getName());
  }

  @Transactional
  public BatchDto createBatch(String ownerId, BatchCreateRequest request) {
    getProject(ownerId, request.projectId());
    Batch batch = new Batch();
    batch.setProjectId(request.projectId());
    batch.setName(request.name());
    batch.setBusinessDate(request.businessDate());
    batch.setMaterialSummary(request.materialSummary());
    batch.setStatus("待确认");
    batch.setProgress(0);
    batch.setRemark(request.remark());
    batchMapper.insert(batch);
    seedDefaultFlow(batch.getId());
    addLog(ownerId, request.projectId(), batch.getId(), "新建批次", "新建批次：" + request.name());
    return getBatch(ownerId, batch.getId());
  }

  @Transactional
  public BatchDto updateBatch(String ownerId, String id, BatchUpdateRequest request) {
    BatchDto current = getBatch(ownerId, id);
    Batch batch = batchMapper.selectById(id);
    if (StringUtils.hasText(request.name())) {
      batch.setName(request.name());
    }
    if (request.businessDate() != null) {
      batch.setBusinessDate(request.businessDate());
    }
    if (StringUtils.hasText(request.materialSummary())) {
      batch.setMaterialSummary(request.materialSummary());
    }
    if (StringUtils.hasText(request.status())) {
      batch.setStatus(request.status());
    }
    if (request.progress() != null) {
      batch.setProgress(request.progress());
    }
    if (request.remark() != null) {
      batch.setRemark(request.remark());
    }
    if (request.businessHints() != null) {
      batch.setBusinessHints(toJson(request.businessHints()));
    }
    if (request.reconciled() != null) {
      batch.setReconciled(request.reconciled());
    }
    batchMapper.updateById(batch);
    // 口径改了：已锁定的送货单销售价必须重算（毛利率口径按采购价倒推，锁定值不会自己跟着变），
    // 销售运费也要按新口径覆盖到已确认送货单，否则账上销售金额会少/多一笔运费。
    if (request.businessHints() != null) {
      deliveryNetPriceService.resyncDeliverySales(id, true);
    }
    addLog(ownerId, current.projectId(), id, "编辑批次", "更新批次：" + current.name());
    return getBatch(ownerId, id);
  }

  public BatchDetailDto batchDetail(String ownerId, String batchId) {
    BatchDto batch = getBatch(ownerId, batchId);
    ProjectDto project = getProject(ownerId, batch.projectId());
    return new BatchDetailDto(
        batch,
        project,
        materials(batchId),
        quotes(ownerId, batchId),
        flows(batchId),
        financeRecords(batchId),
        files(batchId)
    );
  }

  public List<MaterialDto> materials(String batchId) {
    return materialMapper.selectList(Wrappers.<Material>lambdaQuery()
            .eq(Material::getBatchId, batchId)
            .orderByAsc(Material::getCreatedAt))
        .stream()
        .map(this::mapMaterial)
        .toList();
  }

  @Transactional
  public MaterialDto saveMaterial(String ownerId, String batchId, MaterialSaveRequest request) {
    BatchDto batch = getBatch(ownerId, batchId);
    Material material = StringUtils.hasText(request.id()) ? materialMapper.selectById(request.id()) : new Material();
    if (material == null || (StringUtils.hasText(material.getBatchId()) && !batchId.equals(material.getBatchId()))) {
      throw new ApiException(HttpStatus.NOT_FOUND, "物料不存在");
    }
    material.setBatchId(batchId);
    material.setName(request.name());
    material.setSpec(request.spec());
    material.setUnit(request.unit());
    material.setQuantity(request.quantity());
    material.setBudgetUnitPrice(request.budgetUnitPrice());
    material.setSelectedSupplier(request.selectedSupplier());
    material.setSelectedQuote(request.selectedQuote());
    material.setMarketPrice(request.marketPrice());
    if (StringUtils.hasText(request.id())) {
      materialMapper.updateById(material);
    } else {
      materialMapper.insert(material);
    }
    addLog(ownerId, batch.projectId(), batchId, "编辑采购明细", "保存物料：" + request.name());
    return mapMaterial(materialMapper.selectById(material.getId()));
  }

  @Transactional
  public void deleteMaterial(String ownerId, String batchId, String materialId) {
    BatchDto batch = getBatch(ownerId, batchId);
    supplierQuoteMapper.delete(Wrappers.<SupplierQuote>lambdaQuery().eq(SupplierQuote::getMaterialId, materialId));
    // 级联清理行情映射，避免幽灵映射残留
    materialMarketMappingMapper.delete(Wrappers.<MaterialMarketMapping>lambdaQuery()
        .eq(MaterialMarketMapping::getMaterialId, materialId));
    materialMapper.delete(Wrappers.<Material>lambdaQuery()
        .eq(Material::getId, materialId)
        .eq(Material::getBatchId, batchId));
    addLog(ownerId, batch.projectId(), batchId, "删除采购明细", "删除物料记录");
  }

  public List<FinanceRecordDto> financeRecords(String batchId) {
    return financeRecordMapper.selectList(Wrappers.<FinanceRecord>lambdaQuery()
            .eq(FinanceRecord::getBatchId, batchId)
            .orderByDesc(FinanceRecord::getOccurredDate)
            .orderByDesc(FinanceRecord::getCreatedAt))
        .stream()
        .map(this::mapFinance)
        .toList();
  }

  @Transactional
  public FinanceRecordDto saveFinance(String ownerId, String batchId, FinanceSaveRequest request) {
    BatchDto batch = getBatch(ownerId, batchId);
    FinanceRecord record = StringUtils.hasText(request.id()) ? financeRecordMapper.selectById(request.id()) : new FinanceRecord();
    if (record == null || (StringUtils.hasText(record.getBatchId()) && !batchId.equals(record.getBatchId()))) {
      throw new ApiException(HttpStatus.NOT_FOUND, "财务记录不存在");
    }
    record.setBatchId(batchId);
    record.setType(request.type());
    record.setTarget(request.target());
    record.setAmount(request.amount());
    record.setStatus(StringUtils.hasText(request.status()) ? request.status() : "已完成");
    record.setOccurredDate(request.occurredDate());
    if (StringUtils.hasText(request.id())) {
      financeRecordMapper.updateById(record);
    } else {
      financeRecordMapper.insert(record);
    }
    addLog(ownerId, batch.projectId(), batchId, "编辑财务记录", "保存" + request.type() + "：" + request.target());
    return mapFinance(financeRecordMapper.selectById(record.getId()));
  }

  public List<FileRecordDto> files(String batchId) {
    return fileRecordMapper.selectList(Wrappers.<FileRecord>lambdaQuery()
            .eq(FileRecord::getBatchId, batchId)
            .orderByDesc(FileRecord::getCreatedAt))
        .stream()
        .map(this::mapFile)
        .toList();
  }

  public List<FlowEventDto> flows(String batchId) {
    return flowEventMapper.selectList(Wrappers.<FlowEvent>lambdaQuery()
            .eq(FlowEvent::getBatchId, batchId)
            .orderByAsc(FlowEvent::getSortOrder))
        .stream()
        .map(this::mapFlow)
        .toList();
  }

  public List<QuoteDto> quotes(String ownerId, String batchId) {
    getBatch(ownerId, batchId);
    List<SupplierQuote> quotes = supplierQuoteMapper.selectList(Wrappers.<SupplierQuote>lambdaQuery()
        .eq(SupplierQuote::getBatchId, batchId)
        .orderByAsc(SupplierQuote::getQuotePrice));
    if (quotes.isEmpty()) {
      return List.of();
    }
    Map<String, Supplier> suppliers = supplierMapper.selectBatchIds(
            quotes.stream().map(SupplierQuote::getSupplierId).distinct().toList())
        .stream()
        .collect(Collectors.toMap(Supplier::getId, Function.identity()));
    return quotes.stream()
        .map(quote -> mapQuote(quote, suppliers.get(quote.getSupplierId())))
        .toList();
  }

  public List<SupplierDto> suppliers(String ownerId) {
    return supplierMapper.selectList(Wrappers.<Supplier>lambdaQuery()
            .eq(Supplier::getOwnerId, ownerId)
            .orderByDesc(Supplier::getRating)
            .orderByAsc(Supplier::getName))
        .stream()
        .map(this::mapSupplier)
        .toList();
  }

  public List<OperationLogDto> operationLogs(String ownerId, int limit) {
    return operationLogMapper.selectList(Wrappers.<OperationLog>lambdaQuery()
            .eq(OperationLog::getUserId, ownerId)
            .orderByDesc(OperationLog::getCreatedAt)
            .last("LIMIT " + Math.max(1, Math.min(limit, 100))))
        .stream()
        .map(this::mapLog)
        .toList();
  }

  public ReportDto report(String ownerId, String scope, String projectId, String batchId) {
    BigDecimal purchase;
    BigDecimal sales;
    BigDecimal profit;
    if ("batch".equals(scope) && StringUtils.hasText(batchId)) {
      BatchDto batch = getBatch(ownerId, batchId);
      purchase = batch.purchaseAmount();
      sales = batch.salesAmount();
      profit = batch.profitAmount();
    } else if ("project".equals(scope) && StringUtils.hasText(projectId)) {
      ProjectDto project = getProject(ownerId, projectId);
      purchase = project.purchaseAmount();
      sales = project.salesAmount();
      profit = project.profitAmount();
    } else {
      List<ProjectDto> projects = listProjects(ownerId, null);
      purchase = projects.stream().map(ProjectDto::purchaseAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
      sales = projects.stream().map(ProjectDto::salesAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
      profit = projects.stream().map(ProjectDto::profitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    List<KpiDto> kpis = List.of(
        new KpiDto("采购金额", moneyWan(purchase), null),
        new KpiDto("销售金额", moneyWan(sales), null),
        new KpiDto("毛利润", moneyWan(profit), null),
        new KpiDto("毛利率", margin(sales, profit) + "%", null)
    );
    return new ReportDto(
        scope,
        kpis,
        List.of(point("1月", 20), point("2月", 45), point("3月", 35), point("4月", 74), point("5月", 52)),
        List.of(point("1月", 42), point("2月", 66), point("3月", 54), point("4月", 82), point("5月", 72)),
        List.of(point("材料成本", 42), point("利润", 27), point("运输税费", 18), point("其他", 13)),
        supplierSummaries(ownerId)
    );
  }

  @Transactional
  public void addLog(String ownerId, String projectId, String batchId, String action, String content) {
    OperationLog log = new OperationLog();
    log.setUserId(ownerId);
    log.setProjectId(projectId);
    log.setBatchId(batchId);
    log.setAction(action);
    log.setContent(content);
    operationLogMapper.insert(log);
  }

  @Transactional
  public void addFile(String batchId, String name, String fileType, String source) {
    addFile(batchId, name, fileType, source, null);
  }

  @Transactional
  public void addFile(String batchId, String name, String fileType, String source, String url) {
    FileRecord file = new FileRecord();
    file.setBatchId(batchId);
    file.setName(name);
    file.setFileType(fileType);
    file.setSource(source);
    file.setUrl(url);
    fileRecordMapper.insert(file);
  }

  @Transactional
  public void markFlow(String batchId, String code, String status, String note) {
    FlowEvent flow = flowEventMapper.selectOne(Wrappers.<FlowEvent>lambdaQuery()
        .eq(FlowEvent::getBatchId, batchId)
        .eq(FlowEvent::getCode, code));
    if (flow == null) {
      return;
    }
    flow.setStatus(status);
    if (StringUtils.hasText(note)) {
      flow.setNote(note);
    }
    if (flow.getEventTime() != null && flow.getEventTime().startsWith("预计")) {
      flow.setEventTime(LocalDateTime.now().withSecond(0).withNano(0).toString().replace('T', ' '));
    }
    flowEventMapper.updateById(flow);
  }

  private void seedDefaultFlow(String batchId) {
    List<String[]> events = List.of(
        new String[] {"purchase_plan", "采购计划", "待处理"},
        new String[] {"supplier_quote", "供应商报价", "待处理"},
        new String[] {"market_compare", "市场价格对比", "待处理"},
        new String[] {"select_supplier", "选择供应商", "待处理"},
        new String[] {"payment", "付款", "待处理"},
        new String[] {"acceptance", "收货验收", "待处理"},
        new String[] {"receipt", "客户回款", "待处理"},
        new String[] {"settlement", "财务结算", "待处理"}
    );
    for (int i = 0; i < events.size(); i++) {
      String[] event = events.get(i);
      FlowEvent flow = new FlowEvent();
      flow.setBatchId(batchId);
      flow.setCode(event[0]);
      flow.setTitle(event[1]);
      flow.setStatus(event[2]);
      flow.setEventTime("待处理");
      flow.setNote("等待业务资料录入");
      flow.setDetail("由AI识别或用户手动更新");
      flow.setSortOrder((i + 1) * 10);
      flowEventMapper.insert(flow);
    }
  }

  private List<SupplierSummaryDto> supplierSummaries(String ownerId) {
    List<Supplier> suppliers = supplierMapper.selectList(Wrappers.<Supplier>lambdaQuery()
        .eq(Supplier::getOwnerId, ownerId));
    if (suppliers.isEmpty()) {
      return List.of();
    }
    Map<String, Supplier> supplierMap = suppliers.stream().collect(Collectors.toMap(Supplier::getId, Function.identity()));
    // 仅统计有效（未删除）批次下的报价，避免已删批次金额计入供应商汇总
    List<String> validBatchIds = batchMapper.selectList(Wrappers.<Batch>lambdaQuery())
        .stream().map(Batch::getId).toList();
    List<SupplierQuote> quotes;
    if (validBatchIds.isEmpty()) {
      quotes = List.of();
    } else {
      quotes = supplierQuoteMapper.selectList(Wrappers.<SupplierQuote>lambdaQuery()
          .in(SupplierQuote::getSupplierId, supplierMap.keySet())
          .in(SupplierQuote::getBatchId, validBatchIds));
    }
    Map<String, List<SupplierQuote>> grouped = quotes.stream()
        .collect(Collectors.groupingBy(SupplierQuote::getSupplierId, LinkedHashMap::new, Collectors.toList()));
    List<SupplierSummaryDto> summaries = new ArrayList<>();
    for (Supplier supplier : suppliers) {
      List<SupplierQuote> supplierQuotes = grouped.getOrDefault(supplier.getId(), List.of());
      BigDecimal amount = supplierQuotes.stream().map(SupplierQuote::getQuotePrice).reduce(BigDecimal.ZERO, BigDecimal::add);
      summaries.add(new SupplierSummaryDto(supplier.getName(), amount, 92 + Math.min(6, supplier.getRating()), Math.max(1, supplierQuotes.size() + 8)));
    }
    return summaries.stream()
        .sorted(Comparator.comparing(SupplierSummaryDto::amount).reversed())
        .toList();
  }

  private Map<String, Project> ownedProjectMap(String ownerId, String projectId) {
    List<Project> projects = projectMapper.selectList(Wrappers.<Project>lambdaQuery()
        .eq(Project::getOwnerId, ownerId)
        .eq(StringUtils.hasText(projectId), Project::getId, projectId));
    return projects.stream().collect(Collectors.toMap(Project::getId, Function.identity()));
  }

  /** 项目级资金 / 票据累计值，由 {@link #projectFinance} 从台账行汇总得到。 */
  private record ProjectFinance(
      BigDecimal paid,
      BigDecimal invoiced,
      BigDecimal salesInvoiced,
      BigDecimal received
  ) {
    private static final ProjectFinance ZERO =
        new ProjectFinance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
  }

  private ProjectDto mapProject(Project project, int batchCount, ProjectFinance finance) {
    BigDecimal purchase = zero(project.getPurchaseAmount());
    BigDecimal sales = zero(project.getSalesAmount());
    BigDecimal paid = finance.paid();
    BigDecimal invoiced = finance.invoiced();
    BigDecimal salesInvoiced = finance.salesInvoiced();
    BigDecimal received = finance.received();
    return new ProjectDto(
        project.getId(),
        project.getName(),
        project.getCustomerName(),
        project.getStatus(),
        project.getProgress(),
        purchase,
        paid,
        invoiced,
        nonNegative(purchase.subtract(paid)),
        paid.subtract(invoiced),
        sales,
        salesInvoiced,
        received,
        nonNegative(sales.subtract(received)),
        sales.subtract(salesInvoiced),
        zero(project.getProfitAmount()),
        zero(project.getMarginRate()),
        fromJsonMap(project.getBusinessHints()),
        instant(project.getUpdatedAt()),
        batchCount
    );
  }

  private BatchDto mapBatch(Batch batch, String projectName) {
    return mapBatch(batch, projectName, batchReceived(List.of(batch.getId())).getOrDefault(batch.getId(), BigDecimal.ZERO));
  }

  /** 批次已收款 = Σ 项目收款分摊（未删收款）。批量查询，避免列表 N+1。 */
  private Map<String, BigDecimal> batchReceived(List<String> batchIds) {
    if (batchIds.isEmpty()) {
      return Map.of();
    }
    List<ProjectReceiptAllocation> allocations = allocationMapper.selectList(
        Wrappers.<ProjectReceiptAllocation>lambdaQuery()
            .in(ProjectReceiptAllocation::getBatchId, batchIds));
    Map<String, BigDecimal> result = new LinkedHashMap<>();
    allocations.forEach(a -> result.merge(a.getBatchId(), zero(a.getAmount()), BigDecimal::add));
    return result;
  }

  private BatchDto mapBatch(Batch batch, String projectName, BigDecimal received) {
    return new BatchDto(
        batch.getId(),
        batch.getProjectId(),
        projectName,
        batch.getName(),
        batch.getBusinessDate(),
        batch.getMaterialSummary(),
        batch.getStatus(),
        batch.getProgress(),
        zero(batch.getPurchaseAmount()),
        zero(batch.getSalesAmount()),
        zero(batch.getProfitAmount()),
        zero(batch.getMarginRate()),
        batch.getRemark(),
        fromJsonMap(batch.getBusinessHints()),
        instant(batch.getUpdatedAt()),
        Boolean.TRUE.equals(batch.getReconciled()),
        received,
        ReceiptService.receiptStatus(batch.getSalesAmount(), received)
    );
  }

  private String toJson(Map<String, Object> value) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "批次口径格式错误");
    }
  }

  private Map<String, Object> fromJsonMap(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json,
          new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
          });
    } catch (Exception ex) {
      return Map.of();
    }
  }

  private MaterialDto mapMaterial(Material material) {
    return new MaterialDto(
        material.getId(),
        material.getBatchId(),
        material.getName(),
        material.getSpec(),
        material.getUnit(),
        zero(material.getQuantity()),
        zero(material.getBudgetUnitPrice()),
        material.getSelectedSupplier(),
        material.getSelectedQuote(),
        material.getMarketPrice()
    );
  }

  private QuoteDto mapQuote(SupplierQuote quote, Supplier supplier) {
    return new QuoteDto(
        quote.getId(),
        quote.getBatchId(),
        quote.getSupplierId(),
        supplier == null ? "未知供应商" : supplier.getName(),
        zero(quote.getQuotePrice()),
        zero(quote.getMarketPriceSnapshot()),
        zero(quote.getDifferenceRate()),
        quote.getSelected(),
        supplier == null ? 0 : supplier.getRating(),
        supplier == null ? "" : supplier.getPaymentTerms(),
        supplier == null ? 0 : supplier.getDeliveryDays(),
        supplier == null ? "" : supplier.getRiskNote()
    );
  }

  private SupplierDto mapSupplier(Supplier supplier) {
    return new SupplierDto(
        supplier.getId(),
        supplier.getName(),
        supplier.getRating(),
        supplier.getPaymentTerms(),
        supplier.getDeliveryDays(),
        supplier.getRiskNote()
    );
  }

  private FlowEventDto mapFlow(FlowEvent flow) {
    return new FlowEventDto(
        flow.getId(),
        flow.getCode(),
        flow.getTitle(),
        flow.getStatus(),
        flow.getEventTime(),
        flow.getNote(),
        flow.getDetail(),
        flow.getSortOrder()
    );
  }

  private FinanceRecordDto mapFinance(FinanceRecord record) {
    return new FinanceRecordDto(
        record.getId(),
        record.getBatchId(),
        record.getType(),
        record.getTarget(),
        zero(record.getAmount()),
        record.getStatus(),
        record.getOccurredDate()
    );
  }

  private FileRecordDto mapFile(FileRecord file) {
    return new FileRecordDto(
        file.getId(),
        file.getBatchId(),
        file.getName(),
        file.getFileType(),
        file.getUrl(),
        file.getSource(),
        instant(file.getCreatedAt())
    );
  }

  private OperationLogDto mapLog(OperationLog log) {
    return new OperationLogDto(log.getId(), log.getAction(), log.getContent(), instant(log.getCreatedAt()));
  }

  private java.time.Instant instant(LocalDateTime value) {
    return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
  }

  private ChartPointDto point(String label, int value) {
    return new ChartPointDto(label, BigDecimal.valueOf(value));
  }

  private BigDecimal zero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private BigDecimal nonNegative(BigDecimal value) {
    return zero(value).max(BigDecimal.ZERO);
  }

  private String moneyWan(BigDecimal yuan) {
    return "¥" + zero(yuan).divide(TEN_THOUSAND).stripTrailingZeros().toPlainString() + "万";
  }

  private String margin(BigDecimal sales, BigDecimal profit) {
    if (sales == null || sales.compareTo(BigDecimal.ZERO) == 0) {
      return "0.0";
    }
    return zero(profit).multiply(BigDecimal.valueOf(100))
        .divide(sales, 1, java.math.RoundingMode.HALF_UP)
        .toPlainString();
  }

  private long countStatus(List<ProjectDto> projects, String status) {
    return projects.stream().filter(project -> status.equals(project.status())).count();
  }
}
