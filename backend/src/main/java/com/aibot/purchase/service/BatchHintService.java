package com.aibot.purchase.service;

import com.aibot.purchase.entity.Batch;
import com.aibot.purchase.entity.Project;
import com.aibot.purchase.mapper.BatchMapper;
import com.aibot.purchase.mapper.ProjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 业务默认口径（批次级 + 项目级）：用户在会话里单独发送业务口径声明时，由 AI 判定为
 * batch_hint 意图并输出「与当前口径合并后的完整结果」，本服务负责校验（键白名单 +
 * 数值合法性 + 同字段互斥）后落库：
 * <ul>
 *   <li>target=batch（默认）→ batches.business_hints，只影响本批次；</li>
 *   <li>target=project → projects.business_hints，影响该项目下所有「自己没有该字段」的批次。</li>
 * </ul>
 *
 * <p>生效范围：仅 delivery_note scope（本批次后续导入送货单、且单据/说明未提及对应内容时套用）；
 * 扩展其它单据类型 = 新增 scope key，零迁移。合并判断在 AI（能理解「改成X」「按吨算」等自然变体、
 * 能对比历史口径），后端不做句式解析。
 *
 * <p>读取合并（2026-10-28）：批次字段优先，批次为空的组回退项目口径。合并只发生在读取时，
 * 不回写批次，因此改项目口径对所有批次即时生效。
 */
@Service
public class BatchHintService {
  private static final Logger log = LoggerFactory.getLogger(BatchHintService.class);

  /** 提示词 scope：按单据类型分组，本期仅送货单。 */
  public static final String SCOPE_DELIVERY_NOTE = "delivery_note";

  /** 口径作用范围：本批次 / 整个项目（项目级口径作批次未设置字段的默认值）。 */
  public static final String TARGET_BATCH = "batch";
  public static final String TARGET_PROJECT = "project";

  public static final String KEY_SUPPLY_FREIGHT_PER_TRIP = "supplyFreightPerTrip";
  public static final String KEY_SUPPLY_FREIGHT_PER_TON = "supplyFreightPerTon";
  public static final String KEY_SALES_FREIGHT_PER_TRIP = "salesFreightPerTrip";
  public static final String KEY_SALES_FREIGHT_PER_TON = "salesFreightPerTon";
  public static final String KEY_PRICING_MODE = "pricingMode";
  public static final String KEY_UPLIFT_AMOUNT = "upliftAmount";
  public static final String KEY_MARGIN_RATE = "marginRate";

  /**
   * 互斥分组：同组键必须整体回退（批次的组存在就整组用批次），否则会出现
   * 「元/趟 + 元/吨」并存或「定价方式与金额不匹配」。
   */
  private static final List<List<String>> HINT_GROUPS = List.of(
      List.of(KEY_SUPPLY_FREIGHT_PER_TRIP, KEY_SUPPLY_FREIGHT_PER_TON),
      List.of(KEY_SALES_FREIGHT_PER_TRIP, KEY_SALES_FREIGHT_PER_TON),
      List.of(KEY_PRICING_MODE, KEY_UPLIFT_AMOUNT, KEY_MARGIN_RATE));

  /** AI payload hints 里允许写入的键由 applyFreightPair/applyPricing 固定处理，其余键一律丢弃。 */

  /**
   * 「说明文字是否提到运费」：附带信息优先——说明里自己报了运费（「运费30元/吨」）就不再套批次口径。
   * 2026-10-28 起同时识别「送货运费」「销售运费」「采购运费」等带方向前缀的写法。
   */
  private static final java.util.regex.Pattern FREIGHT_MENTION = java.util.regex.Pattern.compile(
      "(销售|送货|采购)?运费\\s*\\d+(?:\\.\\d+)?\\s*元\\s*(?:/|每|一|按)?\\s*(趟|车|吨)"
          + "|运费[^。；;]{0,8}(按吨|按趟|按车)");

  /** 供单据侧共用同一份正则（DocumentService 套用运费默认值前判断）。 */
  public static final java.util.regex.Pattern FREIGHT_MENTION_PATTERN = FREIGHT_MENTION;

  /** 说明文字是否提到运费（附带信息优先：提到就不套用批次运费默认值）。 */
  public static boolean mentionsFreight(String text) {
    return text != null && !text.isBlank() && FREIGHT_MENTION.matcher(text).find();
  }

  private final BatchMapper batchMapper;
  private final ProjectMapper projectMapper;
  private final ObjectMapper objectMapper;

  public BatchHintService(BatchMapper batchMapper, ProjectMapper projectMapper,
                          ObjectMapper objectMapper) {
    this.batchMapper = batchMapper;
    this.projectMapper = projectMapper;
    this.objectMapper = objectMapper;
  }

  /**
   * AI batch_hint 应用结果：saved=false 表示没有识别到可保存的口径（未修改）。
   * projectLevel=true 表示写的是项目级口径（projectId 非空），调用方需回填该项目所有批次。
   */
  public record AppliedHint(boolean saved, boolean projectLevel, String projectId,
                            String summary, Map<String, Object> mergedScope) {

    static AppliedHint ignored() {
      return new AppliedHint(false, false, null, null, Map.of());
    }

    /**
     * 消息文案。saved=true 时**一律用后端渲染的真实落库口径**（summary），不复述 AI 的 reply——
     * AI 复述的是它自己以为的口径，和后端校验/兜底后的结果可能不一致（2026-11-06：说「含运费」
     * 却被回执成「销售运费45元/吨」）。saved=false 时也绝不复述 AI reply，避免假报成功。
     */
    public String replyText(String aiReply) {
      String level = projectLevel ? "本项目" : "本批次";
      if (saved) {
        String body = StringUtils.hasText(summary) ? summary : aiReply;
        return "已保存" + level + "默认口径（送货单）：" + body
            + "。" + (projectLevel ? "本项目下没有单独设置的批次" : "导入送货单未单独说明时")
            + "按此执行，单据自带说明优先。";
      }
      return "这条口径没有保存成功：AI 返回的内容里没有解析到有效的口径字段，" + level
          + "默认口径未修改。请换一种说法重发（例如「运费300元/趟」「销售价网价+30」），"
          + "或在批次编辑里手动设置。";
    }

    public Map<String, Object> payload() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("schemaVersion", "1.0");
      payload.put("cardType", "batch_hint");
      payload.put("action", saved ? "saved" : "ignored");
      payload.put("target", projectLevel ? TARGET_PROJECT : TARGET_BATCH);
      payload.put("hints", mergedScope == null ? Map.of() : mergedScope);
      return payload;
    }
  }

  /**
   * 把 AI 输出的 batch_hint payload 落库。
   * 落库前只做校验：scope 固定 delivery_note、键白名单、数值合法性（>0；marginRate&lt;100）、
   * 同字段互斥（趟/吨、uplift/margin 各留一个），以及按 payload.target 决定写批次（默认）还是写项目。
   * <p>2026-11-06 起与库里已有口径<b>按组合并</b>：AI 没提到的组（例如只回了运费、没回定价口径）
   * 保留原值，不再整段替换——否则补一条运费会把定价口径冲掉，采购毛利突然变空。
   * @param rawText 用户本次原话（留痕用，仅保存时写入）
   */
  public AppliedHint applyAiHint(String batchId, Map<String, Object> aiPayload, String rawText) {
    if (aiPayload == null) {
      return AppliedHint.ignored();
    }
    boolean clear = "clear".equals(aiPayload.get("action"));
    boolean projectLevel = TARGET_PROJECT.equalsIgnoreCase(str(aiPayload.get("target")));
    Map<String, Object> aiHints = extractHints(aiPayload);
    if (!clear && aiHints.isEmpty()) {
      // AI 结构偏差：记录原始 payload 便于排查，返回显式失败
      log.warn("batch_hint payload 无法解析出口径字段 batchId={} payloadKeys={} payload={}",
          batchId, aiPayload.keySet(), safeJson(aiPayload));
    }

    Map<String, Object> scope = new LinkedHashMap<>();
    if (!clear) {
      applyFreightPair(scope, aiHints, KEY_SUPPLY_FREIGHT_PER_TRIP, KEY_SUPPLY_FREIGHT_PER_TON);
      applyFreightPair(scope, aiHints, KEY_SALES_FREIGHT_PER_TRIP, KEY_SALES_FREIGHT_PER_TON);
      applyPricing(scope, aiHints);
      // 「含运费/不另计销售运费」以用户原话为准，覆盖 AI 的返回值（2026-11-06）：
      // 模型经常把继承来的销售运费又抄回来，或把「含运费」当成普通描述，不能只靠它理解。
      clearSalesFreightIfIncluded(scope, rawText);
    }

    Batch batch = batchMapper.selectById(batchId);
    if (batch == null) {
      log.warn("batch_hint 落库失败：批次不存在 batchId={}", batchId);
      return new AppliedHint(false, projectLevel, null, null, scope);
    }
    if (projectLevel) {
      return saveProjectHint(batch.getProjectId(), scope, clear, rawText);
    }

    Map<String, Object> root = parseRoot(batch.getBusinessHints());
    if (scope.isEmpty()) {
      if (!clear) {
        // AI 没给出任何有效口径：不修改
        return new AppliedHint(false, false, null, null, Map.of());
      }
      // 全部清除（清空后该批次回退到项目级口径，这是「批次空 = 用项目」规则的直接结果）
      root.put(SCOPE_DELIVERY_NOTE, new LinkedHashMap<>());
      batch.setBusinessHints(toJson(root));
      batchMapper.updateById(batch);
      return new AppliedHint(true, false, null, "已清空", Map.of());
    }
    if (StringUtils.hasText(rawText)) {
      scope.put("rawText", rawText);
    }
    scope.put("updatedAt", LocalDateTime.now().toString());
    // 按组合并而不是整段替换（2026-11-06）：AI 只回了「运费」时，原来的定价口径不能被冲掉，
    // 否则采购毛利会突然从有到无；新 payload 里出现的组才覆盖旧值，没提到的组原样保留。
    Map<String, Object> merged = mergeScopes(deliveryNoteHints(root), scope);
    root.put(SCOPE_DELIVERY_NOTE, merged);
    batch.setBusinessHints(toJson(root));
    batchMapper.updateById(batch);
    return new AppliedHint(true, false, null, renderScope(merged), merged);
  }

  /**
   * 项目级口径落库：写 projects.business_hints（结构同批次）。
   * 2026-11-06 起按组覆盖：新 payload 里出现的组才替换，没提到的组保留原值。
   */
  private AppliedHint saveProjectHint(String projectId, Map<String, Object> scope, boolean clear,
                                      String rawText) {
    Project project = StringUtils.hasText(projectId) ? projectMapper.selectById(projectId) : null;
    if (project == null) {
      log.warn("项目口径落库失败：项目不存在 projectId={}", projectId);
      return new AppliedHint(false, true, projectId, null, scope);
    }
    if (scope.isEmpty() && !clear) {
      // AI 没给出任何有效口径：不修改
      return new AppliedHint(false, true, projectId, null, Map.of());
    }
    Map<String, Object> root = parseRoot(project.getBusinessHints());
    if (scope.isEmpty()) {
      root.put(SCOPE_DELIVERY_NOTE, new LinkedHashMap<>());
      project.setBusinessHints(toJson(root));
      projectMapper.updateById(project);
      return new AppliedHint(true, true, projectId, "已清空", Map.of());
    }
    if (StringUtils.hasText(rawText)) {
      scope.put("rawText", rawText);
    }
    scope.put("updatedAt", LocalDateTime.now().toString());
    // 与批次级同规则：按组合并，未提及的组保留原值（2026-11-06）
    Map<String, Object> merged = mergeScopes(deliveryNoteHints(root), scope);
    root.put(SCOPE_DELIVERY_NOTE, merged);
    project.setBusinessHints(toJson(root));
    projectMapper.updateById(project);
    return new AppliedHint(true, true, projectId, renderScope(merged), merged);
  }

  /**
   * 从 AI payload 提取口径字段，兼容常见结构偏差：
   * ① payload.hints 为对象（标准）；② hints 里再嵌一层 scope；③ hints 是 JSON 字符串；
   * ④ 口径字段平铺在 payload 顶层（无 hints 键）。
   */
  private Map<String, Object> extractHints(Map<String, Object> aiPayload) {
    Object hints = aiPayload.get("hints");
    Map<String, Object> result;
    if (hints instanceof Map<?, ?> map) {
      result = castMap(map);
    } else if (hints instanceof String s && !s.isBlank()) {
      try {
        result = objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {
        });
      } catch (Exception ex) {
        result = Map.of();
      }
    } else {
      result = Map.of();
    }
    // ② AI 把 scope 名又包了一层：{"hints":{"delivery_note":{...}}}
    if (result.size() == 1 && result.get(SCOPE_DELIVERY_NOTE) instanceof Map<?, ?> nested) {
      result = castMap(nested);
    }
    // ④ 口径字段平铺在 payload 顶层
    if (result.isEmpty() && hasKnownHintKey(aiPayload)) {
      result = castMap(aiPayload);
    }
    return result;
  }

  private boolean hasKnownHintKey(Map<String, Object> map) {
    return map.containsKey(KEY_SUPPLY_FREIGHT_PER_TRIP) || map.containsKey(KEY_SUPPLY_FREIGHT_PER_TON)
        || map.containsKey(KEY_SALES_FREIGHT_PER_TRIP) || map.containsKey(KEY_SALES_FREIGHT_PER_TON)
        || map.containsKey(KEY_PRICING_MODE) || map.containsKey(KEY_UPLIFT_AMOUNT)
        || map.containsKey(KEY_MARGIN_RATE);
  }

  private String safeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      return String.valueOf(value);
    }
  }

  /**
   * 趟/吨互斥：固定顺序写入（后写覆盖先写），同字段只保留一个口径。
   *
   * <p>2026-11-06：允许 <b>0</b>，语义是「显式清零」——「销售价含运费/不另计销售运费」时 AI 返回
   * {@code salesFreightPerTon: 0}，这里必须把它落库，否则按组合并会保留旧的销售运费，
   * 或让项目级销售运费继续继承下来。
   */
  private void applyFreightPair(Map<String, Object> scope, Map<String, Object> aiHints,
                                String perTripKey, String perTonKey) {
    BigDecimal perTrip = nonNegative(aiHints.get(perTripKey));
    BigDecimal perTon = nonNegative(aiHints.get(perTonKey));
    if (perTrip != null) {
      scope.put(perTripKey, perTrip);
      scope.remove(perTonKey);
    }
    if (perTon != null) {
      scope.put(perTonKey, perTon);
      scope.remove(perTripKey);
    }
  }

  /** 「含运费」这类否定式表述：命中即把销售运费清零，覆盖 AI 返回的值。 */
  private static final java.util.List<String> FREIGHT_INCLUDED = java.util.List.of(
      "含运费", "含运价", "运费含在", "运费包含在", "运费算在价", "运费已含", "包运费",
      "不另计销售运费", "不另收运费", "不收运费", "免运费");

  /** 反向表述，命中则不清理（「不含运费」也含「含运费」，必须先判这个）。 */
  private static final java.util.List<String> FREIGHT_EXCLUDED = java.util.List.of(
      "不含运费", "不含运价", "不含送货运费");

  /**
   * 「销售价含运费 / 不另计销售运费」→ 销售运费显式写 0（含在售价里，客户不另付）。
   *
   * <p>不依赖模型理解：AI 常把继承来的销售运费原样抄回来，或把「含运费」当普通描述，
   * 只靠它返回 0 不可靠（2026-11-06 实测：说「网价+37（含运费）」被保存成还收 45 元/吨）。
   * 这里以用户本次原话为准做确定性覆盖，写 0 会按组覆盖掉批次旧值和项目级继承。
   */
  private void clearSalesFreightIfIncluded(Map<String, Object> scope, String rawText) {
    if (!StringUtils.hasText(rawText)) {
      return;
    }
    String text = rawText.replaceAll("\\s+", "");
    if (FREIGHT_EXCLUDED.stream().anyMatch(text::contains)) {
      return;
    }
    if (FREIGHT_INCLUDED.stream().noneMatch(text::contains)) {
      return;
    }
    scope.remove(KEY_SALES_FREIGHT_PER_TRIP);
    scope.put(KEY_SALES_FREIGHT_PER_TON, BigDecimal.ZERO);
  }

  /** uplift/margin 互斥；mode 缺失时按金额推导。 */  private void applyPricing(Map<String, Object> scope, Map<String, Object> aiHints) {
    String mode = str(aiHints.get(KEY_PRICING_MODE));
    BigDecimal uplift = positive(aiHints.get(KEY_UPLIFT_AMOUNT));
    BigDecimal marginRate = positive(aiHints.get(KEY_MARGIN_RATE));
    if (marginRate != null && marginRate.compareTo(BigDecimal.valueOf(100)) >= 0) {
      marginRate = null; // 毛利率≥100% 非法，丢弃
    }
    if ("margin".equals(mode) && marginRate != null) {
      scope.put(KEY_PRICING_MODE, "margin");
      scope.put(KEY_MARGIN_RATE, marginRate);
      return;
    }
    if ("uplift".equals(mode) && uplift != null) {
      scope.put(KEY_PRICING_MODE, "uplift");
      scope.put(KEY_UPLIFT_AMOUNT, uplift);
      return;
    }
    if (uplift != null) {
      scope.put(KEY_PRICING_MODE, "uplift");
      scope.put(KEY_UPLIFT_AMOUNT, uplift);
    } else if (marginRate != null) {
      scope.put(KEY_PRICING_MODE, "margin");
      scope.put(KEY_MARGIN_RATE, marginRate);
    }
  }

  // ---------------------------------------------------------------------------
  // 消费端：AI prompt 渲染 / 送货单默认值兜底
  // ---------------------------------------------------------------------------

  /** 口径渲染成 prompt 一句话（入参是 business_hints 根对象）；无口径返回 null。 */
  public String hintText(Map<String, Object> businessHints) {
    return renderScope(deliveryNoteHints(businessHints));
  }

  /** 当前口径的 JSON 文本（供 AI batch_hint 合并时对比历史口径）；无口径返回 null。 */
  public String hintsJson(Map<String, Object> businessHints) {
    return toScopeJson(deliveryNoteHints(businessHints));
  }

  /** scope 渲染成一句话；无有效键返回 null。 */
  public String renderScope(Map<String, Object> scope) {
    if (scope == null || scope.isEmpty()) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    BigDecimal supplyPerTrip = decimal(scope.get(KEY_SUPPLY_FREIGHT_PER_TRIP));
    BigDecimal supplyPerTon = decimal(scope.get(KEY_SUPPLY_FREIGHT_PER_TON));
    BigDecimal salesPerTrip = decimal(scope.get(KEY_SALES_FREIGHT_PER_TRIP));
    BigDecimal salesPerTon = decimal(scope.get(KEY_SALES_FREIGHT_PER_TON));
    if (supplyPerTrip != null) {
      // 元/趟 = 整批一趟包干价，不乘单据张数
      parts.add(supplyPerTrip.signum() == 0 ? "采购运费不另计"
          : "采购运费" + plain(supplyPerTrip) + "元/趟（整批一趟包干）");
    }
    if (supplyPerTon != null) {
      parts.add(supplyPerTon.signum() == 0 ? "采购运费不另计"
          : "采购运费" + plain(supplyPerTon) + "元/吨");
    }
    if (salesPerTrip != null) {
      // 元/趟 = 整批一趟包干价（与采购运费同口径），不按送货单张数乘；0 = 含在售价里，不另收
      parts.add(salesPerTrip.signum() == 0 ? "销售运费不另计（含在售价里）"
          : "销售运费" + plain(salesPerTrip) + "元/趟（整批一趟包干）");
    }
    if (salesPerTon != null) {
      parts.add(salesPerTon.signum() == 0 ? "销售运费不另计（含在售价里）"
          : "销售运费" + plain(salesPerTon) + "元/吨");
    }
    if ("uplift".equals(scope.get(KEY_PRICING_MODE)) && decimal(scope.get(KEY_UPLIFT_AMOUNT)) != null) {
      parts.add("销售价=网价+" + plain(decimal(scope.get(KEY_UPLIFT_AMOUNT))) + "元/吨");
    } else if ("margin".equals(scope.get(KEY_PRICING_MODE)) && decimal(scope.get(KEY_MARGIN_RATE)) != null) {
      BigDecimal rate = decimal(scope.get(KEY_MARGIN_RATE));
      // 毛利率口径以采购价为成本倒推（采购单自身明细 / 送货单匹配到的同批次采购单）
      parts.add("销售价=采购价÷" + plain(BigDecimal.valueOf(100).subtract(rate)) + "%（毛利率" + plain(rate) + "%）");
    }
    return parts.isEmpty() ? null : String.join("；", parts);
  }

  /**
   * 生效的送货单口径（**读口径的唯一入口**，2026-10-28 起含项目级回退）：
   * 项目口径打底 + 批次口径按组覆盖——批次没给的组才用项目的。
   * 传 Batch 而非 id 是为了兼容既有调用点（都用 batchMapper.selectById 取到的实体）。
   */
  public Map<String, Object> deliveryNoteHints(Batch batch) {
    if (batch == null) {
      return Map.of();
    }
    return mergeScopes(
        projectDeliveryNoteHints(batch.getProjectId()),
        deliveryNoteHints(parseRoot(batch.getBusinessHints())));
  }

  /** 项目级送货单口径（不含批次覆盖）；无则空 map。 */
  public Map<String, Object> projectDeliveryNoteHints(String projectId) {
    if (!StringUtils.hasText(projectId)) {
      return Map.of();
    }
    Project project = projectMapper.selectById(projectId);
    return project == null ? Map.of() : deliveryNoteHints(parseRoot(project.getBusinessHints()));
  }

  /**
   * 按组合并：批次给了某组（如采购运费组）就整组用批次，否则用项目；组之间互不影响，
   * 所以「项目配销售运费、批次配采购运费」能同时生效。
   */
  public static Map<String, Object> mergeScopes(Map<String, Object> projectScope,
                                                Map<String, Object> batchScope) {
    Map<String, Object> base = projectScope == null ? Map.of() : projectScope;
    Map<String, Object> override = batchScope == null ? Map.of() : batchScope;
    if (override.isEmpty()) {
      return base;
    }
    if (base.isEmpty()) {
      return override;
    }
    Map<String, Object> merged = new LinkedHashMap<>(base);
    boolean batchTouchedHints = false;
    for (List<String> group : HINT_GROUPS) {
      if (group.stream().anyMatch(override::containsKey)) {
        group.forEach(merged::remove);
        batchTouchedHints = true;
      }
    }
    if (batchTouchedHints) {
      // 批次 scope 里可能带 rawText/updatedAt，一并带进生效口径（不影响取值，只作留痕）
      merged.putAll(override);
    }
    return merged;
  }

  /**
   * AI 上下文所需的两层口径（一次查库）：生效文本用于「填补单据空缺」的说明，
   * 批次/项目两份 JSON 分别作 target=batch / target=project 的合并基准——
   * 只给合并后的结果会让 AI 把项目值当成批次值写回去。
   */
  public record HintContext(String effectiveText, String batchJson, String projectJson) {

    static final HintContext EMPTY = new HintContext(null, null, null);
  }

  public HintContext contextFor(String batchId) {
    if (!StringUtils.hasText(batchId)) {
      return HintContext.EMPTY;
    }
    Batch batch = batchMapper.selectById(batchId);
    if (batch == null) {
      return HintContext.EMPTY;
    }
    Map<String, Object> projectScope = projectDeliveryNoteHints(batch.getProjectId());
    Map<String, Object> batchScope = deliveryNoteHints(parseRoot(batch.getBusinessHints()));
    return new HintContext(
        renderScope(mergeScopes(projectScope, batchScope)),
        toScopeJson(batchScope),
        toScopeJson(projectScope));
  }

  /** 取送货单 scope 的口径（从 business_hints 根对象里剥一层）；无则空 map。 */
  public Map<String, Object> deliveryNoteHints(Map<String, Object> businessHints) {
    if (businessHints == null || businessHints.isEmpty()) {
      return Map.of();
    }
    Object scope = businessHints.get(SCOPE_DELIVERY_NOTE);
    if (!(scope instanceof Map<?, ?> map) || map.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((k, v) -> result.put(String.valueOf(k), v));
    return result;
  }

  private String toScopeJson(Map<String, Object> scope) {
    if (scope == null || scope.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(scope);
    } catch (Exception ex) {
      log.warn("口径 JSON 序列化失败：{}", ex.getMessage());
      return null;
    }
  }

  /** 从 scope 取数值键；兼容 Jackson 反序列化出的 Integer/Double。 */
  public static BigDecimal decimal(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof BigDecimal bd) {
      return bd;
    }
    if (value instanceof Number number) {
      return new BigDecimal(String.valueOf(number));
    }
    try {
      String s = String.valueOf(value).trim();
      return s.isEmpty() ? null : new BigDecimal(s);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static BigDecimal positive(Object value) {
    BigDecimal v = decimal(value);
    return v != null && v.signum() > 0 ? v : null;
  }

  /** 运费口径允许 0（= 显式清零「不另计」），负数视为非法丢弃。 */
  private static BigDecimal nonNegative(Object value) {
    BigDecimal v = decimal(value);
    return v != null && v.signum() >= 0 ? v : null;
  }

  private static String plain(BigDecimal value) {
    return (value == null ? BigDecimal.ZERO : value).stripTrailingZeros().toPlainString();
  }

  private static String str(Object value) {
    if (value == null) {
      return null;
    }
    String s = String.valueOf(value).trim();
    return s.isEmpty() ? null : s;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Map<?, ?> map) {
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((k, v) -> result.put(String.valueOf(k), v));
    return result;
  }

  private Map<String, Object> parseRoot(String json) {
    if (json == null || json.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
      });
    } catch (Exception ex) {
      log.warn("批次口径 JSON 解析失败，按空处理：{}", ex.getMessage());
      return new LinkedHashMap<>();
    }
  }

  private String toJson(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalStateException("批次口径序列化失败", ex);
    }
  }
}
