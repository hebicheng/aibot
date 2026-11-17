import {
  AlertCircle,
  Banknote,
  Bot,
  Check,
  CheckCircle2,
  ChevronRight,
  ClipboardCheck,
  FileSpreadsheet,
  FilePlus2,
  FileSearch,
  FileText,
  FolderKanban,
  Gauge,
  Image as ImageIcon,
  PackageCheck,
  Paperclip,
  Plus,
  ReceiptText,
  Search,
  Send,
  Sparkles,
  TableProperties,
  Trash2,
  Truck,
  X
} from 'lucide-react';
import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ClipboardEvent,
  type FormEvent,
  type KeyboardEvent
} from 'react';
import {
  api,
  type ApiChatMessage,
  type ClarificationCardPayload,
  type DocumentCardPayload,
  type DuplicateCardPayload,
  type OperationResultPayload,
  type ReconciliationExportCardPayload
} from './api';
import type { Batch, BusinessDocument, PageKey, Project } from './types';

const money = (value: number) =>
  new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(value || 0);

const time = (value: string) =>
  value
    ? new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date(value))
    : '刚刚';

const quantity = (value?: number | null) =>
  new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 3 }).format(value || 0);

const clipboardImageExtension = (mimeType: string) => {
  const subtype = mimeType.split('/')[1]?.toLowerCase();
  if (subtype === 'jpeg') return 'jpg';
  return ['png', 'jpg', 'webp', 'gif'].includes(subtype) ? subtype : 'png';
};

function isDocumentCard(payload: Record<string, unknown>): payload is DocumentCardPayload {
  return payload.cardType === 'document_review' && typeof payload.cardId === 'string';
}

function isDuplicateCard(payload: Record<string, unknown>): payload is DuplicateCardPayload {
  return payload.cardType === 'duplicate_review' && typeof payload.cardId === 'string';
}

function isOperationResult(payload: Record<string, unknown>): payload is OperationResultPayload {
  return payload.cardType === 'operation_result'
    && typeof payload.reference === 'object'
    && payload.reference !== null;
}

function isReconciliationExportCard(
  payload: Record<string, unknown>
): payload is ReconciliationExportCardPayload {
  return payload.cardType === 'reconciliation_export'
    && typeof payload.projectId === 'string';
}

/** 统一对账单导出卡片：日期始终可编辑（按来源预填），汇总按当前日期实时刷新 */
function ReconciliationExportCard({ card }: { card: ReconciliationExportCardPayload }) {
  const defaultStart = card.start || new Date().toISOString().slice(0, 10);
  const defaultEnd = card.end || new Date().toISOString().slice(0, 10);
  const [start, setStart] = useState(defaultStart);
  const [end, setEnd] = useState(defaultEnd);
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState('');
  // 材料筛选：按批次材料导出（空串 = 全部材料），选项来自项目各批次填写的材料
  const [material, setMaterial] = useState('');
  const [materialOptions, setMaterialOptions] = useState<string[]>([]);
  const [summary, setSummary] = useState<{
    projectName?: string;
    customerName?: string;
    deliveries: number;
    salesAmount: number;
  } | null>(card.summary ? {
    projectName: card.projectName,
    customerName: card.customerName,
    deliveries: card.summary.deliveries,
    salesAmount: card.summary.salesAmount
  } : null);
  const [summarizing, setSummarizing] = useState(false);
  const sourceLabel = card.source === 'weekly'
    ? '每周对账提醒'
    : card.source === 'user_reminder'
      ? '预约的对账单'
      : '对账单导出';

  useEffect(() => {
    let cancelled = false;
    api.reconciliationMaterials(card.projectId)
      .then((options) => {
        if (!cancelled) setMaterialOptions(options);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [card.projectId]);

  // 汇总实时刷新：日期范围、材料或项目变化即重取（后端按送货单日期统计张数与对账金额）
  useEffect(() => {
    if (!start || !end || start > end) return;
    let cancelled = false;
    setSummarizing(true);
    api.getReconciliationSummary(card.projectId, start, end, material || undefined)
      .then((data) => {
        if (!cancelled) setSummary(data);
      })
      .catch(() => undefined)
      .finally(() => {
        if (!cancelled) setSummarizing(false);
      });
    return () => {
      cancelled = true;
    };
  }, [card.projectId, start, end, material]);

  const download = async () => {
    if (start > end) {
      setError('开始日期不能晚于结束日期');
      return;
    }
    setDownloading(true);
    setError('');
    try {
      await api.downloadReconciliation(card.projectId, start, end, material || undefined);
    } catch (err) {
      setError(err instanceof Error ? err.message : '对账单导出失败，请稍后重试');
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="chat-reconciliation-card">
      <div className="chat-card-heading">
        <span className="chat-card-icon supply">
          <FileSpreadsheet size={19} />
        </span>
        <span>
          <strong>{sourceLabel}</strong>
          <small>
            {summary
              ? (summarizing ? '正在统计...' : `${summary.projectName || '当前项目'} · ${material || '全部材料'} · 送货单 ${summary.deliveries} 张 · 对账金额 ${money(summary.salesAmount)}`)
              : '按送货单日期取明细，累计数据自动统计至结束日'}
          </small>
        </span>
      </div>
      <div className="reconciliation-card-form">
        <label>
          <span>开始日期</span>
          <input type="date" value={start} onChange={(event) => setStart(event.target.value)} />
        </label>
        <label>
          <span>结束日期</span>
          <input type="date" value={end} onChange={(event) => setEnd(event.target.value)} />
        </label>
        <label>
          <span>材料</span>
          <select value={material} onChange={(event) => setMaterial(event.target.value)}>
            <option value="">全部材料</option>
            {materialOptions.map((option) => (
              <option key={option} value={option}>{option}</option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className="primary-button"
          disabled={downloading}
          onClick={() => void download()}
        >
          <FileSpreadsheet size={15} />{downloading ? '正在导出...' : '下载对账单'}
        </button>
      </div>
      {error && <small className="reconciliation-card-error">{error}</small>}
    </div>
  );
}

function OperationResultCard({ result }: { result: OperationResultPayload }) {
  const reference = result.reference;
  return (
    <div className="chat-operation-result">
      <div className="operation-result-heading">
        <span><CheckCircle2 size={17} /></span>
        <div>
          <strong>{result.summary}</strong>
          <small>本次操作对应以下业务资料</small>
        </div>
      </div>
      <div className="operation-reference">
        <span>
          <small>{reference.docTypeLabel}</small>
          <strong>{reference.docCode || reference.fileName || '未编号资料'}</strong>
        </span>
        <span>
          <small>{reference.supplierName || '未标注往来单位'}</small>
          <strong>{money(reference.amount)}</strong>
        </span>
      </div>
    </div>
  );
}

function DuplicateReviewCard({
  card,
  busy,
  onDecision
}: {
  card: DuplicateCardPayload;
  busy: boolean;
  onDecision: (documentId: string, decision: 'continue' | 'skip') => Promise<void>;
}) {
  const reasonText = {
    same_hash: '附件内容与已有文件完全一致',
    same_filename: '附件名称与已有文件相同',
    same_business: '材料、规格、数量和金额与已有单据高度一致'
  }[card.reason];
  const skipped = card.status === 'duplicate_skipped';
  return (
    <div className="chat-recognition-card duplicate-card">
      <div className="chat-card-heading">
        <span className="chat-card-icon warning"><AlertCircle size={19} /></span>
        <span>
          <strong>发现疑似重复单据</strong>
          <small>{reasonText}，系统不会自动写入台账。</small>
        </span>
        <span className={`status-badge ${skipped ? 'success' : 'warning'}`}>{skipped ? '已归档' : '等待选择'}</span>
      </div>
      <div className="duplicate-compare">
        <span>
          <small>本次导入</small>
          <strong>{card.document.fileName || card.document.docCode || '当前单据'}</strong>
          <em>{money(card.document.amount)}</em>
        </span>
        <ChevronRight size={17} />
        <span>
          <small>已有单据</small>
          <strong>{card.existingDocument?.fileName || card.existingDocument?.docCode || '历史单据'}</strong>
          <em>{money(card.existingDocument?.amount || 0)}</em>
        </span>
      </div>
      <div className="chat-card-note">
        <AlertCircle size={15} />
        文件名相同只是弱证据；文件哈希相同是完全重复；业务内容相同也可能是两次真实采购，因此最终由你确认。
      </div>
      <div className="chat-card-actions">
        <button
          className="secondary-button"
          disabled={busy || skipped}
          onClick={() => onDecision(card.cardId, 'skip')}
        >
          {skipped ? '已作为重复归档' : '视为重复，不再导入'}
        </button>
        <button
          className="primary-button"
          disabled={busy || skipped}
          onClick={() => onDecision(card.cardId, 'continue')}
        >
          仍然作为新单据导入
        </button>
      </div>
    </div>
  );
}

// 追问选项/识别类型可能是 AI 给的 intent 或 docType 英文名，显示时统一转中文（历史消息兜底）
const DOC_TYPE_LABELS: Record<string, string> = {
  supply_order: '采购单',
  delivery_note: '送货单',
  transport_note: '运输费用',
  payment_request: '请款单',
  payment: '付款记录',
  receipt: '收款记录',
  purchase_invoice: '进项发票',
  sales_invoice: '销项发票',
  sales_settlement: '销售结算单',
  invoice_unknown: '待确认发票',
  evidence_only: '原始凭证',
  unknown: '未识别类型'
};
const optionLabel = (option: string) => {
  const docType = option.endsWith('_card') ? option.slice(0, -'_card'.length) : option;
  return DOC_TYPE_LABELS[docType] || option;
};

function ClarificationCard({
  payload,
  busy,
  onAnswer,
  onReupload
}: {
  payload: ClarificationCardPayload;
  busy: boolean;
  onAnswer: (documentId: string, answer: string) => Promise<void>;
  onReupload: () => void;
}) {
  const answered = Boolean(payload.answered);
  const documentId = payload.documentId || '';
  const options = payload.options?.length
    ? payload.options
    : ['按用户说明处理', '按识别结果处理', '重新上传并说明'];
  return (
    <div className="chat-recognition-card clarification-card">
      <div className="chat-card-heading">
        <span className="chat-card-icon warning"><AlertCircle size={19} /></span>
        <span>
          <strong>需要你确认处理意图</strong>
          <small>
            {answered
              ? '该问题已回复，原始资料仍然保留，可继续补充。'
              : documentId
                ? '已保留原始资料，选择后无需重新上传。'
                : '用户说明与单据内容存在冲突时，AI 不会直接写入台账。'}
          </small>
        </span>
        <span className={`status-badge ${answered ? 'success' : 'warning'}`}>
          {answered ? '已回复' : '待回复'}
        </span>
      </div>
      <p className="clarification-question">{payload.question || '请确认这份资料应该按哪种单据处理？'}</p>
      {(payload.requestedType || payload.detectedType) && (
        <div className="intent-conflict">
          <span><small>你的说明</small><strong>{optionLabel(payload.requestedType || '-')}</strong></span>
          <span><small>内容识别</small><strong>{optionLabel(payload.detectedType || '-')}</strong></span>
        </div>
      )}
      {documentId ? (
        <div className="chat-card-actions">
          {options.map((option) => (
            <button
              key={option}
              className="secondary-button"
              disabled={busy || answered}
              onClick={() => void onAnswer(documentId, option)}
            >
              {optionLabel(option)}
            </button>
          ))}
        </div>
      ) : (
        <div className="chat-card-actions">
          <button className="secondary-button" disabled={busy} onClick={onReupload}>
            重新上传资料并说明
          </button>
        </div>
      )}
      {!documentId && (
        <div className="chat-card-note">
          <AlertCircle size={15} />
          这条追问没有关联资料，请重新上传文件并说明处理方式。
        </div>
      )}
    </div>
  );
}

function MarketQuoteCard({
  message,
  busy,
  onConfirm,
  onDiscard
}: {
  message: ApiChatMessage;
  busy: boolean;
  onConfirm: (messageId: string) => Promise<void>;
  onDiscard: (messageId: string) => Promise<void>;
}) {
  type QuoteRow = {
    name: string | null;
    spec: string;
    grade: string | null;
    origin: string | null;
    price: number | null;
    priceChange: number | null;
  };
  type QuoteDraft = {
    source: string;
    city: string;
    quoteDate: string;
    rows: QuoteRow[];
  };
  const payload = message.payload as {
    rows?: QuoteRow[];
    city?: string;
    quoteDate?: string;
    source?: string;
    answered?: string;
  };
  const answered = Boolean(payload.answered);
  // 已确认内容（本地态）：展示用；编辑从它出发，保存后同步——避免父级消息列表不刷新时展示旧值
  const [committed, setCommitted] = useState<QuoteDraft>(() => ({
    source: payload.source || '',
    city: payload.city || '',
    quoteDate: payload.quoteDate || '',
    rows: (payload.rows || []).map((row) => ({ ...row }))
  }));
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<QuoteDraft>(committed);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  const beginEditing = () => {
    setDraft({
      source: committed.source,
      city: committed.city,
      quoteDate: committed.quoteDate,
      rows: committed.rows.map((row) => ({ ...row }))
    });
    setEditing(true);
  };
  const setRow = (index: number, key: keyof QuoteRow, value: string) => {
    setDraft((current) => ({
      ...current,
      rows: current.rows.map((row, rowIndex) => (
        rowIndex === index ? { ...row, [key]: value === '' ? null : value } : row
      ))
    }));
  };
  const removeRow = (index: number) => {
    setDraft((current) => ({ ...current, rows: current.rows.filter((_, i) => i !== index) }));
  };
  const addRow = () => {
    setDraft((current) => ({
      ...current,
      rows: [...current.rows, { name: null, spec: '', grade: null, origin: null, price: null, priceChange: null }]
    }));
  };
  const save = async () => {
    setSaving(true);
    try {
      const next: QuoteDraft = {
        ...draft,
        rows: draft.rows.filter((row) => row.spec || row.name || (row.price != null))
      };
      await api.updateQuoteCard(message.id, { ...next });
      setCommitted(next);
      setSaved(true);
      setEditing(false);
    } finally {
      setSaving(false);
    }
  };
  const changeText = (value: number | null) =>
    value == null ? '-' : `${value > 0 ? '+' : ''}${value}`;

  // 识别日期核对提示（2026-11-12）：补录历史行情是正常业务，不拦截导入；
  // 但年份不是今年、或日期晚于今天时，在卡上显著提示，让用户在确认前就核对。
  const dateNotice = (() => {
    if (answered) return '';
    const text = (editing ? draft.quoteDate : committed.quoteDate)?.trim();
    if (!text) return '';
    const now = new Date();
    const todayText = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    if (text > todayText) {
      return `识别日期 ${text} 晚于今天（${todayText}），截图日期可能没认对，确认前请核对。`;
    }
    const year = Number(text.slice(0, 4));
    if (Number.isFinite(year) && year !== now.getFullYear()) {
      return `识别日期 ${text} 不是今年（${now.getFullYear()} 年）。补录历史行情没问题；若是年份认错了，点「编辑」改日期再确认。`;
    }
    return '';
  })();

  return (
    <div className="chat-recognition-card">
      <div className="chat-card-heading">
        <span className="chat-card-icon supply">
          <TableProperties size={19} />
        </span>
        <span>
          <strong>识别到行情价格截图</strong>
          <small>
            {answered
              ? payload.answered === 'confirmed'
                ? '行情已写入行情库（权威价），相关批次已重算。'
                : '已放弃本次导入，行情库未变更。'
              : `已识别 ${committed.rows.length} 行报价 · 确认导入后写入行情库（权威价），相关批次自动重算`}
          </small>
        </span>
        <span className={`status-badge ${answered ? 'success' : 'warning'}`}>
          {answered ? (payload.answered === 'confirmed' ? '已导入' : '已放弃') : '待确认'}
        </span>
      </div>

      {editing ? (
        <div className="chat-edit-grid">
          <label><small>来源</small><input value={draft.source} placeholder="我的钢铁" onChange={(event) => setDraft({ ...draft, source: event.target.value })} /></label>
          <label><small>城市</small><input value={draft.city} placeholder="杭州" onChange={(event) => setDraft({ ...draft, city: event.target.value })} /></label>
          <label><small>行情日期</small><input type="date" value={draft.quoteDate} onChange={(event) => setDraft({ ...draft, quoteDate: event.target.value })} /></label>
        </div>
      ) : (
        <div className="chat-field-grid">
          <span><small>来源</small><strong>{committed.source || '未识别'}</strong></span>
          <span><small>城市</small><strong>{committed.city || '未识别'}</strong></span>
          <span><small>行情日期</small><strong>{committed.quoteDate || '未识别'}</strong></span>
          <span><small>报价行数</small><strong>{committed.rows.length} 行</strong></span>
        </div>
      )}

      <div className={`chat-material-section ${editing ? 'editing' : ''}`}>
        {editing && (
          <div className="material-editor-heading">
            <span>
              <strong>行情明细</strong>
              <small>点击字段直接修改，可增删行；保存后点「确认导入行情」落库。</small>
            </span>
            <button type="button" onClick={addRow}>
              <Plus size={14} />新增一行
            </button>
          </div>
        )}
        <div className="chat-material-table">
          <div className={`material-row head quote-layout ${editing ? 'with-action' : ''}`}>
            <span>品名</span>
            <span>规格</span>
            <span>材质</span>
            <span>钢厂/产地</span>
            <span>价格（元/吨）</span>
            <span>涨跌</span>
            {editing && <span>操作</span>}
          </div>
          {editing ? draft.rows.map((row, index) => (
            <div className="material-row editable quote-layout with-action" key={index}>
              <span><input aria-label={`第${index + 1}行品名`} placeholder="品名" value={row.name || ''} onChange={(event) => setRow(index, 'name', event.target.value)} /></span>
              <span><input aria-label={`第${index + 1}行规格`} placeholder="规格" value={row.spec || ''} onChange={(event) => setRow(index, 'spec', event.target.value)} /></span>
              <span><input aria-label={`第${index + 1}行材质`} placeholder="材质" value={row.grade || ''} onChange={(event) => setRow(index, 'grade', event.target.value)} /></span>
              <span><input aria-label={`第${index + 1}行钢厂`} placeholder="钢厂" value={row.origin || ''} onChange={(event) => setRow(index, 'origin', event.target.value)} /></span>
              <span><input type="number" min="0" step="any" aria-label={`第${index + 1}行价格`} placeholder="价格" value={row.price ?? ''} onChange={(event) => setRow(index, 'price', event.target.value)} /></span>
              <span><input type="number" step="any" aria-label={`第${index + 1}行涨跌`} placeholder="涨跌" value={row.priceChange ?? ''} onChange={(event) => setRow(index, 'priceChange', event.target.value)} /></span>
              <span className="material-row-action">
                <button type="button" aria-label={`删除第${index + 1}行`} onClick={() => removeRow(index)}>
                  <Trash2 size={14} />
                </button>
              </span>
            </div>
          )) : committed.rows.map((row, index) => (
            <div className="material-row quote-layout" key={index}>
              <span>{row.name || '-'}</span>
              <span>{row.spec || '-'}</span>
              <span>{row.grade || '-'}</span>
              <span>{row.origin || '-'}</span>
              <span>{row.price == null ? '-' : money(row.price)}</span>
              <span>{changeText(row.priceChange)}</span>
            </div>
          ))}
          {!editing && committed.rows.length === 0 && (
            <div className="material-empty-editor">
              <strong>没有识别出行</strong>
              <span>点「编辑」手工补充行情行，或放弃本次导入。</span>
            </div>
          )}
        </div>
      </div>

      {dateNotice && (
        <div className="chat-card-note">
          <AlertCircle size={15} />
          {dateNotice}
        </div>
      )}

      <div className="chat-card-note">
        <AlertCircle size={15} />
        确认导入后写入行情库并标记为权威价（与爬取数据并存、取价时优先），相关批次自动重锁重算；人工绑定与人工销售价不动。
      </div>

      <div className="chat-card-actions">
        {answered ? null : editing ? (
          <>
            <button className="secondary-button" disabled={busy || saving} onClick={() => setEditing(false)}>取消修改</button>
            <button className="primary-button" disabled={busy || saving} onClick={() => void save()}>
              {saved ? '已保存' : '保存修改'}
            </button>
          </>
        ) : (
          <>
            <button className="secondary-button" disabled={busy} onClick={beginEditing}>编辑</button>
            <button className="primary-button" disabled={busy} onClick={() => void onConfirm(message.id)}>
              确认导入行情
            </button>
            <button className="secondary-button" disabled={busy} onClick={() => void onDiscard(message.id)}>
              放弃
            </button>
          </>
        )}
      </div>
    </div>
  );
}

function DocumentReviewCard({
  card,
  project,
  batch,
  busy,
  onConfirm,
  onEdit
}: {
  card: DocumentCardPayload;
  project: Project;
  batch: Batch;
  busy: boolean;
  onConfirm: (documentId: string) => Promise<void>;
  onEdit: (documentId: string, edits: Record<string, unknown>) => Promise<void>;
}) {
  const document = card.document;
  const applied = card.status === 'applied';
  const queued = card.status === 'queued';
  const [editing, setEditing] = useState(false);
  const [amountDecisionOpen, setAmountDecisionOpen] = useState(false);
  const [draft, setDraft] = useState({
    docType: document.docType,
    supplierName: document.supplierName || '',
    docCode: document.docCode || '',
    docDate: document.docDate || '',
    amount: String(document.amount || ''),
    taxRate: document.taxRate || '',
    netAmount: String(document.netAmount || ''),
    taxAmount: String(document.taxAmount || ''),
    expenseCategory: document.expenseCategory || 'material',
    // 备注/用途：AI 识别或人工补录（请款卡片展示编辑）
    remark: document.remark || '',
    // 送货单（销售侧发货单）：承运单位与两种运费为选填人工补录字段
    carrierName: document.carrierName || '',
    transportFee: document.transportFee != null ? String(document.transportFee) : '',
    salesFreight: document.salesFreight != null ? String(document.salesFreight) : ''
  });
  const [itemDrafts, setItemDrafts] = useState(() => document.items.map((item) => ({
    id: item.id || `draft-${Date.now()}`,
    materialName: item.materialName || '',
    spec: item.spec || '',
    origin: item.origin || '',
    packageQuantity: String(item.packageQuantity || ''),
    packageUnit: item.packageUnit || '',
    unitWeight: String(item.unitWeight || ''),
    totalWeight: String(item.totalWeight || ''),
    weightUnit: item.weightUnit || '',
    pricingQuantity: String(item.pricingQuantity || item.quantity || ''),
    pricingUnit: item.pricingUnit || item.unit || '',
    unitPrice: String(item.unitPrice || ''),
    // 采购单销售价：只有人工指定过才回填（空 = 按批次口径自动推算）；
    // 送货单：行上的销售单价就是该行实际定价，直接回填
    salesUnitPrice: document.docType === 'delivery_note'
      ? (item.salesUnitPrice == null ? '' : String(item.salesUnitPrice))
      : (item.salesUnitPriceManual ? String(item.salesUnitPrice ?? '') : ''),
    salesUnitPriceAuto: item.salesUnitPriceAuto != null ? String(item.salesUnitPriceAuto) : '',
    amount: String(item.amount || '')
  })));
  const isSupply = (editing ? draft.docType : document.docType) === 'supply_order';
  const isTransport = (editing ? draft.docType : document.docType) === 'transport_note';
  const isDelivery = (editing ? draft.docType : document.docType) === 'delivery_note';
  // 销售价列：采购单填的是给客户报价的价，送货单填的是这一行实际结算价，两处都能改（2026-11-08）
  const showSalesPrice = isSupply || isDelivery;
  const currentDocType = editing ? draft.docType : document.docType;
  const isPayment = currentDocType === 'payment';
  const isPaymentRequest = currentDocType === 'payment_request';
  const isPurchaseInvoice = ['purchase_invoice', 'invoice'].includes(currentDocType);
  const isSalesInvoice = currentDocType === 'sales_invoice';
  const isInvoice = isPurchaseInvoice || isSalesInvoice || currentDocType === 'invoice_unknown';
  const isFinancial = isPayment || isPaymentRequest || isInvoice;
  const evidenceOnly = currentDocType === 'evidence_only';
  const archiveOnly = ['sales_settlement', 'evidence_only'].includes(currentDocType);
  const supportsItems = ['supply_order', 'delivery_note', 'purchase_invoice', 'sales_invoice', 'invoice_unknown', 'invoice']
    .includes(editing ? draft.docType : document.docType);
  const partyLabel = isSupply
    ? '供应商'
    : isTransport
      ? '承运单位'
      : isDelivery
        ? '客户（收货单位）'
        : isPayment
          ? '收款单位'
          : isPurchaseInvoice
            ? '销售方'
            : isSalesInvoice
              ? '购买方'
              : isInvoice
                ? '往来单位'
            : '往来单位';
  const dateLabel = isPayment ? '付款日期' : isInvoice ? '开票日期' : '单据日期';
  // 送货单 = 销售侧发货单：金额是销售金额；单据上标注的运输费用（transportFee）另行归集
  const amountLabel = isTransport
    ? '运输金额'
    : isDelivery
      ? '销售金额'
      : isPayment
        ? '付款金额'
        : isInvoice
          ? '开票金额'
          : '单据总额';
  const itemTotal = itemDrafts.reduce((sum, item) => sum + Number(item.amount || 0), 0);
  const amountMismatch = isSupply
    && itemDrafts.length > 0
    && Math.abs(Number(draft.amount || 0) - itemTotal) > 0.01;
  const missingFieldLabels = (card.missingFields || []).map((field) => ({
    docDate: dateLabel,
    supplierName: partyLabel,
    amount: amountLabel,
    invoiceDirection: '发票方向',
    invoiceEvidence: '发票关键字段',
    settlementDirection: '结算归属',
    amountMismatchDecision: '金额差异处理方式',
    items: '材料明细'
  }[field] || field));
  // 必填标志：requiredFields 由后端按业务类型下发，missingFields 表示当前仍缺失
  const requiredFields = card.document.requiredFields || [];
  const missingSet = new Set(card.missingFields || []);
  const isRequired = (field: string) => requiredFields.includes(field);
  const isMissing = (field: string) => missingSet.has(field);
  const requiredMark = (field: string) =>
    isRequired(field) ? <em className="required-mark" title="必填">*</em> : null;
  const missingClass = (field: string) =>
    isRequired(field) && isMissing(field) ? 'field-missing' : '';
  const emptyRequiredClass = (field: string, filled: boolean) =>
    isRequired(field) && !filled ? 'field-required-empty' : '';
  const beginEditing = () => {
    setDraft({
      docType: document.docType,
      supplierName: document.supplierName || '',
      docCode: document.docCode || '',
      docDate: document.docDate || '',
      amount: String(document.amount || ''),
      taxRate: document.taxRate || '',
      netAmount: String(document.netAmount || ''),
      taxAmount: String(document.taxAmount || ''),
      expenseCategory: document.expenseCategory || 'material',
      remark: document.remark || '',
      carrierName: document.carrierName || '',
      transportFee: document.transportFee != null ? String(document.transportFee) : '',
      salesFreight: document.salesFreight != null ? String(document.salesFreight) : ''
    });
    setItemDrafts(document.items.map((item) => ({
      id: item.id || `draft-${Date.now()}-${Math.random()}`,
      materialName: item.materialName || '',
      spec: item.spec || '',
      origin: item.origin || '',
      packageQuantity: String(item.packageQuantity || ''),
      packageUnit: item.packageUnit || '',
      unitWeight: String(item.unitWeight || ''),
      totalWeight: String(item.totalWeight || ''),
      weightUnit: item.weightUnit || '',
      pricingQuantity: String(item.pricingQuantity || item.quantity || ''),
      pricingUnit: item.pricingUnit || item.unit || '',
      unitPrice: String(item.unitPrice || ''),
      // 采购单销售价：只有人工指定过才回填（空 = 按批次口径自动推算）；
      // 送货单：行上的销售单价就是该行实际定价，直接回填
      salesUnitPrice: document.docType === 'delivery_note'
        ? (item.salesUnitPrice == null ? '' : String(item.salesUnitPrice))
        : (item.salesUnitPriceManual ? String(item.salesUnitPrice ?? '') : ''),
      salesUnitPriceAuto: item.salesUnitPriceAuto != null ? String(item.salesUnitPriceAuto) : '',
      amount: String(item.amount || '')
    })));
    setEditing(true);
  };
  // 金额留空时不要提交 0（0 会覆盖单据实际金额）；空 → null。
  const draftAmount = draft.amount === '' ? null : Number(draft.amount);
  const updateItemDraft = (index: number, field: string, value: string) => {
    setItemDrafts((items) => items.map((item, itemIndex) => {
      if (itemIndex !== index) return item;
      const next = { ...item, [field]: value };
      if (field === 'pricingQuantity' || field === 'unitPrice') {
        const pricingQuantity = Number(next.pricingQuantity || 0);
        const unitPrice = Number(next.unitPrice || 0);
        if (pricingQuantity > 0 && unitPrice > 0) {
          next.amount = String(Number((pricingQuantity * unitPrice).toFixed(2)));
        }
      }
      return next;
    }));
  };
  const addItemDraft = () => {
    setItemDrafts((items) => [...items, {
      id: `draft-${Date.now()}-${items.length}`,
      materialName: '',
      spec: '',
      origin: '',
      packageQuantity: '',
      packageUnit: '件',
      unitWeight: '',
      totalWeight: '',
      weightUnit: '吨',
      pricingQuantity: '',
      pricingUnit: '吨',
      unitPrice: '',
      salesUnitPrice: '',
      salesUnitPriceAuto: '',
      amount: ''
    }]);
  };
  const removeItemDraft = (index: number) => {
    setItemDrafts((items) => items.filter((_, itemIndex) => itemIndex !== index));
  };
  const cardNote =
    document.docType === 'evidence_only'
      ? `该资料仅存档，不建立业务数据或更新台账${document.detectedDocTypeLabel ? `；原始内容识别为${document.detectedDocTypeLabel}` : ''}。`
      : document.docType === 'delivery_note'
        ? '确认后回填实际到货数量和重量；单据上的运输费用计入该批次运输费用（无运输费用请填 0）。'
      : document.docType === 'transport_note'
        ? '确认后作为独立运输费用写入台账，并按承运单位匹配请款、付款和进项票。'
      : document.docType === 'payment_request'
        ? '确认后回填请款金额，作为本批次应付申请记录。'
        : document.docType === 'payment'
          ? '确认后回填实际付款金额，并自动更新已付与未付金额。'
          : document.docType === 'purchase_invoice'
            ? '确认后回填进项开票金额与税率，并检查采购、付款和发票差异。'
            : document.docType === 'sales_invoice'
              ? '确认后只回填销项开票日期、含税金额与税率，不会重复增加销售总金额。'
              : document.docType === 'sales_settlement'
                ? '结算资料确认后仅归档，不自动修改销售金额、收款或开票数据。'
            : '确认后将该凭证归档并增量更新当前批次台账。';

  // 已在数据中心删除的单据：卡片呈现终态，不渲染任何可交互操作（确认/编辑均已 404）
  if (card.status === 'deleted') {
    return (
      <div className="chat-recognition-card">
        <div className="chat-card-heading">
          <span className="chat-card-icon supply">
            <ClipboardCheck size={19} />
          </span>
          <span>
            <strong>识别到{document.docTypeLabel}</strong>
            <small>该数据已删除，相关台账与汇总已同步回退。</small>
          </span>
          <span className="status-badge neutral">已删除</span>
        </div>
      </div>
    );
  }

  return (
    <div className="chat-recognition-card">
      <div className="chat-card-heading">
        <span className="chat-card-icon supply">
          <ClipboardCheck size={19} />
        </span>
        <span>
          <strong>
            {evidenceOnly
              ? '仅作原始凭证'
              : document.typeOverride
                ? `按要求作为${document.docTypeLabel}处理`
                : `识别到${document.docTypeLabel}`}
          </strong>
          <small>
            {evidenceOnly && document.detectedDocTypeLabel
              ? `内容识别：${document.detectedDocTypeLabel} · 不更新台账`
              : document.typeOverride && document.detectedDocTypeLabel
                ? `原始内容识别：${document.detectedDocTypeLabel} · 请核对目标类型字段`
                : `AI 置信度 ${document.confidence ?? '-'}% ·${
                    document.items.length > 0 ? ` 已提取 ${document.items.length} 条材料明细` : ' 已提取关键业务字段'
                  }`}
          </small>
        </span>
        <span className={`status-badge ${applied || queued ? 'success' : 'warning'}`}>
          {applied ? archiveOnly ? '已归档' : '已写入台账' : queued ? '已确认，等待采购台账' : '待确认'}
        </span>
      </div>

      {document.guidanceText && (
        <div className="user-guidance">
          <small>用户处理说明</small>
          <strong>{document.guidanceText}</strong>
        </div>
      )}

      {missingFieldLabels.length > 0 && (
        <div className="missing-fields-bar">
          <AlertCircle size={15} />
          <span>还需补充必填字段：{missingFieldLabels.join('、')}</span>
        </div>
      )}

      {document.typeOverride && document.detectedDocTypeLabel && !editing && (
        <div className="focused-confirmation">
          <div>
            <strong>处理类型已按你的说明调整</strong>
            <small>
              原始内容识别为{document.detectedDocTypeLabel}，当前按{document.docTypeLabel}处理。
              请核对{document.docType === 'payment' ? '付款日期、收款单位和付款金额' : '当前卡片中的目标业务字段'}；
              {missingFieldLabels.length
                ? ` 当前仍需补充：${missingFieldLabels.join('、')}。`
                : ' 原资料没有明确表达的事实需要手动补充。'}
            </small>
          </div>
        </div>
      )}

      {editing ? (
        <div className="chat-edit-grid">
          <div className="doc-type-editor">
            <small>单据类型</small>
            <div>
              {[
                ['supply_order', '采购单'],
                ['delivery_note', '送货单'],
                ['transport_note', '运输费用'],
                ['payment_request', '请款单'],
                ['payment', '付款记录'],
                ['receipt', '收款记录'],
                ['purchase_invoice', '进项发票'],
                ['sales_invoice', '销项发票'],
                ['sales_settlement', '销售结算资料'],
                ['evidence_only', '仅作原始凭证']
              ].map(([value, label]) => (
                <button
                  key={value}
                  type="button"
                  className={draft.docType === value ? 'active' : ''}
                  onClick={() => setDraft({ ...draft, docType: value })}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>
          <label><small>{partyLabel}{requiredMark('supplierName')}</small><input className={emptyRequiredClass('supplierName', Boolean(draft.supplierName))} value={draft.supplierName} onChange={(event) => setDraft({...draft, supplierName:event.target.value})} /></label>
          <label><small>单据编号</small><input value={draft.docCode} onChange={(event) => setDraft({...draft, docCode:event.target.value})} /></label>
          <label><small>{dateLabel}{requiredMark('docDate')}</small><input type="date" className={emptyRequiredClass('docDate', Boolean(draft.docDate))} value={draft.docDate} onChange={(event) => setDraft({...draft, docDate:event.target.value})} /></label>
          <label><small>{amountLabel}{requiredMark('amount')}</small><input type="number" className={emptyRequiredClass('amount', Number(draft.amount || 0) > 0)} value={draft.amount} onChange={(event) => setDraft({...draft, amount:event.target.value})} /></label>
          {isDelivery && (
            <>
              <label><small>承运单位（选填）</small><input value={draft.carrierName} placeholder="运输单上有则填，无则留空" onChange={(event) => setDraft({...draft, carrierName:event.target.value})} /></label>
              {/* 采购运费不再在送货单上展示/编辑（2026-11-04 方案 B）：它是批次级成本，
                  由台账按「运输单 > 单据识别值 > 批次口径」统一取数；draft 仍透传识别值 */}
              <label><small>销售运费（选填）</small><input type="number" min="0" step="any" value={draft.salesFreight} placeholder="客户承担的运费，进销售总金额" onChange={(event) => setDraft({...draft, salesFreight:event.target.value})} /></label>
            </>
          )}
          {isPaymentRequest && (
            <label className="full"><small>备注 / 用途（选填）</small><input value={draft.remark} placeholder="AI 已识别的用途可直接修改" onChange={(event) => setDraft({...draft, remark:event.target.value})} /></label>
          )}
          {isFinancial && (
            <>
              <label>
                <small>{isInvoice ? '发票内容' : isPaymentRequest ? '请款类别' : '付款类别'}</small>
                <select
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
              {isInvoice && <label><small>税率</small><input value={draft.taxRate} placeholder="例如 13%" onChange={(event) => setDraft({...draft, taxRate:event.target.value})} /></label>}
              {isInvoice && <label><small>不含税金额</small><input type="number" value={draft.netAmount} onChange={(event) => setDraft({...draft, netAmount:event.target.value})} /></label>}
              {isInvoice && <label><small>税额</small><input type="number" value={draft.taxAmount} onChange={(event) => setDraft({...draft, taxAmount:event.target.value})} /></label>}
            </>
          )}
        </div>
      ) : (
        <div className="chat-field-grid">
          <span><small>{partyLabel}{requiredMark('supplierName')}</small><strong className={missingClass('supplierName')}>{document.supplierName || (isRequired('supplierName') ? '待补充（必填）' : '待补充')}</strong></span>
          <span><small>单据编号</small><strong>{document.docCode || '未识别'}</strong></span>
          <span><small>{dateLabel}{requiredMark('docDate')}</small><strong className={missingClass('docDate')}>{document.docDate || (isRequired('docDate') ? '待补充（必填）' : '待补充')}</strong></span>
          <span><small>{amountLabel}{requiredMark('amount')}</small><strong className={`highlight ${missingClass('amount')}`}>{money(document.amount)}</strong></span>
          {(isPaymentRequest || document.remark) && (
            <span>
              <small>备注 / 用途</small>
              <strong>{document.remark || '待补充'}</strong>
            </span>
          )}
          {isDelivery && (
            <>
              <span>
                <small>销售运费</small>
                <strong>{document.salesFreight != null && Number(document.salesFreight) > 0 ? money(Number(document.salesFreight)) : '未标注（无则不计）'}</strong>
              </span>
              <span>
                <small>承运单位</small>
                <strong>{document.carrierName || '未标注'}</strong>
              </span>
              {document.pricingNote && (
                <span>
                  <small>销售定价</small>
                  <strong>{document.pricingNote}</strong>
                </span>
              )}
            </>
          )}
          {isFinancial && (
            <>
              <span><small>{isInvoice ? '发票内容' : isPaymentRequest ? '请款类别' : '付款类别'}</small><strong>{document.expenseCategory === 'transport' ? '运输费用' : '材料业务'}</strong></span>
              {isInvoice && <span><small>税率</small><strong>{document.taxRate || (document.expenseCategory === 'transport' ? '9%' : '13%')}</strong></span>}
            </>
          )}
          {isInvoice && document.netAmount != null && (
            <span><small>不含税金额</small><strong>{money(document.netAmount)}</strong></span>
          )}
          {isInvoice && document.taxAmount != null && (
            <span><small>税额</small><strong>{money(document.taxAmount)}</strong></span>
          )}
          {isInvoice && (
            <>
              <span><small>销售方</small><strong>{document.sellerName || '未识别'}</strong></span>
              <span><small>购买方</small><strong>{document.buyerName || '未识别'}</strong></span>
            </>
          )}
        </div>
      )}
      {isInvoice && !editing && !applied && !queued && (
        <div className="focused-confirmation">
          <div>
            <strong>发票方向：{isSalesInvoice ? '销项发票' : isPurchaseInvoice ? '进项发票' : '待确认'}</strong>
            <small>系统已按「本单位是销售方还是购买方」判定；若与实际不符，可直接切换。</small>
          </div>
          <div>
            <button disabled={busy || isPurchaseInvoice} onClick={() => onEdit(document.id, { docType: 'purchase_invoice' })}>本单位购买，进项发票</button>
            <button disabled={busy || isSalesInvoice} onClick={() => onEdit(document.id, { docType: 'sales_invoice' })}>本单位销售，销项发票</button>
          </div>
        </div>
      )}

      {document.docType === 'invoice_unknown' && !editing && (
        <div className="focused-confirmation">
          <div>
            <strong>已确认这是一张发票</strong>
            <small>目前只需确认本单位在这张发票中的方向。</small>
          </div>
          <div>
            <button disabled={busy} onClick={() => onEdit(document.id, { docType: 'purchase_invoice' })}>本单位购买，进项发票</button>
            <button disabled={busy} onClick={() => onEdit(document.id, { docType: 'sales_invoice' })}>本单位销售，销项发票</button>
          </div>
        </div>
      )}

      {document.docType === 'settlement_unknown' && !editing && (
        <div className="focused-confirmation">
          <div>
            <strong>已确认这是{document.expenseCategory === 'transport' ? '运输' : '材料'}结算资料</strong>
            <small>结算资料不会被当作付款、收款或发票，也不会自动修改销售总金额。</small>
          </div>
          <div>
            <button disabled={busy} onClick={() => onEdit(document.id, { docType: 'sales_settlement' })}>作为销售结算资料</button>
            <button disabled={busy} onClick={() => onEdit(document.id, { docType: 'evidence_only' })}>仅作原始凭证</button>
          </div>
        </div>
      )}

      {supportsItems && (editing || document.items.length > 0) && (
        <div className={`chat-material-section ${editing ? 'editing' : ''}`}>
          {editing && (
            <div className="material-editor-heading">
              <span>
                <strong>单据明细</strong>
                <small>点击表格中的任意字段直接修改，也可以新增或删除明细行。</small>
              </span>
              <button type="button" onClick={addItemDraft}>
                <Plus size={14} />新增一行
              </button>
            </div>
          )}
          <div className="chat-material-table">
          <div className={`material-row head ${editing ? 'editing-layout' : ''} ${showSalesPrice ? 'with-sales' : ''}`}>
            <span>材料</span>
            <span>规格</span>
            <span>产地</span>
            <span>件数</span>
            <span>总重量</span>
            <span>计价数量</span>
            <span>计价单价</span>
            {showSalesPrice && <span title="采购单按批次口径由采购价推算；送货单填了就按它算销售金额（优先于网价/毛利率口径）；清空恢复自动">销售价</span>}
            <span>金额</span>
            {editing && <span>操作</span>}
          </div>
          {editing ? itemDrafts.map((item, index) => (
            <div className={`material-row editable editing-layout ${showSalesPrice ? 'with-sales' : ''}`} key={item.id}>
              <span><input aria-label={`第${index + 1}行材料`} placeholder="材料名称" value={item.materialName} onChange={(event) => updateItemDraft(index, 'materialName', event.target.value)} /></span>
              <span><input aria-label={`第${index + 1}行规格`} placeholder="规格型号" value={item.spec} onChange={(event) => updateItemDraft(index, 'spec', event.target.value)} /></span>
              <span><input aria-label={`第${index + 1}行产地`} placeholder="产地" value={item.origin || ''} onChange={(event) => updateItemDraft(index, 'origin', event.target.value)} /></span>
              <span className="measurement-editor">
                <input type="number" min="0" step="any" aria-label={`第${index + 1}行件数`} placeholder="数量" value={item.packageQuantity} onChange={(event) => updateItemDraft(index, 'packageQuantity', event.target.value)} />
                <input className="unit-input" aria-label={`第${index + 1}行件数单位`} placeholder="单位" value={item.packageUnit} onChange={(event) => updateItemDraft(index, 'packageUnit', event.target.value)} />
              </span>
              <span className="measurement-editor">
                <input type="number" min="0" step="any" aria-label={`第${index + 1}行总重量`} placeholder="重量" value={item.totalWeight} onChange={(event) => updateItemDraft(index, 'totalWeight', event.target.value)} />
                <input className="unit-input" aria-label={`第${index + 1}行重量单位`} placeholder="单位" value={item.weightUnit} onChange={(event) => updateItemDraft(index, 'weightUnit', event.target.value)} />
              </span>
              <span className="measurement-editor">
                <input type="number" min="0" step="any" aria-label={`第${index + 1}行计价数量`} placeholder="数量" value={item.pricingQuantity} onChange={(event) => updateItemDraft(index, 'pricingQuantity', event.target.value)} />
                <input className="unit-input" aria-label={`第${index + 1}行计价单位`} placeholder="单位" value={item.pricingUnit} onChange={(event) => updateItemDraft(index, 'pricingUnit', event.target.value)} />
              </span>
              <span><input type="number" min="0" step="any" aria-label={`第${index + 1}行单价`} placeholder="单价" value={item.unitPrice} onChange={(event) => updateItemDraft(index, 'unitPrice', event.target.value)} /></span>
              {showSalesPrice && (
                <span><input
                  type="number"
                  min="0"
                  step="any"
                  aria-label={`第${index + 1}行销售价`}
                  placeholder={item.salesUnitPriceAuto ? `自动 ${item.salesUnitPriceAuto}` : '自动'}
                  value={item.salesUnitPrice}
                  onChange={(event) => updateItemDraft(index, 'salesUnitPrice', event.target.value)}
                /></span>
              )}
              <span><input type="number" min="0" step="any" aria-label={`第${index + 1}行金额`} placeholder="金额" value={item.amount} onChange={(event) => updateItemDraft(index, 'amount', event.target.value)} /></span>
              <span className="material-row-action">
                <button type="button" aria-label={`删除第${index + 1}行`} onClick={() => removeItemDraft(index)}>
                  <Trash2 size={14} />
                </button>
              </span>
            </div>
          )) : document.items.map((item) => {
            const unitPrice = Number(item.unitPrice ?? 0);
            const itemAmount = Number(item.amount ?? 0);
            const itemQuantity = Number(item.quantity ?? 0);
            const arithmeticQuantity =
              unitPrice > 0 && itemAmount > 0 ? itemAmount / unitPrice : item.quantity;
            const legacyMismatch =
              unitPrice > 0 &&
              itemAmount > 0 &&
              Math.abs(itemQuantity * unitPrice - itemAmount) > Math.max(1, itemAmount * 0.001);
            const pricingQuantity =
              item.pricingQuantity || (legacyMismatch ? arithmeticQuantity : item.quantity);
            const pricingUnit = item.pricingUnit || item.unit;
            const packageQuantity =
              item.packageQuantity || (legacyMismatch ? item.quantity : undefined);
            const packageUnit = item.packageUnit || (legacyMismatch ? '件' : '');
            const totalWeight =
              item.totalWeight || (['吨', 't', 'ton'].includes(pricingUnit.toLowerCase())
                ? pricingQuantity
                : undefined);
            const weightUnit = item.weightUnit || (totalWeight ? pricingUnit : '');
            return (
              <div className={`material-row ${showSalesPrice ? 'with-sales' : ''}`} key={item.id}>
                <span>{item.materialName}</span>
                <span>{item.spec || '-'}</span>
                <span>{item.origin || '-'}</span>
                <span>{packageQuantity ? `${quantity(packageQuantity)} ${packageUnit}` : '-'}</span>
                <span>{totalWeight ? `${quantity(totalWeight)} ${weightUnit}` : '-'}</span>
                <span>{pricingQuantity == null ? '-' : `${quantity(pricingQuantity)} ${pricingUnit}`}</span>
                <span>{item.unitPrice == null ? '-' : money(item.unitPrice)} / {pricingUnit || '单位'}</span>
                {showSalesPrice && (
                  <span>
                    {item.salesUnitPrice == null ? '-' : money(item.salesUnitPrice)}
                    {item.salesUnitPrice != null && (
                      <small className={`sales-price-tag ${item.salesUnitPriceManual ? 'manual' : ''}`}>
                        {item.salesUnitPriceManual ? '手动' : '自动'}
                      </small>
                    )}
                  </span>
                )}
                <span>{item.amount == null ? '-' : money(item.amount)}</span>
              </div>
            );
          })}
          {editing && itemDrafts.length === 0 && (
            <div className="material-empty-editor">
              <strong>当前没有明细行</strong>
              <span>点击“新增一行”补充材料、数量、单价和金额。</span>
            </div>
          )}
          </div>
          {editing && itemDrafts.length > 0 && (
            <div className="material-editor-summary">
              <span>共 {itemDrafts.length} 行，明细金额合计 <strong>{money(itemTotal)}</strong></span>
              <button
                type="button"
                onClick={() => setDraft((current) => ({ ...current, amount: String(itemTotal) }))}
              >
                同步为单据总额
              </button>
            </div>
          )}
        </div>
      )}

      {card.amountDiscrepancy && !card.amountDiscrepancy.decision && !editing && (
        <div className="amount-discrepancy-card">
          <div>
            <AlertCircle size={17} />
            <span>
              <strong>单据总额与材料明细合计不一致</strong>
              <small>台账将以确认后的材料明细合计为准。</small>
            </span>
          </div>
          <dl>
            <span><dt>单据总额</dt><dd>{money(card.amountDiscrepancy.sourceAmount)}</dd></span>
            <span><dt>明细合计</dt><dd>{money(card.amountDiscrepancy.itemTotal)}</dd></span>
            <span><dt>差额</dt><dd>{money(card.amountDiscrepancy.difference)}</dd></span>
          </dl>
          <div className="chat-card-actions">
            <button className="secondary-button" disabled={busy} onClick={() => onEdit(document.id, { amountMismatchDecision: 'keep_source' })}>保留单据总额并登记差异</button>
            <button className="primary-button" disabled={busy} onClick={() => onEdit(document.id, { amountMismatchDecision: 'sync_items' })}>同步为明细合计</button>
          </div>
        </div>
      )}

      <div className="chat-card-note">
        <AlertCircle size={15} />
        {isSupply
          ? '确认后立即建立采购基础和台账骨架，后续送货、付款、发票资料可按任意顺序继续补充。'
          : cardNote}
      </div>

      <div className="chat-card-actions">
        {editing ? (
          <>
            <button className="secondary-button" onClick={() => setEditing(false)} disabled={busy}>取消修改</button>
            {amountDecisionOpen && (
              <div className="inline-amount-choice">
                <strong>当前总额与明细合计相差 {money(Number(draft.amount || 0) - itemTotal)}</strong>
                <button type="button" onClick={async () => {
                  setDraft((current) => ({ ...current, amount: String(itemTotal) }));
                  setAmountDecisionOpen(false);
                }}>先同步总额</button>
                <button type="button" onClick={async () => {
                  await onEdit(document.id, {
                    ...draft,
                    amount: draftAmount,
                    amountMismatchDecision: 'keep_source',
                    items: itemDrafts.map((item) => ({
                      materialName: item.materialName,
                      spec: item.spec,
                      origin: item.origin || null,
                      packageQuantity: item.packageQuantity === '' ? null : Number(item.packageQuantity),
                      packageUnit: item.packageUnit,
                      unitWeight: item.unitWeight === '' ? null : Number(item.unitWeight),
                      totalWeight: item.totalWeight === '' ? null : Number(item.totalWeight),
                      weightUnit: item.weightUnit,
                      // 计价数量缺失即留空（null），不用 0 顶替；金额缺失按 0。
                      pricingQuantity: item.pricingQuantity === '' ? null : Number(item.pricingQuantity),
                      pricingUnit: item.pricingUnit,
                      unit: item.pricingUnit,
                      quantity: item.pricingQuantity === '' ? null : Number(item.pricingQuantity),
                      unitPrice: item.unitPrice === '' ? null : Number(item.unitPrice),
                      salesUnitPrice: item.salesUnitPrice === '' ? null : Number(item.salesUnitPrice),
                      amount: Number(item.amount || 0)
                    }))
                  });
                  setAmountDecisionOpen(false);
                  setEditing(false);
                }}>保留差异并保存</button>
              </div>
            )}
            <button
              className="primary-button"
              disabled={busy}
              onClick={async () => {
                if (amountMismatch) {
                  setAmountDecisionOpen(true);
                  return;
                }
                await onEdit(document.id, {
                  ...draft,
                  amount: draftAmount,
                  items: itemDrafts.map((item) => ({
                    materialName: item.materialName,
                    spec: item.spec,
                    origin: item.origin || null,
                    packageQuantity: item.packageQuantity === '' ? null : Number(item.packageQuantity),
                    packageUnit: item.packageUnit,
                    unitWeight: item.unitWeight === '' ? null : Number(item.unitWeight),
                    totalWeight: item.totalWeight === '' ? null : Number(item.totalWeight),
                    weightUnit: item.weightUnit,
                    // 计价数量缺失即留空（null），不用 0 顶替；金额缺失按 0。
                    pricingQuantity: item.pricingQuantity === '' ? null : Number(item.pricingQuantity),
                    pricingUnit: item.pricingUnit,
                    unit: item.pricingUnit,
                    quantity: item.pricingQuantity === '' ? null : Number(item.pricingQuantity),
                    unitPrice: item.unitPrice === '' ? null : Number(item.unitPrice),
                    salesUnitPrice: item.salesUnitPrice === '' ? null : Number(item.salesUnitPrice),
                    amount: Number(item.amount || 0)
                  }))
                });
                setEditing(false);
              }}
            >
              <Check size={15} />保存修改
            </button>
          </>
        ) : (
          <>
            <button className="primary-button" onClick={() => onConfirm(document.id)} disabled={busy || applied || queued || Boolean(card.missingFields?.length)}>
              <Check size={15} />
              {applied ? archiveOnly ? '已确认归档' : '已确认并写入台账' : queued ? '已确认，待采购台账建立后自动回填' : card.missingFields?.length ? '请先补充必填字段' : busy ? '正在处理...' : archiveOnly ? '确认并归档' : '确认并写入台账'}
            </button>
            <button className="secondary-button" disabled={applied || queued} onClick={beginEditing}>
              <FileSearch size={15} />修改识别字段
            </button>
          </>
        )}
      </div>
    </div>
  );
}

export function ConnectedWorkspacePage({
  project,
  batch,
  documents,
  messages,
  nickname,
  busy,
  error,
  onNavigate,
  onSend,
  onUpload,
  onConfirm,
  onEdit,
  onDuplicateDecision,
  onClarify,
  onQuoteConfirm,
  onQuoteDiscard
}: {
  project: Project;
  batch: Batch;
  documents: BusinessDocument[];
  messages: ApiChatMessage[];
  /** 当前用户昵称，只用来取用户消息头像的首字 */
  nickname: string;
  busy: boolean;
  error: string;
  onNavigate: (page: PageKey) => void;
  onSend: (text: string) => Promise<void>;
  onUpload: (file: File, guidance?: string) => Promise<void>;
  onConfirm: (documentId: string) => Promise<void>;
  onEdit: (documentId: string, edits: Record<string, unknown>) => Promise<void>;
  onDuplicateDecision: (documentId: string, decision: 'continue' | 'skip') => Promise<void>;
  onClarify: (documentId: string, answer: string) => Promise<void>;
  onQuoteConfirm: (messageId: string) => Promise<void>;
  onQuoteDiscard: (messageId: string) => Promise<void>;
}) {
  const userInitial = nickname.trim().slice(0, 1) || '我';
  const [chatInput, setChatInput] = useState('');
  const [attachmentOpen, setAttachmentOpen] = useState(false);
  const [pendingFile, setPendingFile] = useState<File | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const chatTextarea = useRef<HTMLTextAreaElement>(null);
  const chatBody = useRef<HTMLDivElement>(null);
  const chatFeed = useRef<HTMLDivElement>(null);
  const shouldStickToLatest = useRef(true);
  const batchDocuments = documents.filter((item) => item.batchId === batch.id);
  const unresolvedCount = batchDocuments.filter(
    (item) => item.status === '待确认' || item.status === '有差异'
  ).length;
  const confirmedTypes = new Set(
    batchDocuments.filter((item) => item.status === '已匹配').map((item) => item.type)
  );
  // 运输费用已处理的判定：单独的运输费用单据，或已补充金额的送货单/配送单（金额按运输费用归集）
  const hasTransportCost = batchDocuments.some((item) =>
    item.status === '已匹配'
    && (item.type === '运输费用' || (item.type === '送货单' && (item.amount || 0) > 0)));
  const nextSteps = [
    !confirmedTypes.has('采购单') && { title:'导入采购单', detail:'建立采购基础；同批次可导入多张', icon:ClipboardCheck, hint:'请按采购单识别这份资料' },
    confirmedTypes.has('采购单') && !confirmedTypes.has('送货单') && { title:'补充送货单', detail:'核对实际到货数量和重量；可导入多张', icon:PackageCheck, hint:'请按送货单识别这份资料' },
    confirmedTypes.has('采购单') && !hasTransportCost && { title:'补充运输费用', detail:'如有物流费用，补充承运单位、金额和运输日期', icon:Truck, hint:'请按运输费用识别这份资料' },
    confirmedTypes.has('采购单') && !confirmedTypes.has('请款单') && { title:'补充请款记录', detail:'回填请款单位、金额和申请日期', icon:Banknote, hint:'请按请款单识别这份资料' },
    batch.purchase > batch.paid && { title:'补充付款记录', detail:`当前仍未付款 ${money(Math.max(batch.purchase - batch.paid, 0))}`, icon:Banknote, hint:'请按付款记录识别这份资料' },
    batch.purchase > batch.invoiced && { title:'补充进项发票', detail:`当前仍缺进项票 ${money(Math.max(batch.purchase - batch.invoiced, 0))}`, icon:ReceiptText, hint:'请按进项发票识别这份资料' }
  ].filter(Boolean) as Array<{title:string; detail:string; icon:typeof Truck; hint:string}>;

  useLayoutEffect(() => {
    shouldStickToLatest.current = true;
    const container = chatBody.current;
    if (container) {
      container.scrollTop = container.scrollHeight;
    }
    const frame = window.requestAnimationFrame(() => {
      if (container && shouldStickToLatest.current) {
        container.scrollTop = container.scrollHeight;
      }
    });
    return () => window.cancelAnimationFrame(frame);
  }, [messages.length, busy, error, batch.id]);

  useEffect(() => {
    const container = chatBody.current;
    const feed = chatFeed.current;
    if (!container || !feed) return;

    const handleScroll = () => {
      const distance = container.scrollHeight - container.scrollTop - container.clientHeight;
      shouldStickToLatest.current = distance <= 24;
    };
    const observer = new ResizeObserver(() => {
      if (shouldStickToLatest.current) {
        container.scrollTop = container.scrollHeight;
      }
    });

    container.addEventListener('scroll', handleScroll, { passive: true });
    observer.observe(feed);
    return () => {
      container.removeEventListener('scroll', handleScroll);
      observer.disconnect();
    };
  }, [batch.id]);

  useLayoutEffect(() => {
    const textarea = chatTextarea.current;
    if (!textarea) return;
    textarea.style.height = 'auto';
    textarea.style.height = `${Math.min(textarea.scrollHeight, 124)}px`;
  }, [chatInput]);

  const submitChat = async (event: FormEvent) => {
    event.preventDefault();
    const message = chatInput.trim();
    if ((!message && !pendingFile) || busy) return;
    if (pendingFile) {
      const file = pendingFile;
      const guidance = message || undefined;
      setPendingFile(null);
      setChatInput('');
      setAttachmentOpen(false);
      await onUpload(file, guidance);
      return;
    }
    setChatInput('');
    setAttachmentOpen(false);
    await onSend(message);
  };

  const chooseAttachment = (manual = false) => {
    setAttachmentOpen(false);
    if (manual) {
      setChatInput('请识别这条业务记录：');
      return;
    }
    fileInput.current?.click();
  };

  const prepareStep = (hint: string) => {
    setChatInput(hint);
    fileInput.current?.click();
  };

  const pasteImage = (event: ClipboardEvent<HTMLTextAreaElement>) => {
    const image = Array.from(event.clipboardData.items)
      .find((item) => item.kind === 'file' && item.type.startsWith('image/'))
      ?.getAsFile();
    if (!image) return;

    event.preventDefault();
    const timestamp = new Date().toISOString().replace(/[-:T.Z]/g, '').slice(0, 14);
    const extension = clipboardImageExtension(image.type);
    setPendingFile(new File(
      [image],
      `粘贴图片-${timestamp}.${extension}`,
      { type: image.type || `image/${extension}`, lastModified: Date.now() }
    ));
    setAttachmentOpen(false);
  };

  const handleComposerKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing) return;
    event.preventDefault();
    event.currentTarget.form?.requestSubmit();
  };

  return (
    <div className="assistant-page page-enter">
      <div className="assistant-layout">
        <section className="assistant-chat">
          <div className="assistant-chat-header">
            <div className="assistant-identity">
              <span className="assistant-avatar">
                <Bot size={21} />
                <i />
              </span>
              <span>
                <strong>智能助理</strong>
                <small>识别业务资料并协助更新当前批次台账</small>
              </span>
            </div>
            <div className="assistant-header-actions">
              <span>
                <FolderKanban size={14} />
                {project.name}
              </span>
              <span>
                <Gauge size={14} />
                {batch.name}
              </span>
              <button onClick={() => onNavigate('projects')}>
                查看批次
                <ChevronRight size={14} />
              </button>
            </div>
          </div>

          <div className="assistant-chat-body" ref={chatBody}>
            <div className="assistant-chat-feed" ref={chatFeed}>
            {messages.length === 0 && (
              <div className="assistant-message ai">
                <span className="message-avatar">
                  <Bot size={16} />
                </span>
                <div className="message-content">
                  <p>请上传业务资料，或直接输入收付款等信息。</p>
                  <small>现在</small>
                </div>
              </div>
            )}

            {messages.map((message, messageIndex) => {
              const card = isDocumentCard(message.payload) ? message.payload : null;
              const duplicateCard = isDuplicateCard(message.payload) ? message.payload : null;
              const operationResult = isOperationResult(message.payload) ? message.payload : null;
              const reconciliationCard = message.businessType === 'reconciliation_export'
                && isReconciliationExportCard(message.payload)
                ? message.payload
                : null;
              const nextMessage = messages[messageIndex + 1];
              const nextDocument = nextMessage && isDocumentCard(nextMessage.payload)
                ? nextMessage.payload.document
                : nextMessage && isDuplicateCard(nextMessage.payload)
                  ? nextMessage.payload.document
                  : null;
              const persistedGuidance = typeof message.payload.guidanceText === 'string'
                ? message.payload.guidanceText.trim()
                : '';
              const uploadGuidance = message.messageType === 'FILE'
                ? persistedGuidance || nextDocument?.guidanceText?.trim() || ''
                : '';
              const clarification = message.businessType === 'clarify_card'
                ? message.payload as ClarificationCardPayload
                : null;
              const quoteCard = message.businessType === 'market_quote_card'
                && Array.isArray(message.payload.rows)
                ? message
                : null;
              return (
                <div
                  key={message.id}
                  className={`assistant-message ${message.sender === 'AI' ? 'ai' : 'user'}`}
                >
                  {message.sender === 'AI' && (
                    <span className="message-avatar">
                      <Bot size={16} />
                    </span>
                  )}
                  <div className={`message-content ${card || duplicateCard || operationResult || clarification || reconciliationCard || quoteCard ? 'wide' : ''}`}>
                    {message.messageType === 'FILE' ? (
                      <div className="chat-file">
                        <span>
                          <FileText size={18} />
                        </span>
                        <div>
                          <strong>{message.content.replace('上传单据：', '')}</strong>
                        </div>
                        {uploadGuidance && (
                          <div className="chat-file-guidance">
                            <small>处理说明</small>
                            <p>{uploadGuidance}</p>
                          </div>
                        )}
                      </div>
                    ) : (
                      <p>{message.content}</p>
                    )}
                    {card && (
                      <DocumentReviewCard
                        card={card}
                        project={project}
                        batch={batch}
                        busy={busy}
                        onConfirm={onConfirm}
                        onEdit={onEdit}
                      />
                    )}
                    {duplicateCard && <DuplicateReviewCard card={duplicateCard} busy={busy} onDecision={onDuplicateDecision} />}
                    {operationResult && <OperationResultCard result={operationResult} />}
                    {reconciliationCard && <ReconciliationExportCard card={reconciliationCard} />}
                    {clarification && (
                      <ClarificationCard
                        payload={clarification}
                        busy={busy}
                        onAnswer={onClarify}
                        onReupload={() => chooseAttachment(false)}
                      />
                    )}
                    {quoteCard && (
                      <MarketQuoteCard
                        message={quoteCard}
                        busy={busy}
                        onConfirm={onQuoteConfirm}
                        onDiscard={onQuoteDiscard}
                      />
                    )}
                    {clarification?.document && clarification.document.docType !== 'unknown' && (
                      <DocumentReviewCard
                        card={{
                          schemaVersion: '1.0',
                          cardType: 'document_review',
                          cardId: clarification.document.id,
                          status: 'needs_review',
                          missingFields: clarification.missingFields || [],
                          document: clarification.document,
                          actions: []
                        }}
                        project={project}
                        batch={batch}
                        busy={busy}
                        onConfirm={onConfirm}
                        onEdit={onEdit}
                      />
                    )}
                    <span className="message-meta">
                      <small className={message.status === 'failed' ? 'message-status failed' : 'message-status'}>
                        {message.status === 'failed' ? '发送失败' : time(message.createdAt)}
                      </small>
                      {message.sender === 'AI' && message.creditsUsed ? (
                        <small className="message-credits">本次消耗积分 {(message.creditsUsed as number).toFixed(2)}</small>
                      ) : null}
                    </span>
                  </div>
                  {message.sender === 'USER' && <span className="message-avatar user">{userInitial}</span>}
                </div>
              );
            })}

            {busy && (
              <div className="assistant-message ai">
                <span className="message-avatar">
                  <Bot size={16} />
                </span>
                <div className="message-content wide">
                  <div className="chat-processing-card">
                    <span className="processing-orb">
                      <Sparkles size={20} />
                    </span>
                    <span>
                      <strong>正在处理业务资料</strong>
                      <small>读取文件、识别单据并生成可确认卡片...</small>
                    </span>
                    <span className="typing-dots">
                      <i />
                      <i />
                      <i />
                    </span>
                  </div>
                </div>
              </div>
            )}

            {error && (
              <div className="assistant-message ai">
                <span className="message-avatar">
                  <AlertCircle size={16} />
                </span>
                <div className="message-content">
                  <p>{error}</p>
                  <small>处理失败</small>
                </div>
              </div>
            )}
            </div>
          </div>

          <div className="assistant-composer-wrap">
            {attachmentOpen && (
              <div className="attachment-menu">
                <div className="attachment-menu-heading">
                  <span>
                    <Paperclip size={15} />
                    添加到对话
                  </span>
                  <small>支持图片、PDF 与 Excel，选择后不会立即发送</small>
                </div>
                <div className="attachment-options">
                  <button onClick={() => chooseAttachment()}>
                    <span>
                      <ImageIcon size={18} />
                    </span>
                    <strong>图片 / PDF / Excel</strong>
                  </button>
                  <button onClick={() => chooseAttachment(true)}>
                    <span>
                      <FilePlus2 size={18} />
                    </span>
                    <strong>手动输入记录</strong>
                  </button>
                </div>
              </div>
            )}
            {pendingFile && (
              <div className="attachment-draft">
                <span className="attachment-draft-icon">
                  {pendingFile.type.startsWith('image/')
                    ? <ImageIcon size={20} />
                    : pendingFile.name.match(/\.xlsx?$/i)
                      ? <FileSpreadsheet size={20} />
                      : <FileText size={20} />}
                </span>
                <span>
                  <small>待发送附件</small>
                  <strong>{pendingFile.name}</strong>
                  <em>{(pendingFile.size / 1024).toFixed(1)} KB · 可在下方补充处理说明</em>
                </span>
                <button type="button" aria-label="移除待发送附件" onClick={() => setPendingFile(null)}><X size={17} /></button>
              </div>
            )}
            <form className="assistant-composer" onSubmit={submitChat}>
              <input
                ref={fileInput}
                className="hidden-file-input"
                type="file"
                accept="image/*,.pdf,.xls,.xlsx"
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  event.target.value = '';
                  if (file) setPendingFile(file);
                }}
              />
              <button
                type="button"
                className={attachmentOpen ? 'active' : ''}
                aria-label="添加附件"
                onClick={() => setAttachmentOpen((value) => !value)}
              >
                {attachmentOpen ? <X size={19} /> : <Paperclip size={19} />}
              </button>
              <textarea
                ref={chatTextarea}
                rows={1}
                value={chatInput}
                onChange={(event) => setChatInput(event.target.value)}
                onPaste={pasteImage}
                onKeyDown={handleComposerKeyDown}
                placeholder={pendingFile ? '可选：说明这是什么单据、希望如何处理...' : '输入业务信息，或添加、粘贴图片、PDF、Excel...'}
              />
              <button type="submit" className="send-button" aria-label="发送" disabled={busy}>
                <Send size={17} />
              </button>
            </form>
          </div>
        </section>

        <aside className="assistant-history">
          <div className="history-heading">
            <div>
              <h2>下一步建议</h2>
            </div>
          </div>
          <div className="history-group">
            <span>按当前批次进度</span>
            {nextSteps.length ? nextSteps.map((step) => {
              const StepIcon = step.icon;
              return (
                <button key={step.title} onClick={() => prepareStep(step.hint)}>
                  <StepIcon size={16} />
                  <span><strong>{step.title}</strong><small>{step.detail}</small></span>
                </button>
              );
            }) : (
              <div className="context-complete">
                <CheckCircle2 size={18} />
                <span><strong>暂无待补充项</strong></span>
              </div>
            )}
          </div>
          <button className="assistant-context-note" onClick={() => onNavigate('ledger')}>
            <TableProperties size={17} />
            <span>
              <strong>查看批次台账</strong>
              <small>{unresolvedCount} 项待确认，确认后自动更新</small>
            </span>
          </button>
        </aside>
      </div>
    </div>
  );
}
