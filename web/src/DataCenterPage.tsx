import {
  AlertCircle,
  ArrowDownToLine,
  Check,
  CheckCircle2,
  ChevronRight,
  ClipboardCheck,
  Database,
  FileSpreadsheet,
  FileText,
  MessageSquareText,
  PencilLine,
  Plus,
  Search,
  Trash2,
  X
} from 'lucide-react';
import { startTransition, useDeferredValue, useEffect, useState } from 'react';
import {
  api,
  type ApiBusinessDataRecord,
  type ApiDocument,
  type ApiDocumentItem
} from './api';
import type { PageKey, Project } from './types';

const money = (value: number) =>
  new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(value || 0);

const DOC_TYPES = [
  ['全部', ''],
  ['采购单', 'supply_order'],
  ['配货 / 送货', 'delivery_note'],
  ['运输费用', 'transport_note'],
  ['请款', 'payment_request'],
  ['付款', 'payment'],
  ['收款', 'receipt'],
  ['进项发票', 'purchase_invoice'],
  ['销项发票', 'sales_invoice'],
  ['结算资料', 'sales_settlement'],
  ['原始凭证', 'evidence_only']
] as const;

const typeLabel = (type: string) => ({
  supply_order: '采购单',
  delivery_note: '配货 / 送货单',
  transport_note: '运输费用',
  payment_request: '请款记录',
  payment: '付款记录',
  receipt: '收款记录',
  invoice: '进项发票',
  purchase_invoice: '进项发票',
  sales_invoice: '销项发票',
  invoice_unknown: '待确认发票',
  sales_settlement: '销售结算资料',
  settlement_unknown: '待确认结算资料',
  evidence_only: '原始凭证'
}[type] || '其他资料');

const sourceLabel = (source: string) => ({
  image: '图片',
  pdf: 'PDF',
  excel: 'Excel',
  sms: '短信',
  text: '手动输入',
  manual: '手动输入'
}[source] || '文件');

const financialFields = (document?: ApiDocument) => {
  if (!document?.structuredData) {
    return { taxRate: '', netAmount: '', taxAmount: '', expenseCategory: 'material' as const };
  }
  try {
    const structured = JSON.parse(document.structuredData) as Record<string, unknown>;
    return {
      taxRate: structured.taxRate == null ? '' : String(structured.taxRate),
      netAmount: structured.netAmount == null ? '' : String(structured.netAmount),
      taxAmount: structured.taxAmount == null ? '' : String(structured.taxAmount),
      expenseCategory: structured.expenseCategory === 'transport'
        ? 'transport' as const
        : 'material' as const
    };
  } catch {
    return { taxRate: '', netAmount: '', taxAmount: '', expenseCategory: 'material' as const };
  }
};

const statusLabel = (document: ApiDocument) => {
  if (document.duplicateDecision === 'skip') return '重复归档';
  if (document.status === 'matched' || document.status === 'archived') return '已入库';
  if (document.status === 'discrepancy') return '有差异';
  return '待确认';
};

const statusTone = (document: ApiDocument) => {
  const label = statusLabel(document);
  if (label === '已入库') return 'success';
  if (label === '有差异') return 'danger';
  if (label === '重复归档') return 'neutral';
  return 'warning';
};

type ItemDraft = {
  id: string;
  materialName: string;
  spec: string;
  origin: string;
  packageQuantity: string;
  packageUnit: string;
  totalWeight: string;
  weightUnit: string;
  pricingQuantity: string;
  pricingUnit: string;
  unitPrice: string;
  // 采购单销售价：空=按批次口径自动推算；salesUnitPriceAuto 仅用于输入框占位提示
  salesUnitPrice: string;
  salesUnitPriceAuto: string;
  amount: string;
};

const itemDraft = (item?: ApiDocumentItem, index = 0, docType?: string): ItemDraft => ({
  id: item?.id || `data-row-${Date.now()}-${index}`,
  materialName: item?.materialName || '',
  spec: item?.spec || '',
  origin: item?.origin || '',
  packageQuantity: String(item?.packageQuantity || ''),
  packageUnit: item?.packageUnit || '件',
  totalWeight: String(item?.totalWeight || ''),
  weightUnit: item?.weightUnit || '吨',
  pricingQuantity: String(item?.pricingQuantity || item?.quantity || ''),
  pricingUnit: item?.pricingUnit || item?.unit || '吨',
  unitPrice: String(item?.unitPrice || ''),
  // 采购单：只有行级人工价才回填（材料级人工价不回填，避免保存后把材料价固化到行上，2026-11-06）；
  // 送货单：行上的销售单价就是该行的实际定价，直接回填
  salesUnitPrice: docType === 'delivery_note'
    ? (item?.salesUnitPrice != null ? String(item.salesUnitPrice) : '')
    : (item?.salesUnitPriceManual ? String(item?.salesUnitPrice ?? '') : ''),
  salesUnitPriceAuto: item?.salesUnitPriceAuto != null ? String(item.salesUnitPriceAuto) : '',
  amount: String(item?.amount || '')
});

function DataRecordDrawer({
  record,
  project,
  busy,
  onClose,
  onEdit,
  onConfirm,
  onDelete
}: {
  record: ApiBusinessDataRecord | null;
  project?: Project;
  busy: boolean;
  onClose: () => void;
  onEdit: (documentId: string, edits: Record<string, unknown>) => Promise<void>;
  onConfirm: (documentId: string) => Promise<void>;
  onDelete: (documentId: string, label: string) => void;
}) {
  const [section, setSection] = useState<'data' | 'source'>('data');
  const [editing, setEditing] = useState(false);
  const [amountDecisionOpen, setAmountDecisionOpen] = useState(false);
  const [accessUrl, setAccessUrl] = useState('');
  const [previewError, setPreviewError] = useState('');
  const document = record?.document;
  const [draft, setDraft] = useState({
    docType: '',
    supplierName: '',
    docCode: '',
    docDate: '',
    amount: '',
    taxRate: '',
    netAmount: '',
    taxAmount: '',
    expenseCategory: 'material' as 'material' | 'transport',
    // 送货单（销售侧发货单）：承运单位与两种运费为选填人工补录字段
    carrierName: '',
    transportFee: '',
    salesFreight: ''
  });
  const [items, setItems] = useState<ItemDraft[]>([]);
  // 销售价展示值：人工值为空时回落到按批次口径自动推算的值
  const salesPriceText = (item: ItemDraft) =>
    item.salesUnitPrice !== '' ? item.salesUnitPrice : item.salesUnitPriceAuto;

  useEffect(() => {
    if (!record) return;
    setSection('data');
    setEditing(false);
    const financial = financialFields(record.document);
    // 送货单的承运单位/采购运费存在 structuredData JSON 里；销售运费是实体列
    let carrierName = '';
    let transportFee = '';
    if (record.document.docType === 'delivery_note') {
      try {
        const structured = JSON.parse(record.document.structuredData || '{}') as {
          carrierName?: unknown;
          transportFee?: unknown;
        };
        if (structured.carrierName != null) carrierName = String(structured.carrierName);
        if (structured.transportFee != null) transportFee = String(structured.transportFee);
      } catch {
        // structuredData 解析失败按未填写处理
      }
    }
    setDraft({
      docType: record.document.docType,
      supplierName: record.document.supplierName || '',
      docCode: record.document.docCode || '',
      docDate: record.document.docDate || '',
      amount: String(record.document.amount || ''),
      taxRate: financial.taxRate,
      netAmount: financial.netAmount,
      taxAmount: financial.taxAmount,
      expenseCategory: financial.expenseCategory,
      carrierName,
      transportFee,
      salesFreight: record.document.salesFreight != null ? String(record.document.salesFreight) : ''
    });
    setItems(record.items.map((item, index) =>
      itemDraft(item, index, record.document.docType)));
  }, [record?.document.id, record?.document.updatedAt, record?.items.length]);

  useEffect(() => {
    let cancelled = false;
    setAccessUrl('');
    setPreviewError('');
    if (!document || ['manual', 'text', 'sms'].includes(document.source)) return;
    void api.documentAccessUrl(document.id)
      .then((result) => {
        if (!cancelled) setAccessUrl(result.url);
      })
      .catch((error) => {
        if (!cancelled) setPreviewError(error instanceof Error ? error.message : '原始资料暂时无法访问');
      });
    return () => {
      cancelled = true;
    };
  }, [document?.id]);

  if (!record || !document) return null;
  const batch = project?.batches.find((item) => item.id === document.batchId);
  const applied = document.status === 'matched' || document.status === 'archived';
  const isTransport = draft.docType === 'transport_note';
  const isPayment = draft.docType === 'payment';
  const isPaymentRequest = draft.docType === 'payment_request';
  const isInvoice = ['invoice', 'purchase_invoice', 'sales_invoice', 'invoice_unknown'].includes(draft.docType);
  const isPurchaseFinance = isPayment || isPaymentRequest || isInvoice;
  const supportsItems = ['supply_order', 'delivery_note', 'invoice', 'purchase_invoice', 'sales_invoice', 'invoice_unknown'].includes(draft.docType);
  // 采购单：明细多一列销售价（按批次口径由采购价推算，可人工覆盖）
  const showSalesPrice = draft.docType === 'supply_order' || draft.docType === 'delivery_note';
  const itemTotal = items.reduce((sum, item) => sum + Number(item.amount || 0), 0);
  const amountMismatch = draft.docType === 'supply_order'
    && items.length > 0
    && Math.abs(Number(draft.amount || 0) - itemTotal) > 0.01;
  const partyLabel = isTransport
    ? '承运单位'
    : isPayment
      ? '收款单位'
      : isInvoice
        ? '开票单位'
        : '往来单位';
  const dateLabel = isPayment ? '付款日期' : isInvoice ? '开票日期' : '业务日期';
  const amountLabel = isTransport
    ? '运输金额'
    : isPayment
      ? '付款金额'
      : isInvoice
        ? '开票金额'
        : '业务金额';

  const updateItem = (index: number, field: keyof ItemDraft, value: string) => {
    setItems((current) => current.map((item, itemIndex) => {
      if (itemIndex !== index) return item;
      const next = { ...item, [field]: value };
      if (field === 'pricingQuantity' || field === 'unitPrice') {
        const quantity = Number(next.pricingQuantity || 0);
        const unitPrice = Number(next.unitPrice || 0);
        if (quantity > 0 && unitPrice > 0) {
          next.amount = String(Number((quantity * unitPrice).toFixed(2)));
        }
      }
      return next;
    }));
  };

  const save = async (amountMismatchDecision?: 'sync_items' | 'keep_source') => {
    if (amountMismatch && !amountMismatchDecision) {
      setAmountDecisionOpen(true);
      return;
    }
    await onEdit(document.id, {
      ...draft,
      // 金额留空时提交 null（表示不修改），不要用 0 覆盖单据实际金额。
      amount: amountMismatchDecision === 'sync_items'
        ? itemTotal : (draft.amount === '' ? null : Number(draft.amount)),
      ...(amountMismatchDecision ? { amountMismatchDecision } : {}),
      items: items.map((item) => ({
        materialName: item.materialName,
        spec: item.spec,
        origin: item.origin || null,
        packageQuantity: item.packageQuantity === '' ? null : Number(item.packageQuantity),
        packageUnit: item.packageUnit,
        totalWeight: item.totalWeight === '' ? null : Number(item.totalWeight),
        weightUnit: item.weightUnit,
        // 计价数量缺失即留空（null），不用 0 顶替；金额缺失按 0。
        pricingQuantity: item.pricingQuantity === '' ? null : Number(item.pricingQuantity),
        pricingUnit: item.pricingUnit,
        unit: item.pricingUnit,
        quantity: item.pricingQuantity === '' ? null : Number(item.pricingQuantity),
        unitPrice: item.unitPrice === '' ? null : Number(item.unitPrice),
        // 采购单销售价：空 → null（清除人工值，恢复按批次口径自动推算）
        salesUnitPrice: item.salesUnitPrice === '' ? null : Number(item.salesUnitPrice),
        amount: Number(item.amount || 0)
      }))
    });
    setAmountDecisionOpen(false);
    setEditing(false);
  };

  return (
    <div className="drawer-shell data-drawer-shell">
      <button className="drawer-backdrop" onClick={onClose} aria-label="关闭数据详情" />
      <aside className="document-drawer data-record-drawer">
        <div className="drawer-heading">
          <div>
            <span className="data-record-kind"><Database size={15} />{typeLabel(document.docType)}</span>
            <h2>{document.docCode || document.fileName || '未编号业务数据'}</h2>
            <p>{project?.name} · {batch?.name}</p>
          </div>
          <button onClick={onClose} aria-label="关闭详情"><X size={20} /></button>
        </div>

        <div className="data-drawer-tabs">
          <button className={section === 'data' ? 'active' : ''} onClick={() => setSection('data')}>
            <Database size={15} />入库数据
          </button>
          <button className={section === 'source' ? 'active' : ''} onClick={() => setSection('source')}>
            <FileText size={15} />原始资料
          </button>
        </div>

        {section === 'data' ? (
          <>
            <div className="data-edit-note">
              <CheckCircle2 size={16} />
              {applied
                ? '这是当前批次的正式业务数据。修改后会同步更新相关台账和汇总。'
                : '这是待确认的识别数据，确认后才进入当前批次台账。'}
            </div>
            <div className="data-field-grid">
              <label>
                <span>业务类型</span>
                <select
                  value={draft.docType}
                  disabled={!editing || applied}
                  onChange={(event) => setDraft({ ...draft, docType: event.target.value })}
                >
                  {DOC_TYPES.filter((item) => item[1]).map(([label, value]) => (
                    <option key={value} value={value}>{label}</option>
                  ))}
                </select>
              </label>
              <label>
                <span>{partyLabel}</span>
                <input disabled={!editing} value={draft.supplierName} onChange={(event) => setDraft({ ...draft, supplierName: event.target.value })} />
              </label>
              <label>
                <span>单据编号</span>
                <input disabled={!editing} value={draft.docCode} onChange={(event) => setDraft({ ...draft, docCode: event.target.value })} />
              </label>
              <label>
                <span>{dateLabel}</span>
                <input type="date" disabled={!editing} value={draft.docDate} onChange={(event) => setDraft({ ...draft, docDate: event.target.value })} />
              </label>
              <label className="full">
                <span>{amountLabel}</span>
                <input type="number" step="any" disabled={!editing} value={draft.amount} onChange={(event) => setDraft({ ...draft, amount: event.target.value })} />
              </label>
              {draft.docType === 'delivery_note' && (
                <>
                  <label>
                    <span>承运单位（选填）</span>
                    <input disabled={!editing} value={draft.carrierName} placeholder="运输单上有则填，无则留空" onChange={(event) => setDraft({ ...draft, carrierName: event.target.value })} />
                  </label>
                  {/* 采购运费不再展示/编辑（2026-11-04 方案 B）：批次级成本，由台账统一取数；
                      draft.transportFee 仍透传识别值，保存不会抹掉 */}
                  <label>
                    <span>销售运费（选填）</span>
                    <input type="number" step="any" min="0" disabled={!editing} value={draft.salesFreight} placeholder="客户承担的运费，进销售总金额" onChange={(event) => setDraft({ ...draft, salesFreight: event.target.value })} />
                  </label>
                </>
              )}
              {isPurchaseFinance && (
                <>
                  <label>
                    <span>{isInvoice ? '发票内容' : isPaymentRequest ? '请款类别' : '付款类别'}</span>
                    <select
                      disabled={!editing}
                      value={draft.expenseCategory}
                      onChange={(event) => {
                        const expenseCategory = event.target.value as 'material' | 'transport';
                        setDraft({
                          ...draft,
                          expenseCategory,
                          taxRate: isInvoice
                            ? expenseCategory === 'transport' ? '9%' : '13%'
                            : draft.taxRate
                        });
                      }}
                    >
                      <option value="material">材料采购</option>
                      <option value="transport">运输费用</option>
                    </select>
                  </label>
                  {isInvoice && <label>
                    <span>税率</span>
                    <input disabled={!editing} value={draft.taxRate} onChange={(event) => setDraft({ ...draft, taxRate: event.target.value })} />
                  </label>}
                  {isInvoice && <label>
                    <span>不含税金额</span>
                    <input type="number" step="any" disabled={!editing} value={draft.netAmount} onChange={(event) => setDraft({ ...draft, netAmount: event.target.value })} />
                  </label>}
                  {isInvoice && <label>
                    <span>税额</span>
                    <input type="number" step="any" disabled={!editing} value={draft.taxAmount} onChange={(event) => setDraft({ ...draft, taxAmount: event.target.value })} />
                  </label>}
                </>
              )}
            </div>

            {supportsItems && (items.length > 0 || editing) && (
              <div className="data-items-section">
                <div className="data-items-heading">
                  <span><strong>材料与数量明细</strong><small>每一行都是可以直接修正的入库数据</small></span>
                  {editing && (
                    <button onClick={() => setItems((current) => [...current, itemDraft(undefined, current.length)])}>
                      <Plus size={14} />新增明细
                    </button>
                  )}
                </div>
                <div className="data-items-table">
                  <div className={`data-item-row head ${editing ? 'editing' : ''} ${showSalesPrice ? 'with-sales' : ''}`}>
                    <span>材料 / 规格</span>
                    <span>产地</span>
                    <span>件数</span>
                    <span>重量</span>
                    <span>计价数量</span>
                    <span>单价</span>
                    {showSalesPrice && <span title={draft.docType === 'delivery_note'
                      ? '本行销售单价（元/吨）：填了就按它算销售金额，优先于网价/毛利率口径；清空恢复自动'
                      : '按批次默认口径由采购价推算，可改；清空恢复自动'}>销售价</span>}
                    <span>金额</span>
                    {editing && <span />}
                  </div>
                  {items.map((item, index) => (
                    <div className={`data-item-row ${editing ? 'editing' : ''} ${showSalesPrice ? 'with-sales' : ''}`} key={item.id}>
                      <span className="material-cell">
                        {editing ? (
                          <>
                            <input aria-label={`第${index + 1}行材料`} value={item.materialName} onChange={(event) => updateItem(index, 'materialName', event.target.value)} />
                            <input aria-label={`第${index + 1}行规格`} value={item.spec} onChange={(event) => updateItem(index, 'spec', event.target.value)} />
                          </>
                        ) : <><strong>{item.materialName || '未标注材料'}</strong><small>{item.spec || '未标注规格'}</small></>}
                      </span>
                      <span>
                        {editing ? (
                          <input aria-label={`第${index + 1}行产地`} placeholder="产地" value={item.origin} onChange={(event) => updateItem(index, 'origin', event.target.value)} />
                        ) : (item.origin || '-')}
                      </span>
                      {(['packageQuantity', 'totalWeight', 'pricingQuantity'] as const).map((field) => {
                        const unitField = field === 'packageQuantity' ? 'packageUnit' : field === 'totalWeight' ? 'weightUnit' : 'pricingUnit';
                        return (
                          <span className="quantity-cell" key={field}>
                            {editing ? (
                              <>
                                <input type="number" step="any" value={item[field]} onChange={(event) => updateItem(index, field, event.target.value)} />
                                <input value={item[unitField]} onChange={(event) => updateItem(index, unitField, event.target.value)} />
                              </>
                            ) : <>{item[field] || '-'} {item[unitField]}</>}
                          </span>
                        );
                      })}
                      <span>{editing ? <input type="number" step="any" value={item.unitPrice} onChange={(event) => updateItem(index, 'unitPrice', event.target.value)} /> : money(Number(item.unitPrice))}</span>
                      {showSalesPrice && (
                        <span>
                          {editing ? (
                            <input
                              type="number"
                              step="any"
                              min="0"
                              aria-label={`第${index + 1}行销售价`}
                              placeholder={item.salesUnitPriceAuto ? `自动 ${item.salesUnitPriceAuto}` : '自动'}
                              value={item.salesUnitPrice}
                              onChange={(event) => updateItem(index, 'salesUnitPrice', event.target.value)}
                            />
                          ) : (
                            <>
                              {salesPriceText(item) === '' ? '-' : money(Number(salesPriceText(item)))}
                              {item.salesUnitPrice !== '' && <small className="sales-price-tag manual">手动</small>}
                              {item.salesUnitPrice === '' && salesPriceText(item) !== '' && (
                                <small className="sales-price-tag">自动</small>
                              )}
                            </>
                          )}
                        </span>
                      )}
                      <span>{editing ? <input type="number" step="any" value={item.amount} onChange={(event) => updateItem(index, 'amount', event.target.value)} /> : money(Number(item.amount))}</span>
                      {editing && (
                        <span className="data-item-action">
                          <button aria-label={`删除第${index + 1}行`} onClick={() => setItems((current) => current.filter((_, itemIndex) => itemIndex !== index))}>
                            <Trash2 size={14} />
                          </button>
                        </span>
                      )}
                    </div>
                  ))}
                </div>
                {editing && items.length > 0 && (
                  <div className="data-items-total">
                    <span>明细合计 <strong>{money(itemTotal)}</strong></span>
                    <button onClick={() => setDraft({ ...draft, amount: String(itemTotal) })}>同步业务金额</button>
                  </div>
                )}
              </div>
            )}
            {amountDecisionOpen && (
              <div className="amount-discrepancy-card drawer-amount-discrepancy">
                <div>
                  <AlertCircle size={17} />
                  <span>
                    <strong>业务金额与明细合计不一致</strong>
                    <small>台账将以材料明细合计为准，请确认保存方式。</small>
                  </span>
                </div>
                <dl>
                  <span><dt>业务金额</dt><dd>{money(Number(draft.amount || 0))}</dd></span>
                  <span><dt>明细合计</dt><dd>{money(itemTotal)}</dd></span>
                  <span><dt>差额</dt><dd>{money(Number(draft.amount || 0) - itemTotal)}</dd></span>
                </dl>
                <div className="chat-card-actions">
                  <button className="secondary-button" disabled={busy} onClick={() => void save('keep_source')}>保留金额并登记差异</button>
                  <button className="primary-button" disabled={busy} onClick={() => void save('sync_items')}>同步为明细合计</button>
                </div>
              </div>
            )}
          </>
        ) : (
          <div className="source-detail-view">
            <div className="source-preview-large">
              {['manual', 'text', 'sms'].includes(document.source) ? (
                <div className="original-text-preview">
                  <small>用户输入原文</small>
                  <pre>{document.sourceText || '暂无原文记录'}</pre>
                </div>
              ) : document.source === 'image' && accessUrl ? (
                <img src={accessUrl} alt={document.fileName || document.docCode || '原始资料'} />
              ) : (
                <div className="document-file-preview">
                  {document.source === 'excel' ? <FileSpreadsheet size={38} /> : <FileText size={38} />}
                  <strong>{document.fileName || '原始业务资料'}</strong>
                  <small>{previewError || (accessUrl ? '点击下方按钮下载查看' : '正在获取资料...')}</small>
                  {accessUrl && (
                    <a href={accessUrl} target="_blank" rel="noreferrer">
                      <ArrowDownToLine size={15} />下载文件
                    </a>
                  )}
                </div>
              )}
            </div>
            <dl className="source-meta">
              <div><dt>资料分类</dt><dd>{typeLabel(document.docType)}</dd></div>
              <div><dt>资料格式</dt><dd>{sourceLabel(document.source)}</dd></div>
              <div><dt>文件名称</dt><dd>{document.fileName || '手动输入记录'}</dd></div>
              <div><dt>识别置信度</dt><dd>{document.confidence || 0}%</dd></div>
            </dl>
          </div>
        )}

        <div className="drawer-actions data-drawer-actions">
          {section === 'data' && (editing ? (
            <>
              <button className="secondary-button" disabled={busy} onClick={() => setEditing(false)}>取消修改</button>
              <button className="primary-button" disabled={busy} onClick={() => void save()}><Check size={16} />保存并同步更新</button>
            </>
          ) : (
            <>
              <button className="secondary-button" onClick={() => setEditing(true)}><PencilLine size={16} />修改入库数据</button>
              {!applied && <button className="primary-button" disabled={busy} onClick={() => onConfirm(document.id)}><Check size={16} />确认并入库</button>}
              <button
                className="danger-button"
                disabled={busy}
                onClick={() => onDelete(document.id, document.docCode || document.fileName || '未编号业务数据')}
              >
                <Trash2 size={16} />删除
              </button>
            </>
          ))}
        </div>
      </aside>
    </div>
  );
}

export function DataCenterPage({
  records,
  projects,
  busy,
  onNavigate,
  onEdit,
  onConfirm,
  onDelete
}: {
  records: ApiBusinessDataRecord[];
  projects: Project[];
  busy: boolean;
  onNavigate: (page: PageKey) => void;
  onEdit: (documentId: string, edits: Record<string, unknown>) => Promise<void>;
  onConfirm: (documentId: string) => Promise<void>;
  onDelete: (documentId: string, label: string) => void;
}) {
  const [type, setType] = useState('');
  const [query, setQuery] = useState('');
  const [activeId, setActiveId] = useState('');
  const deferredQuery = useDeferredValue(query);
  const activeRecord = records.find((item) => item.document.id === activeId) || null;
  const projectFor = (id: string) => projects.find((item) => item.id === id);
  const batchFor = (document: ApiDocument) =>
    projectFor(document.projectId)?.batches.find((item) => item.id === document.batchId);
  const filtered = records.filter(({ document, items }) => {
    const matchesType = !type || document.docType === type;
    const keyword = deferredQuery.trim().toLowerCase();
    const materialText = items.map((item) => `${item.materialName}${item.spec}`).join('');
    return matchesType && (!keyword || `${document.docCode || ''}${document.fileName || ''}${document.supplierName || ''}${materialText}`.toLowerCase().includes(keyword));
  });
  return (
    <>
      <div className="standard-page data-center-page page-enter">
        <div className="page-title-row">
          <div>
            <h2>数据中心</h2>
            <p>查看、修改已入库的业务数据，并核对对应原始资料。</p>
          </div>
          <button className="primary-button" onClick={() => onNavigate('workspace')}>
            <MessageSquareText size={17} />通过智能助理导入
          </button>
        </div>

        <section className="panel data-center-panel">
          <div className="data-center-toolbar">
            <div className="tab-list data-type-tabs">
              {DOC_TYPES.map(([label, value]) => (
                <button
                  key={label}
                  className={type === value ? 'active' : ''}
                  onClick={() => startTransition(() => setType(value))}
                >
                  {label}
                </button>
              ))}
            </div>
            <label className="search-input">
              <Search size={16} />
              <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索单号、单位、材料" />
            </label>
          </div>

          <div className="table-scroll">
            <table className="data-table business-data-table">
              <thead>
                <tr>
                  <th>业务类型</th>
                  <th>项目 / 批次</th>
                  <th>往来单位</th>
                  <th>日期</th>
                  <th>材料明细</th>
                  <th>业务金额</th>
                  <th>入库状态</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {filtered.map(({ document, items }) => (
                  <tr key={document.id} onClick={() => setActiveId(document.id)}>
                    <td><span className="data-record-kind"><ClipboardCheck size={14} />{typeLabel(document.docType)}</span><small>{document.docCode || '未编号'}</small></td>
                    <td><strong>{projectFor(document.projectId)?.name || '-'}</strong><small>{batchFor(document)?.name || '-'}</small></td>
                    <td>{document.supplierName || '待补充'}</td>
                    <td>{document.docDate || '-'}</td>
                    <td><strong>{items[0]?.materialName || '无材料明细'}</strong><small>{items.length > 1 ? `等 ${items.length} 条明细` : items[0]?.spec || '关键字段数据'}</small></td>
                    <td className="amount-cell">{money(document.amount)}</td>
                    <td><span className={`status-badge ${statusTone(document)}`}>{statusLabel(document)}</span></td>
                    <td><ChevronRight size={16} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {filtered.length === 0 && (
            <div className="empty-state">
              <AlertCircle size={30} />
              <strong>没有符合条件的业务数据</strong>
              <span>可以切换分类，或者通过智能助理继续导入。</span>
            </div>
          )}
        </section>
      </div>

      <DataRecordDrawer
        record={activeRecord}
        project={activeRecord ? projectFor(activeRecord.document.projectId) : undefined}
        busy={busy}
        onClose={() => setActiveId('')}
        onEdit={onEdit}
        onConfirm={onConfirm}
        onDelete={onDelete}
      />
    </>
  );
}
