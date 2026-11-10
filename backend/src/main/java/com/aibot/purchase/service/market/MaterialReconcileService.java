package com.aibot.purchase.service.market;

import com.aibot.purchase.common.InsufficientCreditsException;
import com.aibot.purchase.entity.BatchMaterial;
import com.aibot.purchase.entity.BatchMaterialAlias;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.mapper.BatchMaterialAliasMapper;
import com.aibot.purchase.mapper.BatchMaterialMapper;
import com.aibot.purchase.mapper.DocumentItemMapper;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.service.BillingService;
import com.aibot.purchase.service.MaterialCategoryNormalizer;
import com.aibot.purchase.service.ai.QwenClient;
import com.aibot.purchase.service.ai.QwenClient.QwenResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 材料判同（2026-10-29）：把单据上的写法映射到本批次的<b>权威材料</b>。
 *
 * <p>与"正则归并"的根本区别：规则只能处理格式差异（12*9 vs 12×9），处理不了语义差异
 * （三级螺纹钢 = HRB400、中天 = 中天钢铁、盘元 = 盘螺）。这里把「待归并写法」和
 * 「本批次已有材料」一起交给模型，由它逐条回答"挂到哪个已有材料 / 这是不是新材料"。
 *
 * <p>两个阶段天然区分，不需要额外状态：
 * <ul>
 *   <li><b>首次成形</b>：本批次还没有任何 AI 材料 → 所有写法都要判 → 权威从零建立；</li>
 *   <li><b>增量映射</b>：只判 {@code batch_material_aliases} 里没有的写法 → 权威只增不改。</li>
 * </ul>
 *
 * <p>判定结果落 {@code batch_material_aliases}（写法 → 五维），之后 {@link BatchMaterialService#rebuildFromAliases}
 * 按它重建材料层：同一个写法不会再问第二次，也就不会重复计费；规则也不会把判定重新拆开。
 *
 * <p>为了不被模型幻觉污染，AI 只能<b>选择</b>已有材料编号或声明 new，返回的五维要过后端校验
 * （大类白名单、直径/定尺纯数字、牌号匹配正则），校验不过的字段当"没写"处理，不采信原值。
 */
@Service
public class MaterialReconcileService {

  private static final Logger log = LoggerFactory.getLogger(MaterialReconcileService.class);

  private static final String DOC_PURCHASE = "supply_order";
  private static final String DOC_DELIVERY = "delivery_note";
  private static final List<String> CONFIRMED_STATUS = List.of("matched", "archived");
  private static final String SOURCE_AI = "ai";

  /** 一次判同的结果，供接口回显。 */
  public record Result(String status, int writeups, int mapped, int created,
                       BigDecimal credits, String message) {
    static Result skipped(String status, String message) {
      return new Result(status, 0, 0, 0, BigDecimal.ZERO, message);
    }
  }

  private final DocumentMapper documentMapper;
  private final DocumentItemMapper itemMapper;
  private final BatchMaterialMapper materialMapper;
  private final BatchMaterialAliasMapper aliasMapper;
  private final BatchMaterialService batchMaterialService;
  private final MaterialQuoteBindingService materialQuoteBindingService;
  private final DeliveryNetPriceService deliveryNetPriceService;
  private final QwenClient qwenClient;
  private final BillingService billingService;
  private final ObjectMapper objectMapper;

  /** 批次级串行锁：两张单据同时确认时不要并发问同一个批次（会重复计费）。 */
  private final ConcurrentHashMap<String, Object> batchLocks = new ConcurrentHashMap<>();

  public MaterialReconcileService(
      DocumentMapper documentMapper,
      DocumentItemMapper itemMapper,
      BatchMaterialMapper materialMapper,
      BatchMaterialAliasMapper aliasMapper,
      BatchMaterialService batchMaterialService,
      MaterialQuoteBindingService materialQuoteBindingService,
      DeliveryNetPriceService deliveryNetPriceService,
      QwenClient qwenClient,
      BillingService billingService,
      ObjectMapper objectMapper
  ) {
    this.documentMapper = documentMapper;
    this.itemMapper = itemMapper;
    this.materialMapper = materialMapper;
    this.aliasMapper = aliasMapper;
    this.batchMaterialService = batchMaterialService;
    this.materialQuoteBindingService = materialQuoteBindingService;
    this.deliveryNetPriceService = deliveryNetPriceService;
    this.qwenClient = qwenClient;
    this.billingService = billingService;
    this.objectMapper = objectMapper;
  }

  /**
   * 批次级判同：整批未登记的写法一次判完，是<b>唯一入口</b>（2026-10-30 改）。
   *
   * <p>为什么按批次而不是按单据：采购单与送货单的对应关系要跨单据才看得出
   * （采购写「三级螺纹钢 12*9」、送货写「螺纹钢HRB400 12*9」），只发一张单据的行，
   * 模型看不到另一侧，就退化成"按写法猜身份"，这恰恰是人一眼能看出来、规则做不好的事。
   *
   * @param alsoPrice 判完是否顺带重算销售价（手动校准入口要；确认/编辑单据的流程自己会锁价，
   *                  再算一遍等于同一件事做两次）
   */
  public Result reconcileBatch(String batchId, boolean alsoPrice) {
    if (!StringUtils.hasText(batchId)) {
      return Result.skipped("skipped", "缺少批次");
    }
    return locked(batchId, () -> doReconcile(batchId, alsoPrice));
  }

  public Result reconcileBatch(String batchId) {
    return reconcileBatch(batchId, true);
  }

  private Result locked(String batchId, java.util.function.Supplier<Result> action) {
    Object lock = batchLocks.computeIfAbsent(batchId, key -> new Object());
    synchronized (lock) {
      return action.get();
    }
  }

  private Result doReconcile(String batchId, boolean alsoPrice) {
    if (!qwenClient.isConfigured()) {
      return Result.skipped("disabled", "未配置大模型，本次跳过（材料层继续用规则归并）");
    }
    List<DocumentItem> items = loadItems(batchId);
    if (items.isEmpty()) {
      return Result.skipped("skipped", "没有待归并的采购/送货明细");
    }

    // 1) 未登记的写法（同一写法只发一次，行数作为判断线索）
    Map<String, BatchMaterialAlias> existing = loadAliases(batchId);
    Map<String, List<DocumentItem>> pending = new LinkedHashMap<>();
    for (DocumentItem item : items) {
      String rawKey = MaterialIdentity.rawKey(
          item.getMaterialName(), item.getSpec(), item.getOrigin());
      if (existing.containsKey(rawKey)) {
        continue;
      }
      pending.computeIfAbsent(rawKey, key -> new ArrayList<>()).add(item);
    }
    if (pending.isEmpty()) {
      return Result.skipped("all-mapped", "所有写法都已判过，无需再调用 AI");
    }

    // 2) 权威清单：本批已有 AI 材料。首次成形时为空，由 AI 从零分组
    List<BatchMaterial> authorities = materialMapper.selectList(
        Wrappers.<BatchMaterial>lambdaQuery()
            .eq(BatchMaterial::getBatchId, batchId)
            .orderByAsc(BatchMaterial::getCategory)
            .orderByAsc(BatchMaterial::getDiameter));

    // 3) 调模型
    QwenResult reply;
    try {
      reply = qwenClient.completeJson(systemPrompt(), userPrompt(authorities, pending));
    } catch (RuntimeException ex) {
      log.warn("批次 {} 材料判同调用失败：{}", batchId, ex.toString());
      return Result.skipped("failed", "AI 调用失败：" + ex.getMessage());
    }

    // 4) 先计费：token 已经花了。余额不足则不落库（不能"没扣钱却写了判定"）
    BigDecimal credits;
    try {
      credits = billingService.chargeMaterialMatch(batchId, reply.usage(), null);
    } catch (InsufficientCreditsException ex) {
      log.warn("批次 {} 材料判同积分不足，判定结果未落库：{}", batchId, ex.getMessage());
      return Result.skipped("insufficient", ex.getMessage());
    }

    // 5) 解析 + 校验 + 落库
    int mapped = 0;
    int created = 0;
    try {
      JsonNode mappings = objectMapper.readTree(reply.content()).path("mappings");
      if (!mappings.isArray() || mappings.isEmpty()) {
        return new Result("failed", pending.size(), 0, 0, credits, "AI 返回为空，未写入映射");
      }
      List<String> keys = new ArrayList<>(pending.keySet());
      Map<Integer, BatchMaterial> byIndex = new HashMap<>();
      for (int i = 0; i < authorities.size(); i++) {
        byIndex.put(i + 1, authorities.get(i));
      }
      for (JsonNode node : mappings) {
        int index = node.path("index").asInt(-1);
        if (index < 1 || index > keys.size()) {
          continue;
        }
        String rawKey = keys.get(index - 1);
        String target = node.path("target").asText("");
        MaterialIdentity.Identity identity;
        String canonicalName = text(node, "canonicalName");
        if (target.startsWith("M")) {
          BatchMaterial hit = byIndex.get(parseIndex(target));
          if (hit == null) {
            continue;
          }
          // patch：这条写法里更全的牌号/产地补进已有材料（只补空缺，不覆盖已有值）。
          // 这是"采购单不写产地、送货单写了"能收敛成一个材料的关键。
          identity = mergePatch(hit, node.path("patch"));
          mapped++;
        } else if ("new".equalsIgnoreCase(target)) {
          identity = newIdentity(node, canonicalName);
          if (identity == null) {
            continue;
          }
          created++;
        } else {
          continue;
        }
        upsertAlias(batchId, rawKey, identity, canonicalName);
      }
    } catch (Exception ex) {
      log.warn("批次 {} 材料判同结果解析失败：{}", batchId, ex.toString());
      return new Result("failed", pending.size(), mapped, created, credits,
          "AI 返回格式无法解析：" + ex.getMessage());
    }

    if (mapped + created == 0) {
      return new Result("failed", pending.size(), 0, 0, credits, "AI 没有给出可用的映射");
    }

    // 6) 按 AI 判定重建材料层（AI 优先、规则兜底未判过的写法），再重算价格与台账
    batchMaterialService.rebuild(batchId);
    if (alsoPrice) {
      // 手动校准入口：判完立刻按新归属重绑网价并重算台账，用户关掉页面就是最终结果
      // （这里 refreshAuto=true：人工点了校准，就应该按最新行情重新选一次）
      materialQuoteBindingService.bindBatch(batchId, true);
      deliveryNetPriceService.resyncDeliverySales(batchId, false);
    }

    log.info("批次 {} 材料判同完成：写法 {}，挂到已有 {}，新建 {}，消耗积分 {}",
        batchId, pending.size(), mapped, created, credits.toPlainString());
    return new Result("ok", pending.size(), mapped, created, credits, null);
  }

  // ---------------------------------------------------------------------------
  // 输入组装
  // ---------------------------------------------------------------------------

  private List<DocumentItem> loadItems(String batchId) {
    List<Document> documents = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getBatchId, batchId)
        .in(Document::getDocType, DOC_PURCHASE, DOC_DELIVERY)
        .in(Document::getStatus, CONFIRMED_STATUS));
    if (documents.isEmpty()) {
      return List.of();
    }
    return itemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
        .in(DocumentItem::getDocumentId, documents.stream().map(Document::getId).toList())
        .orderByAsc(DocumentItem::getSortOrder));
  }

  private Map<String, BatchMaterialAlias> loadAliases(String batchId) {
    Map<String, BatchMaterialAlias> aliases = new HashMap<>();
    for (BatchMaterialAlias alias : aliasMapper.selectList(
        Wrappers.<BatchMaterialAlias>lambdaQuery().eq(BatchMaterialAlias::getBatchId, batchId))) {
      aliases.put(alias.getRawKey(), alias);
    }
    return aliases;
  }

  private String systemPrompt() {
    return """
        你是钢材贸易的单据归并助手。同一批次里，采购单（找钢网销售单、提货单）与送货单是同一批货的两侧，
        只是写法不同。你的任务：把「待归并写法」逐条挂到「本批已有材料」，或者判定为一种新材料。
        采购侧与送货侧说的是同一种货时，必须挂到同一个材料上——它们本来就是一一对应的。

        判断依据（按重要性从高到低）：
        1. 品类：螺纹钢 / 盘螺 / 高线 / 圆钢（三级螺纹钢=螺纹钢，盘元=盘螺，线材=高线）。
        2. 直径：12 / Φ12 / 12mm 是同一个；「12*9」里前一个数是直径。
        3. 定尺：「9 / 9m / 9米」是定尺，只对螺纹钢、圆钢有意义；盘螺、高线、盘圆成盘供货，没有定尺。
        4. 牌号：HRB400 / 三级钢 可能指同一个；但 HRB400 与 HRB500、HRB400 与 HRB400E 不同。
        5. 产地/钢厂：中天 = 中天钢铁 = 中天钢厂；不同钢厂是不同的材料。

        补全：已有材料缺的字段（牌号/产地/直径/定尺），如果这条写法里能确定，就在 patch 里补上。
        只补空缺，不要覆盖已有值；不确定就留空。

        铁律：
        - 已有材料编号只能从给定清单里选，禁止编造编号。
        - 直径不同、牌号不同、产地不同的，一律判为不同材料。
        - 拿不准就判 new，不要硬挂到已有材料上；给不出规范值的字段写 null，不要猜。
        - 只返回 JSON，不要解释。

        返回格式：
        {"mappings":[
          {"index":1,"target":"M1","patch":{"grade":"HRB400","origin":"中天"}},
          {"index":2,"target":"new","category":"高线","grade":"HPB300","diameter":"6.5","lengthM":null,"origin":null,"canonicalName":"高线 HPB300 Φ6.5"}
        ]}
        """;
  }

  private String userPrompt(List<BatchMaterial> authorities, Map<String, List<DocumentItem>> pending) {
    List<Map<String, Object>> materialList = new ArrayList<>();
    for (int i = 0; i < authorities.size(); i++) {
      BatchMaterial material = authorities.get(i);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", "M" + (i + 1));
      row.put("category", material.getCategory());
      row.put("grade", material.getGrade());
      row.put("diameter", material.getDiameter());
      row.put("lengthM", material.getLengthM());
      row.put("origin", material.getOrigin());
      materialList.add(row);
    }
    List<Map<String, Object>> writeups = new ArrayList<>();
    // 一次查出所有涉及单据，避免每种写法各查一次
    java.util.Set<String> allDocumentIds = new LinkedHashSet<>();
    for (List<DocumentItem> rows : pending.values()) {
      for (DocumentItem item : rows) {
        allDocumentIds.add(item.getDocumentId());
      }
    }
    Map<String, Document> docById = new HashMap<>();
    for (Document document : documentMapper.selectBatchIds(allDocumentIds)) {
      docById.put(document.getId(), document);
    }
    int index = 0;
    for (Map.Entry<String, List<DocumentItem>> entry : pending.entrySet()) {
      index++;
      List<DocumentItem> rows = entry.getValue();
      DocumentItem sample = rows.get(0);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("index", index);
      row.put("materialName", sample.getMaterialName());
      row.put("spec", sample.getSpec());
      row.put("origin", sample.getOrigin());
      row.put("rows", rows.size());
      // 给出两侧的实际单据信息：日期、往来单位、数量——人就是靠这些把采购单和送货单对上的，
      // 只给材料名和规格，模型没法判断"这两行说的是同一批货"
      List<Map<String, Object>> samples = new ArrayList<>();
      for (DocumentItem item : rows.stream().limit(4).toList()) {
        Document document = docById.get(item.getDocumentId());
        if (document == null) {
          continue;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("docType", DOC_PURCHASE.equals(document.getDocType()) ? "采购单" : "送货单");
        detail.put("docDate", document.getDocDate() == null ? null : document.getDocDate().toString());
        detail.put("party", document.getSupplierName());
        detail.put("quantity", item.getQuantity());
        detail.put("unit", item.getUnit());
        detail.put("amount", item.getAmount());
        samples.add(detail);
      }
      row.put("samples", samples);
      writeups.add(row);
    }
    try {
      return "本批次已有材料：\n" + objectMapper.writeValueAsString(materialList)
          + "\n\n待归并写法：\n" + objectMapper.writeValueAsString(writeups)
          + "\n\n请逐条给出 index 对应关系。";
    } catch (Exception ex) {
      return "本批次已有材料：" + materialList + "\n待归并写法：" + writeups;
    }
  }

  // ---------------------------------------------------------------------------
  // 结果校验与落库
  // ---------------------------------------------------------------------------

  /**
   * 把 patch 里的字段补进已有材料：<b>只补空缺</b>，模型给了已有值也不覆盖
   * （已有值可能来自人工修正，不能被一次判同推翻）。
   *
   * <p>校验与新材料同一套：牌号要匹配正则、直径/定尺只收纯数字，不过关就当"没给"。
   */
  private MaterialIdentity.Identity mergePatch(BatchMaterial hit, JsonNode patch) {
    String grade = hit.getGrade();
    String diameter = hit.getDiameter();
    String lengthM = hit.getLengthM();
    String origin = hit.getOrigin();
    if (patch != null && patch.isObject()) {
      if (!StringUtils.hasText(grade) && MaterialIdentity.looksLikeGrade(text(patch, "grade"))) {
        grade = text(patch, "grade").toUpperCase();
      }
      if (!StringUtils.hasText(diameter)) {
        diameter = MaterialIdentity.numericOrNull(text(patch, "diameter"));
      }
      if (!StringUtils.hasText(lengthM)) {
        lengthM = MaterialIdentity.numericOrNull(text(patch, "lengthM"));
      }
      if (!StringUtils.hasText(origin)) {
        String patchOrigin = text(patch, "origin");
        origin = patchOrigin != null && patchOrigin.length() > 64
            ? patchOrigin.substring(0, 64) : patchOrigin;
      }
    }
    return MaterialIdentity.withResolved(hit.getCategory(), grade, diameter, lengthM, origin);
  }

  /** 新材料：大类走白名单/归一化兜底，直径与定尺只收纯数字，牌号必须匹配正则。 */  private MaterialIdentity.Identity newIdentity(JsonNode node, String canonicalName) {
    String category = cleanCategory(text(node, "category"), canonicalName);
    if (category == null) {
      return null;
    }
    String grade = MaterialIdentity.looksLikeGrade(text(node, "grade"))
        ? text(node, "grade").toUpperCase() : null;
    String diameter = MaterialIdentity.numericOrNull(text(node, "diameter"));
    String lengthM = MaterialIdentity.numericOrNull(text(node, "lengthM"));
    String origin = text(node, "origin");
    if (origin != null && origin.length() > 64) {
      origin = origin.substring(0, 64);
    }
    return MaterialIdentity.withResolved(category, grade, diameter, lengthM, origin);
  }

  /** 大类只认四个标准词；AI 给了别的写法时按材料名归一化兜底，仍认不出就放弃这条判定。 */
  private String cleanCategory(String raw, String canonicalName) {
    if (StringUtils.hasText(raw)) {
      String trimmed = raw.trim();
      if (MaterialCategoryNormalizer.CATEGORIES.contains(trimmed)) {
        return trimmed;
      }
      String normalized = MaterialCategoryNormalizer.category(trimmed);
      if (MaterialCategoryNormalizer.CATEGORIES.contains(normalized)) {
        return normalized;
      }
      return trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
    }
    if (StringUtils.hasText(canonicalName)) {
      String normalized = MaterialCategoryNormalizer.category(canonicalName);
      return MaterialCategoryNormalizer.CATEGORIES.contains(normalized) ? normalized : null;
    }
    return null;
  }

  /** upsert：同一写法的判定只保留一条（unique key = batch_id + raw_key）。 */
  private void upsertAlias(String batchId, String rawKey, MaterialIdentity.Identity identity,
                           String canonicalName) {
    BatchMaterialAlias existing = aliasMapper.selectOne(Wrappers.<BatchMaterialAlias>lambdaQuery()
        .eq(BatchMaterialAlias::getBatchId, batchId)
        .eq(BatchMaterialAlias::getRawKey, rawKey)
        .last("LIMIT 1"));
    if (existing == null) {
      BatchMaterialAlias alias = new BatchMaterialAlias();
      alias.setBatchId(batchId);
      alias.setRawKey(rawKey);
      alias.setCategory(identity.category());
      alias.setGrade(identity.grade());
      alias.setDiameter(identity.diameter());
      alias.setLengthM(identity.lengthM());
      alias.setOrigin(identity.origin());
      alias.setCanonicalName(canonicalName != null && canonicalName.length() > 255
          ? canonicalName.substring(0, 255) : canonicalName);
      alias.setSource(SOURCE_AI);
      alias.setCreatedAt(LocalDateTime.now());
      alias.setUpdatedAt(LocalDateTime.now());
      aliasMapper.insert(alias);
      return;
    }
    // 可空列显式 set：牌号/产地可能被重新判成"没有"
    aliasMapper.update(null, Wrappers.<BatchMaterialAlias>lambdaUpdate()
        .set(BatchMaterialAlias::getCategory, identity.category())
        .set(BatchMaterialAlias::getGrade, identity.grade())
        .set(BatchMaterialAlias::getDiameter, identity.diameter())
        .set(BatchMaterialAlias::getLengthM, identity.lengthM())
        .set(BatchMaterialAlias::getOrigin, identity.origin())
        .set(BatchMaterialAlias::getCanonicalName, canonicalName)
        .set(BatchMaterialAlias::getSource, SOURCE_AI)
        .set(BatchMaterialAlias::getUpdatedAt, LocalDateTime.now())
        .eq(BatchMaterialAlias::getId, existing.getId()));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    String text = value.asText("").trim();
    return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
  }

  private static int parseIndex(String target) {
    try {
      return Integer.parseInt(target.substring(1).trim());
    } catch (RuntimeException ex) {
      return -1;
    }
  }
}
