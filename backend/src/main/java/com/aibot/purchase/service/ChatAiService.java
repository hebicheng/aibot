package com.aibot.purchase.service;

import com.aibot.purchase.config.AiProperties;
import com.aibot.purchase.service.ai.AiReply;
import com.aibot.purchase.service.ai.QwenClient;
import com.aibot.purchase.service.ai.QwenClient.QwenResult;
import com.aibot.purchase.service.ai.QwenClient.QwenUsage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 编排服务：把用户文本/文件 URL 交给千问大模型，得到结构化意图与单据 payload。
 * 支持纯文本（短信、手动输入）和多模态（图片/PDF 直传 VL 模型）两种模式。
 * 已去除 OCR 环节，大模型直接读图/PDF 完成识别+结构化。
 */
@Service
public class ChatAiService {
  private static final Logger log = LoggerFactory.getLogger(ChatAiService.class);

  private static final List<String> INTENTS = List.of(
      "supply_order_card", "delivery_note_card", "transport_note_card", "payment_request_card",
      "payment_card", "receipt_card", "purchase_invoice_card", "sales_invoice_card",
      "invoice_card", "sales_settlement_card", "settlement_card", "clarify_card",
      "evidence_only_card", "reconciliation_export", "batch_hint", "market_quote_card",
      "non_business");

  private final AiProperties aiProperties;
  private final QwenClient qwenClient;
  private final ObjectMapper objectMapper;

  public ChatAiService(AiProperties aiProperties, QwenClient qwenClient, ObjectMapper objectMapper) {
    this.aiProperties = aiProperties;
    this.qwenClient = qwenClient;
    this.objectMapper = objectMapper;
  }

  public record AiContext(
      String companyName,
      String projectName,
      String batchName,
      String customerName,
      /** 生效口径（批次优先、缺项回退项目）渲染文本；null=无任何默认口径。仅作送货单字段默认值。 */
      String batchHintText,
      /** 批次自身口径原始 JSON（target=batch 的合并基准）；null=本批次未单独设置。 */
      String batchHintsJson,
      /** 项目级口径原始 JSON（target=project 的合并基准）；null=项目未设置。 */
      String projectHintsJson
  ) {
    public AiContext(String projectName, String batchName) {
      this(null, projectName, batchName, null, null, null, null);
    }

    /** 兼容旧调用点：不带批次口径。 */
    public AiContext(String companyName, String projectName, String batchName, String customerName) {
      this(companyName, projectName, batchName, customerName, null, null, null);
    }

    /** 带口径渲染文本、不带 JSON 的调用点。 */
    public AiContext(String companyName, String projectName, String batchName,
                     String customerName, String batchHintText) {
      this(companyName, projectName, batchName, customerName, batchHintText, null, null);
    }

    /** 只有批次口径、没有项目口径的调用点。 */
    public AiContext(String companyName, String projectName, String batchName,
                     String customerName, String batchHintText, String batchHintsJson) {
      this(companyName, projectName, batchName, customerName, batchHintText, batchHintsJson, null);
    }
  }

  /**
   * AI 分析结果：结构化意图（{@link AiReply}）+ 本次大模型 token 用量（{@link QwenUsage}）。
   * mock / 空输入场景 usage 为 {@link QwenUsage#ZERO}，不计费。
   */
  public record AiAnalysisResult(AiReply reply, QwenUsage usage) {
  }

  /**
   * 纯文本分析（短信、手动输入等）
   * @param priorClarification 上一轮待澄清的追问内容（question/options/requestedType/detectedType），
   *                           用于把用户的补充回答与原始追问关联起来，避免重新从零判断。
   */
  public AiAnalysisResult analyzeText(
      String userText,
      AiContext context,
      Map<String, Object> priorClarification
  ) {
    if (!StringUtils.hasText(userText)) {
      return new AiAnalysisResult(
          AiReply.text("non_business", "我没有读到有效内容，请上传单据或描述业务信息。"),
          QwenUsage.ZERO);
    }
    if (aiProperties.mock() || !qwenClient.isConfigured()) {
      return new AiAnalysisResult(mockReply(userText), QwenUsage.ZERO);
    }
    try {
      QwenResult result = qwenClient.completeJson(
          systemPrompt(), textUserPrompt(userText, context, priorClarification));
      return new AiAnalysisResult(parse(result.content(), context, userText), result.usage());
    } catch (Exception ex) {
      log.warn("千问文本调用失败：{}", ex.getMessage());
      return new AiAnalysisResult(
          AiReply.text("non_business", "AI 识别暂时不可用，请稍后重试或手动录入。"),
          QwenUsage.ZERO);
    }
  }

  public AiAnalysisResult analyzeText(String userText, AiContext context) {
    return analyzeText(userText, context, null);
  }

  /**
   * 多模态分析（图片/PDF 文件）—— 大模型直接读文件，无需 OCR
   */
  public AiAnalysisResult analyzeFile(
      List<QwenClient.MediaInput> mediaInputs,
      String extractedText,
      String userText,
      AiContext context
  ) {
    return analyzeFile(mediaInputs, extractedText, userText, context, null);
  }

  /**
   * 多模态分析（图片/PDF 文件）—— 大模型直接读文件，无需 OCR
   * @param priorClarification 上一轮待澄清的追问内容，续接处理时把原问题与新说明一起交给模型。
   */
  public AiAnalysisResult analyzeFile(
      List<QwenClient.MediaInput> mediaInputs,
      String extractedText,
      String userText,
      AiContext context,
      Map<String, Object> priorClarification
  ) {
    if ((mediaInputs == null || mediaInputs.isEmpty()) && !StringUtils.hasText(extractedText)) {
      return new AiAnalysisResult(
          AiReply.text("non_business", "未收到有效文件，请重新上传。"),
          QwenUsage.ZERO);
    }
    if (aiProperties.mock() || !qwenClient.isConfigured()) {
      return new AiAnalysisResult(mockReply(userText != null ? userText : "采购单"), QwenUsage.ZERO);
    }
    try {
      String prompt = fileUserPrompt(userText, extractedText, context, priorClarification);
      QwenResult result = mediaInputs != null && !mediaInputs.isEmpty()
          ? qwenClient.completeMultimodal(systemPrompt(), prompt, mediaInputs)
          : qwenClient.completeJson(systemPrompt(), prompt);
      return new AiAnalysisResult(
          parse(result.content(), context, fallbackSourceText(userText, extractedText)),
          result.usage());
    } catch (Exception ex) {
      log.warn("千问多模态调用失败：{}", ex.getMessage());
      return new AiAnalysisResult(
          AiReply.text("non_business", "AI 识别暂时不可用，请稍后重试或手动录入。"),
          QwenUsage.ZERO);
    }
  }

  // ---------------------------------------------------------------------------
  // Prompt 设计
  // ---------------------------------------------------------------------------

  private String systemPrompt() {
    return """
        你是一个项目对账助理。用户会发送采购单、送货单、运输费用、结算单、收付款记录、发票、
        行情价格截图等资料。
        你必须分别判断“资料本身是什么”和“用户要求如何处理”，再抽取结构化信息。
        只输出一个 JSON 对象，不要任何多余文字、不要 markdown。

        JSON 顶层字段：intent、reply、payload、confidence。
        除 clarify_card、non_business、market_quote_card 外，每个 payload 都必须包含：
        - processingMode："apply_to_ledger" 或 "archive_only"；
        - detectedSourceType：资料本身的类型；
        - applyToLedger：processingMode 为 apply_to_ledger 时是 true，否则是 false。
        （market_quote_card 是行情价格截图，不进台账、走独立的确认导入流程，payload 不需要这三件套。）
        detectedSourceType 使用 supply_order、delivery_note、transport_note、payment_request、payment、
        receipt、purchase_invoice、sales_invoice、sales_settlement、market_quote 或 unknown。

        intent 取值与 payload 结构：
        - supply_order_card（采购单/供货单/提货单，本单位向上游采购）：
          payload={"docCode":"单据编号","supplierName":"供应商","docDate":"YYYY-MM-DD","totalAmount":数字(元),
          "vehicleNo":"车牌号或null（送货人及车牌/承运车辆栏）",
          "items":[{"materialName":"品名","spec":"规格","origin":"产地或null","packageQuantity":原始件数或包装数量,"packageUnit":"件/支/箱等",
          "unitWeight":单件重量或null,"totalWeight":总重量或null,"weightUnit":"吨/千克等或null",
          "pricingQuantity":计价数量,"pricingUnit":"吨/千克/件等","unitPrice":数字,"amount":数字}]}
          origin 抽取规则：单据表格中若有「产地/钢厂/品牌/生产厂家」列，逐行照抄原文；整张单据没有该列才填 null。
          数量口径判定：单据「数量」列含义不明（件数还是吨数）时，结合单位列与单价量纲判定——
          单价是「元/吨」则数量是重量（填 pricingQuantity/pricingUnit 并同步填 totalWeight/weightUnit），
          单价是「元/件/支」则数量是件数（填 packageQuantity/packageUnit）；有「件数/支数」与「重量」两列时分别照抄。
          金额口径：amount 按单据该行实际金额填写；amount 与 unitPrice×pricingQuantity 不一致时不要自行凑数修正。
        - delivery_note_card（送货单/发货单/出库单，发给下游客户的销售侧发货凭证）：
          payload={"docCode":"发货单/送货单编号","customerName":"客户或收货单位","supplierName":"发货方或null","docDate":"YYYY-MM-DD","totalAmount":销售总金额数字(元)或null,
          "transportFee":采购运费数字(元)或null,"vehicleNo":"车牌号或null（送货人及车牌/车号栏）",
          "salesFreight":销售运费数字(元)或null,
          "pricingMode":"uplift或margin或null","upliftAmount":数字或null,"marginRate":数字或null,
          "items":[{"materialName":"品名","spec":"规格","origin":"产地或null","packageQuantity":原始件数或包装数量,"packageUnit":"件/支/箱等",
          "unitWeight":单件重量或null,"totalWeight":总重量或null,"weightUnit":"吨/千克等或null",
          "pricingQuantity":计价数量,"pricingUnit":"吨/千克/件等","unitPrice":销售单价数字或null,"amount":金额数字或null}]}
          origin 抽取规则与 supply_order_card 相同：有「产地/钢厂/品牌/生产厂家」列就逐行照抄，没有该列才填 null。
          销售定价指令抽取（从用户处理说明或单据备注里识别，没有则三个字段都填 null）：
          「网价加X」「网价+X」「上浮X」→ pricingMode="uplift"、upliftAmount=X；
          「毛利率X%」「毛利X个点」→ pricingMode="margin"、marginRate=X（如2.5表示2.5%）；
          「按毛利率算/按毛利率」未给数字 → pricingMode="margin"、marginRate=2.5（默认档）。
        - market_quote_card（**行情价格截图：价格表/行情软件截图。这是图片资料的第三大类，
          与采购单、送货单同级的常见上传类型**）：
          **判定锚点（满足即必须是 market_quote_card，禁止判成 evidence_only_card 或任何单据卡）**：
          表格主体是「规格 × 钢厂 × 价格」的多行报价，带「元/吨」量纲或涨跌箭头（如「价格(元/吨) 3830 涨跌 +10」），
          没有往来单位、没有单据号、没有一笔交易的金额合计；页面常有日期、城市、品类筛选标签。
          **无论用户说明写了什么、有没有提「行情/网价」两个字，市场价格表一律输出 market_quote_card。**
          payload={"source":"数据来源名称或null（如「我的钢铁」「Mysteel」「钢易网」）","city":"报价城市",
          "quoteDate":"行情日期YYYY-MM-DD。**截图通常只显示「月-日」（如 09-16 09:35）——年份缺失时
          必须按用户提示里的「今天是YYYY-MM-DD」补当前年份，禁止臆造其他年份**",
          "category":"品类（螺纹钢/盘螺/高线/圆钢）或null",
          "rows":[{"name":"品名（照抄截图品名列，如 盘螺/螺纹钢/高线）或null",
          "spec":"规格原文（如 Φ12-14、Φ8-10、Φ6）","grade":"材质/牌号（如 HRB400）或null",
          "origin":"钢厂/产地/品牌（如 中天、沙钢）","price":数字(元/吨),"priceChange":较前日涨跌数字或null}]}
          每行一条报价，照抄原文不换算；同一钢厂多个规格就是多行；品名列逐行照抄；
          同一张截图混多个品类时每行的 name 各自填写；截图里没有的列填 null。
          示例：行情APP里「建筑钢材 · 杭州 · 盘螺 Φ6 HRB400 中天 价格3830 涨跌+10」这样的列表，
          就是 market_quote_card，逐行抽出 price 与 priceChange。
        - transport_note_card（运输单/物流结算单/运费记录）：
          payload={"docCode":"运输单号或结算单号","supplierName":"承运单位","docDate":"YYYY-MM-DD",
          "totalAmount":运输费用数字(元),"driverName":"司机或经办人","vehicleNo":"车牌号",
          "route":"运输路线或项目地点","remark":"运输摘要"}
        - payment_card（付款记录/转账）：
          payload={"amount":数字(元),"target":"收款方","occurredDate":"YYYY-MM-DD","method":"转账/现金/支票",
          "expenseCategory":"material或transport","relatedDocCodes":["关联采购单/运输单/合同编号"],"remark":"备注"}
        - payment_request_card（请款单/付款申请）：
          payload={"amount":数字(元),"target":"请款单位或拟付款供应商","occurredDate":"YYYY-MM-DD",
          "expenseCategory":"material或transport","relatedDocCodes":["关联采购单/运输单/合同编号"],"remark":"用途或摘要"}
        - receipt_card（收款记录/回款）：
          payload={"amount":数字(元),"target":"付款方","occurredDate":"YYYY-MM-DD","method":"转账/现金/支票","remark":"备注"}
        - purchase_invoice_card（进项发票，本单位是购买方）：
          payload={"invoiceNo":"发票号","sellerName":"销售方","buyerName":"购买方","supplierName":"销售方",
          "docDate":"YYYY-MM-DD","netAmount":数字(不含税金额),"amount":数字(价税合计/元),
          "taxRate":"税率如13%","taxAmount":数字(税额),"invoiceDirection":"purchase",
          "expenseCategory":"material或transport","relatedDocCodes":["关联采购单/运输单/合同编号"],
          "items":[{"materialName":"品名","amount":数字}]}
        - sales_invoice_card（销项发票，本单位是销售方）：
          payload={"invoiceNo":"发票号","sellerName":"销售方","buyerName":"购买方","customerName":"购买方",
          "docDate":"YYYY-MM-DD","netAmount":数字(不含税金额),"amount":数字(价税合计/元),
          "taxRate":"税率如13%","taxAmount":数字(税额),"invoiceDirection":"sales",
          "expenseCategory":"material或transport","items":[{"materialName":"品名","amount":数字}]}
        - invoice_card（能够确认是发票，但不能确定进项或销项）：
          payload 与发票相同，invoiceDirection="unknown"。
        - sales_settlement_card（销售结算单，不是收款、付款或发票）：
          payload={"docCode":"结算单号","sellerName":"销售方","customerName":"客户单位",
          "docDate":"YYYY-MM-DD","amount":数字(销售含税总金额),"requiredReceiptDate":"YYYY-MM-DD或null",
          "expenseCategory":"material或transport","remark":"结算内容"}
        - settlement_card（确认是结算单，但无法判断销售结算归属）：
          payload={"docCode":"结算单号","docDate":"YYYY-MM-DD","amount":数字,
          "expenseCategory":"material或transport","settlementDirection":"unknown","remark":"结算内容"}
        - evidence_only_card（AI 判断用户要求仅存档、仅留档或不写入台账）：
          **仅当用户明确说了「存档/留档/不用处理/只备份」这类话才输出**；
          行情价格截图不属于原始凭证——市场价格表一律走 market_quote_card。
          payload 保留从原资料提取出的字段，并附加
          {"processingMode":"archive_only","detectedSourceType":"原资料类型","applyToLedger":false}。
        - clarify_card（信息不全或归属不明）：
          payload={"question":"要问用户的话","missingFields":[字符串],"options":[字符串]}
        - reconciliation_export（用户要求导出/下载/生成对账单或台账，或预约一个时间要一份对账单。
          判定锚点：纯文字指令、没有附带任何单据图片或具体单据内容；「对账单/台账」单独出现也算）：
          payload={"start":"数据起始日YYYY-MM-DD或null","end":"数据结束日YYYY-MM-DD或null",
          "triggerAt":"到点推送时间YYYY-MM-DD HH:mm或null"}
        - non_business（非业务闲聊）：payload={}
        - batch_hint（用户单独声明业务口径——未附带任何单据、没有明细/单号/车牌/金额结构等
          单据要素；例如「运费300元/趟」「销售运费按吨25」「送货运费30元/吨」
          「钢筋销售价：网价+30」「这批按毛利率2.5个点」「运费改成350」
          「采购运费28元/吨，送货运费30元每吨，毛利率2.5个点」
          「以后这个项目的单子都按运费300一趟」「这个项目统一网价+30」）：
          payload={"scope":"delivery_note","target":"batch或project","action":"update或clear",
          "hints":{...合并后的完整口径...}}
          判定标准：这句话在声明“以后送货单按什么口径处理”，不是在报告一笔业务、
          也不是在回答追问。
          **target 判的是这句话管多大范围，不是管多久**：
            - batch（默认）：只说这一批、眼前这批、没提范围时一律 batch，
              例如「这批按毛利率2.5个点」「运费改成350」；
            - project：明确说整个项目、这个项目所有批次、以后所有单子、一劳永逸，
              例如「整个项目都按这个来」「以后这个项目统一网价+30」
              「别的批次也照这个口径」。
            拿不准是不是项目级就填 batch（宁可只影响本批，不要意外改掉整个项目）。
          用户提示里会分别给出【本批次默认口径 JSON】和【本项目默认口径 JSON】，
          它们只用于理解当前已有哪些口径，**不要把里面的键原样抄进 hints**；
          hints 只放「本次输入明确提到或改变」的口径键，没提到的由后端按组合并保留（2026-11-06 改，
          此前要求 AI 输出完整合并结果，导致继承来的销售运费被反复抄回来）。
          **target=project 时不要把本批次独有的值抄进项目口径**（项目级只是默认值，批次自己的覆盖由批次口径负责）。只允许以下键：
          supplyFreightPerTrip / supplyFreightPerTon（采购运费，元/趟、元/吨，数字）：
            我们付给承运方的那笔运费；「元/趟」= 这个批次这一趟的包干价（一趟多少钱就是多少，
            不乘单据张数），「元/吨」= 每吨单价，按采购吨数乘；
          salesFreightPerTrip / salesFreightPerTon（销售运费，元/趟、元/吨，数字）：
            客户承担、含在售价里或另外向客户收的那笔运费；「元/趟」= 整批一趟包干价
            （与采购运费同语义，不按单据张数乘），「元/吨」= 每吨单价，按送货吨数乘；
          方向判断同单据运费——按「谁承担」判，不按词表：「送货运费」「送货费」通常指
          客户承担的那笔 → 销售运费；「采购运费」「运费」「运输费」偏我方承担 → 采购运费。
          只说「运费」没说方向时按采购运费处理，不要猜成销售运费。
          pricingMode（"uplift"网价上浮 或 "margin"毛利率）/ upliftAmount（元/吨数字）/
          marginRate（百分数数字，如2.5表示2.5%）。
          示例（只是示例，遇到别的说法按上面的标准自己判断）：
          「采购运费28元/吨，送货运费30元每吨，毛利率2.5个点」→
          {"supplyFreightPerTon":28,"salesFreightPerTon":30,"pricingMode":"margin","marginRate":2.5}。
          合并规则：本次输入涉及的字段才修改，未提及的原样保留；「改成X」「按吨算」等要替换
          旧口径（同一字段趟/吨只能保留一种，新的写法删除旧的键）；uplift 与 margin 互斥，
          只能保留一种定价方式。
          **否定式口径必须显式写 0，不能靠省略键**：「这批含运费」「运费含在价里」「不另计销售运费」
          → {"salesFreightPerTon":0}（连同本次涉及的其他键一起给），后端据此清掉本批次/继承自项目的销售运费；
          省略该键只会被当成「没提到、保留原值」，销售运费会照旧收。
          用户只是不提运费时不要给 0，保持省略。
          要求清空全部口径时 action="clear"（hints 给空对象 {}）。
          清掉项目级口径后，各批次会退回自己的口径（或全局默认），这一点要在 reply 里说清。
          reply 用一句话向用户复述保存后的口径；target=project 时明确说「本项目所有批次」。

        规则：
        1. 金额一律换算成「元」的数字（如420万=4200000，3.2万=32000）。
        2. confidence 为 0-100 的整数，表示识别置信度。
        3. reply 为给用户的简短中文话术（一句话概括识别结果）。
        4. 无法确定的字段填 null。**字段缺失本身不是追问理由**：只要资料类型和处理模式能够判断，
           就输出对应类型的卡片，缺失字段填 null，由系统提示用户补充（例如单据上没有金额时，
           仍然输出 supply_order_card 并完整抽取明细，把 totalAmount 填 null）。
           只有以下情况才输出 clarify_card：资料类型无法判断、处理模式无法判断、
           或用户说明与资料内容明显冲突。
        5. 如果一张图包含多种信息，按最主要的单据类型归类。
        6. 市场价格表/行情截图是唯一例外：它不是单据，必须输出 market_quote_card，
           即使用户的说明里出现了「存档」「不用管」等字样也要先给出行情卡（用户可以在卡上放弃导入）。
        6. 严格区分原始“数量/件数”、单重、总重量和计价数量，不得把件数当作吨数。
        7. pricingQuantity 是实际参与金额核算的数量，应满足 pricingQuantity × unitPrice ≈ amount。
        8. 钢材等按重量计价时，pricingQuantity 通常等于 totalWeight，pricingUnit 与 weightUnit 一致。
        9. 保留单据原文精度，不要把 9.592 吨取整成 10 吨。
        10. 送货单是销售侧凭证，本系统只有这一种送货单——发往下游客户的发货凭证，
            不存在"采购侧收货记录"这种业务类型：
            - 钢材行业送货单的原文惯例就是「到货明细、到货数量、本次到货总重量、收货单位、
              收货人、现场验收结果」等措辞（单据随货送到工地）。「到货」「收货」「验收」
              字样是送货单的正常用词，不得据此把它改判为采购收货记录，
              更不得据此输出 clarify_card 反问"是发货还是收货"。
            - 区分锚点：送货单只有件数/重量（通常没有采购单价），或单价是客户价；
              采购单才有「计价数量、单价、金额」的采购价格结构。有明细有重量
              无采购价的到货类资料就是送货单。
            - 类型判断权重：用户处理说明 > 配送特征 > 标题文字。单据出现「送货人及车牌」「车号、
              车牌号」「承运车辆」「送货车」等配送特征（如 浙A6MF15、沪A12345）时，
              即使标题写「供货单/提货单/采购单」，也应输出 delivery_note_card——发货到工地的单据
              才会记录车牌与送货人，标题用词经常不准确。
            - 标题为「本单位的供货单/采购单」（抬头第一行是本单位名称，如「杭州XX科技有限公司-供货单」
              或「杭州XX科技有限公司-采购单」）
              时，这实为本单位作为卖方向客户发出的发货凭证，应输出 delivery_note_card——
              真正的采购单，抬头应是上游供应商/钢厂/贸易商，本单位只会出现在购买方/收货方位置。
              注意：此规则仅适用于标题为「供货单/提货单/采购单」且抬头为本单位的发货类单据；
              若用户处理说明明确为采购，或单据有明确的采购价格结构与上游供应商，仍按采购单处理。
            - 结算单必须有结算属性：结算单号、结算金额、结算/对账口径等。只有数量/重量
              没有任何单价、金额结构的单据，禁止因为「缺金额」改判为结算单，应按上述规则
              在采购单与送货单之间判断（totalAmount 填 null 即可）。
            - 送货单/发货单/出库单/配送单：核心信息是客户（收货单位，customerName）、
              发货日期、材料明细（数量、销售单价、金额）。
              销售单价/金额是客户价，与采购价无关，不得丢弃，也不要把客户误填成供应商。
            - 单据没有销售单价/总金额时，totalAmount 填 null 并照常输出卡片，
              不得追问金额或方向——台账会按材料明细处理。
            - 运费归属只有一个判断标准：**这笔钱最终由谁承担**。不要做词表匹配——
              遇到没见过的说法（「代垫」「到付」「回单付」「码头费」「短驳」「送户费」「含送」
              等）按这个标准自己推，判断不了就填 null，不要硬塞。
              · 客户承担（钱从客户来、含在售价里或另外收）→ salesFreight。
                送货单语境下的「送货运费」「送货费」通常就是这笔：货送到客户手上、运费客户出。
              · 我方承担（钱付给承运方/司机/车队，进采购成本）→ transportFee。
                采购单、运输单上的运费默认都是这个方向。
              · 单据类型是重要旁证：送货单偏销售侧、采购单/运输单偏采购侧；
                两种运费同时出现时各归各，不要合并成一个数，也不要只填一个。
              · 只写「运费 XX 元」没写责任方：按所属单据类型定方向——送货单上同时出现
                客户价和运费两栏的归 salesFreight，其余归 transportFee。
            - 「含运费」类表述（含运费 / 运费已含 / 包含运费 / 包运费 / 送到价 / 货到价 /
              一票制）说明运费已经算在销售单价里，客户不会另付一笔——此时 salesFreight 留 null。
              这几个字本身不是金额，不要当运费金额，也不要写进 transportFee。
              例：「钢筋销售价 网价+30（含运费）」→ upliftAmount=30、salesFreight=null；
              同一张单若另有「付承运方 28 元/吨」的标注，transportFee 照常提取 28。
            - 「货物金额」「材料金额」「单价×数量的合计」「本次发货总金额/合计」是销售金额
              （totalAmount），不是运输费用，禁止填入 transportFee；也不要把运费写成材料明细行。
            - 运输单、物流结算单、独立的运费凭证或明确的运费记录：输出 transport_note_card。
        11. 用户随附件提供的处理说明是高优先级业务意图。先理解处理说明，再判断资料类型。
            例如用户说明“按送货单处理”，且资料内容同时可作为供货/送货凭证时，应优先输出 delivery_note_card。
        12. 用户说明不能覆盖明显相反的证据。若用户指定类型与单据内容明显冲突，输出 clarify_card，
            payload 至少包含 question、requestedType、detectedType、options，并说明冲突原因，不得直接写入任何业务事实。
            注意：措辞风格差异不构成类型冲突——送货单原文写「到货明细/收货人/验收」是行业惯例，
            与"按送货单处理"毫无冲突，不得据此反问。
        13. 文件名只作为弱线索，不得仅凭文件名判断单据类型。
        14. 付款记录和发票是两个独立业务类型，不得把发票字段并入 payment_card。
        15. 材料采购的默认进项税率为13%，运输费用的默认进项税率为9%。
            expenseCategory 根据付款用途、收款单位、备注和单据内容判断。
        16. “本单位身份”是判断业务方向的最高优先级身份依据：
            - 本单位是发票购买方，输出 purchase_invoice_card；
            - 本单位是发票销售方，输出 sales_invoice_card；
            - 本单位是付款方且资金转给供应商，输出 payment_card；
            - 本单位是收款方且资金来自客户，输出 receipt_card。
            不得只根据“已识别为销项/进项”的 reply 文本判断，intent 和 payload.invoiceDirection 必须一致。
        17. 只有满足以下任一条件才能认定为发票：
            - 资料中存在明确发票关键字段组合，例如发票号码、开票日期、销售方、购买方、税额、价税合计；
            - 用户处理说明明确指定“这是发票”。
            仅出现含税金额、税率或“结算”不能认定为发票。
        18. 结算单永远不能输出 payment_card 或 receipt_card，也不能因为包含税率或含税金额就输出发票。
            结算内容为钢材、材料、设备供应时 expenseCategory=material；明确为运输服务或运费结算时为transport。
        19. 无法确定进项或销项时输出 invoice_card，不要自行默认为进项。无法确定收款或付款时输出 clarify_card，
            options 只能包含当前仍有可能的方向，不要把所有业务类型一次性列给用户。
        20. relatedDocCodes 应提取资料中明确出现的运输单号、采购单号、供货单号、合同号等关联编号。
            费用与单据的关联（请款/付款/发票 ↔ 采购单/运输单/送货单）由系统自动完成，模型不得在
            回复或 clarify_card 中询问「对应哪张运输单/采购单/送货单」这类关联问题；即使资料中
            没有出现关联单号，也照常输出对应业务卡片，关联留给系统处理。
        21. “它是什么资料”和“用户要求怎么处理”是两个独立维度，不能互相覆盖。
            当用户表达“作为原始凭证”“仅作为凭证”“留档即可”“只保存原件”“不要写入台账”
            “不作为采购单处理”等含义时，应结合整句话判断其处理模式。若语义是只保存原始资料，
            processingMode 必须为 archive_only，无论原资料是什么类型，都输出 evidence_only_card。
            仍需提取可识别字段，并在 detectedSourceType 中记录原资料类型；不得输出 clarify_card，
            也不得因为资料明显是采购单、请款单等而覆盖用户的归档要求。
            “资料是送货单”和“按原始凭证归档”可以同时成立，这不是类型冲突。
            但“作为付款凭证”“作为到货凭证”等描述未必代表仅存档，必须结合是否要求写入业务数据判断，
            不得只抓取某个词机械决定。
        22. 用户明确要求把一种资料按另一业务类型处理时，intent 按用户指定的目标类型输出，
            payload 同时附加 detectedSourceType、requestedDocType、typeOverride=true 和
            targetMissingFields（目标业务仍缺少的字段名数组，只使用 docDate、supplierName、amount）。
            目标业务字段只能使用原资料中具有对应事实含义的内容，不得偷换字段含义。
            例如请款单按付款记录处理时，如果资料没有明确的实际付款日期，不得把申请日期当作付款日期，
            occurredDate 必须为 null，并在 targetMissingFields 中加入 docDate；
            同理不得把无法确认的请款单位自动当作实际收款单位。
            用户已明确指定类型时，即使缺少金额、日期或供应商，也必须按该类型输出卡片并完整抽取
            已有的明细字段，不得因为关键字段缺失而输出 clarify_card。
        23. 采购单与销售结算单的方向由「本单位身份」决定，不得仅凭标题里的“销售”二字判断：
            - 采购单/供货单（supply_order_card）：本单位是购买方/采购商/收货方，对方是供应商（钢厂、贸易商、找钢网等）。
              卖方平台常把这类单据命名为“销售单/销售合同/订单”，这是平台视角，不等于本单位是销售方；
              只要本单位出现在购买方/收货方位置，就必须输出 supply_order_card。
            - 销售结算单（sales_settlement_card）：本单位是销售方（sellerName=本单位），向客户单位（customerName）结算货款；
              若本单位出现在 customerName（客户单位）位置，则本单位实为买方，应改判为 supply_order_card。
            - 简言之：以“本单位名称 ↔ 购买方/销售方/客户单位”的身份为准，身份优先于标题。
        24. 用户要求导出、下载、生成、汇总对账单/台账，或预约某个时间要一份对账单/台账时，
            输出 reconciliation_export，绝不输出 clarify_card 反问"请提供内容或图片"。
            start/end 按用户消息中的「最近一周/上周/本周/本月/上月/9月1日到9月10日/9.12-9.13/单日」
            等换算（用户提示里会给出今天日期）；「最近一周/近7天」=今天往前推7天；
            用户没有指定数据日期时 start/end 都填 null（系统有默认值）。
            triggerAt 只在用户说了未来的推送时间（如"10分钟后""明天上午9点""周五"）时按
            用户提示里给出的当前日期和当前时间换算（"10分钟后"= 当前时间加10分钟，必须精确，
            不得凭空猜测当前时间），精确到分钟即可；没说未来时间就填 null（系统立即给出可下载卡片）。
            「每周/每月」等周期性导出说法按无 triggerAt 处理（triggerAt=null，系统另有周期提醒配置）。
        25. 用户提示里的【本批次默认口径】只用于**销售定价**推断（pricingMode/upliftAmount/marginRate）：
            - 仅当用户处理说明和单据内容都没有提及定价时才按默认口径推断，提到了以单据/说明为准；
            - 运费口径（采购运费/销售运费）由系统按批次口径独立生成运费行，
              **禁止**据此刻意填写、补全或推算 transportFee / salesFreight；
              这两个字段只在单据上真实写明运费时才抽取，单据没写就留 null。
            - 默认口径不构成任何类型判断依据，不得据此把资料改判为送货单/运输单，
              也不得据此输出 clarify_card。
            口径含义（回答用户提问时按这个讲，不要自己另算）：
            毛利率口径销售价 = 采购价 ÷ (1 − 毛利率)，成本基数取采购价（同批次采购单同材料
            同规格的吨价）；上浮口径销售价 = 网价 + 上浮。⚠️ 这个「毛利率点数」只是定价工具，
            用来倒推销售单价，运费不进销售价。台账材料区/销售区显示的是真实毛利率：
            毛利额 = 销售额 + 客户承担送货运费 − 材料采购 − 采购运费，
            毛利率 = 毛利额 ÷（货款 + 送货运费），所以真实毛利率通常低于定价点数（运费在成本侧扣）。
            批次还没有采购单时毛利率口径的行算不出销售价，属于正常待补状态，
            提示用户先导入采购单即可，不要说成"识别失败"。
        26. batch_hint 的边界（宁可漏判不可错判）：
            - 输入包含单据要素（材料明细、单号、车牌、付款/发票结构、完整金额结构等）时，
              按正常单据类型输出，禁止输出 batch_hint；
            - 存在【上一轮追问】时先看相关性：输入确实在回答追问的内容（补充资料信息、
              选择类型等）→ 按追问处理；输入与追问明显无关、是在声明批次口径
              （运费/销售运费/销售定价等）→ 仍输出 batch_hint，系统会自动归档追问，
              禁止因为存在追问就反问用户「请重新发送单据」；
            - 对账单导出/预约永远是 reconciliation_export，不是 batch_hint；
            - 无法判断是口径声明还是业务输入时，按正常业务类型处理。
        """;
  }

  private String textUserPrompt(
      String userText,
      AiContext context,
      Map<String, Object> priorClarification
  ) {
    StringBuilder sb = new StringBuilder();
    java.time.LocalDateTime now = java.time.LocalDateTime.now();
    // 必须注入当前时间：AI 换算「1分钟后/今天下午3点」等相对时间全靠它，
    // 只给日期时 LLM 会瞎猜时间（实测把「1分钟后」算成 90 分钟后）。
    sb.append("今天是").append(now.toLocalDate()).append("（").append(
        now.getDayOfWeek().getValue()).append("=周一），当前时间 ").append(
        now.toLocalTime().withSecond(0).withNano(0)).append("。\n");
    if (context != null) {
      sb.append("本单位身份=").append(orDash(context.companyName()))
          .append("。当前客户单位=").append(orDash(context.customerName()))
          .append("。当前归属：项目=").append(orDash(context.projectName()))
          .append("，批次=").append(orDash(context.batchName())).append("\n");
      appendBatchHintContext(sb, context);
    }
    appendClarificationContext(sb, priorClarification);
    sb.append("用户输入：").append(userText);
    return sb.toString();
  }

  private String fileUserPrompt(
      String userText,
      String extractedText,
      AiContext context,
      Map<String, Object> priorClarification
  ) {
    StringBuilder sb = new StringBuilder();
    if (context != null) {
      sb.append("本单位身份=").append(orDash(context.companyName()))
          .append("。当前客户单位=").append(orDash(context.customerName()))
          .append("。当前归属：项目=").append(orDash(context.projectName()))
          .append("，批次=").append(orDash(context.batchName())).append("\n");
      appendBatchHintContext(sb, context);
    }
    sb.append("请先判断用户要求的处理模式，再识别文件本身的资料类型并结构化输出。");
    appendClarificationContext(sb, priorClarification);
    if (StringUtils.hasText(extractedText)) {
      sb.append("\n文件解析出的表格内容：\n").append(extractedText);
    }
    if (StringUtils.hasText(userText)) {
      sb.append("\n用户处理说明（最高优先级；归档要求与资料类型不构成冲突）：").append(userText);
    } else {
      sb.append("\n用户未提供额外处理说明。");
    }
    return sb.toString();
  }

  /** 业务默认口径（生效口径 + 两层原始 JSON）：仅作送货单字段默认值，用户说明与单据内容优先。 */
  private void appendBatchHintContext(StringBuilder sb, AiContext context) {
    if (context == null) {
      return;
    }
    if (StringUtils.hasText(context.batchHintText())) {
      sb.append("\n【当前生效的默认口径（批次优先、缺项回退项目）：只用于销售定价推断；")
        .append("运费口径由系统独立生成运费行，禁止据此填写或推算 transportFee/salesFreight，")
        .append("运费只在单据上真实写明时才抽取；用户处理说明与单据内容已提及的内容不得覆盖；")
        .append("不得据此改判单据类型）】：")
        .append(context.batchHintText()).append("\n");
    }
    if (StringUtils.hasText(context.batchHintsJson())) {
      sb.append("\n【本批次默认口径 JSON（target=batch 时以它为合并基准）】：")
        .append(context.batchHintsJson()).append("\n");
    }
    if (StringUtils.hasText(context.projectHintsJson())) {
      sb.append("\n【本项目默认口径 JSON（target=project 时以它为合并基准；")
        .append("批次没单独设置的字段会用它，批次设了的字段不受它影响）】：")
        .append(context.projectHintsJson()).append("\n");
    }
  }

  /** 把上一轮追问与用户的选择一起交给模型，使其把本次输入理解为「针对追问的补充回答」。 */
  private void appendClarificationContext(StringBuilder sb, Map<String, Object> priorClarification) {
    if (priorClarification == null || priorClarification.isEmpty()) {
      return;
    }
    Object question = priorClarification.get("question");
    sb.append("\n【上一轮追问（用户正在回答这条问题，不要重新从零判断）】");
    if (hasValue(question)) {
      sb.append("\n问题：").append(question);
    }
    Object requestedType = priorClarification.get("requestedType");
    if (hasValue(requestedType)) {
      sb.append("\n用户此前指定的类型：").append(requestedType);
    }
    Object detectedType = priorClarification.get("detectedType");
    if (hasValue(detectedType)) {
      sb.append("\n此前识别到的资料类型：").append(detectedType);
    }
    Object options = priorClarification.get("options");
    if (options instanceof List<?> list && !list.isEmpty()) {
      sb.append("\n可选项：").append(String.join("、",
          list.stream().map(String::valueOf).toList()));
    }
    sb.append("\n用户本轮回答即对上述问题的答复，请据此完成识别并输出结构化结果。");
  }

  // ---------------------------------------------------------------------------
  // 解析 AI 返回
  // ---------------------------------------------------------------------------

  private AiReply parse(String json, AiContext context) {
    return parse(json, context, null);
  }

  /**
   * @param sourceText 原始资料文本（用户输入或从文件抽取的文本），用于运输费用等金额的规则化兜底提取。
   */
  private AiReply parse(String json, AiContext context, String sourceText) {
    try {
      // 千问有时会包裹 ```json ... ```
      json = stripMarkdownCodeBlock(json);
      Map<String, Object> root = objectMapper.readValue(json, new TypeReference<>() {
      });
      String intent = String.valueOf(root.getOrDefault("intent", "clarify_card"));
      if (!INTENTS.contains(intent)) {
        intent = "clarify_card";
      }
      String reply = root.get("reply") == null ? defaultReply(intent) : String.valueOf(root.get("reply"));
      Object payloadObj = root.get("payload");
      Map<String, Object> payload = payloadObj instanceof Map<?, ?> map ? castMap(map) : Map.of();
      // 把 confidence 也放进 payload 方便前端展示
      if (root.containsKey("confidence")) {
        payload = new java.util.LinkedHashMap<>(payload);
        payload.put("confidence", root.get("confidence"));
      }
      ProcessingDecision processingDecision = normalizeProcessingDecision(intent, reply, payload);
      intent = processingDecision.intent();
      reply = processingDecision.reply();
      payload = processingDecision.payload();
      payload = normalizeFinancialPayload(intent, payload);

      // 发票方向：以「本单位名称 vs 销售方/购买方」的事实为准，覆盖模型的判断
      ProcessingDecision directionDecision = resolveInvoiceDirection(intent, reply, payload, context);
      intent = directionDecision.intent();
      reply = directionDecision.reply();
      payload = directionDecision.payload();

      // 供货/销售结算方向：以「本单位身份 vs 购买方/销售方/客户单位」为准，覆盖平台标题误导
      ProcessingDecision supplyDecision = resolveSupplyDirection(intent, reply, payload, context);
      intent = supplyDecision.intent();
      reply = supplyDecision.reply();
      payload = supplyDecision.payload();

      // 结算单误判兜底：带车牌+材料明细+无金额的资料实为送货单，不得按结算单入账
      ProcessingDecision settlementDecision = resolveSettlementMisclassification(intent, reply, payload);
      intent = settlementDecision.intent();
      reply = settlementDecision.reply();
      payload = settlementDecision.payload();

      // 追问兜底：用户已给出明确类型线索时，直接产出可补充的数据卡片，不再反复追问
      ProcessingDecision fallbackDecision = resolveClarifyFallback(intent, reply, payload);
      intent = fallbackDecision.intent();
      reply = fallbackDecision.reply();
      payload = fallbackDecision.payload();

      // 运输费用兜底：模型漏抽运费时，用规则直接从原文提取，避免每次都靠人工补
      ProcessingDecision feeDecision = resolveTransportFee(intent, reply, payload, sourceText);
      intent = feeDecision.intent();
      reply = feeDecision.reply();
      payload = feeDecision.payload();

      if ("non_business".equals(intent)) {
        return AiReply.text(intent, reply);
      }
      return AiReply.card(intent, reply, payload);
    } catch (Exception ex) {
      log.warn("解析千问 JSON 失败：{}，原文：{}", ex.getMessage(), json);
      return AiReply.text("non_business", "AI 返回格式异常，请重试或手动录入。");
    }
  }

  // ---------------------------------------------------------------------------
  // 运输费用兜底提取：模型漏抽时，用规则直接从原文把运费抠出来
  // ---------------------------------------------------------------------------

  /** 运费类字样，从具体到宽泛排列（长词在前，避免被子串抢先匹配）。 */
  private static final String FREIGHT_KEYWORDS =
      "运输费用|运输费|运费|配送费|送货费|物流费|承运费|车费";

  /**
   * 「关键词 + 可选合计字样 + 少量标点/空格 + 金额 + 可选单位」。
   * 关键词与数字之间不允许出现汉字，避免「运费由甲方承担，材料款 150000」这类被误抓。
   */
  private static final Pattern FREIGHT_AMOUNT = Pattern.compile(
      "(?:" + FREIGHT_KEYWORDS + ")"
          + "(?:合计|小计|总计|金额)?"
          + "[^0-9\\u4e00-\\u9fa5]{0,4}?"
          + "([0-9][0-9,，]*(?:\\.[0-9]+)?)"
          + "\\s*(万元|万|元|)");

  private static String fallbackSourceText(String userText, String extractedText) {
    boolean hasUser = StringUtils.hasText(userText);
    boolean hasExtracted = StringUtils.hasText(extractedText);
    if (hasUser && hasExtracted) {
      return userText + "\n" + extractedText;
    }
    return hasUser ? userText : (hasExtracted ? extractedText : null);
  }

  /** 送货单漏填 transportFee、运输单漏填 totalAmount 时，用原文规则补上。 */
  private ProcessingDecision resolveTransportFee(
      String intent,
      String reply,
      Map<String, Object> payload,
      String sourceText
  ) {
    boolean delivery = "delivery_note_card".equals(intent);
    boolean transport = "transport_note_card".equals(intent);
    if ((!delivery && !transport) || !StringUtils.hasText(sourceText)) {
      return new ProcessingDecision(intent, reply, payload);
    }
    String amountKey = delivery ? "transportFee" : "totalAmount";
    if (hasValue(payload.get(amountKey))) {
      return new ProcessingDecision(intent, reply, payload);
    }
    BigDecimal fee = extractTransportFee(sourceText);
    if (fee == null) {
      return new ProcessingDecision(intent, reply, payload);
    }
    Map<String, Object> normalized = new java.util.LinkedHashMap<>(payload);
    normalized.put(amountKey, fee);
    normalized.put("transportFeeExtracted", true);
    return new ProcessingDecision(intent, reply, normalized);
  }

  /**
   * 从原文提取运输费用。命中多个不同金额时返回 null（宁可让用户确认，也不猜错）。
   */
  static BigDecimal extractTransportFee(String sourceText) {
    if (!StringUtils.hasText(sourceText)) {
      return null;
    }
    Matcher matcher = FREIGHT_AMOUNT.matcher(sourceText);
    Set<BigDecimal> values = new LinkedHashSet<>();
    while (matcher.find()) {
      BigDecimal value = parseAmountWithUnit(matcher.group(1), matcher.group(2));
      if (value != null && value.compareTo(BigDecimal.ZERO) > 0) {
        values.add(value);
      }
    }
    return values.size() == 1 ? values.iterator().next() : null;
  }

  private static BigDecimal parseAmountWithUnit(String number, String unit) {
    String digits = number.replace(",", "").replace("，", "");
    BigDecimal value;
    try {
      value = new BigDecimal(digits);
    } catch (NumberFormatException ex) {
      return null;
    }
    if ("万".equals(unit) || "万元".equals(unit)) {
      value = value.multiply(new BigDecimal("10000"));
    }
    return value;
  }

  // ---------------------------------------------------------------------------
  // 发票方向校正：模型判断必须让位于「本单位是销售方还是购买方」这一事实
  // ---------------------------------------------------------------------------

  private static final List<String> INVOICE_INTENTS = List.of(
      "purchase_invoice_card", "sales_invoice_card", "invoice_card");

  private ProcessingDecision resolveInvoiceDirection(
      String intent,
      String reply,
      Map<String, Object> payload,
      AiContext context
  ) {
    if (!INVOICE_INTENTS.contains(intent)) {
      return new ProcessingDecision(intent, reply, payload);
    }
    Map<String, Object> normalized = new java.util.LinkedHashMap<>(payload);
    String company = context == null ? null : context.companyName();
    if (!StringUtils.hasText(company)) {
      return new ProcessingDecision(intent, reply, normalized);
    }
    String seller = text(normalized.get("sellerName"));
    String buyer = text(normalized.get("buyerName"));
    boolean sellerIsUs = sameCompany(seller, company);
    boolean buyerIsUs = sameCompany(buyer, company);

    String factDirection;
    if (sellerIsUs && !buyerIsUs) {
      factDirection = "sales";
    } else if (buyerIsUs && !sellerIsUs) {
      factDirection = "purchase";
    } else if (sellerIsUs && buyerIsUs) {
      // 双方都是本单位，事实自相矛盾，交给用户确认
      factDirection = "unknown";
    } else {
      // 无法从双方名称判断（缺字段或名称不匹配），保留模型判断，不误伤
      factDirection = null;
    }

    if (factDirection == null) {
      return new ProcessingDecision(intent, reply, normalized);
    }

    String targetIntent = switch (factDirection) {
      case "sales" -> "sales_invoice_card";
      case "purchase" -> "purchase_invoice_card";
      default -> "invoice_card";
    };
    normalized.put("invoiceDirection", factDirection);
    if (!targetIntent.equals(intent)) {
      log.info("发票方向已按本单位名称校正：{} -> {}（本单位={}，销售方={}，购买方={}）",
          intent, targetIntent, company, seller, buyer);
      normalized.put("directionCorrected", true);
      normalized.put("detectedSourceType", "sales".equals(factDirection)
          ? "sales_invoice" : "purchase_invoice");
    }
    String correctedReply = switch (factDirection) {
      case "sales" -> "识别到销项发票：本单位（" + company + "）为销售方，请核对后确认。";
      case "purchase" -> "识别到进项发票：本单位（" + company + "）为购买方，请核对后确认。";
      default -> "识别到发票，但销售方与购买方都指向本单位，请确认本单位在这张发票中的身份。";
    };
    return new ProcessingDecision(targetIntent, correctedReply, normalized);
  }

  // ---------------------------------------------------------------------------
  // 结算单误判兜底：钢材送货单经常只写数量/重量且标题写「供货单/采购单」，模型缺金额时
  // 偶尔滑向结算单。配送特征（车牌）+ 材料明细 + 无金额 = 送货单，代码层强制改判。
  // ---------------------------------------------------------------------------

  private ProcessingDecision resolveSettlementMisclassification(
      String intent,
      String reply,
      Map<String, Object> payload
  ) {
    if (!List.of("sales_settlement_card", "settlement_card").contains(intent)) {
      return new ProcessingDecision(intent, reply, payload);
    }
    String vehicleNo = text(payload.get("vehicleNo"));
    boolean hasItems = payload.get("items") instanceof List<?> items && !items.isEmpty();
    Object amount = payload.get("amount");
    boolean noAmount = amount == null || !(amount instanceof Number number) || number.doubleValue() <= 0;
    if (!StringUtils.hasText(vehicleNo) || !hasItems || !noAmount) {
      return new ProcessingDecision(intent, reply, payload);
    }
    Map<String, Object> normalized = new java.util.LinkedHashMap<>(payload);
    normalized.put("misclassificationCorrected", true);
    normalized.put("detectedSourceType", "delivery_note");
    // 结算单与送货单字段不对齐：结算金额并入销售总金额位（为空），客户单位尽量保留
    normalized.put("customerName", text(payload.get("customerName")));
    normalized.put("totalAmount", null);
    String correctedReply = "识别到送货单：单据带有送货车牌且只有材料明细数量，"
        + "无结算金额结构，已按送货单处理，请核对后确认。";
    log.info("结算单误判已按配送特征改判为送货单（vehicleNo={}，明细行数由 items 提供）", vehicleNo);
    return new ProcessingDecision("delivery_note_card", correctedReply, normalized);
  }

  // ---------------------------------------------------------------------------
  // 供货/销售结算方向校正：与发票同理，以「本单位身份 vs 购买方/销售方/客户单位」为准，
  // 不被卖方平台标题里的「销售单」带偏。找钢网等平台的“销售单”是平台视角，本单位实为买方。
  // ---------------------------------------------------------------------------
  private static final List<String> SUPPLY_SETTLEMENT_INTENTS = List.of(
      "supply_order_card", "sales_settlement_card");

  private ProcessingDecision resolveSupplyDirection(
      String intent,
      String reply,
      Map<String, Object> payload,
      AiContext context
  ) {
    if (!SUPPLY_SETTLEMENT_INTENTS.contains(intent)) {
      return new ProcessingDecision(intent, reply, payload);
    }
    Map<String, Object> normalized = new java.util.LinkedHashMap<>(payload);
    String company = context == null ? null : context.companyName();
    if (!StringUtils.hasText(company)) {
      return new ProcessingDecision(intent, reply, normalized);
    }
    // supply_order_card 的对方是供应商(supplierName)；sales_settlement_card 有 销售方(sellerName)/客户单位(customerName)
    String supplier = text(normalized.get("supplierName"));
    String seller = text(normalized.get("sellerName"));
    String customer = text(normalized.get("customerName"));
    boolean supplierIsUs = sameCompany(supplier, company);
    boolean sellerIsUs = sameCompany(seller, company);
    boolean customerIsUs = sameCompany(customer, company);

    String targetIntent = null;
    if ("supply_order_card".equals(intent)) {
      // 本单位被标成供应商 → 抬头写本公司名的「供货单/采购单」实为本单位开出的发货凭证（送货单）
      if (supplierIsUs) {
        targetIntent = "delivery_note_card";
      }
    } else { // sales_settlement_card
      if (customerIsUs && !sellerIsUs) {
        // 本单位是客户单位（买方）→ 实为采购/供货，销售方即我们的供应商
        targetIntent = "supply_order_card";
        if (!StringUtils.hasText(supplier)) {
          normalized.put("supplierName", seller);
        }
      } else if (sellerIsUs && !customerIsUs) {
        // 本单位是销售方 → 方向正确，维持销售结算单
        return new ProcessingDecision(intent, reply, normalized);
      }
    }
    if (targetIntent == null) {
      return new ProcessingDecision(intent, reply, normalized);
    }
    normalized.put("directionCorrected", true);
    String typeLabel = "supply_order_card".equals(targetIntent) ? "采购单"
        : "delivery_note_card".equals(targetIntent) ? "送货单" : "销售结算单";
    String detectedType = "supply_order_card".equals(targetIntent) ? "supply_order"
        : "delivery_note_card".equals(targetIntent) ? "delivery_note" : "sales_settlement";
    normalized.put("detectedSourceType", detectedType);
    // 送货单payload结构对齐：本单位为发货方，供应商字段换到客户单位（supplierName→customerName）
    if ("delivery_note_card".equals(targetIntent) && !StringUtils.hasText(text(normalized.get("customerName")))) {
      normalized.put("customerName", supplier);
      normalized.put("supplierName", null);
    }
    String correctedReply = "识别到" + typeLabel + "：单据抬头为本单位（" + company + "），"
        + ("delivery_note_card".equals(targetIntent) ? "本单位为发货方" : "本单位为"
            + ("supply_order_card".equals(targetIntent) ? "购买方" : "销售方"))
        + "，请核对后确认。";
    log.info("供货/送货/结算方向已按本单位名称校正：{} -> {}（本单位={}，供应商={}，销售方={}，客户单位={}）",
        intent, targetIntent, company, supplier, seller, customer);
    return new ProcessingDecision(targetIntent, correctedReply, normalized);
  }

  /** 公司名称归一化后比较：去空白、括号、通用后缀，再做双向包含判断。 */
  private static boolean sameCompany(String left, String right) {
    String a = normalizeCompany(left);
    String b = normalizeCompany(right);
    if (!StringUtils.hasText(a) || !StringUtils.hasText(b)) {
      return false;
    }
    if (a.length() < 4 || b.length() < 4) {
      return a.equals(b);
    }
    return a.contains(b) || b.contains(a);
  }

  private static String normalizeCompany(String value) {
    if (value == null) return "";
    String text = value.toLowerCase()
        .replaceAll("[\\s（）()【】\\[\\],，。.、·:：\"'“”‘’]", "");
    for (String suffix : List.of("股份有限公司", "有限责任公司", "有限公司", "股份公司", "公司")) {
      if (text.length() > suffix.length() + 2 && text.endsWith(suffix)) {
        text = text.substring(0, text.length() - suffix.length());
        break;
      }
    }
    return text;
  }

  // ---------------------------------------------------------------------------
  // 追问兜底：能确定目标类型时直接产出数据卡片，让用户补字段，而不是反复追问
  // ---------------------------------------------------------------------------

  /**
   * 目标类型线索 → 关键词。顺序敏感：更具体的类型放在前面
   * （请款要先于付款、运输要先于送货、仅存档最优先）。
   */
  private static final List<String> TYPE_HINT_ORDER = List.of(
      "evidence_only", "transport_note", "payment_request", "payment", "receipt",
      "invoice_unknown", "supply_order", "delivery_note", "settlement_unknown");

  private static final Map<String, List<String>> TYPE_HINT_KEYWORDS = Map.of(
      "evidence_only", List.of("仅存档", "只存档", "仅作凭证", "仅作为凭证", "只作凭证", "留档", "仅留档",
          "只保存", "不要写入台账", "不写入台账", "不作为业务", "原始凭证"),
      "transport_note", List.of("运输", "运费", "物流", "承运", "车费", "配送费"),
      "payment_request", List.of("请款", "付款申请", "申请付款", "付款审批"),
      "payment", List.of("付款", "打款", "转账", "已付", "支付"),
      "receipt", List.of("收款", "回款", "到账", "收到款"),
      "invoice_unknown", List.of("发票", "增值税", "进项", "销项"),
      "supply_order", List.of("供货", "采购单", "采购合同", "订单", "材料单", "采购", "提货单"),
      "delivery_note", List.of("送货单", "送货", "出库", "配送单", "发货单", "发货", "到货明细", "到货"),
      "settlement_unknown", List.of("结算单", "结算"));

  /** 目标类型 → 对应 intent。 */
  private static final Map<String, String> TYPE_HINT_INTENT = Map.of(
      "supply_order", "supply_order_card",
      "delivery_note", "delivery_note_card",
      "transport_note", "transport_note_card",
      "payment_request", "payment_request_card",
      "payment", "payment_card",
      "receipt", "receipt_card",
      "invoice_unknown", "invoice_card",
      "settlement_unknown", "settlement_card",
      "evidence_only", "evidence_only_card");

  private ProcessingDecision resolveClarifyFallback(
      String intent,
      String reply,
      Map<String, Object> payload
  ) {
    if (!"clarify_card".equals(intent)) {
      return new ProcessingDecision(intent, reply, payload);
    }
    String targetType = inferTargetType(payload);
    if (targetType == null) {
      return new ProcessingDecision(intent, reply, payload);
    }
    String nextIntent = TYPE_HINT_INTENT.get(targetType);
    if (nextIntent == null) {
      return new ProcessingDecision(intent, reply, payload);
    }
    // 兜底只能复用模型已经抽取出的字段：追问 payload 通常只有 question/options，
    // 拿它凭空造卡会丢掉明细。缺明细时必须保留原资料，让用户选择后重新识别。
    if (!hasBusinessFacts(targetType, payload)) {
      log.info("追问兜底跳过：{} 缺少可复用的业务字段，保留原始资料等待用户选择", targetType);
      return new ProcessingDecision(intent, reply, payload);
    }
    Map<String, Object> normalized = new java.util.LinkedHashMap<>(payload);
    String detectedType = text(normalized.get("detectedType"));
    String resolvedDetected = isKnownSourceType(detectedType) ? detectedType : "unknown";
    normalized.put("processingMode", "archive_only".equals(targetType) ? "archive_only" : "apply_to_ledger");
    normalized.put("applyToLedger", !"evidence_only".equals(targetType));
    normalized.put("detectedSourceType", resolvedDetected);
    normalized.put("requestedDocType", targetType);
    if (StringUtils.hasText(detectedType) && !detectedType.equals(targetType)) {
      normalized.put("typeOverride", true);
    }
    normalized.put("clarifyFallback", true);
    String typeLabel = typeHintLabel(targetType);
    String nextReply = "已按你说的「" + typeLabel + "」生成待补充卡片，请补充缺少的必填字段后确认。";
    log.info("追问兜底生效：按 {} 生成待补充卡片（detectedType={}）", targetType, detectedType);
    return new ProcessingDecision(nextIntent, nextReply, normalized);
  }

  /** 从追问 payload（requestedType/detectedType）与用户说明中推断目标业务类型。 */
  private String inferTargetType(Map<String, Object> payload) {
    List<String> candidates = new java.util.ArrayList<>();
    String requested = text(payload.get("requestedType"));
    if (StringUtils.hasText(requested)) candidates.add(requested);
    Object options = payload.get("options");
    if (options instanceof List<?> list && list.size() == 1) {
      candidates.add(String.valueOf(list.get(0)));
    }
    String detected = text(payload.get("detectedType"));
    if (StringUtils.hasText(detected)) candidates.add(detected);
    String question = text(payload.get("question"));
    if (StringUtils.hasText(question)) candidates.add(question);
    String guidance = text(payload.get("guidanceText"));
    if (StringUtils.hasText(guidance)) candidates.add(guidance);

    for (String source : candidates) {
      String hit = matchTypeHint(source);
      if (hit != null) {
        return hit;
      }
    }
    return null;
  }

  /** 依赖明细行的业务类型：追问兜底时必须有明细才能建卡。 */
  private static final List<String> ITEM_REQUIRED_TYPES = List.of(
      "supply_order", "delivery_note", "invoice_unknown");

  /** 判断追问 payload 是否带有可复用的业务事实。 */
  private static boolean hasBusinessFacts(String targetType, Map<String, Object> payload) {
    boolean hasItems = payload.get("items") instanceof List<?> list && !list.isEmpty();
    if (ITEM_REQUIRED_TYPES.contains(targetType)) {
      return hasItems;
    }
    return hasValue(payload.get("totalAmount"))
        || hasValue(payload.get("amount"))
        || hasValue(payload.get("netAmount"))
        || hasValue(payload.get("supplierName"))
        || hasValue(payload.get("target"))
        || hasValue(payload.get("customerName"))
        || hasValue(payload.get("sellerName"))
        || hasValue(payload.get("buyerName"))
        || hasValue(payload.get("docCode"))
        || hasValue(payload.get("invoiceNo"))
        || hasItems;
  }

  /** 文本 → 目标类型；同时命中多个类型时返回 null（交回用户确认，避免猜错）。 */
  private static String matchTypeHint(String source) {
    if (!StringUtils.hasText(source)) {
      return null;
    }
    String text = source.toLowerCase();
    String hit = null;
    for (String type : TYPE_HINT_ORDER) {
      boolean matched = TYPE_HINT_KEYWORDS.getOrDefault(type, List.of()).stream()
          .anyMatch(text::contains);
      if (!matched) {
        continue;
      }
      if (hit != null && !hit.equals(type)) {
        return null;
      }
      hit = type;
    }
    return hit;
  }

  private static String typeHintLabel(String targetType) {
    return switch (targetType) {
      case "supply_order" -> "采购单";
      case "delivery_note" -> "送货单/配送单";
      case "transport_note" -> "运输费用";
      case "payment_request" -> "请款单";
      case "payment" -> "付款记录";
      case "receipt" -> "收款记录";
      case "invoice_unknown" -> "发票";
      case "settlement_unknown" -> "结算单";
      case "evidence_only" -> "仅存档的原始凭证";
      default -> "业务单据";
    };
  }

  private ProcessingDecision normalizeProcessingDecision(
      String intent,
      String reply,
      Map<String, Object> payload
  ) {
    if ("non_business".equals(intent) || "clarify_card".equals(intent)
        || "market_quote_card".equals(intent)) {
      // market_quote_card 不进台账、走独立确认导入流程，不需要 processingMode 三件套
      return new ProcessingDecision(intent, reply, payload);
    }

    Map<String, Object> normalized = new java.util.LinkedHashMap<>(payload);
    String processingMode = text(normalized.get("processingMode"));
    boolean archiveOnly = "evidence_only_card".equals(intent)
        || "archive_only".equals(processingMode);

    if (archiveOnly) {
      String detectedType = text(normalized.get("detectedSourceType"));
      if (!isKnownSourceType(detectedType)) {
        detectedType = sourceTypeFromIntent(intent);
      }
      normalized.put("processingMode", "archive_only");
      normalized.put("applyToLedger", false);
      normalized.put("detectedSourceType", detectedType);
      String summary = "unknown".equals(detectedType)
          ? "已按你的要求作为原始凭证保存，不会更新台账。"
          : "已识别资料内容为" + sourceTypeLabel(detectedType)
              + "，并按你的要求作为原始凭证保存，不会更新台账。";
      return new ProcessingDecision("evidence_only_card", summary, normalized);
    }

    normalized.put("processingMode", "apply_to_ledger");
    normalized.put("applyToLedger", true);
    String detectedType = text(normalized.get("detectedSourceType"));
    if (!isKnownSourceType(detectedType)) {
      normalized.put("detectedSourceType", sourceTypeFromIntent(intent));
    }
    return new ProcessingDecision(intent, reply, normalized);
  }

  private static boolean isKnownSourceType(String sourceType) {
    if (!StringUtils.hasText(sourceType)) {
      return false;
    }
    return List.of(
        "supply_order", "delivery_note", "transport_note", "payment_request", "payment",
        "receipt", "purchase_invoice", "sales_invoice", "sales_settlement", "unknown")
        .contains(sourceType);
  }

  private static String sourceTypeFromIntent(String intent) {
    return switch (intent) {
      case "supply_order_card" -> "supply_order";
      case "delivery_note_card" -> "delivery_note";
      case "transport_note_card" -> "transport_note";
      case "payment_request_card" -> "payment_request";
      case "payment_card" -> "payment";
      case "receipt_card" -> "receipt";
      case "purchase_invoice_card" -> "purchase_invoice";
      case "sales_invoice_card" -> "sales_invoice";
      case "sales_settlement_card" -> "sales_settlement";
      default -> "unknown";
    };
  }

  private static String sourceTypeLabel(String sourceType) {
    return switch (sourceType) {
      case "supply_order" -> "采购单";
      case "delivery_note" -> "送货单";
      case "transport_note" -> "运输费用";
      case "payment_request" -> "请款记录";
      case "payment" -> "付款记录";
      case "receipt" -> "收款记录";
      case "purchase_invoice" -> "进项发票";
      case "sales_invoice" -> "销项发票";
      case "sales_settlement" -> "销售结算单";
      default -> "原始资料";
    };
  }

  private record ProcessingDecision(
      String intent,
      String reply,
      Map<String, Object> payload
  ) {
  }

  private static String stripMarkdownCodeBlock(String text) {
    if (text == null) return "";
    text = text.trim();
    if (text.startsWith("```")) {
      int firstNewline = text.indexOf('\n');
      if (firstNewline > 0) {
        text = text.substring(firstNewline + 1);
      }
      if (text.endsWith("```")) {
        text = text.substring(0, text.length() - 3);
      }
    }
    return text.trim();
  }

  private Map<String, Object> normalizeFinancialPayload(
      String intent,
      Map<String, Object> payload
  ) {
    if (intent == null || !List.of(
        "payment_request_card", "payment_card", "purchase_invoice_card",
        "sales_invoice_card", "invoice_card").contains(intent)) {
      return payload;
    }
    Map<String, Object> normalized = new java.util.LinkedHashMap<>(payload);
    String category = text(normalized.get("expenseCategory"));
    if (!List.of("material", "transport").contains(category)) {
      category = looksLikeTransport(normalized) ? "transport" : "material";
    }
    normalized.put("expenseCategory", category);

    if (List.of("payment_card", "payment_request_card").contains(intent)) {
      return normalized;
    }

    String taxRate = text(normalized.get("taxRate"));
    if (!StringUtils.hasText(taxRate)) {
      normalized.put("taxRate", "transport".equals(category) ? "9%" : "13%");
    }
    if (!hasValue(normalized.get("invoiceDate")) && hasValue(normalized.get("docDate"))) {
      normalized.put("invoiceDate", normalized.get("docDate"));
    }
    if (!hasValue(normalized.get("invoiceAmount")) && hasValue(normalized.get("amount"))) {
      normalized.put("invoiceAmount", normalized.get("amount"));
    }
    if ("purchase_invoice_card".equals(intent)) {
      normalized.put("invoiceDirection", "purchase");
    } else if ("sales_invoice_card".equals(intent)) {
      normalized.put("invoiceDirection", "sales");
    } else {
      normalized.put("invoiceDirection", "unknown");
    }
    return normalized;
  }

  private boolean looksLikeTransport(Map<String, Object> payload) {
    String text = payload.values().stream()
        .filter(java.util.Objects::nonNull)
        .map(String::valueOf)
        .collect(java.util.stream.Collectors.joining(" "));
    return matches(text, "运输", "运费", "物流", "承运", "车费");
  }

  private static boolean hasValue(Object value) {
    return value != null && StringUtils.hasText(String.valueOf(value))
        && !"null".equalsIgnoreCase(String.valueOf(value));
  }

  private static String text(Object value) {
    return value == null ? null : String.valueOf(value).trim();
  }

  // ---------------------------------------------------------------------------
  // Mock 兜底
  // ---------------------------------------------------------------------------

  private AiReply mockReply(String text) {
    if (matches(text, "供货", "采购单", "订单", "supply")) {
      return AiReply.card("supply_order_card", "识别到采购单，请核对信息后确认。", Map.of(
          "docCode", "GH-20260722-001", "supplierName", "上海找钢网信息科技股份有限公司",
          "docDate", "2026-07-22", "totalAmount", 176620.21,
          "items", List.of(
              Map.of("materialName", "螺纹钢", "spec", "HRB400E Φ25",
                  "packageQuantity", 20, "packageUnit", "件", "totalWeight", 45.2,
                  "weightUnit", "吨", "pricingQuantity", 45.2, "pricingUnit", "吨",
                  "unitPrice", 3907.53, "amount", 176620.21)),
          "confidence", 96));
    }
    if (matches(text, "运输", "运费", "物流", "承运")) {
      return AiReply.card("transport_note_card", "识别到运输费用，请核对承运单位和金额。", Map.of(
          "docCode", "YS-20260722-001", "supplierName", "杭州朋达物流有限公司",
          "docDate", "2026-07-22", "totalAmount", 2796.05,
          "driverName", "王大伟", "route", "本批次材料运输",
          "remark", "材料运输费用", "confidence", 94));
    }
    if (matches(text, "送货", "出库", "delivery")) {
      return AiReply.card("delivery_note_card", "识别到送货单，请核对信息后确认。", Map.of(
          "docCode", "SH-20260722-001", "customerName", "浙江众安建设集团有限公司",
          "docDate", "2026-07-22", "totalAmount", 155760, "transportFee", 2796.05,
          "items", List.of(
              Map.of("materialName", "螺纹钢", "spec", "HRB400E Φ25",
                  "packageQuantity", 18, "packageUnit", "件", "totalWeight", 40,
                  "weightUnit", "吨", "pricingQuantity", 40, "pricingUnit", "吨",
                  "unitPrice", 3894, "amount", 155760)),
          "confidence", 94));
    }
    if (matches(text, "请款", "付款申请", "申请付款")) {
      return AiReply.card("payment_request_card", "识别到请款记录，请确认回填应付台账。", Map.of(
          "amount", 120000, "target", "浙江建科新材料开发有限公司",
          "occurredDate", "2026-07-22", "remark", "采购货款请款", "confidence", 96));
    }
    if (matches(text, "付款", "打款", "转账", "已付", "payment")) {
      return AiReply.card("payment_card", "识别到付款记录，请确认归档。", Map.of(
          "amount", 120000, "target", "浙江建科新材料开发有限公司",
          "occurredDate", "2026-07-22", "method", "银行转账", "remark", "货款", "confidence", 98));
    }
    if (matches(text, "收款", "回款", "收到", "转入", "receipt")) {
      return AiReply.card("receipt_card", "识别到收款记录，请确认归档。", Map.of(
          "amount", 189268.6, "target", "杭州市上城区卫生健康局",
          "occurredDate", "2026-07-22", "method", "银行转账", "remark", "项目进度款", "confidence", 97));
    }
    if (matches(text, "发票", "增值税", "invoice")) {
      return AiReply.card("purchase_invoice_card", "识别到进项发票，请核对信息后确认。", Map.of(
          "invoiceNo", "04417206", "supplierName", "上海找钢网信息科技股份有限公司",
          "docDate", "2026-07-22", "amount", 176620.21, "taxRate", "13%", "taxAmount", 20319.47,
          "invoiceDirection", "purchase", "expenseCategory", "material",
          "items", List.of(Map.of("materialName", "螺纹钢", "amount", 156300.74)),
          "confidence", 95));
    }
    return AiReply.card("clarify_card", "我已收到，但还不能确定单据类型。请补充信息。", Map.of(
        "question", "这条信息是采购单、送货单、运输费用、收付款记录、还是发票？",
        "missingFields", List.of("单据类型"),
        "options", List.of("采购单", "送货单", "运输费用", "付款记录", "收款记录", "发票")));
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  private static boolean matches(String text, String... keywords) {
    if (text == null) return false;
    for (String keyword : keywords) {
      if (text.contains(keyword)) return true;
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }

  private static String orDash(String value) {
    return StringUtils.hasText(value) ? value : "未指定";
  }

  private static String defaultReply(String intent) {
    return switch (intent) {
      case "supply_order_card" -> "识别到采购单，请确认。";
      case "delivery_note_card" -> "识别到送货单，请确认。";
      case "transport_note_card" -> "识别到运输费用，请确认承运单位和金额。";
      case "payment_request_card" -> "识别到请款记录，请确认回填应付台账。";
      case "payment_card" -> "识别到付款记录，请确认归档。";
      case "receipt_card" -> "识别到收款记录，请确认归档。";
      case "purchase_invoice_card" -> "识别到进项发票，请确认。";
      case "sales_invoice_card" -> "识别到销项发票，请确认。";
      case "invoice_card" -> "识别到发票，请确认进项或销项。";
      case "sales_settlement_card" -> "识别到销售结算单，请确认销售结算信息。";
      case "settlement_card" -> "识别到结算单，请确认结算归属。";
      case "evidence_only_card" -> "已按要求作为原始凭证保存，请确认。";
      case "market_quote_card" -> "识别到行情价格截图，请核对该日价格后确认导入。";
      case "clarify_card" -> "请补充信息或确认归属。";
      default -> "已收到。";
    };
  }
}
