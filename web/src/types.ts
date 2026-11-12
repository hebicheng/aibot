export type PageKey = 'workspace' | 'projects' | 'documents' | 'ledger' | 'market';
export type DocumentStatus = '已匹配' | '待确认' | '识别中' | '有差异' | '重复归档';

export interface Project {
  id: string;
  name: string;
  customer: string;
  status: '进行中' | '待处理' | '已完成';
  progress: number;
  /** 采购合同总额（与台账同源，含采购运费成本） */
  purchase: number;
  /** 总付款 */
  paid: number;
  /** 总收票（进项发票） */
  invoice: number;
  /** 未付款 = 采购合同总额 − 总付款 */
  unpaidPurchase: number;
  /** 未收发票 = 总付款 − 总收票 */
  uninvoicedPurchase: number;
  /** 销售合同总额（与台账同源，含客户承担运费） */
  sales: number;
  /** 总开票（销项发票） */
  salesInvoiced: number;
  /** 总收款 */
  received: number;
  /** 应收款 = 销售合同总额 − 总收款 */
  receivable: number;
  /** 未开票 = 销售合同总额 − 总开票 */
  uninvoicedSales: number;
  profit: number;
  marginRate: number;
  /** 项目级业务默认口径（批次没单独设置的字段用它；对话里说「整个项目都按这个」时写入） */
  businessHints?: Record<string, Record<string, unknown>>;
  batches: Batch[];
}

export interface Batch {
  id: string;
  name: string;
  date: string;
  status: '进行中' | '待确认' | '已完成';
  material: string;
  purchase: number;
  delivered: number;
  paid: number;
  invoiced: number;
  salesInvoiced: number;
  received: number;
  progress: number;
  /** 批次级业务默认口径（按单据类型 scope 分组，本期仅 delivery_note） */
  businessHints?: Record<string, Record<string, unknown>>;
}

export interface BusinessDocument {
  id: string;
  code: string;
  type: '采购单' | '送货单' | '运输费用' | '请款单' | '付款记录' | '收款记录' | '进项发票' | '销项发票' | '结算资料' | '原始凭证';
  supplier: string;
  projectId: string;
  batchId: string;
  date: string;
  amount: number;
  status: DocumentStatus;
  confidence: number;
  source: '图片' | 'PDF' | 'Excel' | '短信' | '手动录入';
  fileName?: string;
  fileUrl?: string;
  sourceText?: string;
  guidanceText?: string;
  aiSummary?: string;
  duplicateReason?: string;
  duplicateDecision?: 'continue' | 'skip';
  docType?: string;
  ledgerApplied?: boolean;
}

export interface LedgerRow {
  id: string;
  projectId?: string;
  batchId?: string;
  sourceDocumentId?: string;
  project: string;
  batch: string;
  purchaseDate: string;
  material: string;
  supplier: string;
  purchaseAmount: number;
  freight: number;
  deliveryAmount: number;
  deliveredPackageQuantity: number;
  deliveredPackageUnit: string;
  deliveredWeight: number;
  deliveredWeightUnit: string;
  requestedAmount?: number;
  paidAmount: number;
  unpaidAmount?: number;
  invoiceAmount: number;
  taxRate: string;
  salesOrder: string;
  salesAmount: number;
  salesInvoiceDate?: string;
  salesInvoiceAmount: number;
  salesTaxRate: string;
  receivedAmount: number;
  outstanding: number;
  receiptDate?: string;
  requiredReceiptDate?: string;
  isMonthlySettlement?: boolean;
  interest?: number;
  interestDetails?: Array<{
    type: string;
    date?: string;
    amount?: number;
    days?: number;
    rate?: number;
    interest?: number;
    note?: string;
  }>;
  receiptEntries?: Array<{ date: string; amount: number }>;
  overdueFlag?: boolean;
  amountCapFlag?: boolean;
  interestSummary?: string;
  grossProfit?: number;
  grossMargin?: number;
  /** 采购毛利额/毛利率（批次级，后端现算瞬时值；undefined=无口径或无有效行；成本含运费） */
  purchaseMarginAmount?: number;
  purchaseMarginRate?: number;
  /** 毛利的计划值（按采购单计划量与计划运费算）：括号里展示的就是它 */
  purchaseMarginPlanAmount?: number;
  purchaseMarginPlanRate?: number;
  /** 毛利算不出的原因：no_policy=缺定价口径 no_supply=无已确认采购单 no_priced_row=明细缺吨数或金额 */
  purchaseMarginNote?: string;
  /** 采购运费成本（批次级）：运输单运费合计 > 送货单采购运费合计 > 批次运费口径推算 */
  purchaseFreightCost?: number;
  /** 客户承担的送货运费（批次级）：只进毛利额，不进毛利率分母（销售金额已含运费） */
  purchaseSalesFreight?: number;
  /** 待采购价的送货明细行数（批次级）：这些行金额为 0，未计入销售总金额 */
  deliveryCostPendingCount?: number;
  /** 采购毛利差额（批次级，2026-11-12 改语义）：按计划量的毛利 − 按实际送达量的毛利；undefined=无送货单 */
  purchaseMarginGap?: number;
  /** 收入差（批次级）：按采购量估算货款 − 按送货量实际锁定货款（差额详情里的一行） */
  purchaseRevenueGap?: number;
  /** 采购计划金额（批次级）：采购单口径金额，页面括号里展示的就是它 */
  purchasePlanAmount?: number;
  /** 采购金额差额（批次级）= 采购计划金额 − 实际送达成本 */
  purchaseAmountGap?: number;
  /** 计划运费（批次级）：元/吨口径按采购吨数还原 */
  purchaseFreightPlan?: number;
  /** 采购运费差额（批次级）= 计划运费 − 实际运费 */
  purchaseFreightGap?: number;
  /** 批次是否有已确认送货单（false 时实际值等于计划值，差额列显示「-」） */
  purchaseHasDelivery?: boolean;
  /** 送货明细里算不出吨数、未计入实际成本的行数 */
  purchaseUnpricedDeliveryRows?: number;
  /** 采购成本基准逐材料大类明细 */
  materialCostBasis?: Array<{
    category: string;
    planTons: number;
    planAmount: number;
    deliveredTons: number;
    actualAmount: number;
    unitPrice?: number;
    fallbackPriced?: boolean;
    unpriced?: boolean;
  }>;
  /** 运费明细行（批次级）：口径 / 运输单 / 单据写明并列累加，运输费用行的构成明细直接读它 */
  freightItems?: Array<{
    id: string;
    side: 'purchase' | 'sales';
    source: 'policy_ton' | 'policy_trip' | 'transport_note' | 'document_stated';
    sourceDocumentId?: string;
    tons?: number;
    unitPrice?: number;
    amount: number;
    note?: string;
    /** 承运单位（V28 起存在运费行上，可人工覆盖；口径行可能为空） */
    carrierName?: string;
    carrierManual?: boolean;
  }>;
  projectManager?: string;
  status: '已对平' | '待补充' | '待付款' | '待收款' | '票款差异';
}
