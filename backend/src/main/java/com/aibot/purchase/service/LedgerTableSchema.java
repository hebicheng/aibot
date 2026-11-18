package com.aibot.purchase.service;

import java.util.List;

/**
 * 对账台账表头的唯一来源（三层表头）。
 *
 * <p>web 前端（ReconciliationLedgerPage）按 rows 渲染 th（style 即 CSS 类名），
 * xlsx 导出（LedgerExportService.writeHeader）按同一结构换算 rowStart/rowEnd/colStart/colEnd。
 * 修改列结构只改这里，禁止再在前端或导出里硬编码第二份。
 *
 * <p>列顺序（0 基）：0-1 项目与批次，2 业务分类，3-12 材料采购/运输费用/实际送达
 * （金额 6、采购单数量 7、送货单数量 8、送达状态 9、总额 10=材料采购+运输费用合计、
 * 采购毛利金额 11（= 估算货款 + 客户承担送货运费 − 材料采购 − 采购运费）、
 * 采购毛利率 12=采购毛利金额 ÷（估算货款 + 送货运费），总额法），
 * 13-15 计划 / 实际差额（2026-11-12：采购金额差额 13、采购运费差额 14、
 * 采购毛利差额 15 —— 送货单实际送达量与采购单计划量不一致时的三组差额），
 * 16-22 采购与付款（请款 16、付款记录 17-19、进项发票 20-22），
 * 23-36 销售与收款（销售订单号 23、销售总金额 24、销售运费 25、开票 26-28、收款 29-30、
 * 到款日期 31、要求回款日期 32、月结利息 33、毛利 34、毛利率 35、项目经理 36），
 * 37 状态，38 操作。
 * 「操作」列仅供页面使用（exportable=false），导出不含该列。
 * 销售定价说明不在台账列展示，进「销售总金额」明细弹框逐行显示（2026-09-21 定）。
 *
 * <p>2026-11-07 删列：「采购运费成本」「销售运费」两列撤出材料组——采购运费已独立成
 * 「运输费用」行（口径/运费单都并入该行，进总额），不再需要单独列；销售运费在销售区已有同名列，
 * 材料组重复展示。采购毛利金额下方的「含送货运费」小注保留。
 *
 * <p>2026-11-12：原单列「采购毛利差额」（口径为收入差）改为三列一组的「计划 / 实际差额」，
 * 毛利差额改按真毛利计算（收入差 − 采购金额差额 − 运费差额），收入差降为差额详情里的一行；
 * 该组位置紧跟材料组（用户定：先看差额再看采购付款），因此列号比首版实现前移。
 */
public final class LedgerTableSchema {

  /**
   * @param label      表头文字
   * @param rowSpan    跨行数（三层表头内）
   * @param colSpan    跨列数
   * @param style      样式标识：导出映射为 CellStyle，页面映射为 CSS 类名
   * @param exportable 是否写入 xlsx 导出
   */
  public record HeaderCell(
      String label,
      int rowSpan,
      int colSpan,
      String style,
      boolean exportable
  ) {
    public static HeaderCell of(String label, int rowSpan, int colSpan, String style) {
      return new HeaderCell(label, rowSpan, colSpan, style, true);
    }

    public static HeaderCell pageOnly(String label, int rowSpan, int colSpan, String style) {
      return new HeaderCell(label, rowSpan, colSpan, style, false);
    }
  }

  public static final List<List<HeaderCell>> HEADER = List.of(
      // 第 1 层：分组
      List.of(
          HeaderCell.of("项目与批次", 1, 2, "sticky-context-group head-group-context"),
          HeaderCell.of("业务分类", 1, 1, "head-group-category"),
          HeaderCell.of("材料采购 / 运输费用 / 实际送达", 1, 10, "head-group-material"),
          // 计划 / 实际差额紧跟材料组（2026-11-12 用户定）：先看差额，再看采购付款
          HeaderCell.of("计划 / 实际差额", 1, 3, "head-group-gap"),
          HeaderCell.of("采购与付款", 1, 7, "head-group-purchase-finance"),
          HeaderCell.of("销售与收款", 1, 14, "head-group-sales"),
          HeaderCell.of("状态", 3, 1, "head-group-status"),
          HeaderCell.pageOnly("操作", 3, 1, "sticky-action head-group-action")
      ),
      // 第 2 层：子分组 / 纵向合并叶子
      List.of(
          HeaderCell.of("项目", 2, 1, "sticky-project head-context"),
          HeaderCell.of("批次", 2, 1, "sticky-batch head-context"),
          HeaderCell.of("分类", 2, 1, "head-category"),
          HeaderCell.of("材料 / 费用", 2, 1, "head-material"),
          HeaderCell.of("规格 / 单号", 2, 1, "head-material"),
          HeaderCell.of("供应商 / 承运单位", 2, 1, "head-material"),
          HeaderCell.of("采购 / 运输金额", 2, 1, "head-material"),
          HeaderCell.of("采购单数量", 2, 1, "head-material"),
          HeaderCell.of("送货单数量", 2, 1, "head-material"),
          HeaderCell.of("送达状态", 2, 1, "head-material"),
          HeaderCell.of("总额", 2, 1, "head-material"),
          HeaderCell.of("采购毛利金额", 2, 1, "head-material"),
          HeaderCell.of("采购毛利率", 2, 1, "head-material"),
          // 2026-11-12 三列一组：送货单实际送达量 vs 采购单计划量不一致时的差额（正数=少送）
          HeaderCell.of("采购金额差额", 2, 1, "head-purchase-gap"),
          HeaderCell.of("采购运费差额", 2, 1, "head-purchase-gap"),
          HeaderCell.of("采购毛利差额", 2, 1, "head-purchase-gap"),
          HeaderCell.of("请款", 1, 1, "head-group-request"),
          HeaderCell.of("付款记录", 1, 3, "head-group-payment"),
          HeaderCell.of("进项发票", 1, 3, "head-group-invoice"),
          HeaderCell.of("销售订单号", 2, 1, "head-sales"),
          HeaderCell.of("销售总金额", 2, 1, "head-sales"),
          HeaderCell.of("销售运费", 2, 1, "head-sales"),
          HeaderCell.of("开票", 1, 3, "head-sales-subgroup"),
          HeaderCell.of("收款", 1, 2, "head-sales-subgroup"),
          HeaderCell.of("到款日期", 2, 1, "head-sales"),
          HeaderCell.of("要求回款日期", 2, 1, "head-sales"),
          HeaderCell.of("月结利息", 2, 1, "head-sales"),
          HeaderCell.of("毛利", 2, 1, "head-sales"),
          HeaderCell.of("毛利率", 2, 1, "head-sales"),
          HeaderCell.of("项目经理", 2, 1, "head-sales")
      ),
      // 第 3 层：叶子列
      List.of(
          HeaderCell.of("请款金额", 1, 1, "head-request"),
          HeaderCell.of("付款日期", 1, 1, "head-payment"),
          HeaderCell.of("收款单位", 1, 1, "head-payment"),
          HeaderCell.of("付款金额", 1, 1, "head-payment"),
          HeaderCell.of("开票日期", 1, 1, "head-invoice"),
          HeaderCell.of("开票金额", 1, 1, "head-invoice"),
          HeaderCell.of("税率", 1, 1, "head-invoice"),
          HeaderCell.of("开票日期", 1, 1, "head-sales-leaf"),
          HeaderCell.of("开票金额", 1, 1, "head-sales-leaf"),
          HeaderCell.of("税率", 1, 1, "head-sales-leaf"),
          HeaderCell.of("已收款金额", 1, 1, "head-sales-leaf"),
          HeaderCell.of("剩余应收金额", 1, 1, "head-sales-leaf")
      )
  );

  /** 导出 xlsx 的数据列数（不含页面专属的「操作」列）。 */
  public static final int EXPORT_COLUMN_COUNT = 38;

  private LedgerTableSchema() {
  }
}
