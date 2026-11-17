// 领域装饰与格式化：把后端原始数据转换为 UI 友好、可直接渲染的结构。
const { materialCategory } = require('./materialCategory');

const DOC_TYPES = [
  { label: '全部资料', value: '' },
  { label: '采购单', value: 'supply_order' },
  { label: '送货单', value: 'delivery_note' },
  { label: '运输费用', value: 'transport_note' },
  { label: '请款记录', value: 'payment_request' },
  { label: '付款记录', value: 'payment' },
  { label: '收款记录', value: 'receipt' },
  { label: '进项发票', value: 'purchase_invoice' },
  { label: '销项发票', value: 'sales_invoice' },
  { label: '结算资料', value: 'sales_settlement' },
  { label: '原始凭证', value: 'evidence_only' }
];

const TYPE_META = {
  supply_order: { label: '采购单', short: '采购', icon: 'orders-o', tone: 'blue' },
  delivery_note: { label: '配货 / 送货单', short: '送货', icon: 'logistics', tone: 'cyan' },
  transport_note: { label: '运输费用', short: '运输', icon: 'logistics', tone: 'orange' },
  payment_request: { label: '请款记录', short: '请款', icon: 'bill-o', tone: 'orange' },
  payment: { label: '付款记录', short: '付款', icon: 'balance-pay', tone: 'green' },
  receipt: { label: '收款记录', short: '收款', icon: 'cash-back-record-o', tone: 'green' },
  invoice: { label: '进项发票', short: '进项票', icon: 'description-o', tone: 'cyan' },
  purchase_invoice: { label: '进项发票', short: '进项票', icon: 'description-o', tone: 'cyan' },
  sales_invoice: { label: '销项发票', short: '销项票', icon: 'description-o', tone: 'blue' },
  invoice_unknown: { label: '待确认发票', short: '发票', icon: 'question-o', tone: 'orange' },
  sales_settlement: { label: '销售结算资料', short: '结算', icon: 'records-o', tone: 'gray' },
  settlement_unknown: { label: '待确认结算资料', short: '结算', icon: 'question-o', tone: 'orange' },
  evidence_only: { label: '原始凭证', short: '凭证', icon: 'photo-o', tone: 'gray' }
};

// 色调 → 图标描边色。xicon 以 data-uri <image> 渲染，currentColor 无法继承父级，必须传显式色值。
const TONE_COLORS = {
  blue: '#2452d1',
  green: '#0f8f52',
  orange: '#c8781f',
  red: '#d23a3a',
  cyan: '#0e8c99',
  purple: '#7c4ddb',
  gray: '#4d5d78'
};

function toneColor(tone) {
  return TONE_COLORS[tone] || TONE_COLORS.gray;
}

function number(value) {
  const parsed = Number(value || 0);
  return Number.isFinite(parsed) ? parsed : 0;
}

function money(value) {
  const amount = number(value);
  const sign = amount < 0 ? '-' : '';
  const fixed = Math.abs(amount).toFixed(2);
  const parts = fixed.split('.');
  parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${sign}¥${parts.join('.')}`;
}

function compactMoney(value) {
  const amount = number(value);
  if (Math.abs(amount) >= 10000) return `¥${(amount / 10000).toFixed(2)}万`;
  return money(amount);
}

// KPI 金额自适应字号：金额文本越长字号越小（is-sm / is-xs），避免在小卡片里显示不全。
function kpiSize(value) {
  const len = String(value || '').length;
  if (len > 12) return 'is-xs';
  if (len > 10) return 'is-sm';
  return '';
}

// 估算文本在 rpx 下的渲染宽度（用于台账列宽自适应）。
// 中文/全角按 1em，数字/字母按 0.56em，窄符号按 0.38em。
function textWidth(text, fontSize) {
  let width = 0;
  const str = String(text || '');
  for (const ch of str) {
    const code = ch.codePointAt(0);
    if (code >= 0x2e80) width += fontSize;
    else if (/[0-9]/.test(ch)) width += fontSize * 0.56;
    else if (/[.,%¥$＄/\-·\s]/.test(ch)) width += fontSize * 0.38;
    else width += fontSize * 0.56;
  }
  return width;
}

function quantity(value) {
  return number(value).toFixed(3).replace(/\.?0+$/, '');
}

function date(value) {
  if (!value) return '-';
  return String(value).slice(0, 10);
}

function time(value) {
  if (!value) return '刚刚';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '刚刚';
  const pad = (part) => String(part).padStart(2, '0');
  return `${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`;
}

function confidence(value) {
  if (value === undefined || value === null || value === '') return '';
  const score = number(value);
  const percent = score > 0 && score <= 1 ? score * 100 : score;
  return `${Math.round(Math.max(0, Math.min(percent, 100)))}%`;
}

function meta(type) {
  return TYPE_META[type] || { label: '其他资料', short: '资料', icon: 'description-o', tone: 'gray' };
}

function fieldMeta(type) {
  const isTransport = type === 'transport_note';
  const isPayment = type === 'payment';
  const isReceipt = type === 'receipt';
  const isRequest = type === 'payment_request';
  const isInvoice = ['invoice', 'purchase_invoice', 'sales_invoice', 'invoice_unknown'].includes(type);
  const isSettlement = ['sales_settlement', 'settlement_unknown'].includes(type);
  const isDelivery = type === 'delivery_note';
  return {
    partyLabel: isTransport
      ? '承运单位'
      : isPayment
        ? '收款单位'
        : isReceipt
          ? '付款单位'
          : isRequest
            ? '请款单位'
            : isInvoice
              ? '开票单位'
              : isSettlement
                ? '结算单位'
                : isDelivery
                  ? '客户（收货单位）'
                  : '往来单位',
    codeLabel: isInvoice
      ? '发票号码'
      : isPayment
        ? '付款流水号'
        : isReceipt
          ? '收款流水号'
          : '单据编号',
    dateLabel: isTransport
      ? '运输日期'
      : isPayment
        ? '付款日期'
        : isReceipt
          ? '收款日期'
          : isRequest
            ? '请款日期'
            : isInvoice
              ? '开票日期'
              : isSettlement
                ? '结算日期'
                : isDelivery
                  ? '送货日期'
                  : '业务日期',
    amountLabel: isTransport
      ? '运输金额'
      : isPayment
        ? '付款金额'
        : isReceipt
          ? '收款金额'
          : isRequest
            ? '请款金额'
            : isInvoice
              ? '开票金额'
              : isSettlement
                ? '结算金额'
                : isDelivery
                  ? '销售金额'
                  : '业务金额',
    supportsItems: ['supply_order', 'delivery_note', 'invoice', 'purchase_invoice', 'sales_invoice', 'invoice_unknown']
      .includes(type),
    isInvoice,
    isPurchaseFinance: isPayment || isRequest || isInvoice
  };
}

function statusMeta(document) {
  if (document.duplicateDecision === 'skip') {
    return { label: '重复归档', tone: 'gray' };
  }
  if (document.status === 'matched' || document.status === 'archived') {
    return { label: '已入库', tone: 'green' };
  }
  if (document.status === 'discrepancy') {
    return { label: '有差异', tone: 'red' };
  }
  if (document.status === 'recognizing') {
    return { label: '识别中', tone: 'blue' };
  }
  return { label: '待确认', tone: 'orange' };
}

function parseStructured(document) {
  if (!document || !document.structuredData) return {};
  try {
    return JSON.parse(document.structuredData);
  } catch (error) {
    return {};
  }
}

function decorateRecord(record, projects, batches) {
  // 容错：部分接口（如 GET /api/documents）直接返回裸 Document，这里统一成 { document, items }
  const source = record && record.document ? record : { document: record || {}, items: [] };
  const document = source.document || {};
  const project = projects.find((item) => item.id === document.projectId);
  const batch = batches.find((item) => item.id === document.batchId);
  const type = meta(document.docType);
  const fields = fieldMeta(document.docType);
  const status = statusMeta(document);
  const structured = parseStructured(document);
  const decoratedItems = (source.items || []).map((item) => ({
    ...item,
    quantityText: `${quantity(item.pricingQuantity || item.quantity)}${item.pricingUnit || item.unit || ''}`,
    packageText: number(item.packageQuantity) > 0
      ? `${quantity(item.packageQuantity)}${item.packageUnit || '件'}`
      : '-',
    weightText: number(item.totalWeight) > 0
      ? `${quantity(item.totalWeight)}${item.weightUnit || '吨'}`
      : '-',
    priceText: money(item.unitPrice),
    // 采购单销售价：人工值优先，否则按批次口径自动推算（后端现算后随明细下发）
    salesUnitPriceText: item.salesUnitPrice != null ? money(item.salesUnitPrice) : '-',
    salesUnitPriceSource: item.salesUnitPriceManual ? '手动' : (item.salesUnitPrice != null ? '自动' : ''),
    amountText: money(item.amount)
  }));
  return {
    ...source,
    id: document.id,
    document,
    projectName: project ? project.name : '-',
    batchName: batch ? batch.name : '-',
    typeLabel: type.label,
    typeShort: type.short,
    typeIcon: type.icon,
    typeTone: type.tone,
    typeColor: toneColor(type.tone),
    ...fields,
    statusLabel: status.label,
    statusTone: status.tone,
    amountText: money(document.amount),
    dateText: date(document.docDate),
    confidenceText: confidence(document.confidence),
    taxRate: structured.taxRate == null ? '' : String(structured.taxRate),
    netAmount: structured.netAmount == null ? '' : String(structured.netAmount),
    taxAmount: structured.taxAmount == null ? '' : String(structured.taxAmount),
    expenseCategory: structured.expenseCategory === 'transport' ? 'transport' : 'material',
    // 送货单销售侧展示字段（详情抽屉概览）
    deliveryFreightText: document.docType === 'delivery_note'
      ? (structured.transportFee != null && number(structured.transportFee) > 0
        ? money(number(structured.transportFee))
        : (structured.freight != null && number(structured.freight) > 0 ? money(number(structured.freight)) : ''))
      : '',
    salesFreightText: document.docType === 'delivery_note'
      && document.salesFreight != null && number(document.salesFreight) > 0
      ? money(number(document.salesFreight)) : '',
    carrierNameText: document.docType === 'delivery_note' ? (structured.carrierName || '') : '',
    pricingNoteText: document.pricingNote || '',
    sourceLabel: {
      image: '图片',
      pdf: 'PDF',
      excel: 'Excel',
      sms: '短信',
      text: '手动输入',
      manual: '手动输入'
    }[document.source] || '文件',
    itemSummary: decoratedItems.length
      ? `${decoratedItems[0].materialName || '未标注材料'}${decoratedItems.length > 1 ? ` 等 ${decoratedItems.length} 项` : ''}`
      : '无材料明细',
    items: decoratedItems
  };
}

// 项目 / 批次状态色调（对齐 Web statusTone 映射）
function statusToneOf(status) {
  if (status === '已完成') return 'green';
  if (['待处理', '待确认', '待报价'].includes(status)) return 'orange';
  return 'blue';
}

function decorateProject(project, batches) {
  const projectBatches = batches.filter((item) => item.projectId === project.id);
  const purchaseText = money(project.purchaseAmount);
  const paidText = money(project.paidAmount);
  const invoiceText = money(project.invoiceAmount);
  const receivedText = money(project.receivedAmount);
  return {
    ...project,
    progress: number(project.progress),
    purchaseAmount: number(project.purchaseAmount),
    salesAmount: number(project.salesAmount),
    profitAmount: number(project.profitAmount),
    marginRate: number(project.marginRate),
    paidAmount: number(project.paidAmount),
    invoiceAmount: number(project.invoiceAmount),
    receivedAmount: number(project.receivedAmount),
    purchaseText,
    purchaseSize: kpiSize(purchaseText),
    salesText: compactMoney(project.salesAmount),
    profitText: compactMoney(project.profitAmount),
    marginText: `${number(project.marginRate).toFixed(1)}%`,
    paidText,
    paidSize: kpiSize(paidText),
    invoiceText,
    invoiceSize: kpiSize(invoiceText),
    receivedText,
    receivedSize: kpiSize(receivedText),
    progressStyle: `width:${Math.min(Math.max(number(project.progress), 0), 100)}%`,
    letter: (project.name || '项').slice(0, 1),
    statusLabel: project.status || '进行中',
    statusTone: statusToneOf(project.status),
    batches: projectBatches.map((batch) => ({
      ...batch,
      progress: number(batch.progress),
      purchaseAmount: number(batch.purchaseAmount),
      salesAmount: number(batch.salesAmount),
      paidAmount: number(batch.paidAmount),
      invoiceAmount: number(batch.invoiceAmount),
      receivedAmount: number(batch.receivedAmount),
      purchaseText: money(batch.purchaseAmount),
      salesText: compactMoney(batch.salesAmount),
      paidText: money(batch.paidAmount),
      invoiceText: money(batch.invoiceAmount),
      receivedText: money(batch.receivedAmount),
      dateText: date(batch.businessDate),
      shortDate: date(batch.businessDate) === '-' ? '--' : date(batch.businessDate).slice(5),
      materialText: batch.materialSummary || '待补充材料',
      progressStyle: `width:${Math.min(Math.max(number(batch.progress), 0), 100)}%`,
      statusLabel: batch.status || '进行中',
      statusTone: statusToneOf(batch.status)
    }))
  };
}

function decorateCardMessage(message) {
  const payload = message.payload || {};
  const cardType = payload.cardType || '';
  const document = payload.document || {};
  const docType = document.docType;
  const type = meta(docType);
  const fields = fieldMeta(docType);
  const missingFields = (payload.missingFields || []).map((field) => ({
    docDate: fields.dateLabel,
    supplierName: fields.partyLabel,
    amount: fields.amountLabel,
    invoiceDirection: '发票方向',
    invoiceEvidence: '发票关键字段',
    settlementDirection: '结算归属',
    amountMismatchDecision: '金额差异处理方式'
  }[field] || field));

  // 与 Web 端 DocumentReviewCard 对齐的类型派生
  const evidenceOnly = docType === 'evidence_only';
  const archiveOnly = ['sales_settlement', 'evidence_only'].includes(docType);
  const isSupply = docType === 'supply_order';
  const isTransport = docType === 'transport_note';
  const isPayment = docType === 'payment';
  const isPaymentRequest = docType === 'payment_request';
  const isPurchaseInvoice = ['purchase_invoice', 'invoice'].includes(docType);
  const isSalesInvoice = docType === 'sales_invoice';
  const isInvoice = isPurchaseInvoice || isSalesInvoice || docType === 'invoice_unknown';
  const isFinancial = isPayment || isPaymentRequest || isInvoice;

  // 材料明细（含 Web 表格的全部列展示）
  const items = (document.items || []).map((item) => {
    const pricingQuantity = item.pricingQuantity != null ? item.pricingQuantity : item.quantity;
    const pricingUnit = item.pricingUnit || item.unit || '';
    return {
      ...item,
      materialName: item.materialName || '未标注材料',
      spec: item.spec || '-',
      packageText: number(item.packageQuantity) > 0
        ? `${quantity(item.packageQuantity)} ${item.packageUnit || '件'}`
        : '-',
      weightText: number(item.totalWeight) > 0
        ? `${quantity(item.totalWeight)} ${item.weightUnit || pricingUnit || '吨'}`
        : '-',
      quantityText: `${quantity(pricingQuantity)} ${pricingUnit}`.trim(),
      priceText: `${money(item.unitPrice)}/${pricingUnit || '单位'}`,
      amountText: money(item.amount)
    };
  });

  const status = payload.status;
  const applied = status === 'applied';
  const queued = status === 'queued';
  // deleted：单据已在数据中心删除，卡片为终态（锁定全部操作，与 Web 端守卫一致）
  const deleted = status === 'deleted';
  const locked = applied || queued || status === 'duplicate_skipped' || deleted;
  const typeLabel = document.docTypeLabel || type.label;
  const confText = confidence(document.confidence);

  // 聚焦确认（对齐 Web focused-confirmation）：发票方向 / 结算归属未知时，给出快捷二选一。
  const isInvoiceUnknown = docType === 'invoice_unknown';
  const isSettlementUnknown = docType === 'settlement_unknown';
  const needsFocusConfirm = (isInvoiceUnknown || isSettlementUnknown) && !locked;
  // 已定向发票仍可切换方向（对齐 Web：已入库的进项/销项发票展示切换确认块）
  const isInvoiceDirected = (docType === 'purchase_invoice' || docType === 'sales_invoice') && !locked;

  // 送货单：销售侧字段（采购运费/销售运费/承运单位/销售定价/车牌，与 web 卡片字段一致）
  const isDelivery = docType === 'delivery_note';
  const structuredDoc = parseStructured(document);
  const transportFeeValue = structuredDoc.transportFee != null
    ? number(structuredDoc.transportFee)
    : (structuredDoc.freight != null ? number(structuredDoc.freight) : null);
  const freightText = isDelivery && transportFeeValue != null && transportFeeValue > 0
    ? money(transportFeeValue) : '';
  const salesFreightText = isDelivery && document.salesFreight != null && number(document.salesFreight) > 0
    ? money(number(document.salesFreight)) : '';
  const carrierNameText = isDelivery ? (structuredDoc.carrierName || '') : '';

  // 标题 / 副标题（对齐 Web 文案）
  const headingTitle = evidenceOnly
    ? '仅作原始凭证'
    : document.typeOverride
      ? `按要求作为${typeLabel}处理`
      : `识别到${typeLabel}`;
  let headingSub;
  if (evidenceOnly && document.detectedDocTypeLabel) {
    headingSub = `内容识别：${document.detectedDocTypeLabel} · 不更新台账`;
  } else if (document.typeOverride && document.detectedDocTypeLabel) {
    headingSub = `原始内容识别：${document.detectedDocTypeLabel} · 请核对目标字段`;
  } else {
    headingSub = `AI 置信度 ${confText || '-'} · ${items.length ? `已提取 ${items.length} 条材料明细` : '已提取关键业务字段'}`;
  }

  // 状态徽标
  const statusText = applied
    ? (archiveOnly ? '已归档' : '已写入台账')
    : queued ? '已确认，待回填' : deleted ? '已删除' : '待确认';
  const statusTone = applied || queued ? 'green' : deleted ? 'gray' : 'orange';

  // 确认按钮文案
  let confirmLabel;
  if (applied) confirmLabel = archiveOnly ? '已确认归档' : '已确认并写入台账';
  else if (queued) confirmLabel = '已确认，待自动回填';
  else if (deleted) confirmLabel = '该数据已删除';
  else if (missingFields.length) confirmLabel = '请先补充必填字段';
  else confirmLabel = archiveOnly ? '确认并归档' : '确认并写入台账';

  // 卡片底部说明（对齐 Web cardNote）
  let noteText;
  if (isSupply) noteText = '确认后计入批次采购台账；同一批次可继续导入多张采购单，数量与金额自动累加。';
  else if (evidenceOnly) noteText = `该资料仅存档，不建立业务数据或更新台账${document.detectedDocTypeLabel ? `；原始内容识别为${document.detectedDocTypeLabel}` : ''}。`;
  else if (isDelivery) noteText = (document.pricingNote ? `销售定价：${document.pricingNote}。` : '') + '确认后按锁定销售价写入台账，销售运费计入销售总金额；采购运费与运输费用行累加。可继续导入多张送货单。';
  else if (isTransport) noteText = '确认后作为独立运输费用写入台账，并按承运单位匹配请款、付款和进项票。';
  else if (isPaymentRequest) noteText = '确认后回填请款金额，作为本批次应付申请记录。';
  else if (isPayment) noteText = '确认后回填实际付款金额，并自动更新已付与未付金额。';
  else if (isPurchaseInvoice) noteText = '确认后回填进项开票金额与税率，并检查采购、付款和发票差异。';
  else if (isSalesInvoice) noteText = '确认后只回填销项开票日期、含税金额与税率，不会重复增加销售总金额。';
  else if (docType === 'sales_settlement') noteText = '结算资料确认后仅归档，不自动修改销售金额、收款或开票数据。';
  else noteText = '确认后将该凭证归档并增量更新当前批次台账。';

  // 重复原因文案
  const reasonText = ({
    same_hash: '附件内容与已有文件完全一致',
    same_filename: '附件名称与已有文件相同',
    same_business: '材料、规格、数量和金额与已有单据高度一致'
  })[payload.reason] || '与已有资料高度相似';

  return {
    ...message,
    isUser: message.sender === 'USER',
    isFile: message.messageType === 'FILE',
    timeText: time(message.createdAt),
    failed: message.status === 'failed',
    creditsUsed: message.creditsUsed || 0,
    cardType,
    isDocumentCard: cardType === 'document_review',
    isDuplicateCard: cardType === 'duplicate_review',
    isOperationCard: cardType === 'operation_result',
    isClarificationCard: message.businessType === 'clarify_card',
    isReconciliationCard: cardType === 'reconciliation_export',
    payload: {
      ...payload,
      document: {
        ...document,
        typeLabel,
        typeIcon: type.icon,
        typeTone: type.tone,
        ...fields,
        amountText: money(document.amount),
        dateText: date(document.docDate),
        confidenceText: confText,
        items
      },
      headingTitle,
      headingSub,
      statusText,
      statusTone,
      confirmLabel,
      noteText,
      reasonText,
      isFinancial,
      isInvoice,
      financeLabel: isInvoice ? '发票内容' : isPaymentRequest ? '请款类别' : '付款类别',
      expenseCategoryLabel: document.expenseCategory === 'transport' ? '运输费用' : '材料采购',
      taxRateText: document.taxRate || (document.expenseCategory === 'transport' ? '9%' : '13%'),
      netAmountText: document.netAmount != null && document.netAmount !== '' ? money(document.netAmount) : '',
      taxAmountText: document.taxAmount != null && document.taxAmount !== '' ? money(document.taxAmount) : '',
      // 送货单销售侧字段（与 web 卡片一致）
      isDelivery,
      freightText,
      salesFreightText,
      carrierNameText,
      pricingNoteText: document.pricingNote || '',
      vehicleNoText: structuredDoc.vehicleNo || '',
      // 请款单备注/用途（web 卡片展示编辑，mini 只读展示 + 抽屉编辑）
      remarkText: isPaymentRequest ? (document.remark || '') : '',
      // 发票双方名称
      sellerNameText: document.sellerName || '',
      buyerNameText: document.buyerName || '',
      isInvoiceDirected,
      missingFieldLabels: missingFields,
      missingFieldText: missingFields.join('、'),
      hasMissingFields: missingFields.length > 0,
      needsFocusConfirm,
      // 追问卡直连：payload.documentId 存在时可复用原始附件提交答案（与 web 同接口）
      clarifyDocumentId: payload.documentId || '',
      clarifyAnswered: Boolean(payload.answered),
      // 对账单导出卡片：日期按来源预填（缺省今天，本地时区），汇总文案初始化
      reconStart: payload.start || payload.reconStart || localToday(),
      reconEnd: payload.end || payload.reconEnd || localToday(),
      reconSummaryText: payload.summary
        ? `${payload.projectName || '当前项目'} · 全部材料 · 送货单 ${payload.summary.deliveries} 张 · 对账金额 ${payload.summary.salesAmount != null ? money(number(payload.summary.salesAmount)) : '-'}`
        : (payload.reconSummaryText || ''),
      // 材料筛选：默认全部材料，选项在点开下拉时按项目批次材料拉取
      reconMaterialOptions: payload.reconMaterialOptions || ['全部材料'],
      reconMaterialIndex: payload.reconMaterialIndex || 0,
      reconMaterial: payload.reconMaterial || '',
      focusTitle: isInvoiceUnknown
        ? '已确认这是一张发票'
        : isSettlementUnknown
          ? `已确认这是${document.expenseCategory === 'transport' ? '运输' : '材料'}结算资料`
          : '',
      focusSub: isInvoiceUnknown
        ? '目前只需确认本单位在这张发票中的方向。'
        : isSettlementUnknown
          ? '结算资料不会被当作付款、收款或发票，也不会自动修改销售总金额。'
          : '',
      options: (payload.options && payload.options.length)
        ? payload.options
        : ['按用户说明处理', '按识别结果处理', '重新上传并说明'],
      locked,
      canConfirm: cardType === 'document_review'
        && !locked
        && missingFields.length === 0,
      amountDiscrepancy: payload.amountDiscrepancy ? {
        ...payload.amountDiscrepancy,
        sourceAmountText: money(payload.amountDiscrepancy.sourceAmount),
        itemTotalText: money(payload.amountDiscrepancy.itemTotal),
        differenceText: money(payload.amountDiscrepancy.difference)
      } : null,
      expenseCandidates: (payload.expenseCandidates || []).map((candidate) => ({
        ...candidate,
        amountText: money(candidate.amount),
        remainingText: money(candidate.remainingAmount)
      })),
      existingDocument: payload.existingDocument ? {
        ...payload.existingDocument,
        amountText: money(payload.existingDocument.amount)
      } : {},
      operationReference: payload.reference ? {
        ...payload.reference,
        amountText: money(payload.reference.amount)
      } : null
    }
  };
}

function normalize(value) {
  return String(value || '').replace(/\s+/g, '').toLowerCase();
}

// 台账聚合键 = 材料大类（与 web deliveryMatching.ts 同口径）：螺纹钢不分型号规格，
// 规格/等级不参与分组，只按大类显示累加
function materialKey(name) {
  return normalize(materialCategory(name));
}

/** 本地时区今天（YYYY-MM-DD）：toISOString 是 UTC，+8 时区晚间会差一天。 */
function localToday() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}

function isConfirmed(record) {
  return ['matched', 'archived'].includes(record.document.status)
    && record.document.duplicateDecision !== 'skip';
}

function weightInTons(value, unit) {
  return ['kg', '千克', '公斤'].includes(normalize(unit)) ? number(value) / 1000 : number(value);
}

/** 送货单上标注的采购运费（transportFee/freight，原「运输费用」字段，进台账运输费用行）。 */
function deliveryFreightAmount(record) {
  const structured = parseStructured(record.document);
  const value = structured.transportFee != null ? structured.transportFee : structured.freight;
  const parsed = number(value);
  return parsed > 0 ? parsed : 0;
}

/** 运输相关财务的文本提示（与 web transportHint 同口径）。 */
function transportHint(record) {
  const document = record.document || {};
  return /运输|运费|物流|承运/.test(
    `${document.sourceText || ''} ${document.guidanceText || ''} ${document.aiSummary || ''} ${document.remark || ''}`
  );
}

function deliverySummary(records) {
  const confirmed = records.filter(isConfirmed);
  const supply = confirmed.filter((record) => record.document.docType === 'supply_order');
  const deliveries = confirmed.filter((record) => record.document.docType === 'delivery_note');
  const details = {};

  supply.forEach((record) => {
    (record.items || []).forEach((item) => {
      const key = materialKey(item.materialName, item.spec);
      const current = details[key] || {
        materialName: item.materialName || '未标注材料',
        spec: item.spec || '',
        expected: 0,
        unit: item.pricingUnit || item.unit || '',
        packages: 0,
        packageUnit: '',
        weight: 0,
        status: '待送达'
      };
      current.expected += number(item.pricingQuantity || item.quantity);
      details[key] = current;
    });
  });

  deliveries.forEach((record) => {
    (record.items || []).forEach((item) => {
      const current = details[materialKey(item.materialName, item.spec)];
      if (!current) return;
      current.packages += number(item.packageQuantity || item.quantity);
      current.packageUnit = current.packageUnit || item.packageUnit || item.unit || '';
      current.weight += weightInTons(item.totalWeight, item.weightUnit);
    });
  });

  Object.keys(details).forEach((key) => {
    const item = details[key];
    const byWeight = ['吨', 't', 'ton', 'kg', '千克', '公斤'].includes(normalize(item.unit));
    const delivered = byWeight ? item.weight : item.packages;
    // 超供送达：先导送货单时，送达数量可能超过采购单数量（2026-09-21 增加状态）
    item.status = delivered <= 0
      ? '待送达'
      : item.expected > 0 && delivered > item.expected + 0.0001
        ? '超供送达'
        : item.expected > 0 && delivered + 0.0001 < item.expected ? '部分送达' : '已送达';
    item.expectedText = `${quantity(item.expected)}${item.unit}`;
    item.deliveryText = byWeight
      ? `${quantity(item.weight)}吨`
      : `${quantity(item.packages)}${item.packageUnit}`;
    item.statusTone = item.status === '已送达' ? 'green'
      : item.status === '超供送达' ? 'orange'
        : item.status === '部分送达' ? 'orange' : 'gray';
  });
  return details;
}

function buildLedgerGroups(records, ledgerRows, projects, batches) {
  // 与 web `ReconciliationLedgerPage` 对齐：合并材料行 + 运输行为 `rows`，每行带请款 / 付款 / 进项票数据；
  // 材料的财务数据按批次归集（materialFinance），运输按行归集（每行自己的 finance）。
  function parseStructured(document) {
    try {
      return document.structuredData ? JSON.parse(document.structuredData) : {};
    } catch (error) {
      return {};
    }
  }

  function summarizeFinance(records, purchaseAmount) {
    const requested = records
      .filter((record) => record.document.docType === 'payment_request')
      .reduce((sum, record) => sum + number(record.document.amount), 0);
    const paid = records
      .filter((record) => record.document.docType === 'payment')
      .reduce((sum, record) => sum + number(record.document.amount), 0);
    const paymentRecord = records.find((record) => record.document.docType === 'payment');
    const invoiceRecords = records.filter((record) =>
      ['purchase_invoice', 'invoice'].includes(record.document.docType));
    const invoice = invoiceRecords.reduce(
      (sum, record) => sum + number(record.document.amount), 0);
    const taxRates = Array.from(new Set(invoiceRecords
      .map((record) => parseStructured(record.document).taxRate)
      .filter((value) => value != null && value !== '')
      .map(String)));
    return {
      requested,
      paid,
      unpaid: Math.max(purchaseAmount - paid, 0),
      paymentDate: paymentRecord ? date(paymentRecord.document.docDate) : '',
      payee: paymentRecord ? (paymentRecord.document.supplierName || '') : '',
      invoice,
      invoiceDate: invoiceRecords[0] ? date(invoiceRecords[0].document.docDate) : '',
      taxRate: taxRates.join(' / ') || '-'
    };
  }

  return batches.map((batch) => {
    const project = projects.find((item) => item.id === batch.projectId);
    const batchRecords = records.filter((record) =>
      record.document.batchId === batch.id && isConfirmed(record));
    const batchLedger = ledgerRows.filter((row) => row.batchId === batch.id);
    const supplyRecords = batchRecords.filter((record) => record.document.docType === 'supply_order');
    const transportRecords = batchRecords.filter((record) => record.document.docType === 'transport_note');
    const financeRecords = batchRecords.filter((record) =>
      ['payment_request', 'payment', 'purchase_invoice', 'invoice', 'sales_invoice', 'receipt']
        .includes(record.document.docType));
    const delivery = deliverySummary(batchRecords);
    const materialMap = {};

    supplyRecords.forEach((record) => {
      // 材料行按「材料大类」聚合（螺纹钢不分型号规格），多张采购单数量/金额累加；
      // 行金额按明细行自身金额（item.amount），缺失/无价即「空白」（null），
      // 不再用单据总额填满每行、也不按比例摊回差额，且不影响未付款基线。
      const sourceItems = (record.items || []).length ? record.items : [{
        materialName: '未拆分采购数据',
        spec: '',
        pricingQuantity: null,
        pricingUnit: '',
        amount: null
      }];
      sourceItems.forEach((item) => {
        const key = materialKey(item.materialName);
        const current = materialMap[key] || {
          key,
          material: materialCategory(item.materialName),
          spec: '',
          supplier: '',
          purchaseQuantity: 0,
          purchaseUnit: item.pricingUnit || item.unit || '',
          // 明细行无金额时为 null（展示空白），不参与未付款基线
          purchaseAmount: null
        };
        current.purchaseQuantity += number(item.pricingQuantity || item.quantity);
        current.purchaseUnit = current.purchaseUnit || item.pricingUnit || item.unit || '';
        const itemAmount = item.amount == null || item.amount === '' ? null : number(item.amount);
        if (itemAmount != null) {
          current.purchaseAmount = (current.purchaseAmount == null ? 0 : current.purchaseAmount) + itemAmount;
        }
        if (record.document.supplierName && !current.supplier.split(' / ').includes(record.document.supplierName)) {
          current.supplier = current.supplier
            ? `${current.supplier} / ${record.document.supplierName}`
            : record.document.supplierName;
        }
        materialMap[key] = current;
      });
    });

    // 送货单先行行：采购单未到（或明细未匹配上）的大类各自成行——
    // 发货数量只进「送货单数量」，采购数量显示「-」（禁止借用，2026-09-21 修复口径）
    const deliveryOnlyMap = {};
    batchRecords
      .filter((record) => record.document.docType === 'delivery_note')
      .forEach((record) => {
        (record.items || []).forEach((item) => {
          const key = materialKey(item.materialName);
          if (materialMap[key]) return;
          const current = deliveryOnlyMap[key] || {
            key,
            material: materialCategory(item.materialName),
            supplier: '',
            packages: 0,
            packageUnit: '',
            weight: 0
          };
          current.packages += number(item.packageQuantity || item.quantity);
          current.packageUnit = current.packageUnit || item.packageUnit || item.unit || '';
          current.weight += weightInTons(item.totalWeight, item.weightUnit);
          deliveryOnlyMap[key] = current;
        });
      });

    // 把财务记录分流到材料 vs 运输（与 web 同口径）：expenseCategory 显式 transport、
    // targetDocumentId 命中送货单运费单、承运单位命中运输单、文本含运输/运费字样
    const deliveryFeeRecords = batchRecords.filter((record) =>
      record.document.docType === 'delivery_note' && deliveryFreightAmount(record) > 0);
    const deliveryFeeIds = new Set(deliveryFeeRecords.map((record) => record.document.id));
    const transportSupplierKeys = new Set(
      transportRecords.map((record) => String(record.document.supplierName || '').replace(/\s+/g, '').toLowerCase())
    );
    const transportFinanceRecords = financeRecords.filter((record) => {
      const category = parseStructured(record.document).expenseCategory;
      const supplier = String(record.document.supplierName || '').replace(/\s+/g, '').toLowerCase();
      const targetHit = record.document.targetDocumentId
        && deliveryFeeIds.has(record.document.targetDocumentId);
      const hint = transportHint(record);
      return category === 'transport'
        || targetHit
        || (!category && (transportSupplierKeys.has(supplier) || hint));
    });
    const transportFinanceIds = new Set(
      transportFinanceRecords.map((record) => record.document.id)
    );
    const materialFinanceRecords = financeRecords.filter((record) =>
      !transportFinanceIds.has(record.document.id));

    // 请款明细（弹框展开用，与请款列累计值同源，按笔次列出；对齐 web FinanceDetailEntry）
    const docTypeShortLabel = (docType) =>
      docType === 'delivery_note' ? '送货单'
        : docType === 'transport_note' ? '运输单'
          : docType === 'supply_order' ? '采购单' : '单据';
    const buildRequestedDetails = (sourceRecords) => sourceRecords
      .filter((record) => record.document.docType === 'payment_request')
      .map((record) => {
        const targetId = record.document.targetDocumentId;
        const target = targetId
          ? batchRecords.find((item) => item.document.id === targetId)
          : null;
        const targetLabel = target
          ? [docTypeShortLabel(target.document.docType), target.document.docCode].filter(Boolean).join(' ')
          : '';
        return {
          key: record.document.id,
          date: date(record.document.docDate),
          amount: number(record.document.amount),
          amountText: money(number(record.document.amount)),
          target: targetLabel,
          remark: record.document.remark || ''
        };
      });

    // 台账行组装：采购基准行（采购单明细按大类）+ 送货单先行行（待采购单）
    const deliveryOnlyRows = Object.values(deliveryOnlyMap).map((row) => {
      const weightText = row.weight > 0 ? `${quantity(row.weight)}吨` : '';
      const packageText = row.packages > 0 ? `${quantity(row.packages)}${row.packageUnit}` : '';
      return {
        key: row.key,
        kind: 'material',
        sourceTag: 'delivery',
        material: row.material,
        spec: '',
        supplier: '',
        purchaseQuantity: 0,
        purchaseUnit: '',
        purchaseAmount: null,
        purchaseAmountText: null,
        purchaseQuantityText: '-',
        deliveredText: weightText || packageText || '-',
        deliveryStatus: '待采购单',
        deliveryTone: 'gray'
      };
    });
    const materialRows = Object.values(materialMap).map((row) => {
      const deliveryItem = delivery[row.key] || {};
      return {
        ...row,
        purchaseQuantityText: row.purchaseQuantity > 0
          ? `${quantity(row.purchaseQuantity)}${row.purchaseUnit}`
          : '-',
        deliveredText: deliveryItem.deliveryText || '-',
        deliveryStatus: deliveryItem.status || '待送达',
        deliveryTone: deliveryItem.statusTone || 'gray'
      };
    }).concat(deliveryOnlyRows);
    // 未付款基线必须锚定台账权威采购额（后端 upsertSupplySkeleton 按「明细合计 or 单据总额」兜底），
    // 不能用展示行的 item.amount 求和——明细行无金额时会被清零，导致未付款算成 0。
    const materialPurchase = batchLedger.reduce((sum, item) => sum + number(item.purchaseAmount), 0);
    const materialFinance = summarizeFinance(materialFinanceRecords, materialPurchase);
    const materialFinanceText = {
      requestedText: money(materialFinance.requested),
      paidText: money(materialFinance.paid),
      unpaidText: money(materialFinance.unpaid),
      invoiceText: money(materialFinance.invoice)
    };

    // 合并 rows：材料在前，运输在后；每行带请款/付款/进项票（材料共享 materialFinance，运输按行）
    const rows = [];
    materialRows.forEach((row) => {
      rows.push({
        key: `${batch.id}-material-${row.key}`,
        kind: 'material',
        kindLabel: '材料',
        kindTone: 'blue',
        material: row.material,
        spec: row.spec,
        supplier: row.supplier || '-',
        purchaseAmount: row.purchaseAmount,
        purchaseAmountText: row.purchaseAmount == null ? '-' : money(row.purchaseAmount),
        purchaseQuantityText: row.purchaseQuantityText,
        deliveredText: row.deliveredText,
        deliveryStatus: row.deliveryStatus,
        deliveryTone: row.deliveryTone || 'gray',
        // 材料行共享批次级财务（与 web `materialFinance` 对齐）
        requested: materialFinance.requested,
        paid: materialFinance.paid,
        unpaid: materialFinance.unpaid,
        paymentDate: materialFinance.paymentDate,
        payee: materialFinance.payee,
        invoice: materialFinance.invoice,
        invoiceDate: materialFinance.invoiceDate,
        taxRate: materialFinance.taxRate,
        requestedText: materialFinanceText.requestedText,
        paidText: materialFinanceText.paidText,
        unpaidText: materialFinanceText.unpaidText,
        invoiceText: materialFinanceText.invoiceText
      });
    });

    // 运输费用行：批次内运输单 + 送货单采购运费（transportFee）合并为一行（与 web 对齐）；
    // 送货单往来单位是客户，承运单位取人工补录的 structuredData.carrierName（选填）
    const transportSources = transportRecords.concat(deliveryFeeRecords);
    const transportSourceIds = new Set(transportSources.map((record) => record.document.id));
    const sourceSupplier = (record) => record.document.docType === 'delivery_note'
      ? String(parseStructured(record.document).carrierName || '').trim()
      : (record.document.supplierName || '');
    const suppliers = Array.from(new Set(
      transportSources.map(sourceSupplier).filter(Boolean)
    ));
    const codes = Array.from(new Set(
      transportSources.map((record) => record.document.docCode).filter(Boolean)
    ));
    const dates = Array.from(new Set(
      transportSources
        .map((record) => date(record.document.docDate))
        .filter((value) => value && value !== '-')
    ));
    let transportRow = null;
    if (transportSources.length) {
      const transportAmount = transportSources.reduce((sum, record) =>
        sum + (record.document.docType === 'delivery_note'
          ? deliveryFreightAmount(record)
          : number(record.document.amount)), 0);
      // 运输行财务：targetDocumentId 指向运输单/送货单运费单的记录，其余（无指向）也归此行
      const rowFinance = transportFinanceRecords.filter((record) =>
        !record.document.targetDocumentId
          || transportSourceIds.has(record.document.targetDocumentId));
      const finance = summarizeFinance(rowFinance, transportAmount);
      transportRow = {
        amount: transportAmount,
        finance,
        deliveryStatus: dates.join(' / ') || '日期待补充'
      };
      rows.push({
        key: `${batch.id}-transport`,
        kind: 'transport',
        kindLabel: '运输',
        kindTone: 'orange',
        material: '运输费用',
        spec: codes.join(' / ') || '独立运输记录',
        supplier: suppliers.join(' / ') || '待补充承运单位',
        purchaseAmount: transportAmount,
        purchaseAmountText: money(transportAmount),
        purchaseQuantityText: '-',
        deliveredText: '-',
        deliveryStatus: transportRow.deliveryStatus,
        requested: finance.requested,
        paid: finance.paid,
        unpaid: finance.unpaid,
        paymentDate: finance.paymentDate,
        payee: finance.payee,
        invoice: finance.invoice,
        invoiceDate: finance.invoiceDate,
        taxRate: finance.taxRate,
        requestedText: money(finance.requested),
        paidText: money(finance.paid),
        unpaidText: money(finance.unpaid),
        invoiceText: money(finance.invoice)
      });
    }

    // 采购及运输合计 = 台账权威采购额 + 运输费用行金额（与 web totalPurchase 同口径）
    const purchase = materialPurchase + (transportRow ? transportRow.amount : 0);
    const requested = financeRecords
      .filter((record) => record.document.docType === 'payment_request')
      .reduce((sum, item) => sum + number(item.document.amount), 0);
    const paid = financeRecords
      .filter((record) => record.document.docType === 'payment')
      .reduce((sum, item) => sum + number(item.document.amount), 0);
    const invoice = financeRecords
      .filter((record) => ['purchase_invoice', 'invoice'].includes(record.document.docType))
      .reduce((sum, item) => sum + number(item.document.amount), 0);
    const salesInvoice = financeRecords
      .filter((record) => record.document.docType === 'sales_invoice')
      .reduce((sum, item) => sum + number(item.document.amount), 0);
    const row = batchLedger[0] || {};
    const sales = batchLedger.reduce((sum, item) => sum + number(item.salesAmount), 0);
    // 已收款只读台账（与 web 同口径），不再用 receipt 记录兜底
    const received = batchLedger.reduce((sum, item) => sum + number(item.receivedAmount), 0);
    const profit = sales > 0 ? sales - purchase : 0;
    const delivered = materialRows.length > 0 && materialRows.every(
      (item) => item.deliveryStatus === '已送达' || item.deliveryStatus === '超供送达');
    // 已对平：材料与运输分开判定（与 web 同口径）
    const materialBalanced = materialRows.length === 0
      || (materialFinance.unpaid <= 0 && materialFinance.invoice >= materialPurchase);
    const transportBalanced = !transportRow
      || (transportRow.finance.unpaid <= 0 && transportRow.finance.invoice >= transportRow.amount);
    const balanced = materialBalanced && transportBalanced && delivered
      && (materialRows.length > 0 || Boolean(transportRow));
    const unpaid = Math.max(purchase - paid, 0);
    const invoiceGap = Math.max(purchase - invoice, 0);
    const outstanding = Math.max(sales - received, 0);
    const pendingDeliveryCount = materialRows.filter((item) =>
      item.deliveryStatus !== '已送达' && item.deliveryStatus !== '超供送达').length;
    const exceptions = [];
    if (pendingDeliveryCount) {
      exceptions.push({ label: `${pendingDeliveryCount}项待交付`, tone: 'orange' });
    }
    if (unpaid > 0) {
      exceptions.push({ label: `待付 ${compactMoney(unpaid)}`, tone: 'orange' });
    }
    if (invoiceGap > 0) {
      exceptions.push({ label: `缺票 ${compactMoney(invoiceGap)}`, tone: 'red' });
    }
    if (sales <= 0) {
      exceptions.push({ label: '待补销售', tone: 'gray' });
    } else if (outstanding > 0) {
      exceptions.push({ label: `待收 ${compactMoney(outstanding)}`, tone: 'blue' });
    }

    return {
      id: batch.id,
      projectId: batch.projectId,
      projectName: project ? project.name : '-',
      batchName: batch.name,
      dateText: date(batch.businessDate),
      // 新版：合并后的 rows（每行带请款/付款/进项票），保留旧字段兼容其他视图
      rows,
      rowsTotal: rows.length,
      materialRows,
      materialRequested: buildRequestedDetails(materialFinanceRecords),
      transportRequested: buildRequestedDetails(transportFinanceRecords),
      // 总额列：材料采购 + 运输费用（与 web 总额列同口径）
      totalAmountText: money(purchase),
      transportRows: transportRecords.map((record) => ({
        id: record.document.id,
        supplier: record.document.supplierName || '待补充承运单位',
        code: record.document.docCode || '独立运输记录',
        dateText: date(record.document.docDate),
        amountText: money(record.document.amount)
      })),
      materialFinance,
      purchase,
      requested,
      paid,
      unpaid,
      invoice,
      invoiceGap,
      sales,
      salesInvoice,
      received,
      outstanding,
      profit,
      margin: sales > 0 ? profit / sales * 100 : 0,
      purchaseText: money(purchase),
      requestedText: money(requested),
      paidText: money(paid),
      unpaidText: money(unpaid),
      invoiceGapText: money(invoiceGap),
      invoiceText: money(invoice),
      salesText: money(sales),
      salesInvoiceText: money(salesInvoice || row.salesInvoiceAmount),
      salesInvoiceDate: date(row.salesInvoiceDate),
      salesInvoiceAmountText: money(Number(row.salesInvoiceAmount || 0)),
      salesTaxRate: row.salesTaxRate || '-',
      receivedText: money(received),
      outstandingText: money(outstanding),
      profitText: money(profit),
      marginText: `${(sales > 0 ? profit / sales * 100 : 0).toFixed(1)}%`,
      paymentPercent: purchase > 0 ? Math.min(paid / purchase * 100, 100) : 0,
      receiptPercent: sales > 0 ? Math.min(received / sales * 100, 100) : 0,
      paymentStyle: `width:${purchase > 0 ? Math.min(paid / purchase * 100, 100) : 0}%`,
      receiptStyle: `width:${sales > 0 ? Math.min(received / sales * 100, 100) : 0}%`,
      salesOrder: row.salesOrder || '',
      receiptDate: row.receiptDate || '',
      requiredReceiptDate: row.requiredReceiptDate || '',
      projectManager: row.projectManager || '',
      // 月结加价（后端现算返回，时点值）
      isMonthlySettlement: Boolean(row.isMonthlySettlement),
      interest: number(row.interest),
      interestText: money(row.interest),
      interestDetails: row.interestDetails || [],
      receiptEntries: row.receiptEntries || [],
      overdueFlag: Boolean(row.overdueFlag),
      amountCapFlag: Boolean(row.amountCapFlag),
      interestSummary: row.interestSummary || '',
      // 未回款含息合计 = 未回款本金 + 利息（不是销售总额 + 利息）
      dueTotalText: money(Math.max(number(sales) - number(received), 0) + number(row.interest)),
      delivered,
      pendingDeliveryCount,
      exceptions,
      statusLabel: balanced ? '已对平' : '待补充',
      statusTone: balanced ? 'green' : 'orange'
    };
  }).filter((group) =>
    group.rowsTotal > 0 || group.purchase > 0 || group.sales > 0);
}

module.exports = {
  DOC_TYPES,
  compactMoney,
  kpiSize,
  textWidth,
  decorateCardMessage,
  decorateProject,
  decorateRecord,
  buildLedgerGroups,
  deliverySummary,
  fieldMeta,
  meta,
  money,
  number,
  quantity,
  statusMeta,
  toneColor
};
