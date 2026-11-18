import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Building2,
  Check,
  Copy,
  ExternalLink,
  FileCheck2,
  FileText,
  FileWarning,
  Loader2,
  RefreshCw,
  SkipForward,
  Trash2,
  Upload,
  WalletCards
} from 'lucide-react';
import { api, type ApiCompanyTransactionOverview } from './api';
import type { Project } from './types';

/**
 * 公司往来（2026-11-17）：本公司与供货商的采购额 · 进项发票往来。
 * 采购总额 = 已确认采购单；已开票 = 已确认进项发票；未开票 = 采购总额 − 已开票。
 * 上传发票复用智能助理识别流程，落到页头当前批次；识别后在列表中确认入账。
 */

/** 与后端 CompanyTransactionService.MANUAL_UPLOAD_HINT 一致：用于标记「手动上传」来源 */
const MANUAL_UPLOAD_HINT = '公司往来上传：进项发票';

const money = (value: number | null | undefined) =>
  new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(Number(value || 0));

type Invoice = ApiCompanyTransactionOverview['invoices'][number];

export function CompanyTransactionsPage({
  companyName,
  projects,
  currentProjectId,
  currentBatchId,
  onToast,
  onChanged
}: {
  companyName?: string;
  projects: Project[];
  currentProjectId?: string;
  currentBatchId?: string;
  onToast: (message: string) => void;
  /** 发票入账 / 删除后通知外层刷新台账与项目汇总 */
  onChanged: () => Promise<void> | void;
}) {
  const [overview, setOverview] = useState<ApiCompanyTransactionOverview | null>(null);
  const [loading, setLoading] = useState(false);
  const [supplier, setSupplier] = useState('');
  const [busyId, setBusyId] = useState('');
  const [uploading, setUploading] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  const uploadProject = projects.find((project) => project.id === currentProjectId);
  const uploadBatch = uploadProject?.batches.find((batch) => batch.id === currentBatchId);

  const load = async () => {
    setLoading(true);
    try {
      setOverview(await api.companyTransactions());
    } catch (error) {
      onToast(error instanceof Error ? error.message : '公司往来数据加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const suppliers = overview?.suppliers || [];
  // 选中的供货商在刷新后消失（如发票删光）时回到全部
  useEffect(() => {
    if (supplier && overview && !suppliers.some((row) => row.supplierName === supplier)
      && !overview.invoices.some((row) => row.supplierName === supplier)) {
      setSupplier('');
    }
  }, [overview]);

  const invoices = useMemo(() => (overview?.invoices || [])
    .filter((row) => !supplier || row.supplierName === supplier), [overview, supplier]);

  // 汇总卡片：全部供货商取总览，选中供货商取该供货商汇总
  const stats = useMemo(() => {
    const scoped = supplier ? suppliers.filter((row) => row.supplierName === supplier) : suppliers;
    const purchase = scoped.reduce((sum, row) => sum + Number(row.purchaseAmount || 0), 0);
    const invoiced = scoped.reduce((sum, row) => sum + Number(row.invoicedAmount || 0), 0);
    const overInvoiced = scoped.reduce((sum, row) => sum + Math.max(0, -Number(row.uninvoicedAmount || 0)), 0);
    const confirmedInvoices = invoices.filter((row) => row.confirmed).length;
    const rate = purchase > 0 ? (invoiced / purchase) * 100 : (invoiced > 0 ? 100 : 0);
    return {
      purchase,
      invoiced,
      uninvoiced: purchase - invoiced,
      overInvoiced,
      confirmedInvoices,
      pendingInvoices: invoices.length - confirmedInvoices,
      rate
    };
  }, [suppliers, invoices, supplier]);

  const handleUpload = async (files: FileList | null) => {
    const list = Array.from(files || []);
    if (!list.length) return;
    if (!uploadProject || !uploadBatch) {
      onToast('请先在页面顶部选择发票所属的项目和批次');
      return;
    }
    setUploading(true);
    const before = new Set((overview?.invoices || []).map((row) => row.id));
    let failed = 0;
    for (const file of list) {
      try {
        await api.uploadDocument(file, uploadProject.id, uploadBatch.id, MANUAL_UPLOAD_HINT);
      } catch (error) {
        failed += 1;
        onToast(`${file.name}：${error instanceof Error ? error.message : '识别失败'}`);
      }
    }
    try {
      const next = await api.companyTransactions();
      setOverview(next);
      const added = next.invoices.filter((row) => !before.has(row.id)).length;
      const notInvoice = list.length - failed - added;
      onToast(added > 0
        ? `已识别 ${added} 张发票（${uploadProject.name} · ${uploadBatch.name}），请核对后点「确认入账」${notInvoice > 0 ? `；${notInvoice} 个文件未识别为进项发票` : ''}`
        : (failed < list.length ? '文件未识别为进项发票，可在智能助理中查看识别结果' : '上传失败'));
    } finally {
      setUploading(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  };

  const run = async (id: string, action: () => Promise<unknown>, success: string, notifyParent = true) => {
    setBusyId(id);
    try {
      await action();
      onToast(success);
      await load();
      if (notifyParent) await onChanged();
    } catch (error) {
      onToast(error instanceof Error ? error.message : '操作失败');
    } finally {
      setBusyId('');
    }
  };

  const deleteInvoice = (row: Invoice) => {
    if (!window.confirm(`确定删除发票「${row.invoiceNo || row.supplierName}」？已入账的金额会从台账回退。`)) return;
    void run(row.id, () => api.deleteDocument(row.id), '发票已删除');
  };

  const viewFile = async (id: string) => {
    try {
      const { url } = await api.documentAccessUrl(id);
      window.open(url, '_blank', 'noopener');
    } catch (error) {
      onToast(error instanceof Error ? error.message : '附件打开失败');
    }
  };

  const purchaseNote = supplier
    ? `${supplier} · 已确认采购单`
    : `已确认采购单 ${overview?.supplyCount || 0} 张${overview?.pendingSupplyCount ? ` · ${overview.pendingSupplyCount} 张待确认未计入` : ''}`;
  const uninvoicedNote = stats.uninvoiced < 0
    ? '发票金额超过采购额，请核对'
    : stats.overInvoiced > 0
      ? `超开 ${money(stats.overInvoiced)}，请核对`
      : '采购总额 − 已开发票';
  const barWidth = Math.max(0, Math.min(100, stats.rate));

  return (
    <div className="standard-page company-tx-page page-enter">
      <div className="page-title-row">
        <div>
          <h2>公司往来</h2>
          <p>{companyName || '本公司'} 与供货商的采购额 · 进项发票往来</p>
        </div>
        <div className="page-title-actions">
          <button className="secondary-button" type="button" onClick={() => void load()} disabled={loading}>
            <RefreshCw size={16} className={loading ? 'spin' : ''} />刷新
          </button>
          <button className="primary-button" type="button" disabled={uploading}
            title={uploadBatch ? `上传到：${uploadProject?.name} · ${uploadBatch.name}` : '请先在顶部选择项目和批次'}
            onClick={() => fileRef.current?.click()}>
            {uploading ? <Loader2 size={16} className="spin" /> : <Upload size={16} />}
            {uploading ? '识别中...' : '上传发票'}
          </button>
          <input ref={fileRef} type="file" hidden multiple accept="image/*,.pdf,.ofd,.xml"
            onChange={(event) => void handleUpload(event.target.files)} />
        </div>
      </div>

      <div className="company-tx-filter" role="tablist" aria-label="供货商筛选">
        <span className="company-tx-filter-label"><Building2 size={14} />供货商</span>
        <button type="button" role="tab" aria-selected={!supplier}
          className={!supplier ? 'active' : ''} onClick={() => setSupplier('')}>全部供货商</button>
        {suppliers.map((row) => (
          <button key={row.supplierName} type="button" role="tab" aria-selected={supplier === row.supplierName}
            className={supplier === row.supplierName ? 'active' : ''}
            onClick={() => setSupplier(row.supplierName)}>
            {row.supplierName}
          </button>
        ))}
      </div>

      <div className="company-tx-cards">
        <div className="company-tx-card">
          <span className="company-tx-icon blue"><WalletCards size={18} /></span>
          <div>
            <span className="company-tx-card-label">钢筋材料采购总额</span>
            <strong>{money(stats.purchase)}</strong>
            <small>{purchaseNote}</small>
          </div>
        </div>
        <div className="company-tx-card">
          <span className="company-tx-icon green"><FileCheck2 size={18} /></span>
          <div>
            <span className="company-tx-card-label">已开发票金额</span>
            <strong className="green">{money(stats.invoiced)}</strong>
            <small>
              共 {stats.confirmedInvoices} 张发票 · 开票率 {stats.rate.toFixed(2)}%
              {stats.pendingInvoices > 0 ? ` · ${stats.pendingInvoices} 张待确认` : ''}
            </small>
          </div>
        </div>
        <div className="company-tx-card">
          <span className="company-tx-icon orange"><FileWarning size={18} /></span>
          <div>
            <span className="company-tx-card-label">未开发票金额</span>
            <strong className={stats.uninvoiced < 0 ? 'red' : 'orange'}>{money(stats.uninvoiced)}</strong>
            <small>{uninvoicedNote}</small>
          </div>
        </div>
      </div>
      <div className="company-tx-bar" title={`开票率 ${stats.rate.toFixed(2)}%`}>
        <i style={{ width: `${barWidth}%` }} />
      </div>

      <section className="panel company-tx-list">
        <div className="company-tx-list-head">
          <h3>发票列表</h3>
          <p>包含本页上传的发票，以及在智能助理中识别并确认的进项发票</p>
        </div>
        <div className="company-tx-table-wrap">
          <table className="company-tx-table">
            <thead>
              <tr>
                <th>开票日期</th>
                <th>发票号码</th>
                <th>销售方（供货商）</th>
                <th className="num">价税合计</th>
                <th>税率</th>
                <th>附件</th>
                <th>来源</th>
                <th>备注</th>
                <th className="op" aria-label="操作" />
              </tr>
            </thead>
            <tbody>
              {invoices.length === 0 && (
                <tr>
                  <td colSpan={9} className="company-tx-empty">
                    {loading ? '加载中...' : '暂无发票，点击右上角「上传发票」导入'}
                  </td>
                </tr>
              )}
              {invoices.map((row) => (
                <tr key={row.id} className={row.confirmed ? '' : 'pending'}>
                  <td>{row.invoiceDate || <span className="muted">未识别</span>}</td>
                  <td>
                    <strong className="company-tx-no">{row.invoiceNo || '-'}</strong>
                    {!row.confirmed && (
                      <span className={`company-tx-flag ${row.duplicatePending ? 'danger' : ''}`}>
                        {row.duplicatePending ? '疑似重复' : '待确认'}
                      </span>
                    )}
                  </td>
                  <td>{row.supplierName}</td>
                  <td className="num"><strong>{money(row.amount)}</strong></td>
                  <td>{row.taxRate || <span className="muted">-</span>}</td>
                  <td>
                    {row.hasFile ? (
                      <button type="button" className="company-tx-file" title={row.fileName || '原始附件'}
                        onClick={() => void viewFile(row.id)}>
                        <FileText size={13} />
                        <span>{row.fileName || '原始附件'}</span>
                        <ExternalLink size={12} />
                      </button>
                    ) : <span className="muted small">无附件</span>}
                  </td>
                  <td>
                    <span className={`company-tx-source ${row.uploadSource}`}>
                      {row.uploadSource === 'manual' ? '手动上传' : '智能助理'}
                    </span>
                  </td>
                  <td className="company-tx-remark" title={row.remark || ''}>
                    {row.remark || <span className="muted">-</span>}
                  </td>
                  <td className="op">
                    <span className="company-tx-ops">
                      {row.duplicatePending && (
                        <>
                          <button type="button" title="与已有发票不同，保留为新发票" disabled={busyId === row.id}
                            onClick={() => void run(row.id, () => api.duplicateDecision(row.id, 'continue'),
                              '已保留，请核对后确认入账', false)}>
                            <Copy size={13} />保留
                          </button>
                          <button type="button" title="是重复上传，跳过不计入" disabled={busyId === row.id}
                            onClick={() => void run(row.id, () => api.duplicateDecision(row.id, 'skip'),
                              '已作为重复发票跳过', false)}>
                            <SkipForward size={13} />跳过
                          </button>
                        </>
                      )}
                      {!row.confirmed && !row.duplicatePending && (
                        <button type="button" className="confirm" disabled={busyId === row.id}
                          onClick={() => void run(row.id, () => api.confirmDocument(row.id), '发票已确认入账')}>
                          <Check size={13} />确认入账
                        </button>
                      )}
                      <button type="button" className="icon danger" title="删除发票" aria-label="删除发票"
                        disabled={busyId === row.id} onClick={() => deleteInvoice(row)}>
                        <Trash2 size={15} />
                      </button>
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
