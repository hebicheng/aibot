package com.aibot.purchase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aibot.purchase.entity.Batch;
import com.aibot.purchase.entity.Project;
import com.aibot.purchase.mapper.BatchMapper;
import com.aibot.purchase.mapper.ProjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 业务默认口径（batch_hint）落库与渲染测试：AI 已完成合并，这里验证后端校验
 * （键白名单、数值合法性、趟/吨与 uplift/margin 互斥）、清除语义、prompt 渲染，
 * 以及项目级口径「批次优先、缺组回退项目」的合并规则。
 */
@ExtendWith(MockitoExtension.class)
class BatchHintServiceTest {
  @Mock private BatchMapper batchMapper;
  @Mock private ProjectMapper projectMapper;

  private BatchHintService service;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    service = new BatchHintService(batchMapper, projectMapper, objectMapper);
  }

  private Batch batchWithHints(String json) {
    Batch batch = new Batch();
    batch.setId("b1");
    batch.setBusinessHints(json);
    return batch;
  }

  private Batch batchInProject(String projectId, String json) {
    Batch batch = batchWithHints(json);
    batch.setProjectId(projectId);
    return batch;
  }

  private Project projectWithHints(String json) {
    Project project = new Project();
    project.setId("p1");
    project.setBusinessHints(json);
    return project;
  }

  private Map<String, Object> payload(String action, Map<String, Object> hints) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("scope", BatchHintService.SCOPE_DELIVERY_NOTE);
    payload.put("action", action);
    payload.put("hints", hints);
    return payload;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> savedScope() throws Exception {
    ArgumentCaptor<Batch> captor = ArgumentCaptor.forClass(Batch.class);
    verify(batchMapper).updateById(captor.capture());
    Map<String, Object> root = objectMapper.readValue(
        captor.getValue().getBusinessHints(), new TypeReference<>() {
        });
    return (Map<String, Object>) root.get(BatchHintService.SCOPE_DELIVERY_NOTE);
  }

  @Test
  void savesMergedHintsFromAiPayload() throws Exception {
    when(batchMapper.selectById("b1")).thenReturn(batchWithHints(null));

    var applied = service.applyAiHint("b1",
        payload("update", Map.of(
            "supplyFreightPerTrip", 300,
            "pricingMode", "uplift",
            "upliftAmount", 30)),
        "运费300元/趟，钢筋销售价：网价+30");

    assertThat(applied.saved()).isTrue();
    assertThat(applied.replyText("")).contains("默认口径");
    Map<String, Object> scope = savedScope();
    assertThat(scope.get("supplyFreightPerTrip")).isEqualTo(300);
    assertThat(scope.get("pricingMode")).isEqualTo("uplift");
    assertThat(scope.get("rawText")).isEqualTo("运费300元/趟，钢筋销售价：网价+30");
    assertThat(scope.get("updatedAt")).isNotNull();
  }

  @Test
  void unitSwitchDeletesOldKey() throws Exception {
    when(batchMapper.selectById("b1"))
        .thenReturn(batchWithHints("{\"delivery_note\":{\"supplyFreightPerTrip\":300}}"));

    // AI 已按合并规则输出「趟改吨」后的结果：只保留 perTon
    var applied = service.applyAiHint("b1",
        payload("update", Map.of("supplyFreightPerTon", 25)), "运费改成25元一吨");

    assertThat(applied.saved()).isTrue();
    Map<String, Object> scope = savedScope();
    assertThat(scope.get("supplyFreightPerTon")).isEqualTo(25);
    assertThat(scope.get("supplyFreightPerTrip")).isNull();
  }

  @Test
  void invalidValuesAreDropped() {
    when(batchMapper.selectById("b1")).thenReturn(batchWithHints(null));

    var applied = service.applyAiHint("b1",
        payload("update", Map.of(
            "supplyFreightPerTrip", -5,
            "salesFreightPerTon", -1,
            "pricingMode", "margin",
            "marginRate", 120)),
        "乱写的");

    // 全部值非法（负数/超范围）→ 有效口径为空 → 不落库、不修改
    assertThat(applied.saved()).isFalse();
    verify(batchMapper, never()).updateById(any(Batch.class));
  }

  @Test
  void zeroSalesFreightIsAValidExplicitValue() throws Exception {
    when(batchMapper.selectById("b1")).thenReturn(batchWithHints(null));

    // 0 不是非法值：它是「不另计（含在售价里）」的口径，必须落库
    var applied = service.applyAiHint("b1",
        payload("update", Map.of(
            "salesFreightPerTon", 0,
            "pricingMode", "uplift",
            "upliftAmount", 37)),
        "销售价网价+37");

    assertThat(applied.saved()).isTrue();
    Map<String, Object> scope = savedScope();
    assertThat(dec(scope.get("salesFreightPerTon")).signum()).isZero();
    assertThat(scope.get("upliftAmount")).isEqualTo(37);
  }

  @Test
  void zeroSalesFreightBlocksProjectFallback() throws Exception {
    when(batchMapper.selectById("b1")).thenReturn(batchInProject("p1", null));

    service.applyAiHint("b1",
        payload("update", Map.of(
            "salesFreightPerTon", 0,
            "pricingMode", "uplift",
            "upliftAmount", 37)),
        "销售价网价+37，含运费");

    Map<String, Object> batchScope = savedScope();
    Map<String, Object> merged = BatchHintService.mergeScopes(
        parseJson("{\"salesFreightPerTon\":45}"), batchScope);

    // 批次写 0 = 口径存在且为 0 → 必须压掉项目级的 45，不能回退
    assertThat(dec(merged.get("salesFreightPerTon")).signum()).isZero();
  }

  @Test
  void includedFreightKeywordForcesZeroEvenIfAiReturnsValue() throws Exception {
    when(batchMapper.selectById("b1"))
        .thenReturn(batchWithHints("{\"delivery_note\":{\"salesFreightPerTon\":45}}"));

    // 复现 2026-11-06 的线上问题：AI 把继承来的 45 又抄回来，原话明确「含运费」
    service.applyAiHint("b1",
        payload("update", Map.of(
            "salesFreightPerTon", 45,
            "pricingMode", "uplift",
            "upliftAmount", 37,
            "supplyFreightPerTon", 30)),
        "销售价为网价+37（含运费）");

    Map<String, Object> scope = savedScope();
    assertThat(dec(scope.get("salesFreightPerTon")).signum()).isZero();
    assertThat(scope).doesNotContainKey("salesFreightPerTrip");
  }

  @Test
  void notIncludedFreightDoesNotForceZero() throws Exception {
    when(batchMapper.selectById("b1")).thenReturn(batchWithHints(null));

    service.applyAiHint("b1",
        payload("update", Map.of("salesFreightPerTon", 30)), "销售运费30元/吨，不含运费的那部分另算");

    Map<String, Object> scope = savedScope();
    assertThat(dec(scope.get("salesFreightPerTon"))).isEqualByComparingTo("30");
  }

  @Test
  void clearEmptiesScope() throws Exception {
    when(batchMapper.selectById("b1"))
        .thenReturn(batchWithHints("{\"delivery_note\":{\"supplyFreightPerTrip\":300}}"));

    var applied = service.applyAiHint("b1", payload("clear", Map.of()), "取消所有默认口径");

    assertThat(applied.saved()).isTrue();
    Map<String, Object> scope = savedScope();
    assertThat(scope.get("supplyFreightPerTrip")).isNull();
  }

  @Test
  void emptyValidHintsMeansNoChange() {
    when(batchMapper.selectById("b1")).thenReturn(batchWithHints(null));

    var applied = service.applyAiHint("b1",
        payload("update", Map.of("unknownKey", 1)), "随便说说");

    assertThat(applied.saved()).isFalse();
    // 关键：未保存时绝不复述 AI 的「已保存」话术，必须显式报失败
    assertThat(applied.replyText("已保存本批次默认口径：……"))
        .contains("没有保存成功").doesNotContain("已保存本批次默认口径：……");
    verify(batchMapper, never()).updateById(any(Batch.class));
  }

  @Test
  void acceptsHintsAsJsonString() throws Exception {
    when(batchMapper.selectById("b1")).thenReturn(batchWithHints(null));

    // AI 把 hints 序列化成 JSON 字符串的变体
    Map<String, Object> aiPayload = payload("update", Map.of());
    aiPayload.put("hints", "{\"supplyFreightPerTrip\":1300}");
    var applied = service.applyAiHint("b1", aiPayload, "运费1300元/趟");

    assertThat(applied.saved()).isTrue();
    assertThat(savedScope().get("supplyFreightPerTrip")).isEqualTo(1300);
  }

  @Test
  void acceptsScopeNestedInsideHints() throws Exception {
    when(batchMapper.selectById("b1")).thenReturn(batchWithHints(null));

    var applied = service.applyAiHint("b1",
        payload("update", Map.of("delivery_note", Map.of("pricingMode", "uplift", "upliftAmount", 100))),
        "销售价网价+100");

    assertThat(applied.saved()).isTrue();
    Map<String, Object> scope = savedScope();
    assertThat(scope.get("pricingMode")).isEqualTo("uplift");
    assertThat(scope.get("upliftAmount")).isEqualTo(100);
  }

  @Test
  void acceptsFlatKnownKeysOnPayloadTopLevel() throws Exception {
    when(batchMapper.selectById("b1")).thenReturn(batchWithHints(null));

    Map<String, Object> flat = new LinkedHashMap<>();
    flat.put("scope", BatchHintService.SCOPE_DELIVERY_NOTE);
    flat.put("action", "update");
    flat.put("supplyFreightPerTrip", 1300);
    flat.put("salesFreightPerTrip", 1500);
    var applied = service.applyAiHint("b1", flat, "运费1300元/趟，销售运费1500元/趟");

    assertThat(applied.saved()).isTrue();
    Map<String, Object> scope = savedScope();
    assertThat(scope.get("supplyFreightPerTrip")).isEqualTo(1300);
    assertThat(scope.get("salesFreightPerTrip")).isEqualTo(1500);
  }

  @Test
  void unknownBatchIsIgnoredSafely() {
    when(batchMapper.selectById("missing")).thenReturn(null);

    var applied = service.applyAiHint("missing",
        payload("update", Map.of("supplyFreightPerTrip", 300)), "运费300元/趟");

    assertThat(applied.saved()).isFalse();
  }

  @Test
  void rendersHintTextForPrompt() {
    assertThat(service.hintText(null)).isNull();
    assertThat(service.hintText(Map.of())).isNull();

    String text = service.hintText(parseJson("""
        {"delivery_note":{"supplyFreightPerTrip":300,"salesFreightPerTon":25,
        "pricingMode":"uplift","upliftAmount":30}}
        """));
    assertThat(text).contains("采购运费300元/趟").contains("销售运费25元/吨").contains("销售价=网价+30元/吨");

    String marginText = service.hintText(parseJson(
        "{\"delivery_note\":{\"pricingMode\":\"margin\",\"marginRate\":2.5}}"));
    // 毛利率口径以采购价为成本倒推（2026-10-28），不再是网价
    assertThat(marginText).contains("销售价=采购价÷97.5%").contains("毛利率2.5%");
  }

  @Test
  void rendersZeroFreightAsIncluded() {
    // 0 = 显式「不另计」，渲染成人话而不是「0元/吨」
    String text = service.hintText(parseJson(
        "{\"delivery_note\":{\"salesFreightPerTon\":0,\"pricingMode\":\"uplift\",\"upliftAmount\":37}}"));
    assertThat(text).contains("销售运费不另计（含在售价里）").contains("销售价=网价+37元/吨");
  }

  @Test
  void hintsJsonReturnsScopeJsonOrNull() {
    assertThat(service.hintsJson(null)).isNull();
    assertThat(service.hintsJson(parseJson("{\"supply_order\":{\"foo\":1}}"))).isNull();
    String json = service.hintsJson(parseJson("{\"delivery_note\":{\"supplyFreightPerTrip\":300}}"));
    assertThat(json).contains("supplyFreightPerTrip").contains("300");
  }

  @Test
  void deliveryNoteHintsReadsScopeOnly() {
    assertThat(service.deliveryNoteHints((Batch) null)).isEmpty();
    assertThat(service.deliveryNoteHints((Map<String, Object>) null)).isEmpty();
    assertThat(service.deliveryNoteHints(parseJson("{\"supply_order\":{\"foo\":1}}"))).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // 项目级口径（2026-10-28）：批次优先、缺组回退项目
  // ---------------------------------------------------------------------------

  @Test
  void projectHintAppliesWhenBatchHasNone() {
    when(batchMapper.selectById("b1")).thenReturn(batchInProject("p1", null));
    when(projectMapper.selectById("p1")).thenReturn(projectWithHints(
        "{\"delivery_note\":{\"supplyFreightPerTon\":30,\"pricingMode\":\"uplift\",\"upliftAmount\":30}}"));

    Map<String, Object> hints = service.deliveryNoteHints(batchMapper.selectById("b1"));

    assertThat(hints.get("supplyFreightPerTon")).isEqualTo(30);
    assertThat(hints.get("pricingMode")).isEqualTo("uplift");
    assertThat(hints.get("upliftAmount")).isEqualTo(30);
  }

  @Test
  void batchAndProjectMixByGroup() {
    // 项目：采购运费按吨 30 + 销售运费按吨 25；批次：采购运费按趟 300
    when(batchMapper.selectById("b1")).thenReturn(batchInProject("p1",
        "{\"delivery_note\":{\"supplyFreightPerTrip\":300}}"));
    when(projectMapper.selectById("p1")).thenReturn(projectWithHints(
        "{\"delivery_note\":{\"supplyFreightPerTon\":30,\"salesFreightPerTon\":25}}"));

    Map<String, Object> hints = service.deliveryNoteHints(batchMapper.selectById("b1"));

    // 采购运费组整组用批次：只留元/趟，项目那侧的元/吨不能残留
    assertThat(hints.get("supplyFreightPerTrip")).isEqualTo(300);
    assertThat(hints).doesNotContainKey("supplyFreightPerTon");
    // 销售运费组批次没给 → 用项目
    assertThat(hints.get("salesFreightPerTon")).isEqualTo(25);
  }

  @Test
  void batchPricingWinsOverProjectPricingAsWholeGroup() {
    when(batchMapper.selectById("b1")).thenReturn(batchInProject("p1",
        "{\"delivery_note\":{\"pricingMode\":\"margin\",\"marginRate\":2.5}}"));
    when(projectMapper.selectById("p1")).thenReturn(projectWithHints(
        "{\"delivery_note\":{\"pricingMode\":\"uplift\",\"upliftAmount\":30}}"));

    Map<String, Object> hints = service.deliveryNoteHints(batchMapper.selectById("b1"));

    assertThat(hints.get("pricingMode")).isEqualTo("margin");
    assertThat(hints.get("marginRate")).isEqualTo(2.5);
    assertThat(hints).doesNotContainKey("upliftAmount");
  }

  @Test
  void projectTargetSavesToProjectNotBatch() {
    when(batchMapper.selectById("b1")).thenReturn(batchInProject("p1", null));
    when(projectMapper.selectById("p1")).thenReturn(projectWithHints(null));

    Map<String, Object> payload = payload("update",
        Map.of("salesFreightPerTon", 30, "pricingMode", "uplift", "upliftAmount", 30));
    payload.put("target", BatchHintService.TARGET_PROJECT);
    var applied = service.applyAiHint("b1", payload, "整个项目统一网价+30，销售运费30一吨");

    assertThat(applied.saved()).isTrue();
    assertThat(applied.projectLevel()).isTrue();
    assertThat(applied.projectId()).isEqualTo("p1");
    assertThat(applied.replyText("")).contains("本项目");
    verify(batchMapper, never()).updateById(any(Batch.class));

    ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
    verify(projectMapper).updateById(captor.capture());
    Map<String, Object> root = parseJson(captor.getValue().getBusinessHints());
    @SuppressWarnings("unchecked")
    Map<String, Object> scope = (Map<String, Object>) root.get(BatchHintService.SCOPE_DELIVERY_NOTE);
    assertThat(scope.get("salesFreightPerTon")).isEqualTo(30);
    assertThat(scope.get("rawText")).isEqualTo("整个项目统一网价+30，销售运费30一吨");
  }

  @Test
  void defaultTargetStaysBatch() {
    when(batchMapper.selectById("b1")).thenReturn(batchInProject("p1", null));

    var applied = service.applyAiHint("b1",
        payload("update", Map.of("supplyFreightPerTrip", 300)), "这批运费300一趟");

    assertThat(applied.saved()).isTrue();
    assertThat(applied.projectLevel()).isFalse();
    verify(projectMapper, never()).updateById(any(Project.class));
  }

  @Test
  void clearingBatchFallsBackToProject() {
    // 批次清空后不该出现「什么都不用」的错觉：读口径时自动回退项目
    when(batchMapper.selectById("b1"))
        .thenReturn(batchInProject("p1", "{\"delivery_note\":{\"salesFreightPerTon\":25}}"));
    when(projectMapper.selectById("p1")).thenReturn(projectWithHints(
        "{\"delivery_note\":{\"salesFreightPerTon\":30}}"));

    var applied = service.applyAiHint("b1", payload("clear", Map.of()), "取消本批次口径");
    assertThat(applied.saved()).isTrue();

    Batch reloaded = batchInProject("p1", "{\"delivery_note\":{}}");
    assertThat(service.deliveryNoteHints(reloaded).get("salesFreightPerTon")).isEqualTo(30);
  }

  @Test
  void contextForExposesBothLayers() {
    when(batchMapper.selectById("b1")).thenReturn(batchInProject("p1",
        "{\"delivery_note\":{\"supplyFreightPerTrip\":300}}"));
    when(projectMapper.selectById("p1")).thenReturn(projectWithHints(
        "{\"delivery_note\":{\"salesFreightPerTon\":25}}"));

    var context = service.contextFor("b1");

    // 生效口径两句都要有（采购运费来自批次、销售运费来自项目）
    assertThat(context.effectiveText()).contains("采购运费300元/趟").contains("销售运费25元/吨");
    assertThat(context.batchJson()).contains("supplyFreightPerTrip").doesNotContain("salesFreightPerTon");
    assertThat(context.projectJson()).contains("salesFreightPerTon").doesNotContain("supplyFreightPerTrip");
  }

  /** 落库 JSON 回读后数字类型可能是 Integer，统一用字符串构造 BigDecimal 断言。 */
  private static java.math.BigDecimal dec(Object value) {
    return value == null ? null : new java.math.BigDecimal(String.valueOf(value));
  }

  private Map<String, Object> parseJson(String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
      });
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
