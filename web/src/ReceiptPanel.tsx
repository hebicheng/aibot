import { useEffect, useMemo, useRef, useState } from 'react';
import { Check, PencilLine, Plus, Trash2, Wand2, X } from 'lucide-react';
import {
  api,
  type ApiProjectReceiptOverview,
  type ApiReceipt,
  type ApiReceiptBatch,
  type ReceiptStatus
} from './api';

/**
 * 项目收款管理（2026-11-15，方案 A + C）：
 * - 一笔收款关联同项目下多个批次；登记时按销售顺序自动勾选并分摊（C），可手动改批次和金额（A）。
 * - 只在选中的批次上分摊：没选的批次（哪怕之前没收齐）不受影响。
 * - 汇总：项目应收 / 收款 / 差额，按材料汇总收款。
 */

const money = (value: number) =>
  new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(value || 0);

const round2 = (value: number) => Math.round((value || 0) * 100) / 100;

const today = () => {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
};

export const receiptTone: Record<ReceiptStatus, string> = {
  未收款: 'danger',
  部分收款: 'warning',
  已收款: 'success'
};

/** 按销售顺序（批次日期）把金额依次补齐选中批次的未收；钱多了余额放最后一个选中批次。 */
function allocateLocally(
  amount: number,
  ordered: ApiReceiptBatch[],
  selected: Set<string>,
  receivedOutside: Record<string, number>
): Record<string, number> {
  const result: Record<string, number> = {};
  let left = round2(amount);
  const picked = ordered.filter((b) => selected.has(b.batchId));
  picked.forEach((batch) => {
    const remaining = Math.max(0, round2(batch.salesAmount - (receivedOutside[batch.batchId] || 0)));
    const take = Math.max(0, Math.min(left, remaining));
    result[batch.batchId] = round2(take);
    left = round2(left - take);
  });
  if (left > 0 && picked.length > 0) {
    const last = picked[picked.length - 1].batchId;
    result[last] = round2(result[last] + left);
  }
  return result;
}

function ReceiptModal({
  projectId,
  overview,
  editing,
  onClose,
  onSaved
}: {
  projectId: string;
  overview: ApiProjectReceiptOverview;
  editing: ApiReceipt | null;
  onClose: () => void;
  onSaved: (message: string) => void;
}) {
  const ordered = overview.batches;
  // 编辑时：「其他收款」已收 = 批次已收 − 本笔在该批次上的分摊
  const receivedOutside = useMemo(() => {
    const own: Record<string, number> = {};
    editing?.allocations.forEach((a) => { own[a.batchId] = (own[a.batchId] || 0) + a.amount; });
    const map: Record<string, number> = {};
    ordered.forEach((b) => { map[b.batchId] = round2(b.receivedAmount - (own[b.batchId] || 0)); });
    return map;
  }, [ordered, editing]);

  const [date, setDate] = useState(editing?.receiptDate || today());
  const [amountText, setAmountText] = useState(editing ? String(editing.amount) : '');
  const [payer, setPayer] = useState(editing?.payer || '');
  const [remark, setRemark] = useState(editing?.remark || '');
  const [selected, setSelected] = useState<Set<string>>(
    () => new Set(editing?.allocations.map((a) => a.batchId) || []));
  const [amounts, setAmounts] = useState<Record<string, string>>(() => {
    const init: Record<string, string> = {};
    editing?.allocations.forEach((a) => { init[a.batchId] = String(a.amount); });
    return init;
  });
  const [manual, setManual] = useState(Boolean(editing));
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const amount = Number(amountText) || 0;
  const tableRef = useRef<HTMLDivElement>(null);
  const [scrollTick, setScrollTick] = useState(0);

  // 自动勾选后把第一个选中批次滚进可视区，避免选中项落在滚动区下方看不到
  useEffect(() => {
    if (!scrollTick) return;
    tableRef.current?.querySelector('tr.selected-row')?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }, [scrollTick]);

  // 自动模式：金额或勾选变化 → 重新按销售顺序分摊
  useEffect(() => {
    if (manual) return;
    const next = allocateLocally(amount, ordered, selected, receivedOutside);
    setAmounts(Object.fromEntries(Object.entries(next).map(([k, v]) => [k, String(v)])));
  }, [amount, selected, manual, ordered, receivedOutside]);

  const autoPick = () => {
    // 从第一个未收齐的批次开始，按销售顺序勾到钱分完为止
    const next = new Set<string>();
    let left = amount;
    for (const batch of ordered) {
      if (left <= 0) break;
      const remaining = Math.max(0, round2(batch.salesAmount - (receivedOutside[batch.batchId] || 0)));
      if (remaining <= 0) continue;
      next.add(batch.batchId);
      left = round2(left - remaining);
    }
    setManual(false);
    setSelected(next);
    setScrollTick((n) => n + 1);
  };

  const toggle = (batchId: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(batchId)) next.delete(batchId); else next.add(batchId);
      return next;
    });
    if (manual) {
      setAmounts((prev) => {
        const next = { ...prev };
        if (selected.has(batchId)) delete next[batchId]; else next[batchId] = '0';
        return next;
      });
    }
  };

  const allocated = round2([...selected].reduce((sum, id) => sum + (Number(amounts[id]) || 0), 0));
  const diff = round2(amount - allocated);
  const overflow = !manual
    ? round2(amount - ordered.filter((b) => selected.has(b.batchId))
      .reduce((sum, b) => sum + Math.max(0, b.salesAmount - (receivedOutside[b.batchId] || 0)), 0))
    : 0;

  const submit = async () => {
    setError('');
    if (amount <= 0) { setError('请输入收款金额'); return; }
    if (selected.size === 0) { setError('请至少勾选一个批次'); return; }
    if (manual && diff !== 0) {
      setError(`各批次分摊合计 ${money(allocated)}，与收款金额 ${money(amount)} 相差 ${money(diff)}`);
      return;
    }
    const payload = {
      receiptDate: date,
      amount,
      payer: payer.trim() || undefined,
      remark: remark.trim() || undefined,
      allocations: ordered
        .filter((b) => selected.has(b.batchId))
        .map((b) => ({ batchId: b.batchId, amount: manual ? Number(amounts[b.batchId]) || 0 : null }))
    };
    setBusy(true);
    try {
      if (editing) {
        await api.updateReceipt(projectId, editing.id, payload);
        onSaved('收款已修改，台账已同步');
      } else {
        await api.createReceipt(projectId, payload);
        onSaved('收款已登记');
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-shell" role="dialog" aria-modal="true" aria-label={editing ? '修改收款' : '登记收款'}>
      <button className="modal-backdrop" onClick={onClose} aria-label="关闭" />
      <div className="entity-modal receipt-modal">
        <div className="modal-heading">
          <div>
            <h2>{editing ? '修改收款' : '登记收款'}</h2>
            <p>一笔收款可关联多个批次。系统按销售顺序自动分摊，也可以手动改每个批次分到的金额。</p>
          </div>
          <button type="button" onClick={onClose} aria-label="关闭"><X size={20} /></button>
        </div>

        <div className="entity-form-grid">
          <label className="entity-field">
            <span>收款金额（元）</span>
            <input type="number" min="0" step="0.01" value={amountText} autoFocus
              onChange={(e) => setAmountText(e.target.value)} placeholder="如 30000" />
          </label>
          <label className="entity-field">
            <span>收款日期</span>
            <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
          </label>
          <label className="entity-field">
            <span>付款单位 <small>选填</small></span>
            <input value={payer} onChange={(e) => setPayer(e.target.value)} placeholder="如 某某小学" />
          </label>
          <label className="entity-field">
            <span>备注 <small>选填</small></span>
            <input value={remark} onChange={(e) => setRemark(e.target.value)} />
          </label>
        </div>

        <div className="receipt-alloc-head">
          <strong>关联批次</strong>
          <span className="receipt-alloc-tools">
            <button type="button" className="small-button" onClick={autoPick} disabled={amount <= 0}>
              <Wand2 size={14} />按销售顺序自动勾选
            </button>
            <label className="receipt-manual-toggle">
              <input type="checkbox" checked={manual} onChange={(e) => setManual(e.target.checked)} />
              手动改金额
            </label>
          </span>
        </div>

        <div className="receipt-alloc-wrap" ref={tableRef}>
          <table className="data-table receipt-alloc-table">
            <thead>
              <tr>
                <th style={{ width: 36 }} />
                <th>批次</th>
                <th>材料</th>
                <th>销售金额</th>
                <th>其他收款已收</th>
                <th>本次分摊</th>
              </tr>
            </thead>
            <tbody>
              {ordered.map((batch) => {
                const checked = selected.has(batch.batchId);
                return (
                  <tr key={batch.batchId} className={checked ? 'selected-row' : ''}
                    onClick={() => toggle(batch.batchId)}>
                    <td>
                      <input type="checkbox" checked={checked} readOnly aria-label={`关联 ${batch.batchName}`} />
                    </td>
                    <td>
                      <strong>{batch.batchName}</strong>
                      <small>{batch.businessDate || '-'}</small>
                    </td>
                    <td>{batch.material}</td>
                    <td>{money(batch.salesAmount)}</td>
                    <td>{money(receivedOutside[batch.batchId] || 0)}</td>
                    <td onClick={(e) => e.stopPropagation()}>
                      {checked ? (
                        manual ? (
                          <input className="receipt-alloc-input" type="number" min="0" step="0.01"
                            value={amounts[batch.batchId] ?? ''}
                            onChange={(e) => setAmounts((prev) => ({ ...prev, [batch.batchId]: e.target.value }))} />
                        ) : (
                          <strong>{money(Number(amounts[batch.batchId]) || 0)}</strong>
                        )
                      ) : <span className="muted">-</span>}
                    </td>
                  </tr>
                );
              })}
              {ordered.length === 0 && (
                <tr><td colSpan={6} className="muted">本项目还没有批次</td></tr>
              )}
            </tbody>
          </table>
        </div>

        <div className={`receipt-alloc-summary ${manual && diff !== 0 ? 'bad' : ''}`}>
          已分摊 {money(allocated)} / 收款 {money(amount)}
          {manual && diff !== 0 && <> · 还差 {money(diff)}</>}
          {!manual && overflow > 0 && selected.size > 0 && (
            <> · 选中批次全部收齐后多出 {money(overflow)}，记在最后一个批次上</>
          )}
        </div>

        {error && <p role="alert" className="receipt-error">{error}</p>}

        <div className="modal-actions">
          <button type="button" className="secondary-button" onClick={onClose}>取消</button>
          <button type="button" className="primary-button" onClick={submit} disabled={busy}>
            {editing ? <Check size={17} /> : <Plus size={17} />}
            {busy ? '保存中…' : editing ? '保存修改' : '确认登记'}
          </button>
        </div>
      </div>
    </div>
  );
}

export function ReceiptPanel({
  projectId,
  refreshKey,
  onChanged,
  onToast
}: {
  projectId: string;
  /** 外部数据变化（如单据确认）时递增，触发重新加载 */
  refreshKey?: unknown;
  /** 收款变动后通知父组件刷新项目/批次数据 */
  onChanged: () => Promise<void> | void;
  onToast: (message: string) => void;
}) {
  const [overview, setOverview] = useState<ApiProjectReceiptOverview | null>(null);
  const [modal, setModal] = useState<{ editing: ApiReceipt | null } | null>(null);
  const [tab, setTab] = useState<'receipts' | 'materials'>('receipts');

  const load = async () => {
    try {
      setOverview(await api.receiptOverview(projectId));
    } catch (e) {
      onToast(e instanceof Error ? e.message : '收款数据加载失败');
    }
  };

  useEffect(() => { setOverview(null); void load(); }, [projectId, refreshKey]);

  const afterSave = async (message: string) => {
    setModal(null);
    await load();
    await onChanged();
    onToast(message);
  };

  const remove = async (receipt: ApiReceipt) => {
    if (!window.confirm(`确定删除 ${receipt.receiptDate} 这笔 ${money(receipt.amount)} 的收款吗？`)) return;
    try {
      await api.deleteReceipt(projectId, receipt.id);
      await afterSave('收款已删除');
    } catch (e) {
      onToast(e instanceof Error ? e.message : '删除失败');
    }
  };

  const diff = overview?.differenceAmount ?? 0;

  return (
    <section className="panel receipt-panel">
      <div className="panel-heading">
        <div>
          <span className="eyebrow">一笔收款可关联多个批次</span>
          <h2>收款管理</h2>
        </div>
        <button className="small-button" onClick={() => setModal({ editing: null })} disabled={!overview}>
          <Plus size={15} />登记收款
        </button>
      </div>

      <div className="receipt-metrics">
        <div className="metric">
          <span>应收（销售金额合计）</span>
          <strong>{money(overview?.salesAmount ?? 0)}</strong>
          <small>全部批次销售金额</small>
        </div>
        <div className="metric">
          <span>收款金额合计</span>
          <strong>{money(overview?.receivedAmount ?? 0)}</strong>
          <small>共 {overview?.receipts.length ?? 0} 笔</small>
        </div>
        <div className={`metric ${diff < 0 ? 'receipt-over' : diff > 0 ? 'receipt-due' : ''}`}>
          <span>应收差额</span>
          <strong>{money(diff)}</strong>
          <small>{diff < 0 ? '收款已超过应收' : '应收 − 收款'}</small>
        </div>
      </div>

      <div className="receipt-tabs" role="tablist">
        <button role="tab" aria-selected={tab === 'receipts'} className={tab === 'receipts' ? 'active' : ''}
          onClick={() => setTab('receipts')}>收款记录</button>
        <button role="tab" aria-selected={tab === 'materials'} className={tab === 'materials' ? 'active' : ''}
          onClick={() => setTab('materials')}>按材料汇总</button>
      </div>

      {tab === 'receipts' ? (
        <div className="batch-table-wrap">
          <table className="data-table receipt-table">
            <thead>
              <tr>
                <th>收款日期</th>
                <th>收款金额</th>
                <th>关联批次（分摊金额）</th>
                <th>付款单位</th>
                <th className="batch-op-col">操作</th>
              </tr>
            </thead>
            <tbody>
              {overview?.receipts.map((receipt) => (
                <tr key={receipt.id}>
                  <td>
                    <strong>{receipt.receiptDate}</strong>
                    {receipt.source === 'document' && <small>智能助理收款单</small>}
                  </td>
                  <td><strong>{money(receipt.amount)}</strong></td>
                  <td>
                    <div className="receipt-chips">
                      {receipt.allocations.map((a) => (
                        <span key={a.batchId} className="receipt-chip" title={a.material}>
                          {a.batchName}<em>{money(a.amount)}</em>
                        </span>
                      ))}
                    </div>
                  </td>
                  <td>{receipt.payer || '-'}</td>
                  <td className="batch-op-col">
                    <span className="batch-ops">
                      <span className="batch-edit" role="button" tabIndex={0} title="修改收款"
                        aria-label="修改收款" onClick={() => setModal({ editing: receipt })}
                        onKeyDown={(e) => { if (e.key === 'Enter') setModal({ editing: receipt }); }}>
                        <PencilLine size={15} />
                      </span>
                      {receipt.source === 'manual' && (
                        <span className="batch-delete" role="button" tabIndex={0} title="删除收款"
                          aria-label="删除收款" onClick={() => remove(receipt)}
                          onKeyDown={(e) => { if (e.key === 'Enter') void remove(receipt); }}>
                          <Trash2 size={15} />
                        </span>
                      )}
                    </span>
                  </td>
                </tr>
              ))}
              {overview && overview.receipts.length === 0 && (
                <tr><td colSpan={5} className="muted receipt-empty">还没有收款记录，点右上角“登记收款”</td></tr>
              )}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="batch-table-wrap">
          <table className="data-table receipt-table">
            <thead>
              <tr>
                <th>材料</th>
                <th>批次数</th>
                <th>销售金额</th>
                <th>收款金额</th>
                <th>应收差额</th>
              </tr>
            </thead>
            <tbody>
              {overview?.materials.map((m) => (
                <tr key={m.material}>
                  <td><strong>{m.material}</strong></td>
                  <td>{m.batchCount}</td>
                  <td>{money(m.salesAmount)}</td>
                  <td>{money(m.receivedAmount)}</td>
                  <td className={m.differenceAmount < 0 ? 'receipt-over-text' : ''}>{money(m.differenceAmount)}</td>
                </tr>
              ))}
              {overview && overview.materials.length === 0 && (
                <tr><td colSpan={5} className="muted receipt-empty">暂无批次</td></tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {modal && overview && (
        <ReceiptModal projectId={projectId} overview={overview} editing={modal.editing}
          onClose={() => setModal(null)} onSaved={afterSave} />
      )}
    </section>
  );
}
