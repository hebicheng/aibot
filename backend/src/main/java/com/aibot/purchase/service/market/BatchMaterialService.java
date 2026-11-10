package com.aibot.purchase.service.market;

import com.aibot.purchase.entity.BatchMaterial;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.mapper.BatchMaterialMapper;
import com.aibot.purchase.mapper.DocumentItemMapper;
import com.aibot.purchase.mapper.DocumentMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 批次材料归并（P0 影子模式，2026-10-29）。
 *
 * <p>把批次内所有已确认单据的明细行，按材料身份归并成"一个材料一行"，并把材料 id 回写到
 * {@code document_items.material_id}。<b>本阶段只写不读</b>：没有任何价格、台账、对账逻辑
 * 依赖这些数据，出问题不会影响现有功能。
 *
 * <p>归并顺序（两级键，解决「采购单不写牌号/产地、送货单写了」）：
 * <ol>
 *   <li>按粗键 {@code 大类|直径|定尺} 归堆；</li>
 *   <li>堆内牌号、产地各取"唯一的非空值"回填空缺（两个不同值就不补，各自独立）；</li>
 *   <li>按细键 {@code 大类|直径|定尺|牌号|产地} 落材料行。</li>
 * </ol>
 *
 * <p>同一材料在同一批次出现两条以上采购行 = 重复录入或归并错误，标 {@code duplicate}
 * 且不指定采购行——系统报出来，不取平均价糊过去。
 *
 * <p>幂等：每次整批重算（批次内材料量级是几十行，重建比增量维护更不容易错）。
 */
@Service
public class BatchMaterialService {

  private static final Logger log = LoggerFactory.getLogger(BatchMaterialService.class);

  /** 参与归并的单据状态（与锁价、台账同口径）。 */
  private static final List<String> CONFIRMED_STATUS = List.of("matched", "archived");
  private static final String DOC_PURCHASE = "supply_order";
  private static final String DOC_DELIVERY = "delivery_note";

  private static final String LEVEL_EXACT = "exact";
  private static final String LEVEL_INFERRED = "inferred";
  private static final String LEVEL_PENDING = "pending";
  private static final String LEVEL_DUPLICATE = "duplicate";
  private static final String SOURCE_RULE = "rule";
  private static final String SOURCE_AI = "ai";
  private static final String SOURCE_MANUAL = "manual";
  private static final String STATUS_ACTIVE = "active";

  private final DocumentMapper documentMapper;
  private final DocumentItemMapper itemMapper;
  private final BatchMaterialMapper materialMapper;
  private final com.aibot.purchase.mapper.BatchMaterialAliasMapper aliasMapper;

  public BatchMaterialService(
      DocumentMapper documentMapper,
      DocumentItemMapper itemMapper,
      BatchMaterialMapper materialMapper,
      com.aibot.purchase.mapper.BatchMaterialAliasMapper aliasMapper
  ) {
    this.documentMapper = documentMapper;
    this.itemMapper = itemMapper;
    this.materialMapper = materialMapper;
    this.aliasMapper = aliasMapper;
  }

  /** 归并统计：材料数、精确/推断/待定/重复、参与归并的明细行数。 */
  public record Summary(int materials, int exact, int inferred, int pending, int duplicate, int items) {
    public static Summary empty() {
      return new Summary(0, 0, 0, 0, 0, 0);
    }
  }

  /**
   * 一条明细行 + 所属单据。
   *
   * @param enriched 牌号/产地是被组内唯一值补上的（规则路径才有）
   * @param fromAlias 身份来自 AI 判同映射（写法已在 batch_material_aliases 里登记）
   * @param rawKey 单据写法的规范化文本，AI 判同的缓存键
   */
  private record Row(DocumentItem item, Document document, MaterialIdentity.Identity identity,
                     String rawKey, boolean enriched, boolean fromAlias, boolean purchase) {
  }

  /** 材料组：同一细键下的全部行。 */
  private record Group(String identityKey, List<Row> rows) {
  }

  /**
   * 整批重建材料层。身份来源：<b>AI 判同映射优先，规则兜底</b>。
   *
   * <p>为什么规则还留着：AI 可能只判出一部分写法（模型超时、返回不全），或者这个批次还没跑过
   * AI。此时如果"没登记就不归并"，那些行会失去 material_id → 取不到采购价 → 台账销售额漏算。
   * 规则在这里只是<b>临时占位</b>：AI 判过的写法一律以 AI 为准，规则不会把它的判定拆开。
   *
   * <p>刻意不加事务注解、并吞掉自身异常：这是影子层，任何失败都不能把单据确认拖下水；
   * 重建本身幂等，下一次确认会自我修正。
   */
  public Summary rebuild(String batchId) {
    return safeRebuild(batchId);
  }

  private Summary safeRebuild(String batchId) {
    if (!StringUtils.hasText(batchId)) {
      return Summary.empty();
    }
    try {
      return doRebuild(batchId);
    } catch (RuntimeException e) {
      log.warn("批次 {} 材料归并失败（影子层，不影响主流程）：{}", batchId, e.toString());
      return Summary.empty();
    }
  }

  private Summary doRebuild(String batchId) {
    Map<String, MaterialIdentity.Identity> aliases = loadAliases(batchId);
    List<Row> rows = loadRows(batchId, aliases);
    if (rows.isEmpty()) {
      removeAllExceptManual(batchId);
      return Summary.empty();
    }

    // 1) 粗键归堆 → 2) 堆内回填牌号/产地 → 3) 细键聚合
    Map<String, List<Row>> byBase = new LinkedHashMap<>();
    for (Row row : rows) {
      byBase.computeIfAbsent(row.identity().baseKey(), k -> new ArrayList<>()).add(row);
    }
    List<Group> groups = new ArrayList<>();
    for (List<Row> bucket : byBase.values()) {
      Set<String> grades = new LinkedHashSet<>();
      Set<String> origins = new LinkedHashSet<>();
      for (Row row : bucket) {
        if (row.identity().hasGrade()) {
          grades.add(row.identity().grade());
        }
        if (row.identity().hasOrigin()) {
          origins.add(row.identity().origin());
        }
      }
      // 只有一个非空值才回填；出现两个不同值说明确实有两种牌号/产地，不合并
      String fallbackGrade = grades.size() == 1 ? grades.iterator().next() : null;
      String fallbackOrigin = origins.size() == 1 ? origins.iterator().next() : null;

      Map<String, List<Row>> byKey = new LinkedHashMap<>();
      for (Row row : bucket) {
        MaterialIdentity.Identity raw = row.identity();
        MaterialIdentity.Identity resolved = MaterialIdentity.withResolved(
            raw,
            raw.hasGrade() ? raw.grade() : fallbackGrade,
            raw.hasOrigin() ? raw.origin() : fallbackOrigin);
        boolean enriched = !raw.identityKey().equals(resolved.identityKey());
        byKey.computeIfAbsent(resolved.identityKey(), k -> new ArrayList<>())
            .add(new Row(row.item(), row.document(), resolved, row.rawKey(), enriched,
                row.fromAlias(), row.purchase()));
      }
      for (Map.Entry<String, List<Row>> group : byKey.entrySet()) {
        groups.add(new Group(group.getKey(), group.getValue()));
      }
    }

    // 4) 逐组 upsert + 回写明细行
    Map<String, BatchMaterial> existing = new HashMap<>();
    Map<String, BatchMaterial> existingById = new HashMap<>();
    for (BatchMaterial material : materialMapper.selectList(Wrappers.<BatchMaterial>lambdaQuery()
        .eq(BatchMaterial::getBatchId, batchId))) {
      existing.put(material.getIdentityKey(), material);
      existingById.put(material.getId(), material);
    }

    int exact = 0;
    int inferred = 0;
    int pending = 0;
    int duplicate = 0;
    Set<String> liveKeys = new HashSet<>();
    for (Group group : groups) {
      Row first = group.rows().get(0);
      MaterialIdentity.Identity identity = first.identity();
      List<Row> purchaseRows = group.rows().stream().filter(Row::purchase).toList();
      boolean enriched = group.rows().stream().anyMatch(Row::enriched);

      String level;
      if (purchaseRows.size() > 1) {
        level = LEVEL_DUPLICATE;
        duplicate++;
      } else if (!identity.hasGrade() || !identity.hasOrigin()) {
        // 牌号或产地始终定不下来：材料先独立存在，等更完整的单据来了再说
        level = LEVEL_PENDING;
        pending++;
      } else if (enriched) {
        level = LEVEL_INFERRED;
        inferred++;
      } else {
        level = LEVEL_EXACT;
        exact++;
      }

      BatchMaterial material = existing.get(group.identityKey());
      boolean manual = material != null && SOURCE_MANUAL.equals(material.getMatchSource());
      // 组内只要有行来自 AI 判同映射，这个材料就算 AI 定的（规则只在降级时用自己的标记）
      String source = group.rows().stream().anyMatch(Row::fromAlias) ? SOURCE_AI : SOURCE_RULE;
      LocalDate quoteDate = resolveQuoteDate(group.rows());
      // 有采购行就给一个成本引用，取<b>最早单据日</b>那一条——与 quoteDate（最早采购日）同源，
      // 否则网价按 A 日的行情取、成本却是 B 日的采购价，两者对不上。
      // 同材料两条采购行属于异常（标 duplicate 让人核），仍取一条兜底：不能因为异常就让
      // 送货金额凭空变成「待采购价」不计入台账。
      String supplyItemId = purchaseRows.isEmpty() ? null : earliestPurchase(purchaseRows).item().getId();

      if (material == null) {
        material = new BatchMaterial();
        material.setBatchId(batchId);
        material.setIdentityKey(group.identityKey());
        material.setCategory(identity.category());
        material.setGrade(identity.grade());
        material.setDiameter(identity.diameter());
        material.setLengthM(identity.lengthM());
        material.setOrigin(identity.origin());
        material.setBaseKey(identity.baseKey());
        material.setQuoteDate(quoteDate);
        material.setSupplyItemId(supplyItemId);
        material.setMatchLevel(level);
        material.setMatchSource(source);
        material.setStatus(STATUS_ACTIVE);
        inheritParameters(material, predecessor(group, existingById));
        material.setCreatedAt(LocalDateTime.now());
        material.setUpdatedAt(LocalDateTime.now());
        materialMapper.insert(material);
      } else if (manual) {
        // 人工改过的归并结果不被规则覆盖（P1 提供人工入口后生效），只刷新身份与归属
        updateColumns(material.getId(), identity, null, null, null, null);
      } else {
        // 可空列显式 set：身份/牌号/产地可能由有变无，updateById 跳过 null 会留脏值
        updateColumns(material.getId(), identity, level, supplyItemId, quoteDate, source);
      }
      liveKeys.add(group.identityKey());

      for (Row row : group.rows()) {
        writeBackItem(row, material, identity, source);
      }
    }

    // 5) 清掉本批次多出来的材料行（本次没有行落到它上面）
    if (liveKeys.isEmpty()) {
      removeAllExceptManual(batchId);
    } else {
      materialMapper.delete(Wrappers.<BatchMaterial>lambdaQuery()
          .eq(BatchMaterial::getBatchId, batchId)
          .ne(BatchMaterial::getMatchSource, SOURCE_MANUAL)
          .notIn(BatchMaterial::getIdentityKey, liveKeys));
    }

    Summary summary = new Summary(groups.size(), exact, inferred, pending, duplicate, rows.size());
    log.info("批次 {} 材料归并完成：{} 种材料（精确 {} / 推断 {} / 待定 {} / 重复 {}），明细 {} 行",
        batchId, summary.materials(), exact, inferred, pending, duplicate, rows.size());
    return summary;
  }

  /**
   * 新材料行的「前身」：组内明细行在本次重建之前挂着的那个材料。
   *
   * <p>身份键会因为 AI 判同修正牌号/产地而变化，key 一变就等于换了一行（旧行随后被删），
   * 而网价绑定这些参数挂在行上——不迁移就等于「改个写法把绑定的网价清掉了」。
   *
   * <p>合并两个材料时优先保住真正配过网价的那个。
   */
  private BatchMaterial predecessor(Group group, Map<String, BatchMaterial> existingById) {
    BatchMaterial best = null;
    for (Row row : group.rows()) {
      BatchMaterial candidate = existingById.get(row.item().getMaterialId());
      if (candidate == null) {
        continue;
      }
      if (best == null
          || (!StringUtils.hasText(best.getQuoteProductCode())
              && StringUtils.hasText(candidate.getQuoteProductCode()))) {
        best = candidate;
      }
    }
    return best;
  }

  /**
   * 把前身的<b>参数</b>迁到新行：网价绑定 + 定价口径 + 人工销售价。
   *
   * <p>只迁参数，不迁身份和采购行引用——身份由本次归并决定，采购行引用由本次分组决定。
   */
  private void inheritParameters(BatchMaterial target, BatchMaterial predecessor) {
    if (predecessor == null) {
      return;
    }
    target.setQuoteProductCode(predecessor.getQuoteProductCode());
    target.setQuoteSpecName(predecessor.getQuoteSpecName());
    target.setQuoteOrigin(predecessor.getQuoteOrigin());
    // 绑定来源必须一起迁：丢了来源，人工绑定的材料会被下一次自动匹配当成 auto 覆盖掉
    target.setQuoteSource(predecessor.getQuoteSource());
    target.setPricingMode(predecessor.getPricingMode());
    target.setUpliftAmount(predecessor.getUpliftAmount());
    target.setMarginRate(predecessor.getMarginRate());
    target.setManualSalesPrice(predecessor.getManualSalesPrice());
  }

  /**
   * 身份列与归并列显式更新。归并质量/采购行/取价日传 null 表示"本次不改"
   * （人工记录只刷新身份，不让规则覆盖它的归并结论）。
   */
  private void updateColumns(String id, MaterialIdentity.Identity identity, String level,
                             String supplyItemId, LocalDate quoteDate, String matchSource) {
    LambdaUpdateWrapper<BatchMaterial> wrapper = Wrappers.<BatchMaterial>lambdaUpdate()
        .set(BatchMaterial::getCategory, identity.category())
        .set(BatchMaterial::getGrade, identity.grade())
        .set(BatchMaterial::getDiameter, identity.diameter())
        .set(BatchMaterial::getLengthM, identity.lengthM())
        .set(BatchMaterial::getOrigin, identity.origin())
        .set(BatchMaterial::getBaseKey, identity.baseKey())
        .set(BatchMaterial::getUpdatedAt, LocalDateTime.now());
    if (level != null) {
      wrapper.set(BatchMaterial::getMatchLevel, level)
          .set(BatchMaterial::getSupplyItemId, supplyItemId)
          .set(BatchMaterial::getQuoteDate, quoteDate);
    }
    if (matchSource != null) {
      wrapper.set(BatchMaterial::getMatchSource, matchSource);
    }
    wrapper.eq(BatchMaterial::getId, id);
    materialMapper.update(null, wrapper);
  }

  /**
   * 同材料多条采购行时取最早单据日那一条（日期相同保持原有明细顺序）。
   * 采购侧是成本权威，也是"这批货进入业务的时点"，与取价日保持一致。
   */
  private static Row earliestPurchase(List<Row> purchaseRows) {
    Row best = purchaseRows.get(0);
    for (Row row : purchaseRows) {
      LocalDate current = row.document().getDocDate();
      LocalDate bestDate = best.document().getDocDate();
      if (current != null && (bestDate == null || current.isBefore(bestDate))) {
        best = row;
      }
    }
    return best;
  }

  /**
   * 取价日：优先采购侧最早单据日期（成本是权威，也是这批货进入业务的时点），
   * 还没有采购单时退回送货单最早日期。同批次同日只有一个价，因此一个材料一个日期。
   */
  private LocalDate resolveQuoteDate(List<Row> rows) {
    LocalDate purchaseDate = rows.stream()
        .filter(Row::purchase)
        .map(row -> row.document().getDocDate())
        .filter(java.util.Objects::nonNull)
        .min(LocalDate::compareTo)
        .orElse(null);
    if (purchaseDate != null) {
      return purchaseDate;
    }
    return rows.stream()
        .map(row -> row.document().getDocDate())
        .filter(java.util.Objects::nonNull)
        .min(LocalDate::compareTo)
        .orElse(null);
  }

  /**
   * 回写明细行的材料归属。用 UpdateWrapper 显式 set：material_id 可能由有变无，
   * updateById 跳过 null 会留下脏值。
   */
  private void writeBackItem(Row row, BatchMaterial material,
                             MaterialIdentity.Identity identity, String source) {
    DocumentItem item = row.item();
    if (SOURCE_MANUAL.equals(item.getMaterialMatchSource())) {
      return;
    }
    boolean unchanged = material.getId().equals(item.getMaterialId())
        && eq(item.getLengthM(), identity.lengthM())
        && eq(item.getMaterialMatchLevel(), material.getMatchLevel())
        && eq(item.getMaterialMatchSource(), source);
    if (unchanged) {
      return;
    }
    itemMapper.update(null, Wrappers.<DocumentItem>lambdaUpdate()
        .set(DocumentItem::getMaterialId, material.getId())
        .set(DocumentItem::getMaterialMatchLevel, material.getMatchLevel())
        .set(DocumentItem::getMaterialMatchSource, source)
        .set(DocumentItem::getLengthM, identity.lengthM())
        .eq(DocumentItem::getId, item.getId()));
  }

  private static boolean eq(String left, String right) {
    return left == null ? right == null : left.equals(right);
  }

  private void removeAllExceptManual(String batchId) {
    materialMapper.delete(Wrappers.<BatchMaterial>lambdaQuery()
        .eq(BatchMaterial::getBatchId, batchId)
        .ne(BatchMaterial::getMatchSource, SOURCE_MANUAL));
  }

  /**
   * 载入批次内全部已确认采购/送货明细并确定身份。
   *
   * <p>身份来源优先级：<b>AI 判同映射（aliases）> 规则解析</b>。规则只作用于"还没判过"的写法。
   */
  private List<Row> loadRows(String batchId, Map<String, MaterialIdentity.Identity> aliases) {
    List<Document> documents = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getBatchId, batchId)
        .in(Document::getDocType, DOC_PURCHASE, DOC_DELIVERY)
        .in(Document::getStatus, CONFIRMED_STATUS));
    if (documents.isEmpty()) {
      return List.of();
    }
    Map<String, Document> byId = new HashMap<>();
    for (Document document : documents) {
      byId.put(document.getId(), document);
    }
    List<DocumentItem> items = itemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
        .in(DocumentItem::getDocumentId, byId.keySet())
        .orderByAsc(DocumentItem::getSortOrder));
    List<Row> rows = new ArrayList<>();
    for (DocumentItem item : items) {
      Document document = byId.get(item.getDocumentId());
      if (document == null) {
        continue;
      }
      // 材料名为空也照收（归到「未标注材料」）：宁可留一行待归并，也不能让明细行从材料清单里消失
      String rawKey = MaterialIdentity.rawKey(
          item.getMaterialName(), item.getSpec(), item.getOrigin());
      MaterialIdentity.Identity alias = aliases.get(rawKey);
      MaterialIdentity.Identity identity = alias != null
          ? alias
          : MaterialIdentity.parse(item.getMaterialName(), item.getSpec(), item.getOrigin());
      rows.add(new Row(item, document, identity, rawKey, false, alias != null,
          DOC_PURCHASE.equals(document.getDocType())));
    }
    return rows;
  }

  /**
   * 批次内"写法 → 身份"的 AI 判同映射。
   *
   * <p>命中时 identityKey 由 {@link MaterialIdentity#withResolved} 重算，所以同一个写法在
   * 不同批次可以落到不同材料上（各批写法习惯不同），互不影响。
   */
  private Map<String, MaterialIdentity.Identity> loadAliases(String batchId) {
    Map<String, MaterialIdentity.Identity> aliases = new HashMap<>();
    for (com.aibot.purchase.entity.BatchMaterialAlias alias : aliasMapper.selectList(
        Wrappers.<com.aibot.purchase.entity.BatchMaterialAlias>lambdaQuery()
            .eq(com.aibot.purchase.entity.BatchMaterialAlias::getBatchId, batchId))) {
      aliases.put(alias.getRawKey(), MaterialIdentity.withResolved(
          alias.getCategory(), alias.getGrade(), alias.getDiameter(),
          alias.getLengthM(), alias.getOrigin()));
    }
    return aliases;
  }

  /** 只读查询（P0 不对外暴露，供后续阶段与排查使用）。 */
  public List<BatchMaterial> list(String batchId) {
    if (!StringUtils.hasText(batchId)) {
      return List.of();
    }
    return materialMapper.selectList(Wrappers.<BatchMaterial>lambdaQuery()
        .eq(BatchMaterial::getBatchId, batchId)
        .orderByAsc(BatchMaterial::getCategory)
        .orderByAsc(BatchMaterial::getDiameter));
  }
}
