import {
  AlertCircle,
  ArrowDownToLine,
  ArrowRight,
  Banknote,
  Bot,
  Boxes,
  Building2,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  CircleDollarSign,
  ClipboardCheck,
  Coins,
  CloudUpload,
  FileCheck2,
  FileClock,
  FilePlus2,
  FileSearch,
  FileSpreadsheet,
  FileText,
  Filter,
  FolderKanban,
  Gauge,
  Image as ImageIcon,
  LayoutDashboard,
  LogIn,
  LogOut,
  Menu,
  MessageSquareText,
  PackageCheck,
  PencilLine,
  Paperclip,
  Plus,
  ReceiptText,
  ScanLine,
  Search,
  Send,
  Settings,
  Sparkles,
  TableProperties,
  Trash2,
  TrendingUp,
  Truck,
  Upload,
  UserRound,
  WalletCards,
  X
} from 'lucide-react';
import {
  startTransition,
  useDeferredValue,
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type ReactNode
} from 'react';
import {
  api,
  setActAsUserId,
  type ApiAdminUser,
  type ApiBatch,
  type ApiBusinessDataRecord,
  type ApiChatMessage,
  type ApiCreditOverview,
  type ApiDocument,
  type ApiLedgerRow,
  type ApiUsageLog,
  type ApiProject,
  type ApiUserProfile
} from './api';
import { ConnectedWorkspacePage } from './ConnectedWorkspacePage';
import { DataCenterPage } from './DataCenterPage';
import { summarizeBatchDelivery } from './deliveryMatching';
import { MarketPage } from './MarketPage';
import { ReconciliationLedgerPage } from './ReconciliationLedgerPage';
import { ReceiptPanel, receiptTone } from './ReceiptPanel';
import { CompanyTransactionsPage } from './CompanyTransactionsPage';
import type {
  Batch,
  BusinessDocument,
  DocumentStatus,
  LedgerRow,
  PageKey,
  Project
} from './types';
import { v4 as uuidv4 } from "uuid";

const money = (value: number, digits = 2) =>
  new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    minimumFractionDigits: digits,
    maximumFractionDigits: digits
  }).format(value);

const compactMoney = (value: number) => {
  if (value >= 10_000) return `${(value / 10_000).toFixed(2)}万`;
  return money(value);
};

const quantity = (value: number) =>
  new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 3 }).format(value || 0);

const currentBusinessDate = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai'
}).format(new Date());

const percentage = (value: number, total: number) =>
  total > 0 ? Math.min((value / total) * 100, 100).toFixed(1) : '0.0';

const statusTone: Record<DocumentStatus | Project['status'] | Batch['status'], string> = {
  已匹配: 'success',
  待确认: 'warning',
  识别中: 'info',
  有差异: 'danger',
  重复归档: 'neutral',
  进行中: 'info',
  待处理: 'warning',
  已完成: 'success'
};

const docIcons = {
  采购单: ClipboardCheck,
  送货单: Truck,
  运输费用: Truck,
  请款单: WalletCards,
  付款记录: Banknote,
  收款记录: CircleDollarSign,
  进项发票: ReceiptText,
  销项发票: FileCheck2,
  结算资料: FileSpreadsheet,
  原始凭证: FileText
};

const projectStatus = (status: string): Project['status'] => {
  if (status === '已完成') return '已完成';
  if (status === '待处理' || status === '待确认' || status === '待报价') return '待处理';
  return '进行中';
};

const batchStatus = (status: string): Batch['status'] => {
  if (status === '已完成') return '已完成';
  if (status === '待确认' || status === '待处理' || status === '待报价') return '待确认';
  return '进行中';
};

const documentType = (type: string): BusinessDocument['type'] => {
  if (type === 'delivery_note') return '送货单';
  if (type === 'transport_note') return '运输费用';
  if (type === 'payment_request') return '请款单';
  if (type === 'payment') return '付款记录';
  if (type === 'receipt') return '收款记录';
  if (type === 'invoice' || type === 'purchase_invoice') return '进项发票';
  if (type === 'sales_invoice') return '销项发票';
  if (type === 'sales_settlement' || type === 'settlement_unknown') return '结算资料';
  if (type === 'evidence_only') return '原始凭证';
  return '采购单';
};

const documentStatus = (status: string, duplicateDecision?: string): DocumentStatus => {
  if (duplicateDecision === 'skip') return '重复归档';
  if (status === 'matched' || status === 'archived') return '已匹配';
  if (status === 'discrepancy') return '有差异';
  if (status === 'recognizing') return '识别中';
  return '待确认';
};

const documentSource = (source: string): BusinessDocument['source'] => {
  if (source === 'pdf') return 'PDF';
  if (source === 'excel') return 'Excel';
  if (source === 'image') return '图片';
  if (source === 'sms') return '短信';
  return '手动录入';
};

/**
 * 默认口径 scope → 一句中文（与后端 BatchHintService.renderScope 同口径，
 * 只用于展示；真正的取值以后端为准）。
 */
const hintSummaryText = (hints?: Record<string, Record<string, unknown>>): string => {
  const scope = hints?.delivery_note;
  if (!scope) return '';
  const num = (value: unknown) => {
    if (value == null || value === '') return null;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  };
  const parts: string[] = [];
  const supplyTrip = num(scope.supplyFreightPerTrip);
  const supplyTon = num(scope.supplyFreightPerTon);
  const salesTrip = num(scope.salesFreightPerTrip);
  const salesTon = num(scope.salesFreightPerTon);
  // 0 = 显式「不另计」（含在售价里），与后端 renderScope 同口径
  if (supplyTrip != null) parts.push(supplyTrip === 0 ? '采购运费不另计' : `采购运费${supplyTrip}元/趟`);
  if (supplyTon != null) parts.push(supplyTon === 0 ? '采购运费不另计' : `采购运费${supplyTon}元/吨`);
  if (salesTrip != null) parts.push(salesTrip === 0 ? '销售运费不另计（含在售价里）' : `销售运费${salesTrip}元/趟`);
  if (salesTon != null) parts.push(salesTon === 0 ? '销售运费不另计（含在售价里）' : `销售运费${salesTon}元/吨`);
  const uplift = num(scope.upliftAmount);
  const margin = num(scope.marginRate);
  if (scope.pricingMode === 'uplift' && uplift != null) {
    parts.push(`销售价=网价+${uplift}元/吨`);
  } else if (scope.pricingMode === 'margin' && margin != null) {
    parts.push(`销售价=采购价÷${Number((100 - margin).toFixed(2))}%（毛利率${margin}%）`);
  }
  return parts.join('；');
};

/**
 * 项目口径里「批次完全没设置的那些组」→ 一句话（批次编辑页提示用）。
 * 与后端 mergeScopes 同规则：同组的键批次只要给了任意一个，整组就不算继承。
 */
const inheritedHintText = (
  projectHints?: Record<string, Record<string, unknown>>,
  batchHints?: Record<string, Record<string, unknown>>
): string => {
  const projectScope = projectHints?.delivery_note;
  if (!projectScope) return '';
  const batchScope = batchHints?.delivery_note ?? {};
  const groups = [
    ['supplyFreightPerTrip', 'supplyFreightPerTon'],
    ['salesFreightPerTrip', 'salesFreightPerTon'],
    ['pricingMode', 'upliftAmount', 'marginRate']
  ];
  const inherited: Record<string, unknown> = {};
  Object.entries(projectScope).forEach(([key, value]) => {
    const group = groups.find((keys) => keys.includes(key));
    if (group && group.some((field) => batchScope[field] != null)) return;
    inherited[key] = value;
  });
  return hintSummaryText({ delivery_note: inherited });
};

const mapProjects = (apiProjects: ApiProject[], apiBatches: ApiBatch[]): Project[] =>
  apiProjects.map((project) => {
    const batches = apiBatches
      .filter((batch) => batch.projectId === project.id)
      .map<Batch>((batch) => ({
        id: batch.id,
        name: batch.name,
        date: batch.businessDate || batch.name.slice(0, 10),
        status: batchStatus(batch.status),
        material: batch.materialSummary || '待补充',
        purchase: Number(batch.purchaseAmount || 0),
        delivered: 0,
        paid: 0,
        invoiced: 0,
        salesInvoiced: 0,
        received: Number(batch.receivedAmount || 0),
        progress: Number(batch.progress || 0),
        sales: Number(batch.salesAmount || 0),
        reconciled: Boolean(batch.reconciled),
        receiptStatus: batch.receiptStatus || '未收款',
        businessHints: batch.businessHints
      }));
    return {
      id: project.id,
      name: project.name,
      customer: project.customerName,
      status: projectStatus(project.status),
      progress: Number(project.progress || 0),
      purchase: Number(project.purchaseAmount || 0),
      paid: Number(project.paidAmount || 0),
      invoice: Number(project.invoiceAmount || 0),
      unpaidPurchase: Number(project.unpaidPurchase || 0),
      uninvoicedPurchase: Number(project.uninvoicedPurchase || 0),
      sales: Number(project.salesAmount || 0),
      salesInvoiced: Number(project.salesInvoiceAmount || 0),
      received: Number(project.receivedAmount || 0),
      receivable: Number(project.outstanding || 0),
      differenceAmount: Number(project.differenceAmount || 0),
      uninvoicedSales: Number(project.uninvoicedSales || 0),
      profit: Number(project.profitAmount || 0),
      marginRate: Number(project.marginRate || 0),
      businessHints: project.businessHints,
      batches
    };
  });

const mapDocuments = (documents: ApiDocument[]): BusinessDocument[] =>
  documents.map((document) => ({
    id: document.id,
    code: document.docCode || '未编号',
    type: documentType(document.docType),
    supplier: document.supplierName || '待补充',
    projectId: document.projectId,
    batchId: document.batchId,
    date: document.docDate || '-',
    amount: Number(document.amount || 0),
    status: documentStatus(document.status, document.duplicateDecision),
    confidence: Number(document.confidence || 0),
    source: documentSource(document.source),
    fileName: document.fileName,
    fileUrl: document.fileUrl,
    sourceText: document.sourceText,
    guidanceText: document.guidanceText,
    aiSummary: document.aiSummary,
    duplicateReason: document.duplicateReason,
    duplicateDecision: document.duplicateDecision,
    docType: document.docType,
    ledgerApplied: document.ledgerApplied
  }));

const mapLedger = (
  rows: ApiLedgerRow[],
  projectItems: Project[]
): LedgerRow[] =>
  rows.map((row) => {
    const project = projectItems.find((item) => item.id === row.projectId);
    const batch = project?.batches.find((item) => item.id === row.batchId);
    const status: LedgerRow['status'] =
      row.status === 'balanced'
        ? '已对平'
        : row.status === 'pending_payment'
          ? '待付款'
          : row.status === 'pending_receipt'
            ? '待收款'
            : row.status === 'discrepancy'
              ? '票款差异'
              : '待补充';
    return {
      id: row.id,
      projectId: row.projectId,
      batchId: row.batchId,
      sourceDocumentId: row.sourceDocumentId,
      project: project?.name || '-',
      batch: batch?.name || '-',
      purchaseDate: row.purchaseDate || batch?.date || '-',
      material: row.material || batch?.material || '待补充',
      supplier: row.supplier || '待补充',
      purchaseAmount: Number(row.purchaseAmount || 0),
      freight: Number(row.freight || 0),
      deliveryAmount: Number(row.deliveryAmount || 0),
      deliveredPackageQuantity: Number(row.deliveredPackageQuantity || 0),
      deliveredPackageUnit: row.deliveredPackageUnit || '',
      deliveredWeight: Number(row.deliveredWeight || 0),
      deliveredWeightUnit: row.deliveredWeightUnit || '',
      requestedAmount: Number(row.requestedAmount || 0),
      paidAmount: Number(row.paidAmount || 0),
      unpaidAmount: Number(row.unpaidAmount || 0),
      invoiceAmount: Number(row.invoiceAmount || 0),
      taxRate: row.taxRate || '-',
      salesOrder: row.salesOrder || '-',
      salesAmount: Number(row.salesAmount || 0),
      salesInvoiceDate: row.salesInvoiceDate,
      salesInvoiceAmount: Number(row.salesInvoiceAmount || 0),
      salesTaxRate: row.salesTaxRate || '-',
      receivedAmount: Number(row.receivedAmount || 0),
      outstanding: Number(row.outstanding || 0),
      receiptDate: row.receiptDate,
      requiredReceiptDate: row.requiredReceiptDate,
      isMonthlySettlement: row.isMonthlySettlement,
      interest: row.interest == null ? undefined : Number(row.interest),
      interestDetails: row.interestDetails,
      receiptEntries: row.receiptEntries,
      overdueFlag: row.overdueFlag,
      amountCapFlag: row.amountCapFlag,
      interestSummary: row.interestSummary,
      grossProfit: row.grossProfit == null ? undefined : Number(row.grossProfit),
      grossMargin: row.grossMargin == null ? undefined : Number(row.grossMargin),
      purchaseMarginAmount: row.purchaseMarginAmount == null ? undefined : Number(row.purchaseMarginAmount),
      purchaseMarginRate: row.purchaseMarginRate == null ? undefined : Number(row.purchaseMarginRate),
      purchaseMarginNote: row.purchaseMarginNote ?? undefined,
      purchaseFreightCost: row.purchaseFreightCost == null ? undefined : Number(row.purchaseFreightCost),
      purchaseSalesFreight: row.purchaseSalesFreight == null
        ? undefined
        : Number(row.purchaseSalesFreight),
      deliveryCostPendingCount: row.deliveryCostPendingCount == null
        ? undefined
        : Number(row.deliveryCostPendingCount),
      purchaseMarginGap: row.purchaseMarginGap == null ? undefined : Number(row.purchaseMarginGap),
      freightItems: row.freightItems,
      projectManager: row.projectManager,
      status
    };
  });

const navItems: Array<{
  key: PageKey;
  label: string;
  description: string;
  icon: typeof LayoutDashboard;
}> = [
  { key: 'workspace', label: '智能助理', description: '对话识别与处理', icon: MessageSquareText },
  { key: 'projects', label: '项目与批次', description: '核心业务单位', icon: FolderKanban },
  { key: 'documents', label: '数据中心', description: '业务数据与原始资料', icon: FileSearch },
  { key: 'ledger', label: '对账台账', description: '票款货核对', icon: TableProperties },
  { key: 'company', label: '公司往来', description: '供货商发票往来', icon: Building2 },
  { key: 'market', label: '市场行情', description: '价格对比与行情', icon: TrendingUp }
];

function StatusBadge({ children, tone }: { children: ReactNode; tone: string }) {
  return <span className={`status-badge ${tone}`}>{children}</span>;
}

function Panel({
  title,
  eyebrow,
  action,
  className = '',
  children
}: {
  title?: string;
  eyebrow?: string;
  action?: ReactNode;
  className?: string;
  children: ReactNode;
}) {
  return (
    <section className={`panel ${className}`}>
      {(title || action) && (
        <div className="panel-heading">
          <div>
            {eyebrow && <span className="eyebrow">{eyebrow}</span>}
            {title && <h2>{title}</h2>}
          </div>
          {action}
        </div>
      )}
      {children}
    </section>
  );
}

function Metric({
  label,
  value,
  hint,
  tone = ''
}: {
  label: string;
  value: string;
  hint?: string;
  tone?: string;
}) {
  return (
    <div className={`metric ${tone}`}>
      <span>{label}</span>
      <strong>{value}</strong>
      {hint && <small>{hint}</small>}
    </div>
  );
}

function Sidebar({
  page,
  onNavigate,
  mobileOpen,
  onClose,
  onSettings
}: {
  page: PageKey;
  onNavigate: (page: PageKey) => void;
  mobileOpen: boolean;
  onClose: () => void;
  onSettings: () => void;
}) {
  return (
    <>
      <button
        className={`sidebar-backdrop ${mobileOpen ? 'visible' : ''}`}
        aria-label="关闭菜单"
        onClick={onClose}
      />
      <aside className={`sidebar ${mobileOpen ? 'mobile-open' : ''}`}>
        <div className="brand">
          <div className="brand-mark">
            <Boxes size={22} strokeWidth={2.3} />
          </div>
          <div>
            <strong>衡账</strong>
            <span>AI 项目对账平台</span>
          </div>
          <button className="mobile-close" onClick={onClose} aria-label="关闭菜单">
            <X size={19} />
          </button>
        </div>

        <nav className="nav-list" aria-label="主导航">
          {navItems.map((item) => {
            const Icon = item.icon;
            return (
              <button
                key={item.key}
                className={page === item.key ? 'active' : ''}
                onClick={() => onNavigate(item.key)}
              >
                <Icon size={18} />
                <span>
                  <strong>{item.label}</strong>
                  <small>{item.description}</small>
                </span>
                <ChevronRight size={15} className="nav-arrow" />
              </button>
            );
          })}
        </nav>

        <div className="sidebar-group sidebar-settings">
          <button onClick={onSettings}>
            <Settings size={17} />
            系统设置
          </button>
        </div>
      </aside>
    </>
  );
}

function AppHeader({
  page,
  projectsData,
  currentProject,
  currentBatch,
  profile,
  onProjectChange,
  onBatchChange,
  onMenu,
  onLogout,
  onSwitchIdentity,
  onSettings
}: {
  page: PageKey;
  projectsData: Project[];
  currentProject?: Project;
  currentBatch?: Batch;
  profile: ApiUserProfile;
  onProjectChange: (id: string) => void;
  onBatchChange: (id: string) => void;
  onMenu: () => void;
  onLogout: () => void;
  onSwitchIdentity: () => void;
  onSettings: () => void;
}) {
  const currentNav = navItems.find((item) => item.key === page)!;
  const [billingOpen, setBillingOpen] = useState(false);
  const [credit, setCredit] = useState<ApiCreditOverview | null>(null);
  const [usageLogs, setUsageLogs] = useState<ApiUsageLog[]>([]);
  const [billingLoading, setBillingLoading] = useState(false);

  async function openBilling() {
    setBillingOpen(true);
    setOpenPicker(null);
    setBillingLoading(true);
    try {
      const [c, logs] = await Promise.all([
        api.billingCredits(),
        api.billingUsageLogs(0, 50)
      ]);
      setCredit(c);
      setUsageLogs(logs.items);
    } finally {
      setBillingLoading(false);
    }
  }
  const [openPicker, setOpenPicker] = useState<'project' | 'batch' | 'user' | null>(null);

  useEffect(() => {
    if (!openPicker) return;
    const closeOnOutsideClick = (event: PointerEvent) => {
      const target = event.target as Element | null;
      if (!target?.closest('.context-picker')) {
        setOpenPicker(null);
      }
    };
    document.addEventListener('pointerdown', closeOnOutsideClick);
    return () => document.removeEventListener('pointerdown', closeOnOutsideClick);
  }, [openPicker]);

  return (
    <header className="top-header">
      {openPicker && (
        <button
          className="context-picker-backdrop"
          aria-label="关闭下拉菜单"
          onClick={() => setOpenPicker(null)}
        />
      )}
      <button className="menu-button" onClick={onMenu} aria-label="打开菜单">
        <Menu size={21} />
      </button>
      <div className="page-heading">
        <h1>{currentNav.label}</h1>
      </div>
      {currentProject ? <div className="context-selectors">
        <div className={`context-picker ${openPicker === 'project' ? 'open' : ''}`}>
          <button
            className="context-trigger"
            aria-expanded={openPicker === 'project'}
            onClick={() => setOpenPicker(openPicker === 'project' ? null : 'project')}
          >
            <span className="context-trigger-icon">
              <FolderKanban size={16} />
            </span>
            <span className="context-trigger-copy">
              <small>当前项目</small>
              <strong>{currentProject.name}</strong>
            </span>
            <ChevronDown size={15} />
          </button>
          {openPicker === 'project' && (
            <div className="context-menu project-menu">
              <div className="context-menu-heading">
                <span>切换项目</span>
                <small>{projectsData.length} 个项目</small>
              </div>
              <div className="context-menu-list">
                {projectsData.map((project) => (
                  <button
                    key={project.id}
                    className={project.id === currentProject.id ? 'active' : ''}
                    onClick={() => {
                      onProjectChange(project.id);
                      setOpenPicker(null);
                    }}
                  >
                    <span className="context-option-mark">{project.name.slice(0, 1)}</span>
                    <span>
                      <strong>{project.name}</strong>
                      <small>{project.batches.length} 个批次 · {project.status}</small>
                    </span>
                    {project.id === currentProject.id && <Check size={15} />}
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
        {currentBatch && (
          <div className={`context-picker ${openPicker === 'batch' ? 'open' : ''}`}>
            <button
              className="context-trigger batch-trigger"
              aria-expanded={openPicker === 'batch'}
              onClick={() => setOpenPicker(openPicker === 'batch' ? null : 'batch')}
            >
              <span className="context-trigger-icon batch">
                <Gauge size={16} />
              </span>
              <span className="context-trigger-copy">
                <small>当前批次</small>
                <strong>{currentBatch.name}</strong>
              </span>
              <ChevronDown size={15} />
            </button>
            {openPicker === 'batch' && (
              <div className="context-menu batch-menu">
                <div className="context-menu-heading">
                  <span>切换批次</span>
                  <small>{currentProject.name}</small>
                </div>
                <div className="context-menu-list">
                  {currentProject.batches.map((batch) => (
                    <button
                      key={batch.id}
                      className={batch.id === currentBatch.id ? 'active' : ''}
                      onClick={() => {
                        onBatchChange(batch.id);
                        setOpenPicker(null);
                      }}
                    >
                      <span className="batch-option-date">
                        <strong>{batch.date.slice(8, 10)}</strong>
                        <small>{batch.date.slice(5, 7)}月</small>
                      </span>
                      <span>
                        <strong>{batch.name}</strong>
                        <small>{batch.material} · 对账 {batch.progress}%</small>
                      </span>
                      {batch.id === currentBatch.id && <Check size={15} />}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </div> : null}
      <div className={`header-user context-picker ${openPicker === 'user' ? 'open' : ''} ${currentProject ? '' : 'no-context'}`}>
        <button
          className="header-user-trigger"
          aria-expanded={openPicker === 'user'}
          aria-label="打开用户菜单"
          onClick={() => setOpenPicker(openPicker === 'user' ? null : 'user')}
        >
          <div className="avatar small">{profile.nickname.slice(0, 1) || '管'}</div>
          <span className="header-user-copy">
            <strong>{profile.nickname}</strong>
            <small>{profile.roleName}</small>
          </span>
          <ChevronDown size={15} />
        </button>
        {openPicker === 'user' && (
          <div className="context-menu user-menu">
            <div className="user-menu-profile">
              <div className="avatar">{profile.nickname.slice(0, 1) || '管'}</div>
              <span>
                <strong>{profile.nickname}</strong>
                <small>{profile.companyName || '尚未设置本单位身份'}</small>
              </span>
            </div>
            <button
              className="user-menu-item"
              onClick={() => {
                setOpenPicker(null);
                onSettings();
              }}
            >
              <UserRound size={16} />
              个人资料
            </button>
            <button className="user-menu-item" onClick={openBilling}>
              <Coins size={16} />
              积分与消费明细
            </button>
            {profile.superAdmin && (
              <button
                className="user-menu-item"
                onClick={() => {
                  setOpenPicker(null);
                  onSwitchIdentity();
                }}
              >
                <UserRound size={16} />
                {profile.actingAs ? '切换其他用户身份' : '切换查看身份'}
              </button>
            )}
            <button
              className="user-menu-logout"
              onClick={() => {
                setOpenPicker(null);
                onLogout();
              }}
            >
              <LogOut size={16} />
              退出登录
            </button>
          </div>
        )}
      </div>
      {billingOpen && (
        <div className="modal-shell" role="dialog" aria-modal="true" aria-label="积分与消费明细" onClick={() => setBillingOpen(false)}>
          <div className="modal-card billing-modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-head">
              <h3>积分与消费明细</h3>
              <button className="modal-close" onClick={() => setBillingOpen(false)} aria-label="关闭">
                <X size={18} />
              </button>
            </div>
            {billingLoading ? (
              <div className="billing-loading">加载中…</div>
            ) : (
              <div className="billing-body">
                <div className="billing-summary">
                  <div className="billing-stat">
                    <small>本月剩余积分</small>
                    <strong>{credit ? Number(credit.balance).toFixed(2) : '0.00'}</strong>
                  </div>
                  <div className="billing-stat">
                    <small>本月已用</small>
                    <strong>{credit ? Number(credit.usedThisMonth).toFixed(2) : '0.00'}</strong>
                  </div>
                  <div className="billing-stat">
                    <small>月配额</small>
                    <strong>{credit ? Number(credit.monthlyQuota).toFixed(2) : '0.00'}</strong>
                  </div>
                </div>
                {credit?.periodEnd && (
                  <p className="billing-hint">积分将于 {credit.periodEnd.slice(0, 10)} 刷新</p>
                )}
                <h4>消费记录（共 {usageLogs.length} 条）</h4>
                <div className="billing-logs">
                  {usageLogs.length === 0 ? (
                    <div className="billing-empty">暂无消费记录</div>
                  ) : (
                    usageLogs.map((log) => (
                      <div className="billing-log-row" key={log.id}>
                        <span className="billing-log-type">
                          {log.businessType === 'market_match' ? '行情匹配'
                            : log.businessType === 'material_match' ? '材料判同'
                            : log.businessType === 'material_quote' ? '材料网价绑定'
                            : '智能助理'}
                        </span>
                        <span className="billing-log-cost">-{Number(log.costCredits).toFixed(2)}</span>
                        <span className="billing-log-time">
                          {log.createdAt ? log.createdAt.slice(0, 16).replace('T', ' ') : ''}
                        </span>
                      </div>
                    ))
                  )}
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </header>
  );
}

/**
 * 允许账号密码登录的环境：develop=本地联调；trial=体验版。
 * trial 也要开：小程序未完成主体认证时微信直接拒绝生成小程序码（85411/85412），
 * 扫码这条路完全不可用，必须留一条能进系统的路（2026-11-06）。
 */
const PASSWORD_LOGIN_ENVS = ['develop', 'trial'];

/** 把微信原件错误翻译成人话：85411/85412 = 个人主体未认证，小程序码接口被拒。 */
const friendlyQrError = (message: string) =>
  /personal entity not verified|8541[12]/i.test(message)
    ? '该小程序未完成微信主体认证，暂不支持小程序码登录，请改用「账号密码」登录。'
    : message;

function LoginPage({
  busy,
  error,
  onLoginSuccess
}: {
  busy: boolean;
  error: string;
  onLoginSuccess: (result?: { user: ApiUserProfile }) => Promise<void>;
}) {
  // 微信扫码登录：会话、二维码、轮询状态
  const [qrState, setQrState] = useState('');
  const [qrImage, setQrImage] = useState('');
  const [qrStatus, setQrStatus] = useState<'loading' | 'pending' | 'expired' | 'done'>('loading');
  const [qrError, setQrError] = useState('');
  const pollTimerRef = useRef<number | undefined>(undefined);
  // 代际守卫：StrictMode 下 effect 会执行两次，避免过期的 create 调用覆盖当前会话
  const qrGenRef = useRef(0);
  // 小程序码版本：后端配置 qr-env-version 透传过来；非白名单环境只给扫码
  const [envVersion, setEnvVersion] = useState('');
  const [mode, setMode] = useState<'qr' | 'password'>('qr');
  const modeTouchedRef = useRef(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [passwordBusy, setPasswordBusy] = useState(false);
  const [passwordError, setPasswordError] = useState('');
  const allowPassword = PASSWORD_LOGIN_ENVS.includes(envVersion);

  const clearQrPolling = () => {
    if (pollTimerRef.current !== undefined) {
      window.clearInterval(pollTimerRef.current);
      pollTimerRef.current = undefined;
    }
  };

  const startQrSession = async () => {
    const myGen = ++qrGenRef.current;
    clearQrPolling();
    setQrError('');
    setQrStatus('loading');
    try {
      const session = await api.wechatQr();
      // 已经有更新的会话被发起，本次结果作废，避免 state/轮询错乱
      if (myGen !== qrGenRef.current) {
        return;
      }
      if (session.envVersion) {
        setEnvVersion(session.envVersion);
      }
      setQrState(session.state);
      if (session.error) {
        setQrError(session.error);
        setQrStatus('expired');
        return;
      }
      // 后端返回小程序码（data URL），供微信相机「扫一扫」扫描确认登录
      setQrImage(session.qrImageDataUrl || '');
      setQrStatus('pending');
      pollTimerRef.current = window.setInterval(async () => {
        try {
          const status = await api.wechatQrStatus(session.state);
          if (status.status === 'success' && status.token) {
            clearQrPolling();
            localStorage.setItem('ai_bot_token', status.token);
            setQrStatus('done');
            await onLoginSuccess();
          } else if (status.status === 'expired') {
            clearQrPolling();
            setQrStatus('expired');
          }
        } catch {
          // 轮询失败静默，下一轮重试
        }
      }, 2500);
    } catch (sessionError) {
      setQrError(sessionError instanceof Error ? sessionError.message : '获取登录二维码失败');
      setQrStatus('expired');
    }
  };

  useEffect(() => {
    let alive = true;
    // 先取公开配置：允许账号密码的环境直接进密码登录，不用等小程序码；其他环境照旧扫码
    void api.authConfig()
      .then((config) => {
        if (!alive) {
          return;
        }
        const version = config.qrEnvVersion || '';
        setEnvVersion(version);
        if (PASSWORD_LOGIN_ENVS.includes(version)) {
          setMode('password');
          return;
        }
        void startQrSession();
      })
      .catch(() => {
        if (alive) {
          void startQrSession();
        }
      });
    return () => {
      alive = false;
      qrGenRef.current++;
      clearQrPolling();
    };
  }, []);

  // 允许密码登录的环境默认停在账号密码；用户手动切过之后不再抢占
  useEffect(() => {
    if (allowPassword && !modeTouchedRef.current) {
      setMode('password');
      clearQrPolling();
    }
  }, [allowPassword]);

  const switchMode = (next: 'qr' | 'password') => {
    modeTouchedRef.current = true;
    setPasswordError('');
    setMode(next);
    if (next === 'password') {
      clearQrPolling();
      return;
    }
    void startQrSession();
  };

  const submitPassword = async (event: FormEvent) => {
    event.preventDefault();
    if (passwordBusy) {
      return;
    }
    setPasswordError('');
    setPasswordBusy(true);
    try {
      const result = await api.login(username.trim(), password);
      await onLoginSuccess(result);
    } catch (loginFailure) {
      setPasswordError(loginFailure instanceof Error ? loginFailure.message : '登录失败');
    } finally {
      setPasswordBusy(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-atmosphere login-atmosphere-one" />
      <div className="login-atmosphere login-atmosphere-two" />
      <section className="login-intro">
        <div className="login-brand">
          <span><Boxes size={24} /></span>
          <strong>衡账</strong>
        </div>
        <div className="login-copy">
          <h1>O L S 对账系统</h1>
          <p>归集采购、送达、票据和资金信息，持续完善项目台账。</p>
        </div>
      </section>
      <div className="login-card">
        {allowPassword && (
          <div className="login-tabs" role="tablist" aria-label="登录方式">
            <button
              type="button"
              role="tab"
              aria-selected={mode === 'password'}
              className={mode === 'password' ? 'active' : ''}
              onClick={() => switchMode('password')}
            >
              账号密码
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={mode === 'qr'}
              className={mode === 'qr' ? 'active' : ''}
              onClick={() => switchMode('qr')}
            >
              微信扫码
            </button>
          </div>
        )}
        {mode === 'password' ? (
          <form className="login-form" onSubmit={submitPassword}>
            <div className="login-form-head">
              <div className="login-card-icon"><LogIn size={22} /></div>
              <div>
                <h2>账号登录</h2>
                <p>
                  {envVersion === 'develop'
                    ? '开发环境专用，输入用户名和密码即可进入系统。'
                    : '试用环境暂未开通微信扫码登录，请用账号密码进入。'}
                </p>
              </div>
            </div>

            <label className="entity-field">
              <span>用户名</span>
              <input
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                placeholder="请输入用户名"
                autoComplete="username"
                required
              />
            </label>
            <label className="entity-field">
              <span>密码</span>
              <input
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                placeholder="请输入密码"
                autoComplete="current-password"
                required
              />
            </label>

            {passwordError && <div className="login-error"><AlertCircle size={15} />{passwordError}</div>}

            <button className="primary-button login-submit" type="submit" disabled={passwordBusy}>
              {passwordBusy ? '登录中…' : '登录'}
            </button>
          </form>
        ) : (
          <div className="login-form login-wechat">
            <div className="login-form-head">
              <div className="login-card-icon"><ScanLine size={22} /></div>
              <div>
                <h2>微信扫码登录</h2>
                <p>请使用微信「扫一扫」，扫描下方小程序码，在手机上确认登录。</p>
              </div>
            </div>

            <div className="login-qr-list">
              <div className="login-qr-item">
                <div className="login-qr-box">
                  {qrStatus === 'loading' && <div className="login-qr-placeholder">正在生成二维码…</div>}
                  {qrStatus === 'pending' && qrImage && (
                    <img className="login-qr-image" src={qrImage} alt="微信小程序码" />
                  )}
                  {qrStatus === 'pending' && !qrImage && (
                    <div className="login-qr-placeholder">小程序码生成失败，请刷新</div>
                  )}
                  {qrStatus === 'expired' && (
                    <div className="login-qr-placeholder">
                      <div>{qrError ? '小程序码暂不可用' : '二维码已失效'}</div>
                      <button type="button" className="login-qr-refresh" onClick={() => void startQrSession()}>刷新二维码</button>
                    </div>
                  )}
                  {qrStatus === 'done' && <div className="login-qr-placeholder">登录成功，正在进入系统…</div>}
                </div>
                <div className="login-qr-label">微信「扫一扫」扫描此小程序码</div>
              </div>
            </div>

            {qrError && <div className="login-error"><AlertCircle size={15} />{friendlyQrError(qrError)}</div>}

            <div className="login-qr-tip">扫码后，在微信小程序中点击「确认登录」即可进入系统</div>
          </div>
        )}
      </div>
    </div>
  );
}

function SessionLoading() {
  return (
    <div className="session-loading">
      <span><Boxes size={24} /></span>
      <strong>正在恢复登录状态</strong>
    </div>
  );
}

function EmptyBaselinePage({
  hasProject,
  onCreateProject,
  onCreateBatch
}: {
  hasProject: boolean;
  onCreateProject: () => void;
  onCreateBatch: () => void;
}) {
  return (
    <div className="baseline-empty page-enter">
      <div className="baseline-empty-visual">
        <span><FolderKanban size={34} /></span>
        <i />
        <i />
      </div>
      <h2>{hasProject ? '暂无批次' : '暂无项目'}</h2>
      <p>
        {hasProject
          ? '当前项目还没有批次。可以为本项目新建批次，或直接新建另一个项目。'
          : '先创建项目和首个批次，再开始导入资料。'}
      </p>
      <div className="baseline-empty-actions">
        {hasProject ? (
          <>
            <button className="primary-button" onClick={onCreateBatch}>
              <Plus size={17} />
              新建批次
            </button>
            <button className="secondary-button" onClick={onCreateProject}>
              <Plus size={17} />
              新建项目
            </button>
          </>
        ) : (
          <button className="primary-button" onClick={onCreateProject}>
            <Plus size={17} />
            新建项目
          </button>
        )}
      </div>
    </div>
  );
}

function WorkspacePage({
  project,
  batch,
  documents,
  onNavigate
}: {
  project: Project;
  batch: Batch;
  documents: BusinessDocument[];
  onNavigate: (page: PageKey) => void;
}) {
  const [chatInput, setChatInput] = useState('');
  const [attachmentOpen, setAttachmentOpen] = useState(false);
  const [attachmentType, setAttachmentType] = useState('业务单据');
  const [uploadedFile, setUploadedFile] = useState('');
  const [recognitionStage, setRecognitionStage] = useState<'idle' | 'processing' | 'done'>('idle');
  const [confirmed, setConfirmed] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const [chatMessages, setChatMessages] = useState([
    {
      id: 1,
      role: 'ai',
      text: '你好，我是你的 AI 对账助理。你可以把采购单、送货单、请款单、收付款记录、进项发票或销项发票发给我，我会识别后匹配到当前项目和批次。'
    }
  ]);

  const submitChat = (event: FormEvent) => {
    event.preventDefault();
    const message = chatInput.trim();
    if (!message) return;
    setChatMessages((items) => [...items, { id: Date.now(), role: 'user', text: message }]);
    setChatInput('');
    window.setTimeout(() => {
      setChatMessages((items) => [
        ...items,
        {
          id: Date.now() + 1,
          role: 'ai',
          text: `我查了「${batch.name}」。目前采购、到货信息已经归集，付款和发票之间还有 ${money(
            Math.abs(batch.paid - batch.invoiced)
          )} 的差额。你可以继续上传付款回单或发票，我会自动尝试匹配。`
        }
      ]);
    }, 450);
  };

  const chooseAttachment = (type: string) => {
    setAttachmentType(type);
    setAttachmentOpen(false);
    if (type === '手动录入') {
      setChatMessages((items) => [
        ...items,
        {
          id: Date.now(),
          role: 'ai',
          text: '请直接输入记录内容，例如：“7月20日向上海找钢网付款 326954.07 元”，我会识别资金方向、金额、日期和往来单位。'
        }
      ]);
      return;
    }
    fileInput.current?.click();
  };

  const handleFileSelected = (file?: File) => {
    if (!file) return;
    setUploadedFile(file.name);
    setRecognitionStage('processing');
    setConfirmed(false);
    window.setTimeout(() => setRecognitionStage('done'), 850);
  };

  const unresolvedCount = documents.filter(
    (item) => item.status === '待确认' || item.status === '有差异'
  ).length;

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
                <strong>AI 项目对账助理</strong>
                <small>在线 · 支持多模态识别、意图判断和台账回填</small>
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

          <div className="assistant-chat-body">
            <div className="assistant-day">今天</div>
            {chatMessages.slice(0, 1).map((message) => (
              <div key={message.id} className="assistant-message ai">
                <span className="message-avatar">
                  <Bot size={16} />
                </span>
                <div className="message-content">
                  <p>{message.text}</p>
                  <small>10:26</small>
                </div>
              </div>
            ))}

            <div className="assistant-message user">
              <div className="message-content">
                <p>帮我识别这张采购单，并归集到当前批次。</p>
                <div className="chat-file">
                  <span>
                    <FileText size={18} />
                  </span>
                  <div>
                    <strong>找钢网销售单_20260720.pdf</strong>
                    <small>PDF · 2.4 MB</small>
                  </div>
                </div>
                <small>10:28</small>
              </div>
              <span className="message-avatar user">高</span>
            </div>

            <div className="assistant-message ai">
              <span className="message-avatar">
                <Bot size={16} />
              </span>
              <div className="message-content wide">
                <div className="ai-progress-message">
                  <span className="progress-check done">
                    <Check size={13} />
                  </span>
                  <span>大模型读取单据</span>
                  <i />
                  <span className="progress-check done">
                    <Check size={13} />
                  </span>
                  <span>单据意图判断</span>
                  <i />
                  <span className="progress-check done">
                    <Check size={13} />
                  </span>
                  <span>项目批次匹配</span>
                </div>
                <div className="chat-recognition-card">
                  <div className="chat-card-heading">
                    <span className="chat-card-icon supply">
                      <ClipboardCheck size={19} />
                    </span>
                    <span>
                      <strong>识别到采购单</strong>
                      <small>AI 置信度 98% · 已提取 15 个字段</small>
                    </span>
                    <StatusBadge tone={confirmed ? 'success' : 'warning'}>
                      {confirmed ? '已回填' : '待确认'}
                    </StatusBadge>
                  </div>

                  <div className="chat-match-proposal">
                    <span>
                      <small>建议归集项目</small>
                      <strong>{project.name}</strong>
                    </span>
                    <ArrowRight size={15} />
                    <span>
                      <small>建议归集批次</small>
                      <strong>{batch.name}</strong>
                    </span>
                    <span className="match-confidence">
                      <CheckCircle2 size={14} />
                      匹配度 96%
                    </span>
                  </div>

                  <div className="chat-field-grid">
                    <span>
                      <small>供应商</small>
                      <strong>上海找钢网信息科技股份有限公司</strong>
                    </span>
                    <span>
                      <small>销售单号</small>
                      <strong>XS26072019691979</strong>
                    </span>
                    <span>
                      <small>单据日期</small>
                      <strong>2026-07-20</strong>
                    </span>
                    <span>
                      <small>单据总额</small>
                      <strong className="highlight">{money(176_620.21)}</strong>
                    </span>
                  </div>

                  <div className="chat-material-table">
                    <div className="material-row head">
                      <span>材料</span>
                      <span>规格</span>
                      <span>数量</span>
                      <span>重量</span>
                      <span>金额</span>
                    </div>
                    <div className="material-row">
                      <span>三级抗震盘螺</span>
                      <span>HRB400E Φ8</span>
                      <span>3件</span>
                      <span>6.000吨</span>
                      <span>{money(19_920)}</span>
                    </div>
                    <div className="material-row">
                      <span>三级抗震螺纹钢</span>
                      <span>HRB400E 12×9</span>
                      <span>4件</span>
                      <span>9.592吨</span>
                      <span>{money(29_735.2)}</span>
                    </div>
                    <div className="material-row">
                      <span>三级抗震螺纹钢</span>
                      <span>HRB400E 16×9</span>
                      <span>17件</span>
                      <span>41.089吨</span>
                      <span>{money(126_965.01)}</span>
                    </div>
                  </div>

                  <div className="chat-card-note">
                    <AlertCircle size={15} />
                    尚未找到对应送货单。确认后先建立采购记录，后续上传送货单时自动补充到货信息。
                  </div>

                  <div className="chat-card-actions">
                    <button
                      className="primary-button"
                      onClick={() => setConfirmed(true)}
                      disabled={confirmed}
                    >
                      <Check size={15} />
                      {confirmed ? '已确认并回填' : '确认并回填台账'}
                    </button>
                    <button className="secondary-button">
                      <FileSearch size={15} />
                      修改识别字段
                    </button>
                    <button className="secondary-button">
                      <FolderKanban size={15} />
                      更换批次
                    </button>
                  </div>
                </div>
                <small>10:29</small>
              </div>
            </div>

            <div className="assistant-message ai">
              <span className="message-avatar">
                <Bot size={16} />
              </span>
              <div className="message-content wide">
                <p>
                  我还发现当前批次有一张发票与付款金额相差 {money(Math.abs(batch.paid - batch.invoiced))}，
                  可能是运输费单独结算。你可以把运输费用凭证或付款回单发给我继续核对。
                </p>
                <div className="chat-suggestion-row">
                  <button onClick={() => chooseAttachment('运输费用凭证')}>
                    <Truck size={15} />
                    上传运输凭证
                  </button>
                  <button onClick={() => onNavigate('ledger')}>
                    <TableProperties size={15} />
                    查看差异台账
                  </button>
                </div>
                <small>10:29</small>
              </div>
            </div>

            {uploadedFile && (
              <>
                <div className="assistant-message user">
                  <div className="message-content">
                    <div className="chat-file">
                      <span>
                        <FilePlus2 size={18} />
                      </span>
                      <div>
                        <strong>{uploadedFile}</strong>
                        <small>{attachmentType} · 等待 AI 处理</small>
                      </div>
                    </div>
                    <small>刚刚</small>
                  </div>
                  <span className="message-avatar user">高</span>
                </div>
                <div className="assistant-message ai">
                  <span className="message-avatar">
                    <Bot size={16} />
                  </span>
                  <div className="message-content wide">
                    {recognitionStage === 'processing' ? (
                      <div className="chat-processing-card">
                        <span className="processing-orb">
                          <ScanLine size={20} />
                        </span>
                        <span>
                          <strong>正在识别 {attachmentType}</strong>
                          <small>提取文字、判断业务意图并匹配当前批次...</small>
                        </span>
                        <span className="typing-dots">
                          <i />
                          <i />
                          <i />
                        </span>
                      </div>
                    ) : (
                      <div className="chat-complete-card">
                        <span>
                          <CheckCircle2 size={20} />
                        </span>
                        <div>
                          <strong>识别完成，已生成待确认卡片</strong>
                          <small>识别为「{attachmentType}」，匹配到 {batch.name}，置信度 95%</small>
                        </div>
                        <button>查看结果</button>
                      </div>
                    )}
                  </div>
                </div>
              </>
            )}

            {chatMessages.slice(1).map((message) => (
              <div key={message.id} className={`assistant-message ${message.role}`}>
                {message.role === 'ai' && (
                  <span className="message-avatar">
                    <Bot size={16} />
                  </span>
                )}
                <div className="message-content">
                  <p>{message.text}</p>
                  <small>刚刚</small>
                </div>
                {message.role === 'user' && <span className="message-avatar user">高</span>}
              </div>
            ))}
          </div>

          <div className="assistant-composer-wrap">
            {attachmentOpen && (
              <div className="attachment-menu">
                <div className="attachment-menu-heading">
                  <span>
                    <Paperclip size={15} />
                    添加到对话
                  </span>
                  <small>AI 将根据内容自动判断单据类型</small>
                </div>
                <div className="attachment-options">
                  {[
                    ['图片 / PDF / 文件', ImageIcon, '业务单据'],
                    ['采购单', ClipboardCheck, '采购单'],
                    ['送货单', PackageCheck, '送货单'],
                    ['运输费用', Truck, '运输费用'],
                    ['收付款记录', Banknote, '收付款记录'],
                    ['进项 / 销项发票', ReceiptText, '发票'],
                    ['手动录入', FilePlus2, '手动录入']
                  ].map(([label, Icon, type]) => {
                    const AttachmentIcon = Icon as typeof ImageIcon;
                    return (
                      <button key={String(label)} onClick={() => chooseAttachment(String(type))}>
                        <span>
                          <AttachmentIcon size={18} />
                        </span>
                        <strong>{String(label)}</strong>
                      </button>
                    );
                  })}
                </div>
              </div>
            )}
            <form className="assistant-composer" onSubmit={submitChat}>
              <input
                ref={fileInput}
                className="hidden-file-input"
                type="file"
                accept="image/*,.pdf,.xlsx,.xls,.doc,.docx"
                onChange={(event) => handleFileSelected(event.target.files?.[0])}
              />
              <button
                type="button"
                className={attachmentOpen ? 'active' : ''}
                aria-label="添加附件"
                onClick={() => setAttachmentOpen((value) => !value)}
              >
                {attachmentOpen ? <X size={19} /> : <Paperclip size={19} />}
              </button>
              <input
                value={chatInput}
                onChange={(event) => setChatInput(event.target.value)}
                placeholder="输入问题，或点击左侧附件上传业务资料..."
              />
              <button type="button" aria-label="更多输入方式">
                <MessageSquareText size={18} />
              </button>
              <button type="submit" className="send-button" aria-label="发送">
                <Send size={17} />
              </button>
            </form>
            <p>AI 识别结果需要人工确认后才会正式回填台账。</p>
          </div>
        </section>

        <aside className="assistant-history">
          <div className="history-heading">
            <div>
              <h2>对话记录</h2>
            </div>
            <button aria-label="新建对话">
              <Plus size={17} />
            </button>
          </div>
          <label className="history-search">
            <Search size={15} />
            <input placeholder="搜索对话或单据" />
          </label>
          <div className="history-group">
            <span>今天</span>
            <button className="active">
              <MessageSquareText size={16} />
              <span>
                <strong>识别采购单并归集</strong>
                <small>{batch.name} · 10:29</small>
              </span>
            </button>
            <button>
              <AlertCircle size={16} />
              <span>
                <strong>查询票款差异</strong>
                <small>{project.name} · 09:46</small>
              </span>
            </button>
          </div>
          <div className="history-group">
            <span>最近 7 天</span>
            <button>
              <ReceiptText size={16} />
              <span>
                <strong>发票回填与税率核对</strong>
                <small>九堡小学项目 · 周一</small>
              </span>
            </button>
            <button>
              <Banknote size={16} />
              <span>
                <strong>付款短信批量归档</strong>
                <small>滨江实验中学 · 7月18日</small>
              </span>
            </button>
          </div>
          <div className="assistant-context-note">
            <Sparkles size={17} />
            <span>
              <strong>当前对话上下文</strong>
              <small>{unresolvedCount} 项待处理，AI 将优先使用当前项目与批次进行匹配。</small>
            </span>
          </div>
        </aside>
      </div>
    </div>
  );
}

function ProjectsPage({
  projectsData,
  selectedProject,
  selectedBatch,
  documents,
  records,
  onProjectChange,
  onBatchChange,
  onNavigate,
  onCreateProject,
  onCreateBatch,
  onDeleteProject,
  onDeleteBatch,
  onEditProject,
  onEditBatch,
  onToggleReconciled,
  onReceiptsChanged,
  onToast
}: {
  projectsData: Project[];
  selectedProject: Project;
  selectedBatch: Batch;
  documents: BusinessDocument[];
  records: ApiBusinessDataRecord[];
  onProjectChange: (id: string) => void;
  onBatchChange: (id: string) => void;
  onNavigate: (page: PageKey) => void;
  onCreateProject: () => void;
  onCreateBatch: () => void;
  onDeleteProject: (project: Project) => void;
  onDeleteBatch: (batch: Batch, project: Project) => void;
  onEditProject: (project: Project) => void;
  onEditBatch: (batch: Batch, project: Project) => void;
  onToggleReconciled: (batch: Batch, reconciled: boolean) => void;
  onReceiptsChanged: () => Promise<void> | void;
  onToast: (message: string) => void;
}) {
  const batchDocuments = documents.filter((item) => item.batchId === selectedBatch.id);
  const deliverySummary = summarizeBatchDelivery(
    records.filter((record) => record.document.batchId === selectedBatch.id)
  );
  const deliveryDone = ['已送达', '超供送达'].includes(deliverySummary.status);
  const deliveryStarted = ['已送达', '部分送达', '超供送达'].includes(deliverySummary.status);
  const deliveryBaseValue = deliverySummary.expectedMaterialCount > 0
    ? `${deliverySummary.status} · ${deliverySummary.deliveredMaterialCount}/${deliverySummary.expectedMaterialCount} 类材料`
    : deliverySummary.deliveryDocumentCount > 0 ? '待核对送货资料' : '尚未送达';
  const deliveryValue = deliverySummary.unmatchedDeliveryItemCount > 0
    ? `${deliveryBaseValue} · ${deliverySummary.unmatchedDeliveryItemCount} 条待匹配`
    : deliveryBaseValue;
  const unresolvedDocuments = batchDocuments.filter(
    (item) => item.status === '待确认' || item.status === '有差异'
  );

  return (
    <div className="standard-page page-enter">
      <div className="page-title-row">
        <div>
          <h2>项目与批次</h2>
          <p>按项目查看整体进度，并在批次中管理具体业务。</p>
        </div>
        <button className="primary-button" onClick={onCreateProject}>
          <Plus size={17} />
          新建项目
        </button>
      </div>

      <div className="project-layout">
        <Panel
          title="项目列表"
          eyebrow={`${projectsData.length} 个项目`}
          className="project-list-panel"
        >
          <div className="project-list">
            {projectsData.map((project) => (
              <button
                key={project.id}
                className={project.id === selectedProject.id ? 'active' : ''}
                onClick={() => onProjectChange(project.id)}
              >
                <div className="project-letter">{project.name.slice(0, 1)}</div>
                <span>
                  <strong>{project.name}</strong>
                  <small>{project.customer}</small>
                </span>
                <span className="project-list-meta">
                  <StatusBadge tone={statusTone[project.status]}>{project.status}</StatusBadge>
                  <small>{project.batches.length} 个批次</small>
                </span>
                <span className="project-ops">
                  <span
                    className="project-edit"
                    role="button"
                    tabIndex={0}
                    aria-label={`编辑项目 ${project.name}`}
                    title="编辑项目"
                    onClick={(event) => {
                      event.stopPropagation();
                      onEditProject(project);
                    }}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.stopPropagation();
                        onEditProject(project);
                      }
                    }}
                  >
                    <PencilLine size={15} />
                  </span>
                  <span
                    className="project-delete"
                    role="button"
                    tabIndex={0}
                    aria-label={`删除项目 ${project.name}`}
                    title="删除项目"
                    onClick={(event) => {
                      event.stopPropagation();
                      onDeleteProject(project);
                    }}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.stopPropagation();
                        onDeleteProject(project);
                      }
                    }}
                  >
                    <Trash2 size={15} />
                  </span>
                </span>
              </button>
            ))}
          </div>
        </Panel>

        <div className="project-main">
          <section className="project-hero">
            <div>
              <span className="eyebrow">当前项目</span>
              <h2>{selectedProject.name}</h2>
              <p>{selectedProject.customer}</p>
            </div>
            <div className="project-hero-state">
              <StatusBadge tone={statusTone[selectedProject.status]}>
                {selectedProject.status}
              </StatusBadge>
              <strong>{selectedProject.progress}%</strong>
              <span>综合进度</span>
            </div>
          </section>
          <div className="project-finance">
            <section>
              <div className="finance-group-head">
                <strong>采购汇总</strong>
                <span>应付口径</span>
              </div>
              <div className="finance-metrics">
                <Metric
                  label="采购合同总额"
                  value={money(selectedProject.purchase)}
                  hint="与台账同源，含采购运费"
                />
                <Metric
                  label="总收票"
                  value={money(selectedProject.invoice)}
                  hint="进项发票累计"
                />
                <Metric
                  label="总付款"
                  value={money(selectedProject.paid)}
                  hint="已付供应商累计"
                />
                <Metric
                  label="未付款"
                  value={money(selectedProject.unpaidPurchase)}
                  hint="合同总额 − 总付款"
                />
                <Metric
                  label="未收发票"
                  value={money(selectedProject.uninvoicedPurchase)}
                  hint="总付款 − 总收票"
                />
              </div>
            </section>
            <section>
              <div className="finance-group-head">
                <strong>销售汇总</strong>
                <span>应收口径</span>
              </div>
              <div className="finance-metrics">
                <Metric
                  label="销售合同总额"
                  value={money(selectedProject.sales)}
                  hint="与台账同源，含销售运费"
                />
                <Metric
                  label="总开票"
                  value={money(selectedProject.salesInvoiced)}
                  hint="销项发票累计"
                />
                <Metric
                  label="总收款"
                  value={money(selectedProject.received)}
                  hint="客户回款累计"
                />
                <Metric
                  label="应收差额"
                  value={money(selectedProject.differenceAmount)}
                  hint="合同总额 − 总收款，负值表示多收"
                  tone={selectedProject.differenceAmount < 0 ? 'danger' : ''}
                />
                <Metric
                  label="剩余应收"
                  value={money(selectedProject.receivable)}
                  hint="应收差额下限为 0，与台账一致"
                />
                <Metric
                  label="未开票"
                  value={money(selectedProject.uninvoicedSales)}
                  hint="合同总额 − 总开票"
                />
              </div>
            </section>
            <section>
              <div className="finance-group-head">
                <strong>毛利</strong>
                <span>合同额口径</span>
              </div>
              <div className="finance-metrics duo">
                <Metric
                  label="毛利"
                  value={money(selectedProject.profit)}
                  hint="销售合同总额 − 采购合同总额"
                />
                <Metric
                  label="平均毛利率"
                  value={`${selectedProject.marginRate.toFixed(2)}%`}
                  hint="毛利 ÷ 销售合同总额"
                />
              </div>
            </section>
          </div>
          <Panel
            title="批次列表"
            action={
              <button className="small-button" onClick={onCreateBatch}>
                <Plus size={15} />
                新建批次
              </button>
            }
          >
            <div className="batch-table-wrap">
              <table className="data-table batch-table">
                <thead>
                  <tr>
                    <th>批次</th>
                    <th>材料</th>
                    <th>采购金额</th>
                    <th>已付款</th>
                    <th>已开票</th>
                    <th>是否已对账</th>
                    <th>是否已收款</th>
                    <th className="batch-op-col">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {selectedProject.batches.map((batch) => (
                    <tr
                      key={batch.id}
                      className={batch.id === selectedBatch.id ? 'selected-row' : ''}
                      onClick={() => onBatchChange(batch.id)}
                    >
                      <td>
                        <strong>{batch.name}</strong>
                        <small>{batch.date}</small>
                      </td>
                      <td>{batch.material}</td>
                      <td>{money(batch.purchase)}</td>
                      <td>{money(batch.paid)}</td>
                      <td>{money(batch.invoiced)}</td>
                      <td onClick={(event) => event.stopPropagation()}>
                        <label className="reconciled-toggle" title="手动勾选：是否已对账">
                          <input
                            type="checkbox"
                            checked={batch.reconciled}
                            onChange={(event) => onToggleReconciled(batch, event.target.checked)}
                            aria-label={`${batch.name} 是否已对账`}
                          />
                          <span>{batch.reconciled ? '已对账' : '未对账'}</span>
                        </label>
                      </td>
                      <td>
                        <StatusBadge tone={receiptTone[batch.receiptStatus]}>{batch.receiptStatus}</StatusBadge>
                      </td>
                      <td className="batch-op-col">
                        <span className="batch-ops">
                          <span
                            className="batch-edit"
                            role="button"
                            tabIndex={0}
                            aria-label={`编辑批次 ${batch.name}`}
                            title="编辑批次"
                            onClick={(event) => {
                              event.stopPropagation();
                              onEditBatch(batch, selectedProject);
                            }}
                            onKeyDown={(event) => {
                              if (event.key === 'Enter' || event.key === ' ') {
                                event.stopPropagation();
                                onEditBatch(batch, selectedProject);
                              }
                            }}
                          >
                            <PencilLine size={15} />
                          </span>
                          <span
                            className="batch-delete"
                            role="button"
                            tabIndex={0}
                            aria-label={`删除批次 ${batch.name}`}
                            title="删除批次"
                            onClick={(event) => {
                              event.stopPropagation();
                              onDeleteBatch(batch, selectedProject);
                            }}
                            onKeyDown={(event) => {
                              if (event.key === 'Enter' || event.key === ' ') {
                                event.stopPropagation();
                                onDeleteBatch(batch, selectedProject);
                              }
                            }}
                          >
                            <Trash2 size={15} />
                          </span>
                          <ChevronRight size={16} />
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Panel>
          <ReceiptPanel
            projectId={selectedProject.id}
            refreshKey={selectedProject.batches.map((b) => `${b.id}:${b.sales}:${b.received}`).join('|')}
            onChanged={onReceiptsChanged}
            onToast={onToast}
          />
          <Panel
            title="当前批次业务闭环"
            eyebrow={selectedBatch.name}
            action={
              <button className="primary-button compact" onClick={() => onNavigate('workspace')}>
                <MessageSquareText size={15} />
                前往智能助理导入
              </button>
            }
          >
            <div className="batch-timeline">
              {[
                ['采购确认', selectedBatch.purchase, selectedBatch.purchase > 0],
                ['进项发票', selectedBatch.invoiced, selectedBatch.invoiced > 0],
                ['付款', selectedBatch.paid, selectedBatch.paid > 0],
                ['到货确认', deliveryValue, deliveryStarted],
                ['销项发票', selectedBatch.salesInvoiced, selectedBatch.salesInvoiced > 0],
                ['收款', selectedBatch.received, selectedBatch.received > 0]
              ].map(([label, value, done], index) => (
                <div className={`timeline-node ${done ? 'done' : ''}`} key={String(label)}>
                  <span>{done ? <Check size={15} /> : index + 1}</span>
                  <strong>{label}</strong>
                  <small>{typeof value === 'number' ? compactMoney(value) : value}</small>
                </div>
              ))}
            </div>
          </Panel>
          <div className="project-detail-grid">
            <Panel
              title="票款货匹配"
              className="match-panel project-match-panel"
              action={
                <StatusBadge tone={selectedBatch.progress > 80 ? 'success' : 'info'}>
                  {selectedBatch.progress}%
                </StatusBadge>
              }
            >
              <div className="match-flow">
                {[
                  {
                    label: '采购单',
                    count: batchDocuments.filter((item) => item.type === '采购单').length,
                    value: selectedBatch.purchase,
                    icon: ClipboardCheck,
                    done: selectedBatch.purchase > 0
                  },
                  {
                    label: '进项发票',
                    count: batchDocuments.filter((item) => item.type === '进项发票').length,
                    value: selectedBatch.invoiced,
                    detail: compactMoney(selectedBatch.invoiced),
                    icon: ReceiptText,
                    done: selectedBatch.invoiced > 0
                  },
                  {
                    label: '付款',
                    count: batchDocuments.filter((item) => item.type === '付款记录').length,
                    value: selectedBatch.paid,
                    detail: compactMoney(selectedBatch.paid),
                    icon: Banknote,
                    done: selectedBatch.paid > 0
                  },
                  {
                    label: '送货单',
                    count: deliverySummary.deliveryDocumentCount,
                    detail: deliveryValue,
                    icon: Truck,
                    done: deliveryDone,
                    started: deliveryStarted
                  }
                ].map((item, index, list) => {
                  const Icon = item.icon;
                  return (
                    <div className="match-step-wrap" key={item.label}>
                      <div className={`match-step ${item.done ? 'done' : item.started ? 'started' : ''}`}>
                        <span>
                          <Icon size={18} />
                        </span>
                        <strong>{item.label}</strong>
                        <small>{item.count} 份 · {item.detail || compactMoney(item.value || 0)}</small>
                      </div>
                      {index < list.length - 1 && (
                        <div className="step-arrow">
                          <ArrowRight size={16} />
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
              <div className="ratio-grid">
                <span>
                  <small>开票率</small>
                  <strong>{percentage(selectedBatch.invoiced, selectedBatch.purchase)}%</strong>
                </span>
                <span>
                  <small>付款率</small>
                  <strong>{percentage(selectedBatch.paid, selectedBatch.purchase)}%</strong>
                </span>
                <span>
                  <small>到货率</small>
                  <strong>{deliverySummary.completionRate.toFixed(1)}%</strong>
                </span>
              </div>
            </Panel>

            <Panel
              title="批次对账摘要"
              className="batch-summary-panel"
              action={
                <button className="text-button" onClick={() => onNavigate('ledger')}>
                  查看台账
                  <ArrowRight size={14} />
                </button>
              }
            >
              <div className="batch-summary-kpis">
                <Metric label="采购金额" value={money(selectedBatch.purchase)} />
                <Metric
                  label="未付款"
                  value={money(Math.max(selectedBatch.purchase - selectedBatch.paid, 0))}
                  tone="danger"
                />
                <Metric
                  label="未开票"
                  value={money(Math.max(selectedBatch.purchase - selectedBatch.invoiced, 0))}
                  tone="warning"
                />
                <Metric
                  label="已收款"
                  value={money(selectedBatch.received)}
                  tone="success"
                />
              </div>
              <div className="batch-risk-line">
                <span className={unresolvedDocuments.length ? 'warning' : 'success'}>
                  {unresolvedDocuments.length ? <AlertCircle size={16} /> : <CheckCircle2 size={16} />}
                </span>
                <div>
                  <strong>
                    {batchDocuments.length === 0
                      ? '尚未导入业务单据'
                      : unresolvedDocuments.length
                      ? `${unresolvedDocuments.length} 份单据需要处理`
                      : '当前批次单据已全部确认'}
                  </strong>
                  <small>
                    {batchDocuments.length === 0
                      ? '从智能助理上传采购单后，将立即建立采购基础和台账骨架。'
                      : unresolvedDocuments.length
                      ? 'AI 已完成字段提取，等待人工确认或处理金额差异。'
                      : '票、款、货信息已完成匹配。'}
                  </small>
                </div>
              </div>
            </Panel>

            <Panel
              title="批次关联单据"
              eyebrow={`${batchDocuments.length} 份业务凭证`}
              className="recent-documents project-documents-panel"
              action={
                <button className="text-button" onClick={() => onNavigate('documents')}>
                  全部单据
                  <ArrowRight size={14} />
                </button>
              }
            >
              <div className="document-mini-list">
                {batchDocuments.slice(0, 4).map((document) => {
                    const Icon = docIcons[document.type];
                    return (
                      <button key={document.id} onClick={() => onNavigate('documents')}>
                        <span className={`doc-mini-icon type-${document.type}`}>
                          <Icon size={17} />
                        </span>
                        <span className="doc-mini-copy">
                          <strong>
                            {document.type} · {document.code}
                          </strong>
                          <small>{document.supplier}</small>
                        </span>
                        <span className="doc-mini-amount">
                          <strong>{money(document.amount)}</strong>
                          <StatusBadge tone={statusTone[document.status]}>
                            {document.status}
                          </StatusBadge>
                        </span>
                      </button>
                    );
                  })}
                {batchDocuments.length === 0 && (
                  <div className="project-empty-state">
                    <FilePlus2 size={19} />
                    <span>
                      <strong>当前批次还没有业务凭证</strong>
                      <small>上传采购单、送货单、付款资料或发票后会在这里归集。</small>
                    </span>
                  </div>
                )}
              </div>
            </Panel>

            <Panel title="批次待办" className="attention-panel project-attention">
              <div className="attention-list">
                {batchDocuments.length === 0 ? (
                  <button onClick={() => onNavigate('workspace')}>
                    <span className="attention-icon warning">
                      <FilePlus2 size={18} />
                    </span>
                    <span>
                      <strong>先导入本批次第一份采购单</strong>
                      <small>AI 确认采购事实后，会自动生成后续票款货补录提示。</small>
                    </span>
                    <ChevronRight size={16} />
                  </button>
                ) : unresolvedDocuments.length > 0 ? (
                  <button onClick={() => onNavigate('documents')}>
                    <span className="attention-icon warning">
                      <FileClock size={18} />
                    </span>
                    <span>
                      <strong>{unresolvedDocuments.length} 张单据等待人工确认</strong>
                      <small>识别字段已预填，可在数据中心检查。</small>
                    </span>
                    <ChevronRight size={16} />
                  </button>
                ) : (
                  <button onClick={() => onNavigate('workspace')}>
                    <span className="attention-icon warning">
                      <AlertCircle size={18} />
                    </span>
                    <span>
                      <strong>继续补充送货、付款和发票资料</strong>
                      <small>系统会根据新增凭证自动计算缺口和差异。</small>
                    </span>
                    <ChevronRight size={16} />
                  </button>
                )}
              </div>
            </Panel>
          </div>
        </div>
      </div>
    </div>
  );
}

type ProjectCreatePayload = {
  name: string;
  customer: string;
  batchDate: string;
  batchName: string;
  material: string;
};

type BatchCreatePayload = {
  date: string;
  name: string;
  material: string;
  /** 编辑模式下随表单提交的批次默认口径（delivery_note scope）；新建批次不带 */
  hints?: Record<string, unknown>;
};

function CreateProjectModal({
  open,
  onClose,
  onCreate,
  editProject
}: {
  open: boolean;
  onClose: () => void;
  onCreate: (payload: ProjectCreatePayload) => void;
  /** 传入即为「编辑项目」模式：只改项目名称与建设/采购单位，批次字段不参与。 */
  editProject?: Project | null;
}) {
  const isEdit = Boolean(editProject);
  const [name, setName] = useState('');
  const [customer, setCustomer] = useState('');
  const [batchDate, setBatchDate] = useState(currentBusinessDate);
  const [batchName, setBatchName] = useState('');
  const [material, setMaterial] = useState('');

  useEffect(() => {
    if (!open) return;
    if (editProject) {
      setName(editProject.name);
      setCustomer(editProject.customer);
    } else {
      setName('');
      setCustomer('');
      setBatchDate(currentBusinessDate);
      setBatchName('');
      setMaterial('');
    }
  }, [open, editProject]);

  if (!open) return null;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!name.trim() || !customer.trim()) return;
    if (isEdit) {
      onCreate({
        name: name.trim(),
        customer: customer.trim(),
        batchDate: '',
        batchName: '',
        material: ''
      });
      return;
    }
    const resolvedMaterial = material.trim();
    const resolvedBatchName =
      batchName.trim() || `${batchDate} ${resolvedMaterial || '首批'}批次`;
    if (!batchDate || !resolvedMaterial) return;
    onCreate({
      name: name.trim(),
      customer: customer.trim(),
      batchDate,
      batchName: resolvedBatchName,
      material: resolvedMaterial
    });
  };

  return (
    <div className="modal-shell" role="dialog" aria-modal="true" aria-label={isEdit ? '编辑项目' : '新建项目'}>
      <button className="modal-backdrop" onClick={onClose} aria-label={isEdit ? '关闭编辑项目' : '关闭新建项目'} />
      <form className="entity-modal" onSubmit={submit}>
        <div className="modal-heading">
          <div>
            <h2>{isEdit ? '编辑项目' : '新建项目'}</h2>
            <p>
              {isEdit
                ? '修改后立即生效：台账、对账单、AI 识别上下文中的项目名称与单位会同步更新。'
                : '项目与首个批次一起建立，创建后即可导入和归集业务单据。'}
            </p>
          </div>
          <button type="button" onClick={onClose} aria-label="关闭">
            <X size={20} />
          </button>
        </div>

        <div className="entity-form-section">
          <div className="entity-section-heading">
            <span className="entity-section-icon">
              <Building2 size={17} />
            </span>
            <span>
              <strong>项目基本信息</strong>
              <small>用于项目总账、权限和对账归属</small>
            </span>
          </div>
          <div className="entity-form-grid">
            <label className="entity-field full">
              <span>项目名称</span>
              <input
                autoFocus
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="例如：城北医院扩建项目"
                required
              />
            </label>
            <label className="entity-field full">
              <span>建设 / 采购单位</span>
              <input
                value={customer}
                onChange={(event) => setCustomer(event.target.value)}
                placeholder="输入项目甲方或采购主体"
                required
              />
            </label>
          </div>
        </div>

        {isEdit && hintSummaryText(editProject?.businessHints) && (
          <div className="entity-form-section project-hint-section">
            <div className="entity-section-heading">
              <span className="entity-section-icon">
                <Coins size={17} />
              </span>
              <span>
                <strong>项目默认口径</strong>
                <small>本项目下没单独设置的批次按此执行；批次自己设置的字段优先</small>
              </span>
            </div>
            <p className="project-hint-line">{hintSummaryText(editProject?.businessHints)}</p>
            <small className="project-hint-note">
              在对话里说「整个项目都按运费30元/吨、网价+30」即可修改；说「取消项目默认口径」清空。
            </small>
          </div>
        )}

        {!isEdit && (
        <div className="entity-form-section tinted">
          <div className="entity-section-heading">
            <span className="entity-section-icon batch">
              <Gauge size={17} />
            </span>
            <span>
              <strong>首个批次</strong>
              <small>业务数据必须归属到具体批次</small>
            </span>
          </div>
          <div className="entity-form-grid">
            <label className="entity-field">
              <span>业务日期</span>
              <input
                type="date"
                value={batchDate}
                onChange={(event) => setBatchDate(event.target.value)}
                required
              />
            </label>
            <label className="entity-field">
              <span>主要材料</span>
              <input
                value={material}
                onChange={(event) => setMaterial(event.target.value)}
                placeholder="例如：钢筋 HRB400E"
                required
              />
            </label>
            <label className="entity-field full">
              <span>批次名称 <small>可不填，系统会自动生成</small></span>
              <input
                value={batchName}
                onChange={(event) => setBatchName(event.target.value)}
                placeholder={`${batchDate} 首批`}
              />
            </label>
          </div>
        </div>
        )}

        <div className="entity-modal-note">
          <Sparkles size={16} />
          {isEdit
            ? '保存后智能助理与各页面会立即使用新的项目信息。'
            : '创建后，智能助理会优先把新上传的单据匹配到这个项目和批次。'}
        </div>
        <div className="modal-actions">
          <button type="button" className="secondary-button" onClick={onClose}>
            取消
          </button>
          <button type="submit" className="primary-button">
            {isEdit ? <Check size={17} /> : <Plus size={17} />}
            {isEdit ? '保存修改' : '创建项目'}
          </button>
        </div>
      </form>
    </div>
  );
}

function CreateBatchModal({
  open,
  project,
  onClose,
  onCreate,
  editBatch
}: {
  open: boolean;
  project: Project;
  onClose: () => void;
  onCreate: (payload: BatchCreatePayload) => void;
  /** 传入即为「编辑批次」模式：改批次名称、业务日期、主要材料。 */
  editBatch?: Batch | null;
}) {
  const isEdit = Boolean(editBatch);
  const [date, setDate] = useState(currentBusinessDate);
  const [name, setName] = useState('');
  const [material, setMaterial] = useState('');
  const [supplyFreight, setSupplyFreight] = useState('');
  const [supplyFreightUnit, setSupplyFreightUnit] = useState<'trip' | 'ton'>('trip');
  const [salesFreight, setSalesFreight] = useState('');
  const [salesFreightUnit, setSalesFreightUnit] = useState<'trip' | 'ton'>('trip');
  const [pricingMode, setPricingMode] = useState<'' | 'uplift' | 'margin'>('');
  const [pricingValue, setPricingValue] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return;
    if (!editBatch) {
      setDate(currentBusinessDate);
      setName('');
      setMaterial('');
      setSupplyFreight('');
      setSupplyFreightUnit('trip');
      setSalesFreight('');
      setSalesFreightUnit('trip');
      setPricingMode('');
      setPricingValue('');
      return;
    }
    const fill = (batch: Batch | Record<string, unknown>) => {
      // 列表项是前端 Batch（date/material），批次详情接口返回的是后端 BatchDto
      // （businessDate/materialSummary）——两种都要认，否则详情覆盖后日期变空，
      // 保存时命中下面的校验，表现为「点保存没反应」（2026-11-11 修）
      const raw = batch as unknown as Record<string, unknown>;
      const text = (value: unknown) => (value == null ? '' : String(value));
      setDate(text(raw.date ?? raw.businessDate));
      setName(text(raw.name));
      setMaterial(text(raw.material ?? raw.materialSummary));
      const hints = (raw.businessHints ?? {}) as Record<string, Record<string, unknown>>;
      const hint = (hints.delivery_note || {}) as Record<string, unknown>;
      const numToText = (value: unknown) => (value == null ? '' : String(value));
      const supplyIsTon = hint.supplyFreightPerTon != null;
      setSupplyFreight(numToText(supplyIsTon ? hint.supplyFreightPerTon : hint.supplyFreightPerTrip));
      setSupplyFreightUnit(supplyIsTon ? 'ton' : 'trip');
      const salesIsTon = hint.salesFreightPerTon != null;
      setSalesFreight(numToText(salesIsTon ? hint.salesFreightPerTon : hint.salesFreightPerTrip));
      setSalesFreightUnit(salesIsTon ? 'ton' : 'trip');
      setPricingMode(hint.pricingMode === 'uplift' ? 'uplift' : hint.pricingMode === 'margin' ? 'margin' : '');
      setPricingValue(numToText(hint.upliftAmount ?? hint.marginRate));
    };
    // 先用列表里的对象快速填充（打开即有值，不闪空）
    fill(editBatch);
    // 再用批次详情覆盖：AI 刚改过的口径可能还没进列表缓存，用旧对象会让
    // 采购/销售运费显示成上一次的值或空白（2026-10-28 修）
    let cancelled = false;
    api.batchDetail(editBatch.id)
      .then((detail) => {
        const latest = (detail as { batch?: Batch } | null)?.batch;
        if (!cancelled && latest) {
          fill(latest);
        }
      })
      .catch(() => {
        // 详情拉取失败就沿用列表值，不打断编辑
      });
    return () => {
      cancelled = true;
    };
  }, [open, project.id, editBatch]);

  if (!open) return null;

  // 本批次没填、会从项目默认口径继承的部分（只在编辑已有批次时提示）
  const inheritedHints = isEdit
    ? inheritedHintText(project.businessHints, editBatch?.businessHints)
    : '';

  const buildHints = (): Record<string, unknown> => {
    const scope: Record<string, unknown> = {};
    // 批次原有口径：用来判断「用户把这个框清空了」是不是要从有值改成不另计
    const original = ((isEdit ? editBatch?.businessHints?.delivery_note : undefined) ?? {}) as
      Record<string, unknown>;
    const putFreight = (
      text: string,
      unit: 'ton' | 'trip',
      tripKey: string,
      tonKey: string
    ) => {
      const hadTrip = original[tripKey] != null;
      const hadTon = original[tonKey] != null;
      if (!text.trim()) {
        // 清空原本有值的口径 = 不另计，显式写 0；否则按组合并会保留旧值（2026-11-06）
        if (hadTrip) scope[tripKey] = 0;
        else if (hadTon) scope[tonKey] = 0;
        return;
      }
      const value = Number(text);
      if (!Number.isFinite(value) || value < 0) return;
      // 0 也照样写入：表示「不另计（含在售价里）」
      scope[unit === 'ton' ? tonKey : tripKey] = value;
    };
    putFreight(supplyFreight, supplyFreightUnit, 'supplyFreightPerTrip', 'supplyFreightPerTon');
    putFreight(salesFreight, salesFreightUnit, 'salesFreightPerTrip', 'salesFreightPerTon');
    const value = Number(pricingValue);
    if (pricingMode && pricingValue.trim() && Number.isFinite(value) && value > 0) {
      scope.pricingMode = pricingMode;
      scope[pricingMode === 'uplift' ? 'upliftAmount' : 'marginRate'] = value;
    }
    if (Object.keys(scope).length > 0) {
      scope.updatedAt = new Date().toISOString();
    }
    return scope;
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const resolvedMaterial = material.trim();
    if (!date || !resolvedMaterial) {
      setError(!date ? '请先选择业务日期' : '请填写主要材料');
      return;
    }
    setError('');
    const resolvedName = name.trim() || `${date} ${resolvedMaterial}批次`;
    onCreate({
      date,
      name: isEdit ? name.trim() || editBatch!.name : resolvedName,
      material: resolvedMaterial,
      hints: isEdit ? buildHints() : undefined
    });
  };

  return (
    <div className="modal-shell" role="dialog" aria-modal="true" aria-label={isEdit ? '编辑批次' : '新建批次'}>
      <button className="modal-backdrop" onClick={onClose} aria-label={isEdit ? '关闭编辑批次' : '关闭新建批次'} />
      <form className="entity-modal batch-create-modal" onSubmit={submit}>
        <div className="modal-heading">
          <div>
            <h2>{isEdit ? '编辑批次' : '新建批次'}</h2>
            <p>
              {isEdit
                ? '修改后立即生效：台账、导出与 AI 上下文中的批次信息会同步更新。'
                : '为当前项目建立新的采购、票据和资金归集单位。'}
            </p>
          </div>
          <button type="button" onClick={onClose} aria-label="关闭">
            <X size={20} />
          </button>
        </div>

        <div className="batch-project-context">
          <span className="context-option-mark">{project.name.slice(0, 1)}</span>
          <span>
            <small>归属项目</small>
            <strong>{project.name}</strong>
          </span>
          <CheckCircle2 size={18} />
        </div>

        <div className="entity-form-section">
          <div className="entity-form-grid">
            <label className="entity-field">
              <span>业务日期</span>
              <input
                type="date"
                value={date}
                onChange={(event) => setDate(event.target.value)}
                required
              />
            </label>
            <label className="entity-field">
              <span>主要材料</span>
              <input
                autoFocus={isEdit}
                value={material}
                onChange={(event) => setMaterial(event.target.value)}
                placeholder="例如：水泥 PO42.5"
                required
              />
            </label>
            <label className="entity-field full">
              <span>批次名称 <small>{isEdit ? '' : '可不填，系统会自动生成'}</small></span>
              <input
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder={`${date} 材料批次`}
              />
            </label>
          </div>
        </div>

        {isEdit && (
          <div className="entity-form-section">
            <div className="entity-form-grid">
              <label className="entity-field">
                <span>采购运费 <small>选填，整批默认；元/趟 = 这一趟的包干价</small></span>
                <div style={{ display: 'flex', gap: 8 }}>
                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    value={supplyFreight}
                    onChange={(event) => setSupplyFreight(event.target.value)}
                    placeholder="不填则不套用"
                  />
                  <select
                    value={supplyFreightUnit}
                    onChange={(event) => setSupplyFreightUnit(event.target.value === 'ton' ? 'ton' : 'trip')}
                    aria-label="采购运费单位"
                  >
                    <option value="trip">元/趟</option>
                    <option value="ton">元/吨</option>
                  </select>
                </div>
              </label>
              <label className="entity-field">
                <span>销售运费 <small>选填，客户承担；元/趟 = 整批包干价</small></span>
                <div style={{ display: 'flex', gap: 8 }}>
                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    value={salesFreight}
                    onChange={(event) => setSalesFreight(event.target.value)}
                    placeholder="不填则不套用"
                  />
                  <select
                    value={salesFreightUnit}
                    onChange={(event) => setSalesFreightUnit(event.target.value === 'ton' ? 'ton' : 'trip')}
                    aria-label="销售运费单位"
                  >
                    <option value="trip">元/趟</option>
                    <option value="ton">元/吨</option>
                  </select>
                </div>
              </label>
              <label className="entity-field">
                <span>销售定价 <small>选填，钢筋销售价口径</small></span>
                <div style={{ display: 'flex', gap: 8 }}>
                  <select
                    value={pricingMode}
                    onChange={(event) => setPricingMode(
                      event.target.value === 'uplift' ? 'uplift'
                        : event.target.value === 'margin' ? 'margin' : '')}
                    aria-label="销售定价方式"
                  >
                    <option value="">不指定</option>
                    <option value="uplift">网价上浮</option>
                    <option value="margin">毛利率</option>
                  </select>
                  <input
                    type="number"
                    min="0"
                    max={pricingMode === 'margin' ? 99 : undefined}
                    step="0.01"
                    value={pricingValue}
                    onChange={(event) => setPricingValue(event.target.value)}
                    disabled={!pricingMode}
                    placeholder={pricingMode === 'margin' ? '如 2.5 表示 2.5%' : '元/吨'}
                  />
                </div>
              </label>
            </div>
            {inheritedHints && (
              <small className="project-hint-note">
                以上留空的组按项目默认口径执行：{inheritedHints}
              </small>
            )}
          </div>
        )}

        {error && (
          <p role="alert" style={{ margin: '0 0 10px', color: '#c0392b', fontSize: 13 }}>
            {error}
          </p>
        )}
        <div className="entity-modal-note">
          <FolderKanban size={16} />
          {isEdit
            ? '单据、台账与财务归集不受改名影响（按 id 关联）。默认口径仅用于送货单：导入时单据自带说明优先，未说明的字段按此套用；全部留空即清除。'
            : '新批次初始状态为“待确认”，金额会在单据确认回填后自动汇总。'}
        </div>
        <div className="modal-actions">
          <button type="button" className="secondary-button" onClick={onClose}>
            取消
          </button>
          <button type="submit" className="primary-button">
            {isEdit ? <Check size={17} /> : <Plus size={17} />}
            {isEdit ? '保存修改' : '创建批次'}
          </button>
        </div>
      </form>
    </div>
  );
}

function DeleteConfirmModal({
  target,
  busy,
  onCancel,
  onConfirm
}: {
  target: { kind: 'project' | 'batch' | 'document'; name: string; detail: string } | null;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  if (!target) return null;
  const heading = target.kind === 'project' ? '删除项目' : target.kind === 'document' ? '删除业务数据' : '删除批次';
  return (
    <div className="modal-shell" role="dialog" aria-modal="true" aria-label="删除确认">
      <button className="modal-backdrop" onClick={onCancel} aria-label="关闭删除确认" />
      <div className="entity-modal delete-confirm-modal">
        <div className="modal-heading">
          <div>
            <h2>{heading}</h2>
            <p>「{target.name}」删除后无法在界面恢复，请确认。</p>
          </div>
          <button type="button" onClick={onCancel} aria-label="关闭">
            <X size={20} />
          </button>
        </div>

        <div className="delete-confirm-body">
          <AlertCircle size={20} />
          <p>{target.detail}</p>
        </div>

        <div className="modal-actions">
          <button type="button" className="secondary-button" onClick={onCancel} disabled={busy}>
            取消
          </button>
          <button type="button" className="danger-button" onClick={onConfirm} disabled={busy}>
            <Trash2 size={17} />
            {busy ? '正在删除…' : '确认删除'}
          </button>
        </div>
      </div>
    </div>
  );
}

function BusinessIdentityModal({
  open,
  profile,
  busy,
  onClose,
  onSave
}: {
  open: boolean;
  profile: ApiUserProfile | null;
  busy: boolean;
  onClose: () => void;
  onSave: (
    nickname: string,
    companyName: string,
    reminder: { enabled: boolean; weekday: number; hour: number }
  ) => Promise<void>;
}) {
  const [nickname, setNickname] = useState('');
  const [companyName, setCompanyName] = useState('');
  // 每周对账提醒（user_settings.reconciliation：enabled/weekday 1-7/hour 0-23）
  const [reminder, setReminder] = useState({ enabled: true, weekday: 1, hour: 9 });

  useEffect(() => {
    if (!open) return;
    setNickname(profile?.nickname || '');
    setCompanyName(profile?.companyName || '');
    api.getUserSettings().then((settings) => {
      const recon = (settings.reconciliation ?? {}) as Record<string, unknown>;
      setReminder({
        enabled: recon.enabled !== false,
        weekday: typeof recon.weekday === 'number' ? recon.weekday : 1,
        hour: typeof recon.hour === 'number' ? recon.hour : 9
      });
    }).catch(() => undefined);
  }, [open, profile?.nickname, profile?.companyName]);

  const weekdayLabel = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

  if (!open) return null;
  return (
    <div className="modal-shell" role="dialog" aria-modal="true" aria-label="个人资料与设置">
      <button className="modal-backdrop" onClick={onClose} aria-label="关闭设置" />
      <form
        className="entity-modal identity-modal"
        onSubmit={(event) => {
          event.preventDefault();
          if (nickname.trim() && companyName.trim()) {
            void onSave(nickname.trim(), companyName.trim(), reminder);
          }
        }}
      >
        <div className="modal-heading">
          <div>
            <h2>个人资料与设置</h2>
            <p>昵称只影响你自己的显示（右上角、用户菜单）；单位名称用于识别时判断收付款方向和发票进销项；对账提醒按下方配置自动推送到聊天。</p>
          </div>
          <button type="button" onClick={onClose} aria-label="关闭"><X size={20} /></button>
        </div>
        <div className="entity-form-section identity-fields">
          <label className="entity-field full">
            <span>昵称</span>
            <input
              autoFocus
              value={nickname}
              maxLength={20}
              onChange={(event) => setNickname(event.target.value)}
              placeholder="例如：老张"
              required
            />
          </label>
          <label className="entity-field full">
            <span>本单位完整名称</span>
            <input
              value={companyName}
              onChange={(event) => setCompanyName(event.target.value)}
              placeholder="例如：杭州衡建供应链有限公司"
              required
            />
          </label>
        </div>
        <div className="entity-form-section reminder-settings">
          <div className="entity-field full reminder-toggle">
            <span>每周对账提醒</span>
            <label className="reminder-switch">
              <input
                type="checkbox"
                checked={reminder.enabled}
                onChange={(event) => setReminder({...reminder, enabled: event.target.checked})}
              />
              <small>{reminder.enabled ? '已开启' : '已关闭'}</small>
            </label>
          </div>
          {reminder.enabled && (
            <div className="reminder-fields">
              <label className="entity-field">
                <span>提醒时间</span>
                <select
                  value={reminder.weekday}
                  onChange={(event) => setReminder({...reminder, weekday: Number(event.target.value)})}
                >
                  {weekdayLabel.map((label, index) => (
                    <option key={label} value={index + 1}>{label}</option>
                  ))}
                </select>
              </label>
              <label className="entity-field">
                <span>&nbsp;</span>
                <select
                  value={reminder.hour}
                  onChange={(event) => setReminder({...reminder, hour: Number(event.target.value)})}
                >
                  {Array.from({length: 24}, (_, hour) => (
                    <option key={hour} value={hour}>{String(hour).padStart(2, '0')}:00</option>
                  ))}
                </select>
              </label>
            </div>
          )}
          <small className="reminder-note">
            到点自动检查上周新增送货单，有数据的项目会把对账单卡片发到聊天里；没有新数据不打扰。
          </small>
        </div>
        <div className="modal-actions">
          <button type="button" className="secondary-button" onClick={onClose}>取消</button>
          <button type="submit" className="primary-button" disabled={busy}>
            <Check size={16} />{busy ? '正在保存' : '保存设置'}
          </button>
        </div>
      </form>
    </div>
  );
}

/**
 * 超级用户「以他人身份查看」：选中后写入本地会话并整页重载，
 * 所有请求自动带上 X-Act-As，后端把生效身份换成目标用户，业务代码零感知。
 */
function ActAsModal({
  open,
  currentId,
  actingAs,
  onClose,
  onSelect
}: {
  open: boolean;
  currentId?: string;
  actingAs?: { id: string; nickname: string } | null;
  onClose: () => void;
  onSelect: (userId: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [users, setUsers] = useState<ApiAdminUser[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setQuery('');
    setLoading(true);
    setError('');
    api.adminUsers()
      .then((rows) => {
        if (!cancelled) setUsers(rows);
      })
      .catch((exception) => {
        if (!cancelled) setError(exception instanceof Error ? exception.message : '用户清单加载失败');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open]);

  const keyword = query.trim().toLowerCase();
  const filtered = keyword
    ? users.filter((user) =>
        [user.nickname, user.companyName, user.username]
          .filter(Boolean)
          .some((value) => String(value).toLowerCase().includes(keyword))
      )
    : users;

  if (!open) return null;
  return (
    <div className="modal-shell" role="dialog" aria-modal="true" aria-label="切换查看身份">
      <button className="modal-backdrop" onClick={onClose} aria-label="关闭" />
      <div className="entity-modal act-as-modal">
        <div className="modal-heading">
          <div>
            <h2>切换查看身份</h2>
            <p>选中后整个平台切换到该用户视角，可查看其项目、批次、单据与台账；期间的操作会记在该用户名下。</p>
          </div>
          <button type="button" onClick={onClose} aria-label="关闭"><X size={20} /></button>
        </div>
        <label className="act-as-search">
          <Search size={16} />
          <input
            autoFocus
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="搜索昵称、单位或账号"
          />
        </label>
        <div className="act-as-list">
          {actingAs && (
            <button className="act-as-row own" onClick={() => onSelect('')}>
              <span className="act-as-avatar">{actingAs.nickname.slice(0, 1) || '我'}</span>
              <span className="act-as-copy">
                <strong>返回我的账号</strong>
                <small>{actingAs.nickname} · 超级用户</small>
              </span>
              <ChevronLeft size={15} />
            </button>
          )}
          {loading && <div className="act-as-empty">正在加载用户清单…</div>}
          {!loading && error && <div className="act-as-empty">{error}</div>}
          {!loading && !error && filtered.length === 0 && (
            <div className="act-as-empty">没有匹配的用户</div>
          )}
          {!loading && !error && filtered.map((user) => (
            <button
              key={user.id}
              className={`act-as-row ${user.id === currentId ? 'active' : ''}`}
              onClick={() => onSelect(user.id)}
            >
              <span className="act-as-avatar">{user.nickname?.slice(0, 1) || '用'}</span>
              <span className="act-as-copy">
                <strong>{user.nickname}</strong>
                <small>
                  {[user.companyName, user.username].filter(Boolean).join(' · ') || '未填写单位'}
                </small>
              </span>
              <span className="act-as-stat">
                <strong>{user.projectCount}</strong>
                <small>项目</small>
              </span>
              <span className="act-as-stat">
                <strong>{user.documentCount}</strong>
                <small>单据</small>
              </span>
              {user.id === currentId ? <Check size={15} /> : <ChevronRight size={15} />}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

function DocumentsPage({
  documents,
  projectsData,
  onNavigate,
  onOpenDocument
}: {
  documents: BusinessDocument[];
  projectsData: Project[];
  onNavigate: (page: PageKey) => void;
  onOpenDocument: (document: BusinessDocument) => void;
}) {
  const [type, setType] = useState('全部');
  const [query, setQuery] = useState('');
  const deferredQuery = useDeferredValue(query);
  const tabs = ['全部', '采购单', '送货单', '运输费用', '请款单', '付款记录', '收款记录', '进项发票', '销项发票'];
  const filtered = documents.filter((document) => {
    const matchesType = type === '全部' || document.type === type;
    const keyword = deferredQuery.trim().toLowerCase();
    const matchesQuery =
      !keyword ||
      `${document.code}${document.supplier}${document.type}`.toLowerCase().includes(keyword);
    return matchesType && matchesQuery;
  });

  const projectName = (id: string) => projectsData.find((item) => item.id === id)?.name || '-';
  const batchName = (projectId: string, batchId: string) =>
    projectsData
      .find((item) => item.id === projectId)
      ?.batches.find((item) => item.id === batchId)?.name || '-';

  return (
    <div className="standard-page page-enter">
      <div className="page-title-row">
        <div>
          <h2>数据中心</h2>
          <p>查看识别入库的数据及其原始资料。</p>
        </div>
        <button className="primary-button" onClick={() => onNavigate('workspace')}>
          <MessageSquareText size={17} />
          通过智能助理导入
        </button>
      </div>

      <div className="document-stat-grid">
        <Metric label="全部单据" value={`${documents.length} 份`} hint="覆盖 3 个项目" />
        <Metric
          label="已自动匹配"
          value={`${documents.filter((item) => item.status === '已匹配').length} 份`}
          hint="无需人工处理"
          tone="success"
        />
        <Metric
          label="待人工确认"
          value={`${documents.filter((item) => item.status === '待确认').length} 份`}
          hint="字段已预填"
          tone="warning"
        />
        <Metric
          label="金额差异"
          value={`${documents.filter((item) => item.status === '有差异').length} 份`}
          hint="需要核对票款"
          tone="danger"
        />
      </div>

      <Panel className="document-table-panel">
        <div className="table-toolbar">
          <div className="tab-list">
            {tabs.map((tab) => (
              <button
                key={tab}
                className={type === tab ? 'active' : ''}
                onClick={() => startTransition(() => setType(tab))}
              >
                {tab}
              </button>
            ))}
          </div>
          <div className="toolbar-actions">
            <label className="search-input">
              <Search size={16} />
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="搜索单号、供应商"
              />
            </label>
            <button className="icon-text-button">
              <Filter size={16} />
              筛选
            </button>
          </div>
        </div>
        <div className="table-scroll">
          <table className="data-table documents-table">
            <thead>
              <tr>
                <th>单据编号</th>
                <th>类型</th>
                <th>项目 / 批次</th>
                <th>往来单位</th>
                <th>单据日期</th>
                <th>金额</th>
                <th>识别结果</th>
                <th>来源</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {filtered.map((document) => {
                const Icon = docIcons[document.type];
                return (
                  <tr key={document.id} onClick={() => onOpenDocument(document)}>
                    <td>
                      <strong>{document.code}</strong>
                      <small>置信度 {document.confidence}%</small>
                    </td>
                    <td>
                      <span className={`document-type type-${document.type}`}>
                        <Icon size={15} />
                        {document.type}
                      </span>
                    </td>
                    <td>
                      <strong>{projectName(document.projectId)}</strong>
                      <small>{batchName(document.projectId, document.batchId)}</small>
                    </td>
                    <td>{document.supplier}</td>
                    <td>{document.date}</td>
                    <td className="amount-cell">{money(document.amount)}</td>
                    <td>
                      <StatusBadge tone={statusTone[document.status]}>{document.status}</StatusBadge>
                    </td>
                    <td>{document.source}</td>
                    <td>
                      <ChevronRight size={16} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        {filtered.length === 0 && (
          <div className="empty-state">
            <FileSearch size={30} />
            <strong>没有找到匹配的单据</strong>
            <span>可以调整筛选条件或导入新的业务单据。</span>
          </div>
        )}
      </Panel>
    </div>
  );
}

function LedgerPage({
  onToast,
  rows
}: {
  onToast: (message: string) => void;
  rows: LedgerRow[];
}) {
  const ledgerRows = rows;
  const [view, setView] = useState<'all' | 'purchase' | 'sales'>('all');
  const totalPurchase = ledgerRows.reduce((sum, row) => sum + row.purchaseAmount, 0);
  const totalRequested = ledgerRows.reduce((sum, row) => sum + (row.requestedAmount || 0), 0);
  const totalPaid = ledgerRows.reduce((sum, row) => sum + row.paidAmount, 0);
  const totalUnpaid = ledgerRows.reduce((sum, row) => sum + (row.unpaidAmount || 0), 0);
  const totalInvoice = ledgerRows.reduce((sum, row) => sum + row.invoiceAmount, 0);
  const totalOutstanding = ledgerRows.reduce((sum, row) => sum + row.outstanding, 0);

  const exportCsv = () => {
    const columns = [
      '项目',
      '批次',
      '采购日期',
      '材料',
      '供应商',
      '采购金额',
      '运费',
      '实际送达件数',
      '实际送达重量',
      '实际送达金额',
      '请款金额',
      '已付款',
      '未付款',
      '进项票',
      '销售单号',
      '销售金额',
      '已收款',
      '剩余应收',
      '状态'
    ];
    const rows = ledgerRows.map((row) => [
      row.project,
      row.batch,
      row.purchaseDate,
      row.material,
      row.supplier,
      row.purchaseAmount,
      row.freight,
      `${row.deliveredPackageQuantity || 0}${row.deliveredPackageUnit || ''}`,
      `${row.deliveredWeight || 0}${row.deliveredWeightUnit || ''}`,
      row.deliveryAmount,
      row.requestedAmount || 0,
      row.paidAmount,
      row.unpaidAmount || 0,
      row.invoiceAmount,
      row.salesOrder,
      row.salesAmount,
      row.receivedAmount,
      row.outstanding,
      row.status
    ]);
    const csv = `\uFEFF${[columns, ...rows]
      .map((row) => row.map((cell) => `"${String(cell).replaceAll('"', '""')}"`).join(','))
      .join('\n')}`;
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = '项目批次对账台账.csv';
    anchor.click();
    URL.revokeObjectURL(url);
    onToast('台账已导出为 CSV 文件');
  };

  return (
    <div className="standard-page ledger-page page-enter">
      <div className="page-title-row">
        <div>
          <h2>项目批次对账台账</h2>
          <p>集中核对采购、票款、销售和回款情况。</p>
        </div>
        <div className="page-actions">
          <button className="secondary-button">
            <Filter size={16} />
            筛选
          </button>
          <button className="primary-button" onClick={exportCsv}>
            <ArrowDownToLine size={17} />
            导出台账
          </button>
        </div>
      </div>

      <div className="ledger-stat-grid">
        <Metric label="采购总额" value={money(totalPurchase)} />
        <Metric label="请款总额" value={money(totalRequested)} />
        <Metric label="已付款" value={money(totalPaid)} />
        <Metric label="未付款" value={money(totalUnpaid)} tone="danger" />
        <Metric label="进项票金额" value={money(totalInvoice)} tone="success" />
        {view === 'sales' && <Metric label="剩余应收金额" value={money(totalOutstanding)} tone="danger" />}
      </div>

      <Panel className="ledger-panel">
        <div className="ledger-toolbar">
          <div className="segmented">
            <button className={view === 'all' ? 'active' : ''} onClick={() => setView('all')}>
              综合台账
            </button>
            <button
              className={view === 'purchase' ? 'active' : ''}
              onClick={() => setView('purchase')}
            >
              采购付款
            </button>
            <button
              className={view === 'sales' ? 'active' : ''}
              onClick={() => setView('sales')}
            >
              销售收款
            </button>
          </div>
          <span className="ledger-sync">
            <CheckCircle2 size={15} />
            数据已自动更新
          </span>
        </div>
        <div className="ledger-table-scroll">
          <table className="ledger-table">
            <thead>
              <tr className="group-head">
                <th colSpan={4}>项目与批次</th>
                {view !== 'sales' && <th colSpan={8}>采购 / 送达 / 请款 / 付款</th>}
                {view !== 'purchase' && <th colSpan={5}>销售 / 开票 / 收款</th>}
                <th rowSpan={2}>状态</th>
              </tr>
              <tr>
                <th className="sticky-project">项目</th>
                <th className="sticky-batch">批次</th>
                <th>日期</th>
                <th>材料</th>
                {view !== 'sales' && (
                  <>
                    <th>供应商</th>
                    <th>采购金额</th>
                    <th>运输费</th>
                    <th>实际送达</th>
                    <th>请款金额</th>
                    <th>已付款</th>
                    <th>未付款</th>
                    <th>进项票 / 税率</th>
                  </>
                )}
                {view !== 'purchase' && (
                  <>
                    <th>销售单号</th>
                    <th>销售金额</th>
                    <th>已收款</th>
                    <th>剩余应收</th>
                    <th>收款进度</th>
                  </>
                )}
              </tr>
            </thead>
            <tbody>
              {ledgerRows.map((row) => {
                const receiveRate =
                  row.salesAmount > 0 ? (row.receivedAmount / row.salesAmount) * 100 : 0;
                return (
                  <tr key={row.id}>
                    <td className="sticky-project">{row.project}</td>
                    <td className="sticky-batch">
                      <strong>{row.batch}</strong>
                    </td>
                    <td>{row.purchaseDate}</td>
                    <td>{row.material}</td>
                    {view !== 'sales' && (
                      <>
                        <td>{row.supplier}</td>
                        <td>{money(row.purchaseAmount)}</td>
                        <td>{money(row.freight)}</td>
                        <td>
                          <strong>
                            {row.deliveredWeight > 0
                              ? `${quantity(row.deliveredWeight)} ${row.deliveredWeightUnit || '吨'}`
                              : money(row.deliveryAmount)}
                          </strong>
                          <small>
                            {row.deliveredPackageQuantity > 0
                              ? `${quantity(row.deliveredPackageQuantity)} ${row.deliveredPackageUnit || '件'}`
                              : row.deliveryAmount > 0 ? `送达金额 ${money(row.deliveryAmount)}` : '待补充到货数据'}
                          </small>
                        </td>
                        <td>{money(row.requestedAmount || 0)}</td>
                        <td>{money(row.paidAmount)}</td>
                        <td className={(row.unpaidAmount || 0) > 0 ? 'negative' : ''}>
                          {money(row.unpaidAmount || 0)}
                        </td>
                        <td>
                          <strong>{money(row.invoiceAmount)}</strong>
                          <small>{row.taxRate}</small>
                        </td>
                      </>
                    )}
                    {view !== 'purchase' && (
                      <>
                        <td className="order-code">{row.salesOrder}</td>
                        <td>{money(row.salesAmount)}</td>
                        <td>{money(row.receivedAmount)}</td>
                        <td className={row.outstanding > 0 ? 'negative' : ''}>
                          {money(row.outstanding)}
                        </td>
                        <td>
                          <div className="receive-rate">
                            <i style={{ width: `${Math.min(receiveRate, 100)}%` }} />
                            <span>{receiveRate.toFixed(0)}%</span>
                          </div>
                        </td>
                      </>
                    )}
                    <td>
                      <StatusBadge
                        tone={
                          row.status === '已对平'
                            ? 'success'
                            : row.status === '待收款' ||
                                row.status === '待付款' ||
                                row.status === '待补充'
                              ? 'warning'
                              : 'danger'
                        }
                      >
                        {row.status}
                      </StatusBadge>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <div className="ledger-note">
          <AlertCircle size={16} />
          红色金额表示剩余应收或存在差异。每一行均可追溯到原始单据、大模型识别结果和人工修改记录。
        </div>
      </Panel>
    </div>
  );
}

function UploadModal({
  open,
  initialType,
  project,
  batch,
  onClose,
  onComplete
}: {
  open: boolean;
  initialType: string;
  project: Project;
  batch: Batch;
  onClose: () => void;
  onComplete: (document: BusinessDocument) => void;
}) {
  const [selectedType, setSelectedType] = useState(initialType || '自动识别');
  const [fileName, setFileName] = useState('');
  const [processing, setProcessing] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  if (!open) return null;

  const processFile = () => {
    if (!fileName || processing) return;
    setProcessing(true);
    window.setTimeout(() => {
      const resolvedType =
        selectedType === '自动识别' || selectedType === '全部' ? '采购单' : selectedType;
      const nextDocument: BusinessDocument = {
        id: `d-${Date.now()}`,
        code: `AI-${new Date().getTime().toString().slice(-8)}`,
        type: resolvedType as BusinessDocument['type'],
        supplier: 'AI 识别待确认单位',
        projectId: project.id,
        batchId: batch.id,
        date: currentBusinessDate,
        amount: 176_620.21,
        status: '待确认',
        confidence: 95,
        source: fileName.toLowerCase().endsWith('.pdf') ? 'PDF' : '图片'
      };
      setProcessing(false);
      setFileName('');
      onComplete(nextDocument);
    }, 900);
  };

  return (
    <div className="modal-shell" role="dialog" aria-modal="true" aria-label="导入业务单据">
      <button className="modal-backdrop" onClick={onClose} aria-label="关闭" />
      <div className="upload-modal">
        <div className="modal-heading">
          <div>
            <h2>导入业务单据</h2>
            <p>自动识别单据类型、关键字段，并匹配到项目和批次。</p>
          </div>
          <button onClick={onClose} aria-label="关闭">
            <X size={20} />
          </button>
        </div>
        <div className="upload-context">
          <span>
            <FolderKanban size={15} />
            {project.name}
          </span>
          <ArrowRight size={14} />
          <span>
            <Gauge size={15} />
            {batch.name}
          </span>
        </div>
        <div className="type-picker">
          {['自动识别', '采购单', '送货单', '运输费用', '请款单', '付款记录', '收款记录', '进项发票', '销项发票'].map(
            (type) => (
              <button
                key={type}
                className={selectedType === type ? 'active' : ''}
                onClick={() => setSelectedType(type)}
              >
                {type}
              </button>
            )
          )}
        </div>
        <button className="drop-zone" onClick={() => fileInput.current?.click()}>
          <input
            ref={fileInput}
            type="file"
            accept="image/*,.pdf,.xlsx,.xls,.doc,.docx"
            onChange={(event) => setFileName(event.target.files?.[0]?.name || '')}
          />
          {fileName ? (
            <>
              <FileCheck2 size={34} />
              <strong>{fileName}</strong>
              <span>文件已就绪，点击下方开始识别</span>
            </>
          ) : (
            <>
              <CloudUpload size={36} />
              <strong>点击选择或拖入图片、PDF、Excel</strong>
              <span>单个文件不超过 20MB，支持多种业务凭证</span>
            </>
          )}
        </button>
        <div className="ai-process">
          {[
            ['大模型读取单据', ScanLine],
            ['AI 意图判断', Sparkles],
            ['项目批次匹配', FolderKanban],
            ['台账字段回填', TableProperties]
          ].map(([label, Icon]) => {
            const ProcessIcon = Icon as typeof ScanLine;
            return (
              <span key={String(label)}>
                <ProcessIcon size={16} />
                {String(label)}
              </span>
            );
          })}
        </div>
        <div className="modal-actions">
          <button className="secondary-button" onClick={onClose}>
            取消
          </button>
          <button
            className="primary-button"
            disabled={!fileName || processing}
            onClick={processFile}
          >
            {processing ? (
              <>
                <span className="spinner" />
                AI 正在识别
              </>
            ) : (
              <>
                <Sparkles size={17} />
                开始识别并归档
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
}

function DocumentDrawer({
  document,
  project,
  batch,
  onClose
}: {
  document: BusinessDocument | null;
  project?: Project;
  batch?: Batch;
  onClose: () => void;
}) {
  const [accessUrl, setAccessUrl] = useState('');
  const [previewError, setPreviewError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setAccessUrl('');
    setPreviewError('');
    if (!document || document.source === '手动录入' || document.source === '短信') return;
    void api.documentAccessUrl(document.id)
      .then((result) => {
        if (!cancelled) setAccessUrl(result.url);
      })
      .catch((error) => {
        if (!cancelled) setPreviewError(error instanceof Error ? error.message : '原文件暂时无法访问');
      });
    return () => {
      cancelled = true;
    };
  }, [document?.id]);

  if (!document) return null;
  const Icon = docIcons[document.type];
  return (
    <div className="drawer-shell">
      <button className="drawer-backdrop" onClick={onClose} aria-label="关闭详情" />
      <aside className="document-drawer">
        <div className="drawer-heading">
          <div>
            <span className={`document-type type-${document.type}`}>
              <Icon size={15} />
              {document.type}
            </span>
            <h2>{document.code}</h2>
          </div>
          <button onClick={onClose} aria-label="关闭详情">
            <X size={20} />
          </button>
        </div>
        <div className="document-preview">
          {(document.source === '手动录入' || document.source === '短信') ? (
            <div className="original-text-preview">
              <small>用户输入原文</small>
              <pre>{document.sourceText || '暂无原文记录'}</pre>
            </div>
          ) : document.source === '图片' && accessUrl ? (
            <img className="document-image-preview" src={accessUrl} alt={document.fileName || document.code} />
          ) : document.source === 'PDF' && accessUrl ? (
            <iframe className="document-pdf-preview" src={accessUrl} title={document.fileName || document.code} />
          ) : (
            <div className="document-file-preview">
              {document.source === 'Excel' ? <FileSpreadsheet size={36} /> : <FileText size={36} />}
              <strong>{document.fileName || '原始业务文件'}</strong>
              <small>{previewError || (accessUrl ? '文件已就绪，可打开或下载查看' : '正在获取安全访问地址...')}</small>
              {accessUrl && (
                <a href={accessUrl} target="_blank" rel="noreferrer">
                  <ArrowDownToLine size={16} />
                  打开或下载原文件
                </a>
              )}
            </div>
          )}
          <span>{document.source} · {document.fileName || '业务记录'}</span>
        </div>
        <div className="drawer-section">
          <h3>AI 识别结果</h3>
          <div className="confidence">
            <span>识别置信度</span>
            <div>
              <i style={{ width: `${document.confidence}%` }} />
            </div>
            <strong>{document.confidence}%</strong>
          </div>
          <dl>
            <div>
              <dt>项目</dt>
              <dd>{project?.name}</dd>
            </div>
            <div>
              <dt>批次</dt>
              <dd>{batch?.name}</dd>
            </div>
            <div>
              <dt>往来单位</dt>
              <dd>{document.supplier}</dd>
            </div>
            <div>
              <dt>单据日期</dt>
              <dd>{document.date}</dd>
            </div>
            <div>
              <dt>单据金额</dt>
              <dd>{money(document.amount)}</dd>
            </div>
            <div>
              <dt>匹配状态</dt>
              <dd>
                <StatusBadge tone={statusTone[document.status]}>{document.status}</StatusBadge>
              </dd>
            </div>
          </dl>
        </div>
        <div className="drawer-section audit-section">
          <h3>处理记录</h3>
          <p>
            <CheckCircle2 size={16} />
            大模型已提取关键字段
          </p>
          <p>
            <CheckCircle2 size={16} />
            AI 已匹配项目与批次
          </p>
          {document.status === '有差异' && (
            <p className="audit-warning">
              <AlertCircle size={16} />
              金额与同批次付款记录存在差异
            </p>
          )}
        </div>
        <div className="drawer-actions">
          <button className="secondary-button">编辑识别结果</button>
          <button className="primary-button">
            <Check size={16} />
            确认并回填台账
          </button>
        </div>
      </aside>
    </div>
  );
}

type AuthState = 'checking' | 'anonymous' | 'authenticated';

export default function App() {
  const [page, setPage] = useState<PageKey>('workspace');
  const [projectsData, setProjectsData] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState('');
  const [batchId, setBatchId] = useState('');
  const [documentItems, setDocumentItems] = useState<BusinessDocument[]>([]);
  const [businessDataItems, setBusinessDataItems] = useState<ApiBusinessDataRecord[]>([]);
  const [ledgerItems, setLedgerItems] = useState<LedgerRow[]>([]);
  const [chatMessages, setChatMessages] = useState<ApiChatMessage[]>([]);
  const [authState, setAuthState] = useState<AuthState>('checking');
  const [authReady, setAuthReady] = useState(false);
  const [loginBusy, setLoginBusy] = useState(false);
  const [loginError, setLoginError] = useState('');
  const [assistantBusy, setAssistantBusy] = useState(false);
  const [assistantError, setAssistantError] = useState('');
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [createProjectOpen, setCreateProjectOpen] = useState(false);
  const [createBatchOpen, setCreateBatchOpen] = useState(false);
  const [editProjectTarget, setEditProjectTarget] = useState<Project | null>(null);
  const [editBatchTarget, setEditBatchTarget] = useState<Batch | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<
    | { kind: 'project'; projectId: string; name: string; detail: string }
    | { kind: 'batch'; batchId: string; name: string; detail: string }
    | { kind: 'document'; documentId: string; name: string; detail: string }
    | null
  >(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [toast, setToast] = useState('');
  const [profile, setProfile] = useState<ApiUserProfile | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settingsBusy, setSettingsBusy] = useState(false);
  const [actAsOpen, setActAsOpen] = useState(false);

  /** 切换生效身份：写本地会话后整页重载，避免逐页状态残留。 */
  const applyActAs = (userId: string) => {
    setActAsUserId(userId);
    window.location.reload();
  };

  const currentProject =
    projectsData.find((project) => project.id === projectId) || projectsData[0];
  const currentBatch =
    currentProject?.batches.find((batch) => batch.id === batchId) || currentProject?.batches[0];

  const loadBaseData = async (preferredProjectId?: string, preferredBatchId?: string) => {
    const [projectRows, batchRows] = await Promise.all([api.projects(), api.batches()]);
    const mapped = mapProjects(projectRows, batchRows);
    if (mapped.length === 0) {
      setProjectsData([]);
      setProjectId('');
      setBatchId('');
      setDocumentItems([]);
      setBusinessDataItems([]);
      setLedgerItems([]);
      setChatMessages([]);
      return { projects: mapped, projectId: '', batchId: '' };
    }
    const nextProject =
      mapped.find((project) => project.id === preferredProjectId)
      || mapped.find((project) => project.batches.length > 0)
      || mapped[0];
    const nextBatch =
      nextProject.batches.find((batch) => batch.id === preferredBatchId) || nextProject.batches[0];
    setProjectsData(mapped);
    setProjectId(nextProject.id);
    setBatchId(nextBatch?.id || '');
    return { projects: mapped, projectId: nextProject.id, batchId: nextBatch?.id || '' };
  };

  const refreshContext = async (
    nextProjectId = projectId,
    nextBatchId = batchId,
    projectSnapshot = projectsData
  ) => {
    if (!nextProjectId || !nextBatchId) {
      setDocumentItems([]);
      setBusinessDataItems([]);
      setLedgerItems([]);
      setChatMessages([]);
      return;
    }
    const [messages, documents, ledger, businessData] = await Promise.all([
      api.chatMessages(nextProjectId, nextBatchId),
      api.documents(nextProjectId),
      api.ledger(nextProjectId),
      api.businessData(nextProjectId, nextBatchId)
    ]);
    setChatMessages(messages);
    setDocumentItems(mapDocuments(documents));
    setLedgerItems(mapLedger(ledger, projectSnapshot));
    setBusinessDataItems(businessData);
    setProjectsData((items) =>
      items.map((project) => ({
        ...project,
        batches: project.batches.map((batch) => {
          const rows = ledger.filter((row) => row.batchId === batch.id);
          return {
            ...batch,
            delivered: rows.reduce((sum, row) => sum + Number(row.deliveryAmount || 0), 0),
            paid: rows.reduce((sum, row) => sum + Number(row.paidAmount || 0), 0),
            invoiced: rows.reduce((sum, row) => sum + Number(row.invoiceAmount || 0), 0),
            salesInvoiced: rows.reduce((sum, row) => sum + Number(row.salesInvoiceAmount || 0), 0),
            // 已收款以项目收款分摊为准（批次接口已带出），没有台账行的批次也能显示收款
            received: batch.received
          };
        })
      }))
    );
  };

  useEffect(() => {
    let cancelled = false;
    const initialize = async () => {
      if (!api.hasSession()) {
        setAuthState('anonymous');
        return;
      }
      try {
        const currentProfile = await api.me();
        if (cancelled) return;
        setProfile(currentProfile);
        const loaded = await loadBaseData();
        if (cancelled) return;
        setAuthReady(true);
        setAuthState('authenticated');
        if (loaded.projectId && loaded.batchId) {
          await refreshContext(loaded.projectId, loaded.batchId, loaded.projects);
        }
      } catch (error) {
        if (cancelled) return;
        await api.logout();
        setProfile(null);
        setAuthReady(false);
        setAuthState('anonymous');
        setLoginError(error instanceof Error ? error.message : '登录状态已失效');
      }
    };
    void initialize();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!authReady || authState !== 'authenticated' || !projectId || !batchId) return;
    void refreshContext(projectId, batchId).catch((error) => {
      setAssistantError(error instanceof Error ? error.message : '加载当前批次失败');
    });
  }, [authReady, authState, projectId, batchId]);

  // 切换页面时重拉上下文数据：行情中心人工绑定/改价等操作后，切到台账/数据中心
  // 直接显示最新值，无需整页刷新。工作台跳过（避免覆盖对话中正在流式输出的消息）；
  // 助手忙时也跳过（与消息轮询同一保护）。拉取期间旧数据照常显示，无闪烁。
  useEffect(() => {
    if (!authReady || authState !== 'authenticated' || !projectId || !batchId) return;
    if (page === 'workspace' || assistantBusy) return;
    void refreshContext(projectId, batchId).catch(() => {
      // 切页刷新失败静默：页面仍显示上次数据，下一次切换会重试
    });
  }, [page]);

  // 聊天消息轻量轮询：预约/周提醒推送的对账单卡片无需手动刷新即可见。
  // 只拉消息列表，不动台账/单据等重数据；助手忙时跳过，避免覆盖发送中的乐观消息。
  useEffect(() => {
    if (!authReady || authState !== 'authenticated' || !projectId || !batchId) return;
    const poll = async () => {
      if (assistantBusy) return;
      try {
        const messages = await api.chatMessages(projectId, batchId);
        setChatMessages(messages);
      } catch {
        // 轮询失败静默，不打扰用户
      }
    };
    const timer = setInterval(poll, 15000);
    return () => clearInterval(timer);
  }, [authReady, authState, projectId, batchId, assistantBusy]);

  useEffect(() => {
    const handleExpired = () => {
      setAuthReady(false);
      setAuthState('anonymous');
      setProfile(null);
      setLoginError('登录已过期，请重新登录');
    };
    window.addEventListener('ai-bot-auth-expired', handleExpired);
    return () => window.removeEventListener('ai-bot-auth-expired', handleExpired);
  }, []);

  // 微信扫码登录入口：写入会话 → 加载基础数据 → 进入工作台。
  const finalizeLogin = async (result?: { user: ApiUserProfile }) => {
    const profile = result?.user ?? (await api.me());
    setProfile(profile);
    const loaded = await loadBaseData();
    setAuthReady(true);
    setAuthState('authenticated');
    if (loaded.projectId && loaded.batchId) {
      await refreshContext(loaded.projectId, loaded.batchId, loaded.projects);
    }
  };

  const logout = async () => {
    await api.logout();
    setAuthReady(false);
    setAuthState('anonymous');
    setProfile(null);
    setProjectsData([]);
    setProjectId('');
    setBatchId('');
    setDocumentItems([]);
    setBusinessDataItems([]);
    setLedgerItems([]);
    setChatMessages([]);
    setPage('workspace');
    setLoginError('');
  };

  const navigate = (nextPage: PageKey) => {
    startTransition(() => setPage(nextPage));
    setMobileMenuOpen(false);
  };

  const changeProject = (nextProjectId: string) => {
    const nextProject = projectsData.find((project) => project.id === nextProjectId);
    if (!nextProject) return;
    setProjectId(nextProject.id);
    setBatchId(nextProject.batches[0]?.id || '');
  };

  const showToast = (message: string) => {
    setToast(message);
    window.setTimeout(() => setToast(''), 2200);
  };

  const saveBusinessIdentity = async (
    nickname: string,
    companyName: string,
    reminder: { enabled: boolean; weekday: number; hour: number }
  ) => {
    setSettingsBusy(true);
    try {
      const [updated] = await Promise.all([
        api.updateMe({ nickname, companyName }),
        api.updateUserSettings({ reconciliation: reminder })
      ]);
      setProfile(updated);
      setSettingsOpen(false);
      showToast('设置已保存');
    } catch (error) {
      showToast(error instanceof Error ? error.message : '设置保存失败');
    } finally {
      setSettingsBusy(false);
    }
  };

  const createProject = async (payload: ProjectCreatePayload) => {
    try {
      const project = await api.createProject({
        name: payload.name,
        customerName: payload.customer
      });
      const batch = await api.createBatch({
        projectId: project.id,
        name: payload.batchName,
        businessDate: payload.batchDate,
        materialSummary: payload.material
      });
      const loaded = await loadBaseData(project.id, batch.id);
      await refreshContext(loaded.projectId, loaded.batchId, loaded.projects);
      setCreateProjectOpen(false);
      showToast('项目与首个批次已创建');
    } catch (error) {
      showToast(error instanceof Error ? error.message : '创建项目失败');
    }
  };

  const createBatch = async (payload: BatchCreatePayload) => {
    if (!currentProject) return;
    try {
      const batch = await api.createBatch({
        projectId: currentProject.id,
        name: payload.name,
        businessDate: payload.date,
        materialSummary: payload.material
      });
      const loaded = await loadBaseData(currentProject.id, batch.id);
      await refreshContext(loaded.projectId, loaded.batchId, loaded.projects);
      setCreateBatchOpen(false);
      showToast('新批次已创建并切换');
    } catch (error) {
      showToast(error instanceof Error ? error.message : '创建批次失败');
    }
  };

  const updateProject = async (payload: ProjectCreatePayload) => {
    if (!editProjectTarget) return;
    try {
      await api.updateProject(editProjectTarget.id, {
        name: payload.name,
        customerName: payload.customer
      });
      await loadBaseData(editProjectTarget.id, batchId);
      setEditProjectTarget(null);
      showToast('项目信息已更新');
    } catch (error) {
      showToast(error instanceof Error ? error.message : '项目更新失败');
    }
  };

  /** 是否已对账：人工勾选，乐观更新，失败回滚 */
  const toggleReconciled = async (batch: Batch, reconciled: boolean) => {
    const patch = (value: boolean) => setProjectsData((items) => items.map((project) => ({
      ...project,
      batches: project.batches.map((item) => (item.id === batch.id ? { ...item, reconciled: value } : item))
    })));
    patch(reconciled);
    try {
      await api.updateBatch(batch.id, { reconciled });
    } catch (error) {
      patch(!reconciled);
      showToast(error instanceof Error ? error.message : '更新对账状态失败');
    }
  };

  /** 收款变动 → 项目汇总、批次收款状态、台账都要刷新 */
  const reloadAfterReceipt = async () => {
    const loaded = await loadBaseData(projectId, batchId);
    await refreshContext(loaded.projectId, loaded.batchId, loaded.projects);
  };

  const updateBatch = async (payload: BatchCreatePayload) => {
    if (!editBatchTarget) return;
    try {
      await api.updateBatch(editBatchTarget.id, {
        name: payload.name,
        businessDate: payload.date,
        materialSummary: payload.material,
        businessHints: payload.hints ? { delivery_note: payload.hints } : undefined
      });
      // 口径可能改了：台账的销售价/运费成本/采购毛利都是读时按口径现算的，
      // 只 loadBaseData 只刷新项目批次列表，台账仍停在旧值（2026-11-06 修）
      const loaded = await loadBaseData(currentProject?.id, editBatchTarget.id);
      await refreshContext(loaded.projectId, loaded.batchId, loaded.projects);
      setEditBatchTarget(null);
      showToast('批次信息已更新');
    } catch (error) {
      showToast(error instanceof Error ? error.message : '批次更新失败');
    }
  };

  const requestDeleteProject = (project: Project) => {    setDeleteTarget({
      kind: 'project',
      projectId: project.id,
      name: project.name,
      detail: `「${project.name}」及其下 ${project.batches.length} 个批次将被删除。单据、台账、附件等业务数据将从平台隐藏，不再计入任何统计（数据保留可审计）。`
    });
  };

  const requestDeleteBatch = (batch: Batch, project: Project) => {
    setDeleteTarget({
      kind: 'batch',
      batchId: batch.id,
      name: batch.name,
      detail: `批次「${batch.name}」将被删除。该批次的采购金额与进度将不再计入项目「${project.name}」统计，关联单据、台账、附件从平台隐藏（数据保留可审计）。`
    });
  };

  const requestDeleteDocument = (documentId: string, label: string) => {
    setDeleteTarget({
      kind: 'document',
      documentId,
      name: label,
      detail: `「${label}」将从数据中心移除。已入库的数据会同步回退对应台账金额、数量与汇总，批次和项目统计随之更新（数据保留可审计）。`
    });
  };

  const confirmDelete = async () => {
    if (!deleteTarget) return;
    setDeleteBusy(true);
    try {
      if (deleteTarget.kind === 'project') {
        await api.deleteProject(deleteTarget.projectId);
        await loadBaseData();
        showToast('项目及其下批次已删除');
      } else if (deleteTarget.kind === 'document') {
        await api.deleteDocument(deleteTarget.documentId);
        if (currentProject && currentBatch) {
          const loaded = await loadBaseData(currentProject.id, currentBatch.id);
          await refreshContext(loaded.projectId, loaded.batchId, loaded.projects);
        } else {
          await loadBaseData(currentProject?.id);
        }
        showToast('业务数据已删除，相关汇总已同步更新');
      } else {
        await api.deleteBatch(deleteTarget.batchId);
        await loadBaseData(currentProject?.id);
        showToast('批次已删除，项目统计已更新');
      }
      setDeleteTarget(null);
    } catch (error) {
      showToast(error instanceof Error ? error.message : '删除失败');
    } finally {
      setDeleteBusy(false);
    }
  };

  const sendChat = async (text: string) => {
    if (!currentProject || !currentBatch) return;
    const contextProjectId = currentProject.id;
    const contextBatchId = currentBatch.id;
    const optimisticId = `local-${uuidv4()}`;
    const optimisticMessage: ApiChatMessage = {
      id: optimisticId,
      sender: 'USER',
      messageType: 'TEXT',
      businessType: 'free_text',
      content: text,
      status: 'done',
      projectId: contextProjectId,
      projectName: currentProject.name,
      batchId: contextBatchId,
      batchName: currentBatch.name,
      payload: {},
      createdAt: new Date().toISOString()
    };
    setChatMessages((messages) => [...messages, optimisticMessage]);
    setAssistantBusy(true);
    setAssistantError('');
    try {
      const result = await api.sendChat(text, contextProjectId, contextBatchId);
      setChatMessages((messages) => [
        ...messages.filter((message) => message.id !== optimisticId),
        ...result.appended
      ]);
      const [documents, businessData] = await Promise.all([
        api.documents(contextProjectId),
        api.businessData(contextProjectId, contextBatchId)
      ]);
      setDocumentItems(mapDocuments(documents));
      setBusinessDataItems(businessData);
    } catch (error) {
      setChatMessages((messages) => messages.map((message) =>
        message.id === optimisticId ? { ...message, status: 'failed' } : message
      ));
      setAssistantError(error instanceof Error ? error.message : '发送失败');
    } finally {
      setAssistantBusy(false);
    }
  };

  const uploadDocument = async (file: File, guidance?: string) => {
    if (!currentProject || !currentBatch) return;
    const contextProjectId = currentProject.id;
    const contextBatchId = currentBatch.id;
    const optimisticId = `local-${uuidv4()}`;
    const optimisticMessage: ApiChatMessage = {
      id: optimisticId,
      sender: 'USER',
      messageType: 'FILE',
      businessType: 'upload',
      content: `上传单据：${file.name}`,
      fileName: file.name,
      status: 'done',
      projectId: contextProjectId,
      projectName: currentProject.name,
      batchId: contextBatchId,
      batchName: currentBatch.name,
      payload: guidance ? { guidanceText: guidance } : {},
      createdAt: new Date().toISOString()
    };
    setChatMessages((messages) => [...messages, optimisticMessage]);
    setAssistantBusy(true);
    setAssistantError('');
    try {
      const result = await api.uploadDocument(file, contextProjectId, contextBatchId, guidance);
      setChatMessages((messages) => [
        ...messages.filter((message) => message.id !== optimisticId),
        ...result.appended
      ]);
      const [documents, businessData] = await Promise.all([
        api.documents(contextProjectId),
        api.businessData(contextProjectId, contextBatchId)
      ]);
      setDocumentItems(mapDocuments(documents));
      setBusinessDataItems(businessData);
    } catch (error) {
      setChatMessages((messages) => messages.map((message) =>
        message.id === optimisticId ? { ...message, status: 'failed' } : message
      ));
      setAssistantError(error instanceof Error ? error.message : '文件识别失败');
    } finally {
      setAssistantBusy(false);
    }
  };

  // 追问续接：基于已保存的原始附件继续处理，不需要重新上传
  const clarifyDocument = async (documentId: string, answer: string) => {
    if (!currentProject || !currentBatch) return;
    const contextProjectId = currentProject.id;
    const contextBatchId = currentBatch.id;
    const optimisticId = `local-${uuidv4()}`;
    const optimisticMessage: ApiChatMessage = {
      id: optimisticId,
      sender: 'USER',
      messageType: 'TEXT',
      businessType: 'clarify_answer',
      content: answer,
      status: 'done',
      projectId: contextProjectId,
      projectName: currentProject.name,
      batchId: contextBatchId,
      batchName: currentBatch.name,
      payload: {},
      createdAt: new Date().toISOString()
    };
    setChatMessages((messages) => [...messages, optimisticMessage]);
    setAssistantBusy(true);
    setAssistantError('');
    try {
      const result = await api.clarifyDocument(documentId, answer);
      setChatMessages((messages) => [
        ...messages.filter((message) => message.id !== optimisticId),
        ...result.appended
      ]);
      const [documents, businessData] = await Promise.all([
        api.documents(contextProjectId),
        api.businessData(contextProjectId, contextBatchId)
      ]);
      setDocumentItems(mapDocuments(documents));
      setBusinessDataItems(businessData);
    } catch (error) {
      setChatMessages((messages) => messages.map((message) =>
        message.id === optimisticId ? { ...message, status: 'failed' } : message
      ));
      setAssistantError(error instanceof Error ? error.message : '补充回答失败');
    } finally {
      setAssistantBusy(false);
    }
  };

  // 行情卡确认/放弃（卡片按钮专用）：卡片转为已处理态 + 追加结果消息 + 刷新台账数据
  const confirmQuoteCard = async (messageId: string) => {
    if (!currentProject || !currentBatch) return;
    setAssistantBusy(true);
    setAssistantError('');
    try {
      const result = await api.confirmQuoteCard(messageId);
      setChatMessages((messages) => [
        ...messages.map((message) => message.id === messageId
          ? { ...message, status: 'archived', payload: { ...message.payload, answered: 'confirmed' } }
          : message),
        ...result.appended
      ]);
      const [documents, businessData] = await Promise.all([
        api.documents(currentProject.id),
        api.businessData(currentProject.id, currentBatch.id)
      ]);
      setDocumentItems(mapDocuments(documents));
      setBusinessDataItems(businessData);
    } catch (error) {
      setAssistantError(error instanceof Error ? error.message : '行情导入失败');
    } finally {
      setAssistantBusy(false);
    }
  };

  const discardQuoteCard = async (messageId: string) => {
    setAssistantBusy(true);
    setAssistantError('');
    try {
      const result = await api.discardQuoteCard(messageId);
      setChatMessages((messages) => [
        ...messages.map((message) => message.id === messageId
          ? { ...message, status: 'archived', payload: { ...message.payload, answered: 'cancelled' } }
          : message),
        ...result.appended
      ]);
    } catch (error) {
      setAssistantError(error instanceof Error ? error.message : '操作失败');
    } finally {
      setAssistantBusy(false);
    }
  };

  const editDocument = async (documentId: string, edits: Record<string, unknown>) => {
    if (!currentProject || !currentBatch) return;
    setAssistantBusy(true);
    setAssistantError('');
    try {
      await api.editDocument(documentId, edits);
      const loaded = await loadBaseData(currentProject.id, currentBatch.id);
      await refreshContext(loaded.projectId, loaded.batchId, loaded.projects);
      showToast('业务数据已更新，相关汇总已同步');
    } catch (error) {
      const message = error instanceof Error ? error.message : '修改识别字段失败';
      setAssistantError(message);
      // 数据中心等页面的保存失败也要可见，否则表现为「改了不生效」
      showToast(`保存失败：${message}`);
    } finally {
      setAssistantBusy(false);
    }
  };

  const decideDuplicate = async (documentId: string, decision: 'continue' | 'skip') => {
    if (!currentProject || !currentBatch) return;
    setAssistantBusy(true);
    setAssistantError('');
    try {
      await api.duplicateDecision(documentId, decision);
      const loaded = await loadBaseData(currentProject.id, currentBatch.id);
      await refreshContext(loaded.projectId, loaded.batchId, loaded.projects);
      showToast(decision === 'skip' ? '已作为重复单据归档' : '已保留为新的待确认单据');
    } catch (error) {
      setAssistantError(error instanceof Error ? error.message : '处理重复单据失败');
    } finally {
      setAssistantBusy(false);
    }
  };

  const confirmDocument = async (documentId: string) => {
    if (!currentProject || !currentBatch) return;
    setAssistantBusy(true);
    setAssistantError('');
    try {
      const document = await api.confirmDocument(documentId);
      const loaded = await loadBaseData(currentProject.id, currentBatch.id);
      await refreshContext(loaded.projectId, loaded.batchId, loaded.projects);
      const confirmationMessage = {
        supply_order: '采购单已确认，采购基础与台账已更新',
        delivery_note: '送货单已确认，实际到货信息已回填',
        transport_note: '运输费用已确认，承运单位和运费已写入台账',
        payment_request: '请款单已确认，请款金额已回填',
        payment: '付款记录已确认，已付与未付金额已更新',
        invoice: '进项发票已确认，开票金额与税率已回填',
        purchase_invoice: '进项发票已确认，开票金额与税率已回填',
        sales_invoice: '销项发票已确认，销项开票信息已回填',
        sales_settlement: '销售结算资料已确认归档，未改写销售金额',
        evidence_only: '原始凭证已确认归档',
        receipt: '收款记录已确认，回款信息已更新',
      }[document.docType] || '单据已确认，台账已更新';
      showToast(confirmationMessage);
    } catch (error) {
      setAssistantError(error instanceof Error ? error.message : '确认回填失败');
    } finally {
      setAssistantBusy(false);
    }
  };

  const updateLedgerBatch = async (
    targetBatchId: string,
    edits: {
      receiptDate?: string;
      salesOrder?: string;
      salesAmount?: number;
      requiredReceiptDate?: string;
      projectManager?: string;
      isMonthlySettlement?: boolean;
    }
  ) => {
    try {
      const updatedRows = await api.updateLedgerBatch(targetBatchId, edits);
      const mappedUpdates = mapLedger(updatedRows, projectsData);
      const updatedIds = new Set(mappedUpdates.map((row) => row.id));
      setLedgerItems((current) => [
        ...current.filter((row) => !updatedIds.has(row.id)),
        ...mappedUpdates
      ]);
      showToast('销售与回款信息已更新，毛利已自动计算');
    } catch (error) {
      showToast(error instanceof Error ? error.message : '批次信息更新失败');
      throw error;
    }
  };

  // 台账导出：由后端生成 xlsx，保留表头分组、合并单元格与样式（与小程序共用同一接口）。
  // 只导出当前所在批次（batchId 不传时后端保持项目级导出，兼容小程序）。
  const exportLedger = async () => {
    const fileName = await api.downloadLedger(currentProject?.id, batchId || undefined);
    showToast(`台账已导出：${fileName}`);
  };

  // 对账单导出：模板渲染，按日期范围取送货单明细 + 项目累计
  const exportReconciliation = async (start: string, end: string, material?: string) => {
    if (!currentProject) {
      throw new Error('请先选择项目');
    }
    const fileName = await api.downloadReconciliation(currentProject.id, start, end, material);
    showToast(`对账单已导出：${fileName}`);
  };

  if (authState === 'checking') {
    return <SessionLoading />;
  }

  if (authState === 'anonymous') {
    return <LoginPage busy={loginBusy} error={loginError} onLoginSuccess={finalizeLogin} />;
  }

  if (!profile) {
    return <SessionLoading />;
  }

  const hasContext = Boolean(currentProject && currentBatch);

  return (
    <div className="app-shell">
      <Sidebar
        page={page}
        onNavigate={navigate}
        mobileOpen={mobileMenuOpen}
        onClose={() => setMobileMenuOpen(false)}
        onSettings={() => setSettingsOpen(true)}
      />
      <main className={`main-shell ${profile.actingAs ? 'acting-as' : ''}`}>
        <AppHeader
          page={page}
          projectsData={projectsData}
          currentProject={currentProject}
          currentBatch={currentBatch}
          profile={profile}
          onProjectChange={changeProject}
          onBatchChange={setBatchId}
          onMenu={() => setMobileMenuOpen(true)}
          onLogout={() => void logout()}
          onSwitchIdentity={() => setActAsOpen(true)}
          onSettings={() => setSettingsOpen(true)}
        />
        {profile.actingAs && (
          <div className="act-as-banner">
            <UserRound size={15} />
            <span>
              正在以 <strong>{profile.nickname}</strong>
              {profile.companyName ? ` · ${profile.companyName}` : ''} 的身份查看
            </span>
            <button type="button" onClick={() => applyActAs('')}>
              返回我的账号（{profile.actingAs.nickname}）
            </button>
          </div>
        )}
        <div className="page-scroll">
          {!hasContext && (
            <EmptyBaselinePage
              hasProject={Boolean(currentProject)}
              onCreateProject={() => setCreateProjectOpen(true)}
              onCreateBatch={() => setCreateBatchOpen(true)}
            />
          )}
          {hasContext && page === 'workspace' && (
            <ConnectedWorkspacePage
              project={currentProject!}
              batch={currentBatch!}
              documents={documentItems}
              messages={chatMessages}
              nickname={profile.nickname}
              busy={assistantBusy}
              error={assistantError}
              onNavigate={navigate}
              onSend={sendChat}
              onUpload={uploadDocument}
              onConfirm={confirmDocument}
              onEdit={editDocument}
              onDuplicateDecision={decideDuplicate}
              onClarify={clarifyDocument}
              onQuoteConfirm={confirmQuoteCard}
              onQuoteDiscard={discardQuoteCard}
            />
          )}
          {hasContext && page === 'projects' && (
            <ProjectsPage
              projectsData={projectsData}
              selectedProject={currentProject!}
              selectedBatch={currentBatch!}
              documents={documentItems}
              records={businessDataItems}
              onProjectChange={changeProject}
              onBatchChange={setBatchId}
              onNavigate={navigate}
              onCreateProject={() => setCreateProjectOpen(true)}
              onCreateBatch={() => setCreateBatchOpen(true)}
              onDeleteProject={requestDeleteProject}
              onDeleteBatch={requestDeleteBatch}
              onEditProject={setEditProjectTarget}
              onEditBatch={(batch) => setEditBatchTarget(batch)}
              onToggleReconciled={toggleReconciled}
              onReceiptsChanged={reloadAfterReceipt}
              onToast={showToast}
            />
          )}
          {hasContext && page === 'documents' && (
            <DataCenterPage
              records={businessDataItems}
              projects={projectsData}
              busy={assistantBusy}
              onNavigate={navigate}
              onEdit={editDocument}
              onConfirm={confirmDocument}
              onDelete={requestDeleteDocument}
            />
          )}
          {hasContext && page === 'ledger' && (
            <ReconciliationLedgerPage
              records={businessDataItems}
              rows={ledgerItems}
              projects={projectsData}
              currentBatchId={batchId}
              onToast={showToast}
              onUpdateBatch={updateLedgerBatch}
              onExportLedger={exportLedger}
              onExportReconciliation={exportReconciliation}
            />
          )}
          {hasContext && page === 'company' && (
            <CompanyTransactionsPage
              companyName={profile?.companyName}
              projects={projectsData}
              currentProjectId={projectId}
              currentBatchId={batchId}
              onToast={showToast}
              onChanged={reloadAfterReceipt}
            />
          )}
          {page === 'market' && (
            <MarketPage
              projects={projectsData}
              currentProject={currentProject}
              currentBatch={currentBatch}
              onProjectChange={changeProject}
              onBatchChange={setBatchId}
            />
          )}
        </div>
      </main>

      <CreateProjectModal
        open={createProjectOpen || Boolean(editProjectTarget)}
        editProject={editProjectTarget}
        onClose={() => {
          setCreateProjectOpen(false);
          setEditProjectTarget(null);
        }}
        onCreate={(payload) => {
          if (editProjectTarget) {
            void updateProject(payload);
          } else {
            void createProject(payload);
          }
        }}
      />
      {currentProject && (
        <CreateBatchModal
          open={createBatchOpen || Boolean(editBatchTarget)}
          project={currentProject}
          editBatch={editBatchTarget}
          onClose={() => {
            setCreateBatchOpen(false);
            setEditBatchTarget(null);
          }}
          onCreate={(payload) => {
            if (editBatchTarget) {
              void updateBatch(payload);
            } else {
              void createBatch(payload);
            }
          }}
        />
      )}
      <DeleteConfirmModal
        target={deleteTarget}
        busy={deleteBusy}
        onCancel={() => setDeleteTarget(null)}
        onConfirm={() => void confirmDelete()}
      />
      <BusinessIdentityModal
        open={settingsOpen}
        profile={profile}
        busy={settingsBusy}
        onClose={() => setSettingsOpen(false)}
        onSave={saveBusinessIdentity}
      />
      {profile.superAdmin && (
        <ActAsModal
          open={actAsOpen}
          currentId={profile.id}
          actingAs={profile.actingAs ?? null}
          onClose={() => setActAsOpen(false)}
          onSelect={(userId) => {
            setActAsOpen(false);
            const realId = profile.actingAs?.id;
            applyActAs(realId && userId === realId ? '' : userId);
          }}
        />
      )}
      {toast && (
        <div className="toast">
          <CheckCircle2 size={17} />
          {toast}
        </div>
      )}
    </div>
  );
}
