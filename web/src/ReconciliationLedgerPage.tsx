import { AlertCircle, ArrowDownToLine, Check, ChevronDown, ChevronRight, Layers, PackageCheck, PencilLine, Truck, X } from 'lucide-react';
import { Fragment, useEffect, useState } from 'react';
import { api } from './api';
import type { ApiBusinessDataRecord, ApiLedgerHeaderCell, MaterialRow, SalesDetailRow } from './api';
import { isWeightUnit, materialKey, summarizeBatchDelivery, weightInTons } from './deliveryMatching';
import { materialCategory } from './materialCategory';
import type { LedgerRow, Project } from './types';

const money = (value: number) =>
  new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(value || 0);

const quantity = (value: number) =>
  new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 3 }).format(value || 0);

/** 销售单价的口径标注：数值旁边的小字（人工价 / 毛利率 / 上浮） */
const salesPriceLabel = (basis: string | null) =>
  basis === 'manual' ? '人工价' : basis === 'margin' ? '毛利率' : basis === 'uplift' ? '上浮' : '';

/** 算不出销价时说明原因，而不是给一个看着像 0 的空值 */
const salesPriceHint = (basis: string | null) =>
  basis === 'no_policy' ? '未设口径'
    : basis === 'cost_pending' ? '待采购价'
      : basis === 'net_pending' ? '待网价'
        : '未算';

/** 采购毛利算不出时的原因文案（后端 purchaseMarginNote） */
const purchaseMarginHint = (note?: string) =>
  note === 'no_policy' ? '缺定价口径'
    : note === 'no_supply' ? '无已确认采购单'
      : note === 'no_priced_row' ? '采购明细缺吨价'
        : '';

function LedgerMoney({
  value,
  tone = ''
}: {
  value: number;
  tone?: 'amount-completed' | 'amount-pending' | '';
}) {
  return <span className={`ledger-money-value ${tone}`.trim()}>{money(value)}</span>;
}

function LedgerDate({ value }: { value?: string }) {
  return <span className="ledger-date-value">{value || '-'}</span>;
}

function LedgerRate({ value }: { value?: string }) {
  return <span className="ledger-rate-value">{value || '-'}</span>;
}

const normalize = (value?: string) => (value || '').replace(/\s+/g, '').toLowerCase();

type FinancialSummary = {
  requested: number;
  paid: number;
  unpaid: number;
  paymentDate: string;
  payee: string;
  invoice: number;
  invoiceDate: string;
  taxRate: string;
};

/** 运输费用行的构成明细条目：送货单运费条目 + 独立运输单。 */
type TransportDetailEntry = {
  key: string;
  source: string;
  date: string;
  code: string;
  party: string;
  amount: number;
};

/** 请款明细条目（台账请款列弹框展开用）。 */
type FinanceDetailEntry = {
  key: string;
  date: string;
  amount: number;
  target: string;
  remark: string;
};

type DetailRow = FinancialSummary & {
  key: string;
  kind: 'material' | 'transport';
  material: string;
  spec: string;
  supplier: string;
  purchaseQuantity: number;
  purchaseUnit: string;
  // 材料行：明细行无金额时为 null（展示空白），不参与未付款基线；
  // 运输行：始终为数值。
  purchaseAmount: number | null;
  deliveredPackages: number;
  deliveredPackageUnit: string;
  deliveredWeight: number;
  deliveredWeightUnit: string;
  deliveryStatus: string;
  // 送货单先行行（发货口径，采购单未到或未匹配上）标记
  sourceTag?: 'delivery';
  // 仅运输行使用：构成明细
  details?: TransportDetailEntry[];
};

type BatchGroup = {
  key: string;
  project: string;
  batch: string;
  date: string;
  // 完整行：材料明细行（展开态）+ 运输费用行
  rows: DetailRow[];
  // 折叠态展示的材料汇总行
  summaryRow: DetailRow;
  // 是否已有真实材料行（送货单单独导入时为 false，展示占位行且不可展开）
  hasMaterials: boolean;
  materialRowCount: number;
  hasDelivery: boolean;
  materialFinance: FinancialSummary;
  // 请款明细（弹框展开）：材料 / 运输两个维度分开归集
  materialRequested: FinanceDetailEntry[];
  transportRequested: FinanceDetailEntry[];
  totalPurchase: number;
  totalRequested: number;
  totalPaid: number;
  totalUnpaid: number;
  totalInvoice: number;
  salesOrder: string;
  sales: number;
  salesInvoiceDate: string;
  salesInvoiceAmount: number;
  salesTaxRate: string;
  received: number;
  outstanding: number;
  receiptDate: string;
  requiredReceiptDate: string;
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
  grossProfit: number;
  grossMargin: number;
  // 采购毛利（批次级，后端现算瞬时值；undefined=无口径或无有效行，显示 -；成本含运费）
  purchaseMarginAmount?: number;
  purchaseMarginRate?: number;
  // 毛利算不出的原因：no_policy=缺定价口径 no_supply=无已确认采购单 no_priced_row=明细缺吨数或金额
  purchaseMarginNote?: string;
  // 采购运费成本（批次级）：运输单合计 > 送货单采购运费 > 批次运费口径推算
  purchaseFreightCost?: number;
  // 客户承担的送货运费（批次级）：与货款一起构成总收入（毛利率分母），同时是毛利额加项
  purchaseSalesFreight?: number;
  // 销售运费的构成说明（运费行 note 拼接，如「按口径 40 元/吨 × 13.653 吨」）
  salesFreightDetail?: string;
  // 待采购价的送货明细行数（批次级）：这些行金额为 0，未计入销售总金额
  deliveryCostPendingCount?: number;
  // 采购毛利差额（批次级）：按采购量估算货款 − 按送货量实际货款；undefined=还没有送货单
  purchaseMarginGap?: number;
  // 运费明细行（批次级）：口径/运输单/单据写明并列累加
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
  projectManager: string;
  status: string;
};

const structuredData = (record: ApiBusinessDataRecord): Record<string, unknown> => {
  try {
    return record.document.structuredData
      ? JSON.parse(record.document.structuredData) as Record<string, unknown>
      : {};
  } catch {
    return {};
  }
};

const recordTaxRate = (record: ApiBusinessDataRecord) => {
  const value = structuredData(record).taxRate;
  return value == null ? '' : String(value);
};

const recordExpenseCategory = (record: ApiBusinessDataRecord) => {
  const value = structuredData(record).expenseCategory;
  return value === 'transport' ? 'transport' : value === 'material' ? 'material' : '';
};

const transportHint = (record: ApiBusinessDataRecord) =>
  `${record.document.sourceText || ''} ${record.document.guidanceText || ''} ${record.document.aiSummary || ''} ${record.document.remark || ''}`
    .match(/运输|运费|物流|承运/);

/**
 * 送货单上标注为运输费用的金额（transportFee/freight）。
 * 送货单语义修正后 amount 字段是销售额，不能再当运费使用。
 */
const deliveryFreight = (record: ApiBusinessDataRecord) => {
  const structured = structuredData(record);
  const value = structured.transportFee ?? structured.freight;
  const parsed = value == null ? NaN : Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
};

/** 送货单上人工补录的承运单位（structuredData.carrierName，选填）。 */
const deliveryCarrier = (record: ApiBusinessDataRecord) => {
  const value = structuredData(record).carrierName;
  return value == null ? '' : String(value).trim();
};

function summarizeFinance(
  records: ApiBusinessDataRecord[],
  purchaseAmount: number
): FinancialSummary {
  const requested = records
    .filter((record) => record.document.docType === 'payment_request')
    .reduce((sum, record) => sum + Number(record.document.amount || 0), 0);
  const paid = records
    .filter((record) => record.document.docType === 'payment')
    .reduce((sum, record) => sum + Number(record.document.amount || 0), 0);
  const paymentRecord = records.find((record) => record.document.docType === 'payment');
  const invoiceRecords = records.filter((record) =>
    ['purchase_invoice', 'invoice'].includes(record.document.docType));
  const invoiceRecord = invoiceRecords[0];
  const invoice = invoiceRecords.reduce(
    (sum, record) => sum + Number(record.document.amount || 0),
    0
  );
  const taxRates = Array.from(new Set(invoiceRecords.map(recordTaxRate).filter(Boolean)));
  return {
    requested,
    paid,
    unpaid: Math.max(purchaseAmount - paid, 0),
    paymentDate: paymentRecord?.document.docDate || '',
    payee: paymentRecord?.document.supplierName || '',
    invoice,
    invoiceDate: invoiceRecord?.document.docDate || '',
    taxRate: taxRates.join(' / ') || '-'
  };
}

const emptyFinance = (): FinancialSummary => ({
  requested: 0,
  paid: 0,
  unpaid: 0,
  paymentDate: '',
  payee: '',
  invoice: 0,
  invoiceDate: '',
  taxRate: '-'
});

function buildGroups(
  records: ApiBusinessDataRecord[],
  ledgerRows: LedgerRow[],
  projects: Project[]
): BatchGroup[] {
  const groups: BatchGroup[] = [];
  for (const project of projects) {
    for (const batch of project.batches) {
      const batchRecords = records.filter((record) =>
        record.document.batchId === batch.id
        && ['matched', 'archived'].includes(record.document.status)
        && record.document.duplicateDecision !== 'skip');
      const supplyRecords = batchRecords.filter((record) =>
        record.document.docType === 'supply_order');
      const transportRecords = batchRecords.filter((record) =>
        record.document.docType === 'transport_note');
      const financeRecords = batchRecords.filter((record) =>
        ['payment_request', 'payment', 'purchase_invoice', 'invoice'].includes(record.document.docType));
      const batchLedger = ledgerRows.filter((row) => row.batchId === batch.id);
      if (supplyRecords.length === 0 && transportRecords.length === 0 && batchLedger.length === 0) {
        continue;
      }

      const deliverySummary = summarizeBatchDelivery(batchRecords);

      const materialMap = new Map<string, DetailRow>();
      for (const supply of supplyRecords) {
        // 材料行按「材料大类」聚合（螺纹钢不分型号规格），多张采购单数量/金额累加；
        // 行金额按明细行自身金额（item.amount），缺失/无价即「空白」（null），
        // 不再用单据总额填满每行、也不按比例摊回差额，且不影响未付款基线。
        const sourceItems = supply.items.length ? supply.items : [{
          id: supply.document.id,
          materialName: '未拆分采购数据',
          spec: '',
          pricingQuantity: null,
          pricingUnit: '',
          unit: '',
          quantity: null,
          unitPrice: null,
          amount: null
        }];
        for (const item of sourceItems) {
          const key = materialKey(item.materialName, item.spec);
          const delivery = deliverySummary.detailsByMaterial.get(key);
          const purchaseQuantity = Number(item.pricingQuantity ?? item.quantity ?? 0);
          const purchaseUnit = item.pricingUnit || item.unit || '';
          const itemAmount = item.amount == null ? null : Number(item.amount);
          const current = materialMap.get(key) || {
            key,
            kind: 'material' as const,
            material: materialCategory(item.materialName),
            spec: '',
            supplier: '',
            purchaseQuantity: 0,
            purchaseUnit,
            purchaseAmount: null,
            deliveredPackages: delivery?.deliveredPackages || 0,
            deliveredPackageUnit: delivery?.deliveredPackageUnit || '',
            deliveredWeight: delivery?.deliveredWeight || 0,
            deliveredWeightUnit: delivery?.deliveredWeightUnit || '吨',
            deliveryStatus: delivery?.status || '待送达',
            ...emptyFinance()
          };
          current.purchaseQuantity += purchaseQuantity;
          if (itemAmount != null) {
            current.purchaseAmount = (current.purchaseAmount ?? 0) + itemAmount;
          }
          current.purchaseUnit = current.purchaseUnit || purchaseUnit;
          const supplier = supply.document.supplierName || '';
          if (supplier && !current.supplier.split(' / ').includes(supplier)) {
            current.supplier = current.supplier ? `${current.supplier} / ${supplier}` : supplier;
          }
          materialMap.set(key, current);
        }
      }

      // 采购基准行（采购单明细按大类）+ 送货单先行行：采购单未到的大类的送货明细
      // 各自成行，显示发货数量（状态=待采购单）；金额列留空白——送货明细金额是销售侧
      // 的，不能进采购金额列（会污染折叠汇总与毛利基数），批次销售列已有该单金额。
      // 匹配只用于送达核对，不参与任何金额计算；采购单同大类确认后自动并入采购基准行。
      const deliveryOnlyMap = new Map<string, DetailRow>();
      for (const delivery of batchRecords.filter((record) =>
        record.document.docType === 'delivery_note')) {
        for (const item of delivery.items) {
          const key = materialKey(item.materialName, item.spec);
          if (materialMap.has(key)) {
            continue;
          }
          const deliveryQuantity = Number(
            item.pricingQuantity ?? item.quantity ?? item.totalWeight ?? 0);
          const deliveryUnit = item.pricingUnit || item.weightUnit || item.unit || '';
          const current = deliveryOnlyMap.get(key) || {
            key: `delivery-${key}`,
            kind: 'material' as const,
            material: materialCategory(item.materialName),
            spec: '',
            supplier: '',
            purchaseQuantity: 0,
            purchaseUnit: '',
            purchaseAmount: null,
            deliveredPackages: 0,
            deliveredPackageUnit: '',
            deliveredWeight: 0,
            deliveredWeightUnit: '吨',
            deliveryStatus: '待采购单',
            sourceTag: 'delivery' as const,
            ...emptyFinance()
          };
          // 送货单先行行：发货数量只进「送货单数量」（delivered），不再借用 purchaseQuantity——
          // 数量列拆分后借用会把送货量串到「采购单数量」列，并污染送达状态判定（2026-09-21 修复）
          if (isWeightUnit(deliveryUnit)) {
            current.deliveredWeight += weightInTons(deliveryQuantity, deliveryUnit);
          } else {
            current.deliveredPackages += deliveryQuantity;
            current.deliveredPackageUnit = current.deliveredPackageUnit || deliveryUnit;
          }
          deliveryOnlyMap.set(key, current);
        }
      }
      const materialRows = [...Array.from(materialMap.values()), ...deliveryOnlyMap.values()];

      // 送货单 = 销售侧发货单：只有其上标注的运输费用（transportFee）参与运输费用行，
      // 销售金额走台账销售列（后端按送货单自动汇总，人工仍可改）。
      const deliveryFeeRecords = batchRecords.filter(
        (record) => record.document.docType === 'delivery_note'
          && deliveryFreight(record) > 0);
      const deliveryFeeIds = new Set(deliveryFeeRecords.map((record) => record.document.id));

      const transportSupplierKeys = new Set(
        transportRecords.map((record) => normalize(record.document.supplierName))
      );
      const transportFinanceRecords = financeRecords.filter((record) => {
        const category = recordExpenseCategory(record);
        return category === 'transport'
          || (!category && (
            (record.document.targetDocumentId && deliveryFeeIds.has(record.document.targetDocumentId))
            || transportSupplierKeys.has(normalize(record.document.supplierName))
            || Boolean(transportHint(record))
          ));
      });
      const transportFinanceIds = new Set(
        transportFinanceRecords.map((record) => record.document.id)
      );
      const materialFinanceRecords = financeRecords.filter((record) =>
        !transportFinanceIds.has(record.document.id));
      // 未付款基线必须锚定台账权威采购额（后端 upsertSupplySkeleton 按「明细合计 or 单据总额」兜底），
      // 不能用展示行的 item.amount 求和——明细行无金额时会被清零，导致未付款算成 0。
      const materialPurchase = batchLedger.reduce(
        (sum, row) => sum + Number(row.purchaseAmount || 0),
        0
      );
      const materialFinance = summarizeFinance(materialFinanceRecords, materialPurchase);

      // 请款明细（弹框展开用）：与请款列累计值同源（同一条记录集合），按笔次列出
      const docTypeShortLabel = (docType: string) =>
        docType === 'delivery_note' ? '送货单'
          : docType === 'transport_note' ? '运输单'
            : docType === 'supply_order' ? '采购单' : '单据';
      const targetLabel = (record: ApiBusinessDataRecord) => {
        const targetId = record.document.targetDocumentId;
        if (!targetId) return '';
        const target = batchRecords.find((item) => item.document.id === targetId);
        if (!target) return '';
        return [docTypeShortLabel(target.document.docType), target.document.docCode]
          .filter(Boolean).join(' ');
      };
      const requestedDetails = (records: ApiBusinessDataRecord[]): FinanceDetailEntry[] =>
        records
          .filter((record) => record.document.docType === 'payment_request')
          .map((record) => ({
            key: record.document.id,
            date: record.document.docDate || '',
            amount: Number(record.document.amount || 0),
            target: targetLabel(record),
            remark: record.document.remark || ''
          }));
      const materialRequested = requestedDetails(materialFinanceRecords);
      const transportRequested = requestedDetails(transportFinanceRecords);

      // 运输费用行：批次内运输单 + 送货单标注的运输费用（transportFee）+ 批次运费口径推算，合并为一条累加值，
      // 付款、进项发票按「运输费用」类别归集到这一行；构成明细可展开查看。
      // 2026-11-07：口径运费（元/趟 包干价 或 元/吨×采购吨数）也落在这一行——批次没有运费单据时，
      // 这一行就是「按口径估算」的运费，金额取台账权威值 purchaseFreightCost（后端已把口径与单据累加）。
      const transportSources = [...transportRecords, ...deliveryFeeRecords];
      const transportSourceIds = new Set(transportSources.map((record) => record.document.id));
      const docFreight = transportSources.reduce(
        (sum, record) => sum + (record.document.docType === 'delivery_note'
          ? deliveryFreight(record)
          : Number(record.document.amount || 0)),
        0
      );
      const summaryLedgerRow = batchLedger[0];
      const ledgerFreight = Number(summaryLedgerRow?.purchaseFreightCost ?? 0);
      const totalFreight = ledgerFreight > 0 ? ledgerFreight : docFreight;
      // 运费行（采购侧）：口径 / 运输单 / 单据写明并列累加，构成明细直接读后端运费行（2026-11-11）
      const purchaseFreightRows = (summaryLedgerRow?.freightItems ?? [])
        .filter((item) => item.side === 'purchase');
      const transportRows: DetailRow[] = [];
      if (totalFreight > 0) {
        const rowFinanceRecords = transportFinanceRecords.filter((record) =>
          !record.document.targetDocumentId
          || transportSourceIds.has(record.document.targetDocumentId));
        const finance = summarizeFinance(rowFinanceRecords, totalFreight);
        const details: TransportDetailEntry[] = purchaseFreightRows.map((item) => {
          const sourceDocument = item.sourceDocumentId
            ? batchRecords.find((record) => record.document.id === item.sourceDocumentId)?.document
            : undefined;
          const byPolicy = item.source.startsWith('policy');
          return {
            key: item.id,
            source: item.source === 'transport_note' ? '运输单'
              : item.source === 'document_stated' ? '送货单运费'
                : item.source === 'policy_trip' ? '按口径（元/趟）' : '按口径（元/吨）',
            date: sourceDocument?.docDate || batch.date || '',
            code: sourceDocument?.docCode || (byPolicy ? '按口径' : ''),
            // 承运单位以运费行为准（V28 起存在行上，可人工覆盖），没有才回退源单据
            party: item.carrierName || sourceDocument?.supplierName
              || (byPolicy ? '待补充承运单位' : ''),
            amount: item.amount
          };
        });
        // 承运单位汇总：运输单取往来单位，送货单取人工补录的承运单位（structuredData.carrierName）
        const carriers = transportSources.map((record) =>
          record.document.docType === 'delivery_note'
            ? deliveryCarrier(record)
            : record.document.supplierName || '');
        transportRows.push({
          key: `${batch.id}-transport`,
          kind: 'transport',
          material: '运输费用',
          spec: Array.from(new Set(transportSources
            .map((record) => record.document.docCode)
            .filter(Boolean))).join(' / ')
            || purchaseFreightRows.map((item) => item.note).filter(Boolean).slice(0, 1).join('')
            || '运输费用',
          // 承运单位以运费行为准（V28 起存在行上、可人工覆盖），不再回头拼源单据
          supplier: Array.from(new Set(purchaseFreightRows
            .map((item) => item.carrierName)
            .filter(Boolean))).join(' / ') || '待补充承运单位',
          purchaseQuantity: Math.max(purchaseFreightRows.length, transportSources.length, 1),
          purchaseUnit: '笔',
          purchaseAmount: totalFreight,
          deliveredPackages: 0,
          deliveredPackageUnit: '',
          deliveredWeight: 0,
          deliveredWeightUnit: '',
          deliveryStatus: Array.from(new Set(transportSources
            .map((record) => record.document.docDate)
            .filter(Boolean))).join(' / ') || '按口径估算',
          details,
          ...finance
        });
      }

      // 折叠态的材料汇总行：一行收纳全部材料大类，点击展开按大类核对（规格级不再展示）。
      // 送货单单独导入（暂无采购单）时用占位行保住台账骨架，待采购单确认后自动替换。
      const placeholderRow: DetailRow = {
        key: `${batch.id}-material-placeholder`,
        kind: 'material',
        material: '材料明细（待采购单）',
        spec: '确认采购单后自动生成材料行',
        supplier: '',
        purchaseQuantity: 0,
        purchaseUnit: '',
        purchaseAmount: null,
        deliveredPackages: 0,
        deliveredPackageUnit: '',
        deliveredWeight: 0,
        deliveredWeightUnit: '吨',
        deliveryStatus: '待送达',
        ...emptyFinance()
      };
      const allAmountsNull = materialRows.every((row) => row.purchaseAmount == null);
      const summaryPurchaseAmount = allAmountsNull
        ? null
        : materialRows.reduce((sum, row) => sum + (row.purchaseAmount ?? 0), 0);
      const isDeliveredStatus = (status: string) => status === '已送达' || status === '超供送达';
      const summaryDeliveryStatus = materialRows.every((row) => isDeliveredStatus(row.deliveryStatus))
        ? materialRows.some((row) => row.deliveryStatus === '超供送达') ? '超供送达' : '已送达'
        : materialRows.some((row) => isDeliveredStatus(row.deliveryStatus))
          ? '部分送达'
          : '待送达';
      const summaryRow: DetailRow = materialRows.length > 0 ? {
        key: `${batch.id}-material-summary`,
        kind: 'material',
        material: `材料明细（${materialRows.length} 大类）`,
        spec: Array.from(new Set(materialRows.map((row) => row.material)
          .filter((name) => name !== '未标注材料' && name !== '未拆分采购数据')))
          .join(' / ') || '未拆分采购数据',
        supplier: Array.from(new Set(materialRows
          .flatMap((row) => row.supplier.split(' / '))
          .filter(Boolean)))
          .join(' / '),
        purchaseQuantity: materialRows.reduce((sum, row) => sum + row.purchaseQuantity, 0),
        purchaseUnit: materialRows.length === 1
          ? materialRows[0].purchaseUnit
          : '合计',
        purchaseAmount: summaryPurchaseAmount,
        deliveredPackages: materialRows.reduce((sum, row) => sum + row.deliveredPackages, 0),
        deliveredPackageUnit: materialRows.length === 1 ? materialRows[0].deliveredPackageUnit : '',
        deliveredWeight: materialRows.reduce((sum, row) => sum + row.deliveredWeight, 0),
        deliveredWeightUnit: '吨',
        deliveryStatus: summaryDeliveryStatus,
        ...emptyFinance()
      } : placeholderRow;

      const materialDisplayRows = materialRows.length > 0 ? materialRows : [placeholderRow];
      const rows = [...materialDisplayRows, ...transportRows];
      if (rows.length === 0) continue;

      const totalRequested = materialFinance.requested + transportRows.reduce(
        (sum, row) => sum + row.requested,
        0
      );
      // 采购及运输合计 = 台账权威材料采购额 + 运输费用行（单据 + 运费口径，见上方 transportRows）；
      // 展示行的 item.amount 可能为空白，不能作为合计基线。
      const totalPurchase = materialPurchase + transportRows.reduce(
        (sum, row) => sum + (row.purchaseAmount ?? 0),
        0
      );
      const totalPaid = materialFinance.paid + transportRows.reduce(
        (sum, row) => sum + row.paid,
        0
      );
      const totalUnpaid = materialFinance.unpaid + transportRows.reduce(
        (sum, row) => sum + row.unpaid,
        0
      );
      const totalInvoice = materialFinance.invoice + transportRows.reduce(
        (sum, row) => sum + row.invoice,
        0
      );
      const sales = batchLedger.reduce((sum, row) => sum + Number(row.salesAmount || 0), 0);
      const received = batchLedger.reduce((sum, row) => sum + Number(row.receivedAmount || 0), 0);
      const outstanding = Math.max(sales - received, 0);
      // 客户承担的送货运费：台账销售总金额已含运费，毛利率分母直接用销售总金额（总额法，2026-10-28 定）。
      const salesFreight = Number(summaryLedgerRow?.purchaseSalesFreight ?? 0);
      const grossProfit = sales > 0 ? sales - totalPurchase : 0;
      const grossMargin = sales > 0 ? grossProfit / sales * 100 : 0;
      const materialBalanced = materialRows.length === 0
        || (materialFinance.unpaid <= 0 && materialFinance.invoice >= materialPurchase);
      const transportBalanced = transportRows.every((row) =>
        row.unpaid <= 0 && row.invoice >= (row.purchaseAmount ?? 0));

      groups.push({
        key: batch.id,
        hasDelivery: batchRecords.some((record) =>
          record.document.docType === 'delivery_note' && Boolean(record.document.ledgerApplied)),
        project: project.name,
        batch: batch.name,
        date: batch.date,
        rows,
        summaryRow,
        hasMaterials: materialRows.length > 0,
        materialRowCount: Math.max(materialRows.length, 1),
        materialFinance,
        materialRequested,
        transportRequested,
        totalPurchase,
        totalRequested,
        totalPaid,
        totalUnpaid,
        totalInvoice,
        salesOrder: summaryLedgerRow?.salesOrder || '',
        sales,
        salesInvoiceDate: summaryLedgerRow?.salesInvoiceDate || '',
        salesInvoiceAmount: Number(summaryLedgerRow?.salesInvoiceAmount || 0),
        salesTaxRate: summaryLedgerRow?.salesTaxRate || '-',
        received,
        outstanding,
        receiptDate: summaryLedgerRow?.receiptDate || '',
        requiredReceiptDate: summaryLedgerRow?.requiredReceiptDate || '',
        isMonthlySettlement: Boolean(summaryLedgerRow?.isMonthlySettlement),
        interest: summaryLedgerRow?.interest == null ? undefined : Number(summaryLedgerRow.interest),
        interestDetails: summaryLedgerRow?.interestDetails,
        receiptEntries: summaryLedgerRow?.receiptEntries,
        overdueFlag: summaryLedgerRow?.overdueFlag,
        amountCapFlag: summaryLedgerRow?.amountCapFlag,
        interestSummary: summaryLedgerRow?.interestSummary,
        grossProfit,
        grossMargin,
        purchaseMarginAmount: summaryLedgerRow?.purchaseMarginAmount == null
          ? undefined
          : Number(summaryLedgerRow.purchaseMarginAmount),
        purchaseMarginRate: summaryLedgerRow?.purchaseMarginRate == null
          ? undefined
          : Number(summaryLedgerRow.purchaseMarginRate),
        purchaseMarginNote: summaryLedgerRow?.purchaseMarginNote ?? undefined,
        purchaseFreightCost: summaryLedgerRow?.purchaseFreightCost == null
          ? undefined
          : Number(summaryLedgerRow.purchaseFreightCost),
        purchaseSalesFreight: summaryLedgerRow?.purchaseSalesFreight == null
          ? undefined
          : salesFreight,
        salesFreightDetail: (summaryLedgerRow?.freightItems ?? [])
          .filter((item) => item.side === 'sales')
          .map((item) => item.note || '')
          .filter(Boolean)
          .join('；'),
        freightItems: summaryLedgerRow?.freightItems ?? [],
        deliveryCostPendingCount: summaryLedgerRow?.deliveryCostPendingCount == null
          ? undefined
          : Number(summaryLedgerRow.deliveryCostPendingCount),
        purchaseMarginGap: summaryLedgerRow?.purchaseMarginGap == null
          ? undefined
          : Number(summaryLedgerRow.purchaseMarginGap),
        projectManager: summaryLedgerRow?.projectManager || '',
        status: materialBalanced
          && transportBalanced
          && materialRows.every((row) => isDeliveredStatus(row.deliveryStatus))
          && (materialRows.length > 0 || transportRows.length > 0)
          ? '已对平' : '待补充'
      });
    }
  }
  return groups;
}

export function ReconciliationLedgerPage({
  records,
  rows,
  projects,
  currentBatchId,
  onToast,
  onUpdateBatch,
  onExportLedger,
  onExportReconciliation
}: {
  records: ApiBusinessDataRecord[];
  rows: LedgerRow[];
  projects: Project[];
  currentBatchId: string;
  onToast: (message: string) => void;
  onUpdateBatch: (batchId: string, edits: {
    salesOrder?: string;
    salesAmount?: number;
    receiptDate?: string;
    requiredReceiptDate?: string;
    projectManager?: string;
    isMonthlySettlement?: boolean;
  }) => Promise<void>;
  onExportLedger: () => Promise<void>;
  onExportReconciliation: (start: string, end: string, material?: string) => Promise<void>;
}) {
  // 对账台账以当前所在批次为准：只显示当前批次的行，不展示整个项目（BatchGroup.key = batchId）
  const groups = buildGroups(records, rows, projects)
    .filter((group) => group.key === currentBatchId);
  // 表头定义来自后端 LedgerTableSchema（唯一来源），与导出 xlsx 共用同一结构
  const [columns, setColumns] = useState<ApiLedgerHeaderCell[][] | null>(null);
  const [columnsError, setColumnsError] = useState(false);
  const [expandedBatches, setExpandedBatches] = useState<Set<string>>(new Set());
  const [editingBatchId, setEditingBatchId] = useState('');
  const [savingBatchId, setSavingBatchId] = useState('');
  const [exporting, setExporting] = useState(false);
  // 对账单导出弹框：选日期范围（默认当月 1 日 ~ 今天）
  const [reconOpen, setReconOpen] = useState(false);
  const [reconExporting, setReconExporting] = useState(false);
  const [reconRange, setReconRange] = useState(() => {
    const now = new Date();
    const iso = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
    return {
      start: `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`,
      end: iso(now)
    };
  });
  // 导出材料：按批次材料（新建批次时填写）筛选，空串 = 全部材料
  const [reconMaterial, setReconMaterial] = useState('');
  const currentProjectForRecon = projects.find((project) =>
    project.batches.some((batch) => batch.id === currentBatchId));
  const reconMaterialOptions = Array.from(new Set(
    (currentProjectForRecon?.batches || [])
      .map((batch) => (batch.material || '').trim())
      .filter((material) => material && material !== '待补充')
  ));
  const [interestDetailGroup, setInterestDetailGroup] = useState<BatchGroup | null>(null);
  // 运输费用行构成明细的展开状态（按批次，与材料行展开同交互）
  const [expandedTransports, setExpandedTransports] = useState<Set<string>>(new Set());
  // 请款明细弹框：材料 / 运输两个维度
  const [requestedDetail, setRequestedDetail] = useState<{
    title: string;
    subtitle: string;
    details: FinanceDetailEntry[];
  } | null>(null);
  // 销售金额明细弹框（网价锁定值，只读展示；人工改价统一在行情中心操作）
  const [salesDetail, setSalesDetail] = useState<{
    title: string;
    subtitle: string;
    batchId: string;
    rows: SalesDetailRow[];
  } | null>(null);
  const [salesDetailLoading, setSalesDetailLoading] = useState(false);
  // 材料清单弹框（只读概览，2026-11-04 职责单一化）：绑定/改价/AI 校准统一在「市场行情 · 批次材料」
  const [materialsOpen, setMaterialsOpen] = useState(false);
  const [materialRows, setMaterialRows] = useState<MaterialRow[]>([]);
  const [materialsLoading, setMaterialsLoading] = useState(false);

  const openMaterials = async () => {
    setMaterialsOpen(true);
    setMaterialsLoading(true);
    try {
      setMaterialRows(await api.materials(currentBatchId));
    } catch (error) {
      setMaterialRows([]);
      onToast(error instanceof Error ? error.message : '加载材料清单失败');
    } finally {
      setMaterialsLoading(false);
    }
  };
  const openSalesDetail = async (batchId: string, batchName: string, sales: number) => {
    setSalesDetail({ title: '销售金额明细', subtitle: `${batchName} · 累计 ${money(sales)}`, batchId, rows: [] });
    setSalesDetailLoading(true);
    try {
      const rows = await api.salesDetail(batchId);
      setSalesDetail((current) => (current && current.batchId === batchId ? { ...current, rows } : current));
    } catch (error) {
      setSalesDetail(null);
      onToast(error instanceof Error ? error.message : '加载销售明细失败');
    } finally {
      setSalesDetailLoading(false);
    }
  };
  const [summaryDraft, setSummaryDraft] = useState({
    salesOrder: '',
    salesAmount: '',
    receiptDate: '',
    requiredReceiptDate: '',
    projectManager: '',
    isMonthlySettlement: false
  });
  // 运费行承运单位草稿（编辑台账时逐行改，保存一次性提交）
  const [freightDraft, setFreightDraft] = useState<Record<string, string>>({});
  useEffect(() => {
    let cancelled = false;
    api.ledgerColumns()
      .then((definition) => {
        if (!cancelled) setColumns(definition);
      })
      .catch(() => {
        if (!cancelled) setColumnsError(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);
  const toggleExpand = (batchId: string) => {
    setExpandedBatches((current) => {
      const next = new Set(current);
      if (next.has(batchId)) {
        next.delete(batchId);
      } else {
        next.add(batchId);
      }
      return next;
    });
  };
  const toggleTransportExpand = (batchId: string) => {
    setExpandedTransports((current) => {
      const next = new Set(current);
      if (next.has(batchId)) {
        next.delete(batchId);
      } else {
        next.add(batchId);
      }
      return next;
    });
  };
  const totalPurchase = groups.reduce((sum, group) => sum + group.totalPurchase, 0);
  const totalRequested = groups.reduce((sum, group) => sum + group.totalRequested, 0);
  const totalPaid = groups.reduce((sum, group) => sum + group.totalPaid, 0);
  const totalUnpaid = groups.reduce((sum, group) => sum + group.totalUnpaid, 0);
  const totalInvoice = groups.reduce((sum, group) => sum + group.totalInvoice, 0);
  // 销售侧 KPI：销售总金额含销售运费；毛利 = 销售 - 采购及运输
  const totalSales = groups.reduce((sum, group) => sum + group.sales, 0);
  const totalReceived = groups.reduce((sum, group) => sum + group.received, 0);
  const totalOutstanding = groups.reduce((sum, group) => sum + group.outstanding, 0);
  const totalGrossProfit = totalSales > 0 ? totalSales - totalPurchase : 0;
  // 待采购价的明细行数：毛利率口径没采购价就算不出销售价，弹框里要明确提示而不是留着 0
  const pendingCostRows = salesDetail
    ? salesDetail.rows.filter((row) => row.priceBasis === 'cost_pending').length
    : 0;
  // 项目级待采购价行数：这些行金额不会进对账单，导出前必须先提醒（对账单是对外文件）
  const totalPendingCostRows = groups.reduce(
    (sum, group) => sum + (group.deliveryCostPendingCount || 0), 0);
  const beginSummaryEdit = (group: BatchGroup) => {
    setEditingBatchId(group.key);
    // 承运单位草稿：运费行 id → 输入值（V28 起存在运费行上，可逐行改）
    setFreightDraft(Object.fromEntries(
      (group.freightItems ?? [])
        .filter((item) => item.side === 'purchase')
        .map((item) => [item.id, item.carrierName ?? ''])
    ));
    setSummaryDraft({
      receiptDate: group.receiptDate,
      salesOrder: group.salesOrder,
      salesAmount: String(group.sales || ''),
      requiredReceiptDate: group.requiredReceiptDate,
      projectManager: group.projectManager,
      isMonthlySettlement: Boolean(group.isMonthlySettlement)
    });
  };
  const saveSummary = async (group: BatchGroup) => {
    setSavingBatchId(group.key);
    try {
      // 主体字段先保存：它的失败由上层 toast 提示，不受承运单位影响
      await onUpdateBatch(group.key, {
        salesOrder: summaryDraft.salesOrder,
        salesAmount: Number(summaryDraft.salesAmount || 0),
        receiptDate: summaryDraft.receiptDate,
        requiredReceiptDate: summaryDraft.requiredReceiptDate,
        projectManager: summaryDraft.projectManager,
        isMonthlySettlement: summaryDraft.isMonthlySettlement
      });
      setEditingBatchId('');
      // 承运单位单独提交（只提交改过的行）：失败只提示，不回滚已保存的主体字段
      const carriers: Record<string, string> = {};
      for (const item of group.freightItems ?? []) {
        if (item.side !== 'purchase') continue;
        const draft = freightDraft[item.id];
        if (draft !== undefined && draft !== (item.carrierName ?? '')) {
          carriers[item.id] = draft;
        }
      }
      if (Object.keys(carriers).length > 0) {
        try {
          await api.updateFreightCarriers(group.key, carriers);
        } catch (error) {
          onToast(error instanceof Error ? error.message : '承运单位保存失败');
        }
      }
    } finally {
      setSavingBatchId('');
    }
  };

  const exportLedger = async () => {
    setExporting(true);
    try {
      await onExportLedger();
    } catch (error) {
      onToast(error instanceof Error ? error.message : '台账导出失败，请稍后重试');
    } finally {
      setExporting(false);
    }
  };

  const exportReconciliation = async () => {
    if (!reconRange.start || !reconRange.end) {
      onToast('请选择导出日期范围');
      return;
    }
    if (reconRange.start > reconRange.end) {
      onToast('开始日期不能晚于结束日期');
      return;
    }
    setReconExporting(true);
    try {
      await onExportReconciliation(reconRange.start, reconRange.end, reconMaterial || undefined);
      setReconOpen(false);
    } catch (error) {
      onToast(error instanceof Error ? error.message : '对账单导出失败，请稍后重试');
    } finally {
      setReconExporting(false);
    }
  };

  return (
    <div className="standard-page ledger-page page-enter">
      <div className="page-title-row">
        <div>
          <h2>项目批次对账台账</h2>
          <p>材料与运输分别核对采购、付款、发票，销售金额按送货单自动汇总。</p>
        </div>
        <div className="page-title-actions">
          <button className="secondary-button" onClick={() => void openMaterials()}>
            <Layers size={17} />材料
          </button>
          <button className="secondary-button" onClick={() => setReconOpen(true)}>
            <ArrowDownToLine size={17} />导出对账单
          </button>
          <button className="primary-button" onClick={() => void exportLedger()} disabled={exporting}>
            <ArrowDownToLine size={17} />{exporting ? '正在导出...' : '导出台账'}
          </button>
        </div>
      </div>

      <div className="ledger-stat-grid">
        <div className="metric"><span>采购及运输</span><strong>{money(totalPurchase)}</strong></div>
        <div className="metric"><span>请款总额</span><strong>{money(totalRequested)}</strong></div>
        <div className="metric success"><span>已付款</span><strong>{money(totalPaid)}</strong></div>
        <div className="metric danger"><span>未付款</span><strong>{money(totalUnpaid)}</strong></div>
        <div className="metric success"><span>进项票金额</span><strong>{money(totalInvoice)}</strong></div>
        <div className="metric"><span>销售总金额</span><strong>{money(totalSales)}</strong></div>
        <div className="metric success"><span>已收款</span><strong>{money(totalReceived)}</strong></div>
        <div className="metric danger"><span>剩余应收</span><strong>{money(totalOutstanding)}</strong></div>
        <div className="metric success"><span>毛利</span><strong>{money(totalGrossProfit)}</strong></div>
      </div>

      <section className="panel ledger-panel grouped-ledger-panel">
        <div className="ledger-toolbar">
          <div>
            <strong>批次综合台账</strong>
          </div>
        </div>
        <div className="ledger-table-scroll">
          <table className="ledger-table grouped-ledger-table">
            <thead>
              {columns ? columns.map((row, rowIndex) => (
                <tr key={rowIndex} className={rowIndex === 0 ? 'group-head' : undefined}>
                  {row.map((cell, cellIndex) => (
                    <th
                      key={cellIndex}
                      className={cell.style}
                      rowSpan={cell.rowSpan > 1 ? cell.rowSpan : undefined}
                      colSpan={cell.colSpan > 1 ? cell.colSpan : undefined}
                    >{cell.label}</th>
                  ))}
                </tr>
              )) : (
                <tr className="group-head">
                  <th colSpan={37}>
                    {columnsError ? '表头定义加载失败，请刷新重试' : '表头加载中…'}
                  </th>
                </tr>
              )}
            </thead>
            <tbody>
              {groups.map((group) => {
                const expanded = expandedBatches.has(group.key);
                const materialRows = group.rows.filter((row) => row.kind === 'material');
                const transportRows = group.rows.filter((row) => row.kind === 'transport');
                const displayRows = expanded ? group.rows : [group.summaryRow, ...transportRows];
                const transportExpanded = expandedTransports.has(group.key);
                // 运输构成明细子行数：first 行的跨行 rowSpan 需要覆盖它们
                const transportDetailRows = transportExpanded
                  ? transportRows.reduce((sum, row) => sum + (row.details?.length || 0), 0)
                  : 0;
                const renderRowCount = displayRows.length + transportDetailRows;
                // 运输财务 7 列跨运输维度合并（运输行 + 构成明细子行），与材料财务 rowSpan 同构
                const transportSpan = 1 + transportDetailRows;
                // 折叠态材料财务合并 1 行；展开态按材料行数合并
                const materialSpan = expanded ? group.materialRowCount : 1;
                const previewSales = editingBatchId === group.key
                  ? Number(summaryDraft.salesAmount || 0)
                  : group.sales;
                const groupCost = group.totalPurchase;
                const previewGrossProfit = previewSales > 0 ? previewSales - groupCost : 0;
                const previewGrossMargin = previewSales > 0
                  ? previewGrossProfit / previewSales * 100
                  : 0;
                return displayRows.map((row, displayIndex) => {
                  const first = displayIndex === 0;
                  const firstMaterial = first && row.kind === 'material';
                  return (
                    <Fragment key={`${group.key}-${row.key}`}>
                    <tr className={row.kind === 'transport' ? 'transport-row' : ''}>
                      {first && <td className="sticky-project" rowSpan={renderRowCount}>{group.project}</td>}
                      {first && <td className="sticky-batch" rowSpan={renderRowCount}><strong>{group.batch}</strong><small>{group.date}</small></td>}
                      <td>
                        <span className={`ledger-kind ${row.kind}`}>
                          {row.kind === 'material' ? <PackageCheck size={14} /> : <Truck size={14} />}
                          {row.kind === 'material' ? '材料' : '运输'}
                        </span>
                        {firstMaterial && group.hasMaterials && (
                          <button
                            type="button"
                            className="ledger-expand-toggle"
                            title={expanded ? '收起材料明细' : '展开材料明细'}
                            onClick={() => toggleExpand(group.key)}
                          >
                            {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                          </button>
                        )}
                        {row.kind === 'transport' && row.details && row.details.length > 0 && (
                          <button
                            type="button"
                            className="ledger-expand-toggle"
                            title={transportExpanded ? '收起构成明细' : '展开构成明细'}
                            onClick={() => toggleTransportExpand(group.key)}
                          >
                            {transportExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                          </button>
                        )}
                      </td>
                      <td><strong>{row.material}</strong></td>
                      <td>{row.spec}</td>
                      <td>{row.supplier || '-'}</td>
                      <td>
                        {row.purchaseAmount == null
                          ? <span className="ledger-date-value">-</span>
                          : <LedgerMoney value={row.purchaseAmount} />}
                      </td>
                      <td>
                        {row.sourceTag === 'delivery' || row.purchaseQuantity <= 0
                          ? <span className="ledger-date-value">-</span>
                          : <>{quantity(row.purchaseQuantity)} {row.purchaseUnit}</>}
                      </td>
                      <td>
                        {row.kind === 'material'
                          ? (row.deliveredWeight > 0
                            ? `${quantity(row.deliveredWeight)} ${row.deliveredWeightUnit}`
                            : row.deliveredPackages > 0
                              ? `${quantity(row.deliveredPackages)} ${row.deliveredPackageUnit}`
                              : '-')
                          : '-'}
                      </td>
                      <td>
                        {row.kind === 'material'
                          ? row.deliveryStatus
                          : <LedgerDate value={row.deliveryStatus} />}
                      </td>
                      {first && (
                        <td rowSpan={renderRowCount} className="merged-finance-cell" title="材料采购金额 + 运输费用合计（运输费用行含运费单据与运费口径推算，我们付给承运方）">
                          <LedgerMoney value={group.totalPurchase} />
                        </td>
                      )}
                      {first && (
                        <td
                          rowSpan={renderRowCount}
                          className="merged-finance-cell"
                          title="销售额（送货量 × 销售价）+ 客户承担送货运费 − 采购成本（采购单价 × 送货重量）− 采购运费；毛利率 = 毛利 ÷（销售额 + 送货运费）"
                        >
                          {group.purchaseMarginAmount != null
                            ? <LedgerMoney
                                value={group.purchaseMarginAmount}
                                tone={group.purchaseMarginAmount < 0 ? 'amount-pending' : ''}
                              />
                            : <small className="amount-pending" title="采购毛利需要批次定价口径（销售价=网价+上浮 或 采购价÷(1−毛利率)）；没有定价口径就算不出毛利，运费仍按口径/运输单显示在运输费用行">
                                {purchaseMarginHint(group.purchaseMarginNote) || '-'}
                              </small>}
                          {group.purchaseSalesFreight != null && group.purchaseSalesFreight > 0 && (
                            <small className="margin-note">
                              含送货运费 <LedgerMoney value={group.purchaseSalesFreight} />
                            </small>
                          )}
                        </td>
                      )}
                      {first && (
                        <td
                          rowSpan={renderRowCount}
                          className="merged-finance-cell"
                          title="采购毛利金额 ÷（估算货款 + 客户承担送货运费）——分母是总收入，与销售区口径一致"
                        >
                          <LedgerRate
                            value={group.purchaseMarginRate != null
                              ? `${quantity(group.purchaseMarginRate)}%`
                              : '-'}
                          />
                        </td>
                      )}
                      {firstMaterial && (
                        <>
                          <td rowSpan={materialSpan} className="merged-finance-cell">
                            <LedgerMoney value={group.materialFinance.requested} />
                            {group.materialRequested.length > 0 && (
                              <button
                                type="button"
                                className="ledger-interest-detail-trigger"
                                title="查看请款明细"
                                onClick={() => setRequestedDetail({
                                  title: '材料采购请款明细',
                                  subtitle: `${group.batch} · 累计 ${money(group.materialFinance.requested)}`,
                                  details: group.materialRequested
                                })}
                              >ⓘ 明细</button>
                            )}
                          </td>
                          <td rowSpan={materialSpan} className="merged-finance-cell"><LedgerDate value={group.materialFinance.paymentDate} /></td>
                          <td rowSpan={materialSpan} className="merged-finance-cell">{group.materialFinance.payee || '-'}</td>
                          <td rowSpan={materialSpan} className="merged-finance-cell">
                            <LedgerMoney value={group.materialFinance.paid} tone={group.materialFinance.paid > 0 ? 'amount-completed' : ''} />
                            <small className={group.materialFinance.unpaid > 0 ? 'amount-pending' : ''}>
                              未付 {money(group.materialFinance.unpaid)}
                            </small>
                          </td>
                          <td rowSpan={materialSpan} className="merged-finance-cell"><LedgerDate value={group.materialFinance.invoiceDate} /></td>
                          <td rowSpan={materialSpan} className="merged-finance-cell"><LedgerMoney value={group.materialFinance.invoice} /></td>
                          <td rowSpan={materialSpan} className="merged-finance-cell"><LedgerRate value={group.materialFinance.taxRate} /></td>
                        </>
                      )}
                      {row.kind === 'transport' && (
                        <>
                          <td rowSpan={transportSpan} className="transport-finance-cell">
                            <LedgerMoney value={row.requested} />
                            {group.transportRequested.length > 0 && (
                              <button
                                type="button"
                                className="ledger-interest-detail-trigger"
                                title="查看请款明细"
                                onClick={() => setRequestedDetail({
                                  title: '运输费用请款明细',
                                  subtitle: `${group.batch} · 累计 ${money(group.transportRequested.reduce((sum, entry) => sum + entry.amount, 0))}`,
                                  details: group.transportRequested
                                })}
                              >ⓘ 明细</button>
                            )}
                          </td>
                          <td rowSpan={transportSpan} className="transport-finance-cell"><LedgerDate value={row.paymentDate} /></td>
                          <td rowSpan={transportSpan} className="transport-finance-cell">{row.payee || '-'}</td>
                          <td rowSpan={transportSpan} className="transport-finance-cell">
                            <LedgerMoney value={row.paid} tone={row.paid > 0 ? 'amount-completed' : ''} />
                            <small className={row.unpaid > 0 ? 'amount-pending' : ''}>未付 {money(row.unpaid)}</small>
                          </td>
                          <td rowSpan={transportSpan} className="transport-finance-cell"><LedgerDate value={row.invoiceDate} /></td>
                          <td rowSpan={transportSpan} className="transport-finance-cell"><LedgerMoney value={row.invoice} /></td>
                          <td rowSpan={transportSpan} className="transport-finance-cell"><LedgerRate value={row.taxRate} /></td>
                        </>
                      )}
                      {first && (
                        <td
                          rowSpan={renderRowCount}
                          className="merged-finance-cell"
                          title="采购毛利差额：按采购量估算的货款 − 按送货量的实际货款。正数=送货少于采购，实际毛利低于账面估算；还没有送货单时显示 -"
                        >
                          {group.purchaseMarginGap != null
                            ? <LedgerMoney
                                value={group.purchaseMarginGap}
                                tone={group.purchaseMarginGap < 0 ? 'amount-pending' : ''}
                              />
                            : '-'}
                        </td>
                      )}
                      {first && (
                        <>
                          <td rowSpan={renderRowCount} className="merged-receipt-cell editable-summary-cell">
                            {editingBatchId === group.key
                              ? <input value={summaryDraft.salesOrder} placeholder="销售订单号" onChange={(event) => setSummaryDraft({...summaryDraft, salesOrder:event.target.value})} />
                              : group.salesOrder || '-'}
                          </td>
                          <td
                            rowSpan={renderRowCount}
                            className="merged-receipt-cell editable-summary-cell"
                            title={group.deliveryCostPendingCount
                              ? `销售金额按送货单锁定值汇总；其中 ${group.deliveryCostPendingCount} 行待采购价，金额未计入`
                              : '销售金额按送货单锁定值汇总（毛利率口径按采购价倒推），可人工调整'}
                          >
                            {editingBatchId === group.key
                              ? <input type="number" step="any" value={summaryDraft.salesAmount} onChange={(event) => setSummaryDraft({...summaryDraft, salesAmount:event.target.value})} />
                              : (
                                <>
                                  <LedgerMoney value={group.sales} />
                                  {(group.sales > 0 || group.hasDelivery) && (
                                    <button
                                      type="button"
                                      className="ledger-interest-detail-trigger"
                                      title="查看销售明细（含采购价、未匹配行）"
                                      onClick={() => void openSalesDetail(group.key, group.batch, group.sales)}
                                    >ⓘ 明细</button>
                                  )}
                                  {!!group.deliveryCostPendingCount && (
                                    <button
                                      type="button"
                                      className="ledger-interest-detail-trigger amount-pending"
                                      title={`${group.deliveryCostPendingCount} 行待采购价：毛利率口径需要采购价才能倒推销售价，这些行金额未计入销售金额`}
                                      onClick={() => void openSalesDetail(group.key, group.batch, group.sales)}
                                    >待采购价 {group.deliveryCostPendingCount} 行</button>
                                  )}
                                </>
                              )}
                          </td>
                          <td
                            rowSpan={renderRowCount}
                            className="merged-receipt-cell"
                            title={group.salesFreightDetail
                              ? `销售运费（客户承担）：${group.salesFreightDetail}。已包含在左侧销售总金额里，不再另加`
                              : '销售运费（客户承担）：已包含在左侧销售总金额里，不再另加；为 0 表示售价含运费、客户不另付，或还没配销售运费口径'}
                          >
                            {group.purchaseSalesFreight != null && group.purchaseSalesFreight > 0
                              ? <LedgerMoney value={group.purchaseSalesFreight} />
                              : '-'}
                          </td>
                          <td rowSpan={renderRowCount} className="merged-receipt-cell"><LedgerDate value={group.salesInvoiceDate} /></td>
                          <td rowSpan={renderRowCount} className="merged-receipt-cell"><LedgerMoney value={group.salesInvoiceAmount} /></td>
                          <td rowSpan={renderRowCount} className="merged-receipt-cell"><LedgerRate value={group.salesTaxRate} /></td>
                          <td rowSpan={renderRowCount} className="merged-receipt-cell">
                            <LedgerMoney value={group.received} tone={group.received > 0 ? 'amount-completed' : ''} />
                          </td>
                          <td rowSpan={renderRowCount} className="merged-receipt-cell">
                            {group.isMonthlySettlement && group.interest != null && group.interest > 0 ? (
                              <span className="ledger-due-cell" title={`剩余应收加利息合计 = ${money(group.outstanding)} + ${money(group.interest)}（月结利息）`}>
                                <LedgerMoney value={group.outstanding} tone={group.outstanding > 0 ? 'amount-pending' : ''} />
                                <small className="ledger-due-breakdown">含息 {money(group.outstanding + group.interest)}</small>
                              </span>
                            ) : (
                              <LedgerMoney value={group.outstanding} tone={group.outstanding > 0 ? 'amount-pending' : ''} />
                            )}
                            {group.isMonthlySettlement && (group.overdueFlag || group.amountCapFlag) && (
                              <span className="ledger-flag" title="超过月结风控红线，请关注">
                                {group.overdueFlag ? '超3个月' : ''}{group.overdueFlag && group.amountCapFlag ? ' · ' : ''}{group.amountCapFlag ? '超300万' : ''}
                              </span>
                            )}
                          </td>
                          <td rowSpan={renderRowCount} className="merged-receipt-cell editable-summary-cell">
                            {editingBatchId === group.key
                              ? <input type="date" value={summaryDraft.receiptDate} onChange={(event) => setSummaryDraft({...summaryDraft, receiptDate:event.target.value})} />
                              : <LedgerDate value={group.receiptDate} />}
                          </td>
                          <td rowSpan={renderRowCount} className="merged-receipt-cell editable-summary-cell">
                            {editingBatchId === group.key
                              ? <input type="date" value={summaryDraft.requiredReceiptDate} onChange={(event) => setSummaryDraft({...summaryDraft, requiredReceiptDate:event.target.value})} />
                              : <LedgerDate value={group.requiredReceiptDate} />}
                          </td>
                          <td rowSpan={renderRowCount} className="merged-receipt-cell editable-summary-cell">
                            {editingBatchId === group.key ? (
                              <label className="ledger-monthly-toggle" title="月结时按要求回款日期计算加价利息">
                                <input
                                  type="checkbox"
                                  checked={summaryDraft.isMonthlySettlement}
                                  onChange={(event) => setSummaryDraft({...summaryDraft, isMonthlySettlement:event.target.checked})}
                                />
                                月结
                              </label>
                            ) : group.isMonthlySettlement ? (
                              <span className="ledger-interest-cell">
                                {group.interest != null && group.interest > 0 ? (
                                  <LedgerMoney value={group.interest} tone="amount-pending" />
                                ) : (
                                  <span className="ledger-interest-zero">¥0.00</span>
                                )}
                                <button
                                  type="button"
                                  className="ledger-interest-detail-trigger"
                                  onClick={() => setInterestDetailGroup(group)}
                                >ⓘ 计息明细</button>
                                <small className="ledger-monthly-tag">月结</small>
                              </span>
                            ) : (
                              <span className="ledger-interest-none">—</span>
                            )}
                          </td>
                          <td rowSpan={renderRowCount} className="merged-receipt-cell">
                            <LedgerMoney value={previewGrossProfit} />
                          </td>
                          <td rowSpan={renderRowCount} className="merged-receipt-cell">
                            <LedgerRate value={`${quantity(previewGrossMargin)}%`} />
                          </td>
                          <td rowSpan={renderRowCount} className="merged-receipt-cell editable-summary-cell project-manager-cell">
                            {editingBatchId === group.key
                              ? <input value={summaryDraft.projectManager} placeholder="项目经理" onChange={(event) => setSummaryDraft({...summaryDraft, projectManager:event.target.value})} />
                              : group.projectManager || '-'}
                          </td>
                          <td rowSpan={renderRowCount}><span className={`status-badge ${group.status === '已对平' ? 'success' : 'warning'}`}>{group.status}</span></td>
                          <td rowSpan={renderRowCount} className="sticky-action">
                            {editingBatchId === group.key ? (
                              <span className="summary-edit-actions">
                                <button disabled={savingBatchId === group.key} onClick={() => void saveSummary(group)}><Check size={13} />保存</button>
                                <button disabled={savingBatchId === group.key} onClick={() => setEditingBatchId('')}><X size={13} />取消</button>
                              </span>
                            ) : (
                              <button className="summary-edit-trigger" onClick={() => beginSummaryEdit(group)}><PencilLine size={13} />编辑</button>
                            )}
                          </td>
                        </>
                      )}
                    </tr>
                    {row.kind === 'transport' && transportExpanded
                      && row.details && row.details.length > 0 && (
                      row.details.map((entry) => (
                        <tr key={`${group.key}-${row.key}-detail-${entry.key}`} className="transport-detail-row">
                          {/* 子行与主行逐列对齐；运输财务 7 列被上方 rowSpan={transportSpan} 占据不渲染 */}
                          <td />
                          <td><strong>{entry.source}</strong></td>
                          <td>{entry.code || '—'}</td>
                          <td>
                            {editingBatchId === group.key && freightDraft[entry.key] !== undefined
                              ? (
                                <input
                                  className="freight-carrier-input"
                                  value={freightDraft[entry.key]}
                                  placeholder="承运单位"
                                  onChange={(event) => setFreightDraft({
                                    ...freightDraft,
                                    [entry.key]: event.target.value
                                  })}
                                />
                              )
                              : (entry.party || '—')}
                          </td>
                          <td>1 笔</td>
                          <td><LedgerMoney value={entry.amount} /></td>
                          <td><LedgerDate value={entry.date} /></td>
                        </tr>
                      ))
                    )}
                    </Fragment>
                  );
                });
              })}
            </tbody>
          </table>
        </div>
        {groups.length === 0 && (
          <div className="empty-state"><AlertCircle size={30} /><strong>当前批次还没有可生成台账的采购或送货数据</strong><span>确认采购单或送货单后，系统会自动建立对应类目行；切换批次可查看其他批次的台账。</span></div>
        )}
        <div className="ledger-note">
          <AlertCircle size={16} />
          材料行默认折叠（汇总一行），点击展开按材料大类核对（螺纹钢等不分型号规格累加）；运输费用行为运输单、送货单运费与批次运费口径（元/趟 包干价或 元/吨×采购吨数）的累加值，可直接归集付款与进项发票；销售金额按送货单自动汇总，可人工调整；采购毛利按批次默认口径估算（毛利率口径以采购价为基数），成本已含运输费用行。两处毛利率口径一致：毛利额 = 销售额 + 客户承担送货运费 − 材料采购 − 运输费用，毛利率 = 毛利额 ÷（货款 + 送货运费）。
        </div>
      </section>

      {interestDetailGroup && (
        <div className="ledger-detail-overlay" onClick={() => setInterestDetailGroup(null)}>
          <div className="ledger-detail-popover" onClick={(event) => event.stopPropagation()}>
            <div className="ledger-detail-head">
              <div>
                <strong>月结加价利息明细</strong>
                <small>{interestDetailGroup.batch} · 销售 {money(interestDetailGroup.sales)} · 剩余应收 {money(interestDetailGroup.outstanding)}</small>
              </div>
              <button type="button" onClick={() => setInterestDetailGroup(null)}><X size={14} /></button>
            </div>
            {interestDetailGroup.interestDetails?.length ? (
              <div className="ledger-detail-list">
                {interestDetailGroup.interestDetails.map((detail, index) => (
                  <div className="ledger-detail-row" key={index}>
                    <span className="ledger-detail-row-note">
                      {detail.type === 'receipt' ? '回款' : '剩余应收'}
                      {detail.date ? `（${detail.date}）` : ''}
                    </span>
                    <span className="ledger-detail-row-meta">
                      本金 {money(detail.amount || 0)} · 月息 {detail.rate != null ? `${(detail.rate * 100).toFixed(1)}%` : '-'}
                      · {detail.days != null ? `${detail.days} 天` : '-'}
                    </span>
                    <strong className={detail.interest && detail.interest > 0 ? 'amount-pending' : ''}>{money(detail.interest || 0)}</strong>
                  </div>
                ))}
              </div>
            ) : (
              <div className="ledger-detail-empty">当前无计息（可能在 7 天宽限期内或无剩余应收本金）</div>
            )}
            <div className="ledger-detail-total">
              <span>剩余应收含息合计 = 剩余应收 + 利息</span>
              <strong>{money(interestDetailGroup.outstanding)} + {money(interestDetailGroup.interest || 0)} = {money(interestDetailGroup.outstanding + (interestDetailGroup.interest || 0))}</strong>
            </div>
            {(interestDetailGroup.overdueFlag || interestDetailGroup.amountCapFlag) && (
              <div className="ledger-detail-warn">
                {interestDetailGroup.overdueFlag ? '欠款超 3 个月' : ''}
                {interestDetailGroup.overdueFlag && interestDetailGroup.amountCapFlag ? ' · ' : ''}
                {interestDetailGroup.amountCapFlag ? '剩余应收超 300 万' : ''}
              </div>
            )}
          </div>
        </div>
      )}
      {requestedDetail && (
        <div className="ledger-detail-overlay" onClick={() => setRequestedDetail(null)}>
          <div className="ledger-detail-popover wide" onClick={(event) => event.stopPropagation()}>
            <div className="ledger-detail-head">
              <div>
                <strong>{requestedDetail.title}</strong>
                <small>{requestedDetail.subtitle}</small>
              </div>
              <button type="button" onClick={() => setRequestedDetail(null)}><X size={14} /></button>
            </div>
            <div className="ledger-detail-list">
              {requestedDetail.details.map((entry) => (
                <div className="ledger-detail-row" key={entry.key}>
                  <span className="ledger-detail-row-note">
                    请款{entry.date ? `（${entry.date}）` : ''}
                  </span>
                  <span className="ledger-detail-row-meta">
                    {[
                      entry.target || '',
                      entry.remark || ''
                    ].filter(Boolean).join(' · ') || '—'}
                  </span>
                  <strong>{money(entry.amount)}</strong>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
      {salesDetail && (
        <div className="ledger-detail-overlay" onClick={() => setSalesDetail(null)}>
          <div className="ledger-detail-popover wide sales-detail-popover" onClick={(event) => event.stopPropagation()}>
            <div className="ledger-detail-head">
              <div>
                <strong>{salesDetail.title}</strong>
                <small>{salesDetail.subtitle}</small>
              </div>
              <button type="button" onClick={() => setSalesDetail(null)}><X size={14} /></button>
            </div>
            {salesDetailLoading ? (
              <div className="ledger-detail-list"><small>正在加载...</small></div>
            ) : (
              <>
                {pendingCostRows > 0 && (
                  <div className="sales-detail-warning">
                    {pendingCostRows} 行待采购价，金额未计入销售总金额：毛利率口径的销售价 = 采购价 ÷ (1−毛利率)，
                    需要同批次采购单里有对应材料才能倒推。
                  </div>
                )}
                <div className="table-scroll">
                <table className="data-table sales-detail-table">
                  <thead>
                    <tr>
                      <th>送货日期</th>
                      <th>送货单号</th>
                      <th>材料 / 规格</th>
                      <th>匹配行情</th>
                      <th>行情日期</th>
                      <th>网价</th>
                      <th>采购价</th>
                      <th>定价</th>
                      <th>销售单价</th>
                      <th>计价数量</th>
                      <th>金额</th>
                      <th>口径</th>
                    </tr>
                  </thead>
                  <tbody>
                    {salesDetail.rows.map((row, rowIndex) => {
                      const basisLabel = row.priceBasis === 'quote'
                        ? '网价'
                        : row.priceBasis === 'cost' ? '采购价倒推'
                        : row.priceBasis === 'manual' ? '人工定价'
                        : row.priceBasis === 'cost_pending' ? '待采购价'
                        : row.priceBasis === 'fallback_amount' ? '单据金额' : '未匹配';
                      const isFreightRow = row.rowType === 'sales_freight';
                      // 待采购价的行：销售单价位置直接说明「为什么空着」，不要只显示一个破折号
                      const costPendingNote = row.priceBasis === 'cost_pending'
                        ? (row.costLevel === 'spec_missing'
                          ? '待采购价 · 采购单没这个规格'
                          : '待采购价 · 批次还没有采购单')
                        : null;
                      // 不同送货单的材料明细之间加一条浅分隔（运费条目仍归属当前送货单，不加）
                      const docBoundary = rowIndex > 0
                        && salesDetail.rows[rowIndex - 1].documentId !== row.documentId
                        && !isFreightRow;
                      return (
                        <tr
                          key={`${row.documentId}-${row.materialName}-${row.spec}-${row.mappingId}`}
                          className={docBoundary ? 'doc-boundary' : undefined}
                        >
                          <td><LedgerDate value={row.docDate ?? undefined} /></td>
                          <td>{row.docCode || '-'}</td>
                          <td>
                            {isFreightRow
                              ? <span className="freight-entry">{row.materialName}</span>
                              : <><strong>{row.materialName}</strong>{row.spec ? <small> {row.spec}</small> : null}</>}
                          </td>
                          <td>{isFreightRow ? '-' : ([row.quoteSpecName, row.quoteSource].filter(Boolean).join(' · ') || <span className="ledger-date-value">未匹配</span>)}</td>
                          <td>{isFreightRow ? '-' : <LedgerDate value={row.quoteDate ?? undefined} />}</td>
                          <td>{isFreightRow || row.quotePrice == null ? '-' : money(row.quotePrice)}</td>
                          <td
                            title={isFreightRow
                              ? '客户承担的运费，已计入台账销售总金额'
                              : row.costPrice != null
                                ? `采购吨价，来自同批次采购单（${
                                  row.costLevel === 'weighted' ? '多张采购单按吨数加权'
                                    : row.costLevel === 'origin' ? '同产地'
                                      : '同材料同规格'
                                }）`
                                : '没有匹配到采购价'}
                          >
                            {isFreightRow || row.costPrice == null ? '-' : money(row.costPrice)}
                          </td>
                          <td
                            title={isFreightRow ? '客户承担的运费，已计入台账销售总金额' : '该行采用的定价方式：毛利率X%（销售价按采购价倒推）或 网价+X'}
                          >
                            {isFreightRow
                              ? null
                              : (row.pricingNote || (row.uplift != null ? money(row.uplift) : '-'))}
                          </td>
                          <td>
                            {costPendingNote
                              ? <small className="amount-pending">{costPendingNote}</small>
                              : (row.salesUnitPrice != null ? money(row.salesUnitPrice) : '-')}
                          </td>
                          <td>{row.pricingQuantity != null ? `${quantity(row.pricingQuantity)} ${row.pricingUnit || ''}` : '-'}</td>
                          <td>
                            {costPendingNote
                              ? <small className="amount-pending">未计入</small>
                              : <strong>{row.amount != null ? money(row.amount) : '-'}</strong>}
                          </td>
                          <td><small>{isFreightRow ? '运费' : basisLabel}{!isFreightRow && row.status === 'manual' ? ' · 人工' : ''}</small></td>
                        </tr>
                      );
                    })}
                    {salesDetail.rows.length === 0 && (
                      <tr><td colSpan={12} className="market-empty-cell">暂无送货明细</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
              </>
            )}
          </div>
        </div>
      )}

      {/* 材料清单弹框：只读概览（职责单一化 2026-11-04）——绑定/改价/AI 校准统一在「市场行情 · 批次材料」操作 */}
      {materialsOpen && (
        <div className="ledger-detail-overlay" onClick={() => setMaterialsOpen(false)}>
          <div className="ledger-detail-popover wide sales-detail-popover" onClick={(event) => event.stopPropagation()}>
            <div className="ledger-detail-head">
              <div>
                <strong>材料清单</strong>
                <small>采购单与送货单按材料归并的只读概览；销售单价为按批次定价口径（或材料人工价）估算的售价，与采购毛利同源；绑定 / 改价 / AI 校准请在「市场行情 · 批次材料」中操作，改采购价请到采购单修改</small>
              </div>
              <button type="button" onClick={() => setMaterialsOpen(false)}><X size={14} /></button>
            </div>
            {materialsLoading ? (
              <div className="ledger-detail-list"><small>正在加载...</small></div>
            ) : (
              <>
                {materialRows.some((row) => row.status === 'unbound' || row.status === 'duplicate') && (
                  <div className="sales-detail-warning">
                    {materialRows.filter((row) => row.status === 'unbound').length} 种材料未绑定网价
                    {materialRows.some((row) => row.status === 'duplicate')
                      ? `；${materialRows.filter((row) => row.status === 'duplicate').length} 种材料在同批次出现多条采购行（疑似重复录入，请核对采购单）`
                      : ''}
                    。请到「市场行情 · 批次材料」绑定或校准。
                  </div>
                )}
                <div className="table-scroll">
                  <table className="data-table sales-detail-table material-table">
                    <thead>
                      <tr>
                        <th>材料</th>
                        <th>采购吨数</th>
                        <th>采购吨价</th>
                        <th title="材料级人工价优先，其次按批次定价口径推算（毛利率=采购吨价÷(1−X%)，上浮=网价+上浮）；按采购吨数加权，与采购毛利的估算货款同源">销售单价</th>
                        <th>送货吨数</th>
                        <th>销售金额</th>
                        <th>网价绑定</th>
                        <th>锁定网价（取价日）</th>
                        <th>状态</th>
                      </tr>
                    </thead>
                    <tbody>
                      {materialRows.map((row) => {
                        const statusLabel = row.status === 'manual' ? '人工绑定'
                          : row.status === 'auto' ? '自动绑定'
                          : row.status === 'duplicate' ? '重复待核'
                          : '未绑定';
                        const identity = [
                          row.category,
                          row.grade,
                          row.diameter ? `Φ${row.diameter}` : null,
                          row.lengthM ? `${row.lengthM}m` : null,
                          row.origin
                        ].filter(Boolean).join(' ');
                        return (
                          <tr key={row.id} className={row.status === 'unbound' || row.status === 'duplicate' ? 'row-unmatched' : undefined}>
                            <td>
                              <strong>{identity || '未标注材料'}</strong>
                              {row.purchaseRows + row.deliveryRows > 0 && (
                                <small> {row.purchaseRows} 采购行 / {row.deliveryRows} 送货行</small>
                              )}
                            </td>
                            <td>{row.purchaseTons != null ? quantity(row.purchaseTons) : '-'}</td>
                            <td>{row.purchasePrice != null ? money(row.purchasePrice) : '-'}</td>
                            <td>
                              {row.salesPrice != null
                                ? <span className="ledger-money-value">
                                    {money(row.salesPrice)}
                                    {salesPriceLabel(row.salesPriceBasis) && (
                                      <small> · {salesPriceLabel(row.salesPriceBasis)}</small>
                                    )}
                                  </span>
                                : <small className="amount-pending">{salesPriceHint(row.salesPriceBasis)}</small>}
                            </td>
                            <td>{row.deliveryTons != null ? quantity(row.deliveryTons) : '-'}</td>
                            <td>{row.deliveryAmount != null ? <strong>{money(row.deliveryAmount)}</strong> : '-'}</td>
                            <td>
                              {row.quoteSpecName
                                ? <span>
                                    {[row.quoteProductName, row.quoteSpecName].filter(Boolean).join(' · ')}
                                    {row.origin ? `（${row.origin}）` : ''}
                                  </span>
                                : <span className="ledger-date-value">未绑定</span>}
                            </td>
                            <td>
                              {row.lockedNetPrice != null
                                ? <span className="ledger-money-value">
                                    {money(row.lockedNetPrice)}
                                    <LedgerDate value={row.quoteDate ?? undefined} />
                                  </span>
                                : (row.quoteSpecName
                                  ? <small className="amount-pending">当日无此规格行情</small>
                                  : <span className="ledger-date-value">-</span>)}
                            </td>
                            <td><small>{statusLabel}</small></td>
                          </tr>
                        );
                      })}
                      {materialRows.length === 0 && (
                        <tr><td colSpan={9} className="market-empty-cell">本批次还没有归并出材料（确认采购单/送货单后自动生成）</td></tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </div>
        </div>
      )}

      {/* 对账单导出弹框：选日期范围，明细行 = 范围内送货单，累计 = 项目内 ≤ 结束日（含本次） */}
      {reconOpen && (
        <div className="ledger-detail-overlay" onClick={() => setReconOpen(false)}>
          <div className="ledger-detail-popover recon-export-popover" onClick={(event) => event.stopPropagation()}>
            <div className="ledger-detail-head">
              <div>
                <strong>导出对账单</strong>
                <small>按送货单日期取明细；选择材料后只导出该材料的批次，累计与已支付同样只统计这些批次</small>
              </div>
              <button type="button" onClick={() => setReconOpen(false)}><X size={14} /></button>
            </div>
            <div className="recon-export-form">
              <label>
                <span>开始日期</span>
                <input
                  type="date"
                  value={reconRange.start}
                  onChange={(event) => setReconRange({...reconRange, start: event.target.value})}
                />
              </label>
              <label>
                <span>结束日期</span>
                <input
                  type="date"
                  value={reconRange.end}
                  onChange={(event) => setReconRange({...reconRange, end: event.target.value})}
                />
              </label>
              <label className="recon-export-material">
                <span>材料</span>
                <select value={reconMaterial} onChange={(event) => setReconMaterial(event.target.value)}>
                  <option value="">全部材料</option>
                  {reconMaterialOptions.map((material) => (
                    <option key={material} value={material}>{material}</option>
                  ))}
                </select>
              </label>
            </div>
            {totalPendingCostRows > 0 && (
              <div className="sales-detail-warning">
                项目内还有 {totalPendingCostRows} 行待采购价（毛利率口径缺采购价，算不出销售金额），
                这些行的金额不会进入对账单，本次导出金额会偏低。建议先补采购单再导出。
              </div>
            )}
            <div className="recon-export-actions">
              <button className="primary-button" disabled={reconExporting} onClick={() => void exportReconciliation()}>
                {reconExporting ? '正在导出...' : '导出'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
