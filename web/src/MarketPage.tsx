import {
  ArrowDown,
  ArrowUp,
  Check,
  CheckCircle2,
  ChevronDown,
  LineChart,
  Link2,
  RefreshCw,
  Search,
  TrendingUp,
  XCircle
} from 'lucide-react';
import { useDeferredValue, useEffect, useRef, useState } from 'react';
import {
  api,
  type MaterialRow,
  type MarketMeta,
  type MarketQuoteRow
} from './api';
import type { ApiBatch } from './api';
import type { Batch, Project } from './types';

const money = (value: number | null | undefined, digits = 2) => {
  if (value == null || Number.isNaN(value)) return '—';
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    minimumFractionDigits: digits,
    maximumFractionDigits: digits
  }).format(value);
};

const yuan = (value: number | null | undefined) => {
  if (value == null || Number.isNaN(value)) return '—';
  return new Intl.NumberFormat('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 0
  }).format(value);
};

const quantity = (value: number | null | undefined) => {
  if (value == null || Number.isNaN(value)) return '—';
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 3 }).format(value);
};

/** 涨跌样式：中国惯例红涨绿跌 */
const changeClass = (change: number | null | undefined) => {
  if (change == null || change === 0) return 'market-flat';
  return change > 0 ? 'market-up' : 'market-down';
};

const changeText = (change: number | null | undefined) => {
  if (change == null) return '—';
  if (change === 0) return '0';
  return change > 0 ? `+${change}` : `${change}`;
};

type TabKey = 'compare' | 'quotes';

export interface SelectOption {
  value: string;
  label: string;
}

/** 自定义下拉（仿系统 context-picker：触发器 + 弹出菜单） */
function MarketSelect({
  label,
  value,
  options,
  placeholder = '请选择',
  onChange,
  width,
  disabled = false
}: {
  label?: string;
  value: string;
  options: SelectOption[];
  placeholder?: string;
  onChange: (value: string) => void;
  width?: string;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const closeOnOutside = (event: PointerEvent) => {
      const target = event.target as Element | null;
      if (!target?.closest('.market-picker')) {
        setOpen(false);
      }
    };
    document.addEventListener('pointerdown', closeOnOutside);
    return () => document.removeEventListener('pointerdown', closeOnOutside);
  }, [open]);

  const selected = options.find((option) => option.value === value);

  return (
    <label className="market-select">
      {label && <span className="market-select-label">{label}</span>}
      <div className={`market-picker ${open ? 'open' : ''} ${disabled ? 'disabled' : ''}`.trim()} ref={ref} style={width ? { width } : undefined}>
        <button
          type="button"
          className="market-trigger"
          aria-expanded={open}
          disabled={disabled}
          onClick={() => setOpen((current) => !current)}
        >
          <span className={selected ? 'market-trigger-text' : 'market-trigger-text placeholder'}>
            {selected ? selected.label : placeholder}
          </span>
          <ChevronDown size={15} />
        </button>
        {open && (
          <div className="market-menu">
            {options.map((option) => (
              <button
                key={option.value}
                type="button"
                className={option.value === value ? 'active' : ''}
                onClick={() => {
                  onChange(option.value);
                  setOpen(false);
                }}
              >
                <span className="market-option-text">{option.label}</span>
                {option.value === value && <Check size={14} />}
              </button>
            ))}
          </div>
        )}
      </div>
    </label>
  );
}

/** 规格候选 → 下拉选项：标注来源渠道；取价日当天没价的补「最近有价」日期，并保留当前绑定值。 */
function specBindOptions(
  rows: Array<{
    spec: string;
    sources?: string[];
    price?: number;
    latestDate?: string;
    onQuoteDay?: boolean;
  }>,
  current: string
): SelectOption[] {
  const options = rows.map((row) => {
    const parts = [row.spec];
    if (row.sources && row.sources.length > 0) {
      parts.push(row.sources.join(' / '));
    }
    if (row.onQuoteDay === false && row.latestDate) {
      parts.push(`最近有价 ${row.latestDate}`);
    }
    return { value: row.spec, label: parts.join(' · ') };
  });
  // 已绑定的规格不在候选窗口内（老绑定/取价日无此规格）：保留一条，避免打开弹框就显示占位符
  if (current && !options.some((option) => option.value === current)) {
    options.unshift({ value: current, label: `${current} · 当前绑定` });
  }
  return options;
}

export function MarketPage({
  projects,
  currentProject,
  currentBatch,
  onProjectChange,
  onBatchChange
}: {
  projects: Project[];
  currentProject?: Project;
  currentBatch?: Batch;
  onProjectChange: (projectId: string) => void;
  onBatchChange: (batchId: string) => void;
}) {
  const [tab, setTab] = useState<TabKey>('compare');
  const [meta, setMeta] = useState<MarketMeta | null>(null);
  const [batches, setBatches] = useState<ApiBatch[]>([]);
  // 材料维度单表（2026-11-04 合并）：采购+送货归并、绑定、锁定网价都在一张表
  const [materialRows, setMaterialRows] = useState<MaterialRow[]>([]);
  const [materialsLoading, setMaterialsLoading] = useState(false);
  const [bindMaterialRow, setBindMaterialRow] = useState<MaterialRow | null>(null);
  const [matchRunning, setMatchRunning] = useState(false);

  // Tab2 筛选
  const [quoteCategory, setQuoteCategory] = useState('');
  const [quoteProduct, setQuoteProduct] = useState('');
  const [quoteCity, setQuoteCity] = useState('');
  const [quoteOrigin, setQuoteOrigin] = useState('');
  const [quoteKeyword, setQuoteKeyword] = useState('');
  const [quotes, setQuotes] = useState<MarketQuoteRow[]>([]);
  const [quotesLoading, setQuotesLoading] = useState(false);
  const deferredKeyword = useDeferredValue(quoteKeyword);

  // 材料级绑定（采购/送货共用一条绑定）
  const [bindProduct, setBindProduct] = useState('');
  const [bindSpec, setBindSpec] = useState('');
  // 人工销售价（元/吨，选填）：非空时该材料销售价直接用它，不按口径推算
  const [bindManualPrice, setBindManualPrice] = useState('');
  const [recalibrating, setRecalibrating] = useState(false);
  // 行情改价（Tab2）：改某条行情价格，保存后按产品+规格刷新受影响批次
  const [editQuote, setEditQuote] = useState<MarketQuoteRow | null>(null);
  const [editPrice, setEditPrice] = useState('');
  const [savingQuote, setSavingQuote] = useState(false);
  // 材料级改价（批次材料表）：把该材料取价日的绑定规格价钉成权威价
  const [editMaterialQuote, setEditMaterialQuote] = useState<MaterialRow | null>(null);
  const [editMaterialPrice, setEditMaterialPrice] = useState('');
  const [savingMaterialPrice, setSavingMaterialPrice] = useState(false);
  // 规格候选按「当前选中的行情产品 + 该材料的取价日」实时拉取
  const [bindSpecOptions, setBindSpecOptions] =
    useState<Array<{
      spec: string;
      sources?: string[];
      price?: number;
      latestDate?: string;
      onQuoteDay?: boolean;
    }>>([]);
  const [toast, setToast] = useState('');

  useEffect(() => {
    api.marketMeta().then(setMeta).catch(() => setMeta(null));
  }, []);

  useEffect(() => {
    if (!bindMaterialRow || !bindProduct) {
      setBindSpecOptions([]);
      return;
    }
    let cancelled = false;
    api
      // 带上材料 id：候选按该材料的取价日（≤单据日期最近有该产品行情的一天）取，
      // 与锁价同一天，人工导入的旧日期行情才不会在候选里消失
      .marketProductSpecs(bindProduct, bindMaterialRow.id)
      .then((specs) => {
        if (!cancelled) setBindSpecOptions(specs || []);
      })
      .catch(() => {
        if (!cancelled) setBindSpecOptions([]);
      });
    return () => {
      cancelled = true;
    };
  }, [bindMaterialRow, bindProduct]);

  // 批次选项
  useEffect(() => {
    if (!currentProject?.id) {
      setBatches([]);
      return;
    }
    api.batches(currentProject.id).then(setBatches).catch(() => setBatches([]));
  }, [currentProject?.id]);

  useEffect(() => {
    if (tab !== 'compare' || !currentBatch) return;
    setMaterialsLoading(true);
    api
      .materials(currentBatch.id)
      .then(setMaterialRows)
      .catch(() => setMaterialRows([]))
      .finally(() => setMaterialsLoading(false));
  }, [tab, currentBatch?.id]);

  useEffect(() => {
    if (tab !== 'quotes') return;
    setQuotesLoading(true);
    api
      .marketQuotes({
        category: quoteCategory || undefined,
        productCode: quoteProduct || undefined,
        city: quoteCity || undefined,
        origin: quoteOrigin || undefined,
        keyword: deferredKeyword || undefined
      })
      .then(setQuotes)
      .catch(() => setQuotes([]))
      .finally(() => setQuotesLoading(false));
  }, [tab, quoteCategory, quoteProduct, quoteCity, quoteOrigin, deferredKeyword]);

  const notify = (message: string) => {
    setToast(message);
    window.setTimeout(() => setToast(''), 2600);
  };

  /** 材料级绑定（采购/送货共用一条绑定，manual 不会被自动匹配覆盖）。 */
  const confirmMaterialBind = async () => {
    if (!bindMaterialRow) return;
    const manualPriceText = bindManualPrice.trim();
    if (manualPriceText && !(Number(manualPriceText) > 0)) {
      notify('人工销售价必须大于 0');
      return;
    }
    try {
      await api.bindMaterial(
        bindProduct && bindSpec
          ? {
              materialId: bindMaterialRow.id,
              productCode: bindProduct,
              specName: bindSpec,
              status: 'manual',
              manualSalesPrice: manualPriceText ? Number(manualPriceText) : null
            }
          : { materialId: bindMaterialRow.id, status: 'unmatched' }
      );
      notify('绑定已保存，本批次采购/送货取价已同步重锁');
      setBindMaterialRow(null);
      setBindProduct('');
      setBindSpec('');
      setBindManualPrice('');
      if (currentBatch) {
        api.materials(currentBatch.id).then(setMaterialRows).catch(() => setMaterialRows([]));
      }
    } catch (error) {
      notify(error instanceof Error ? error.message : '保存失败');
    }
  };

  /**
   * 材料级改价（2026-11-12 重构）：只改这张材料自己的网价，只重锁本批次，不动行情库。
   * 输入留空 = 清空人工网价，恢复按行情库取价。
   */
  const confirmMaterialQuoteUpdate = async () => {
    if (!editMaterialQuote) return;
    const text = editMaterialPrice.trim();
    const manual = text === '' ? null : Number(text);
    if (manual !== null && !(manual > 0)) {
      notify('价格必须大于 0；留空表示清空人工网价');
      return;
    }
    setSavingMaterialPrice(true);
    try {
      const outcome = await api.materialQuoteUpdate(editMaterialQuote.id, manual);
      notify(manual == null
        ? '已清空人工网价，恢复按行情库取价并重算本批次'
        : `改价已保存（只影响本材料）${outcome.relocked > 0 ? '，本批次已重算' : ''}`);
      setEditMaterialQuote(null);
      setEditMaterialPrice('');
      if (currentBatch) {
        api.materials(currentBatch.id).then(setMaterialRows).catch(() => setMaterialRows([]));
      }
    } catch (error) {
      notify(error instanceof Error ? error.message : '保存失败');
    } finally {
      setSavingMaterialPrice(false);
    }
  };

  /** 行情改价：保存后按该条的产品+规格刷新受影响批次（取价日解析到该日的材料自动换新价）。 */
  const confirmQuoteUpdate = async () => {
    if (!editQuote) return;
    if (!(Number(editPrice) > 0)) {
      notify('价格必须大于 0');
      return;
    }
    setSavingQuote(true);
    try {
      const outcome = await api.marketQuoteUpdate(editQuote.id, Number(editPrice), currentBatch?.id);
      if (outcome.refreshedBatches > 0) {
        notify(outcome.backgroundBatches > 0
          ? `改价已保存，${currentBatch ? '当前批次' : '发起批次'}已重算；其他 ${outcome.backgroundBatches} 个批次后台刷新中`
          : '改价已保存，相关批次已按新价重算');
      } else {
        notify('改价已保存（没有批次用到这个规格，无需重算）');
      }
      setEditQuote(null);
      setEditPrice('');
      if (currentBatch) {
        api.materials(currentBatch.id).then(setMaterialRows).catch(() => setMaterialRows([]));
      }
    } catch (error) {
      notify(error instanceof Error ? error.message : '保存失败');
    } finally {
      setSavingQuote(false);
    }
  };

  /** AI 校准：判同新写法 + 重绑 + 重算（同步返回摘要）。 */
  const recalibrateMaterials = async () => {
    if (!currentBatch || recalibrating) return;
    setRecalibrating(true);
    try {
      const result = await api.recalibrateMaterials(currentBatch.id);
      if (result.status === 'ok') {
        notify(`校准完成：判同 ${result.writeups} 种写法，归并 ${result.mapped}、新建 ${result.created}`);
      } else if (result.status === 'all-mapped') {
        notify('所有写法都已判过，无需校准');
      } else {
        notify(result.message || '本次校准未产生变更');
      }
      api.materials(currentBatch.id).then(setMaterialRows).catch(() => setMaterialRows([]));
    } catch (error) {
      notify(error instanceof Error ? error.message : '校准失败');
    } finally {
      setRecalibrating(false);
    }
  };

  const materialLabel = (row: MaterialRow) =>
    [row.category, row.grade, row.diameter ? `Φ${row.diameter}` : null, row.lengthM ? `${row.lengthM}m` : null, row.origin]
      .filter(Boolean)
      .join(' ') || '未标注材料';

  const rerunMatch = async () => {
    if (!currentBatch || matchRunning) return;
    setMatchRunning(true);
    try {
      const matched = await api.marketRunMatch(currentBatch.id);
      notify(`匹配完成：${matched} 条`);
      api.materials(currentBatch.id).then(setMaterialRows).catch(() => setMaterialRows([]));
    } catch (error) {
      notify(error instanceof Error ? error.message : '匹配失败');
    } finally {
      setMatchRunning(false);
    }
  };

  // 概要指标（材料维度现算）
  const boundCount = materialRows.filter((row) => row.quoteSpecName).length;
  const totalPurchaseAmount = materialRows.reduce(
    (sum, row) => sum + (row.purchasePrice != null && row.purchaseTons != null
      ? row.purchasePrice * row.purchaseTons : 0),
    0
  );

  const productOptions: SelectOption[] = (meta?.products || []).map((p) => ({
    value: p.code,
    label: p.name
  }));
  const categoryOptions: SelectOption[] = (meta?.categories || []).map((c) => ({
    value: c,
    label: c
  }));
  const cityOptions: SelectOption[] = (meta?.cities || []).map((c) => ({
    value: c,
    label: c
  }));
  const originOptions: SelectOption[] = (meta?.origins || []).map((o) => ({
    value: o,
    label: o
  }));

  return (
    <div className="standard-page market-page page-enter">
      <div className="page-title-row">
        <div>
          <h2>市场行情</h2>
          <p>
            每日市场行情 · 与批次采购价对比，掌握价差
            {meta?.latestDate && ` · 行情日期 ${meta.latestDate}`}
          </p>
        </div>
        <div className="tab-list market-page-tabs">
          <button className={tab === 'compare' ? 'active' : ''} onClick={() => setTab('compare')}>
            <Link2 size={14} />项目对比
          </button>
          <button className={tab === 'quotes' ? 'active' : ''} onClick={() => setTab('quotes')}>
            <LineChart size={14} />全量行情
          </button>
        </div>
      </div>

      {/* Tab1 项目对比 */}
      {tab === 'compare' && (
        <div className="market-compare">
          <div className="market-filter-bar">
            <MarketSelect
              label="项目"
              value={currentProject?.id || ''}
              placeholder="选择项目"
              options={projects.map((p) => ({ value: p.id, label: p.name }))}
              onChange={onProjectChange}
              width="190px"
            />
            <MarketSelect
              label="批次"
              value={currentBatch?.id || ''}
              placeholder="选择批次"
              options={batches.map((b) => ({ value: b.id, label: b.name }))}
              onChange={onBatchChange}
              width="190px"
            />
            <button
              className={`text-button market-rerun${matchRunning ? ' is-running' : ''}`}
              onClick={rerunMatch}
              disabled={matchRunning}
            >
              <RefreshCw size={14} />
              {matchRunning ? '匹配中…' : '重新匹配'}
            </button>
          </div>

          {!currentBatch ? (
            <div className="empty-state market-empty">
              <TrendingUp size={26} />
              <p>请先选择项目与批次</p>
            </div>
          ) : materialsLoading ? (
            <div className="market-loading">加载中…</div>
          ) : (
            <>
              {/* 概要指标（材料维度现算） */}
              <div className="metric-row market-metrics">
                <div className="metric">
                  <span>材料数</span>
                  <strong>{materialRows.length}</strong>
                  <small>已绑定 {boundCount} · 未绑定 {materialRows.length - boundCount}</small>
                </div>
                <div className="metric">
                  <span>批次采购额（采购吨价×吨数）</span>
                  <strong>{money(totalPurchaseAmount, 0)}</strong>
                  <small>不含采购运费（批次级另计）</small>
                </div>
              </div>

              {/* 批次材料 × 行情对比（合并单表，2026-11-04） */}
              <section className="panel">
                <div className="panel-heading">
                  <div>
                    <span className="eyebrow">批次 {currentBatch.name}</span>
                    <h2>批次材料 × 行情对比</h2>
                    <small className="panel-sub">
                      采购单与送货单按材料归并（写法不同也能认成同一种货）：一种材料一行、一条网价绑定，
                      采购侧与销售侧取价共用。锁定网价 = 绑定规格在<b>取价日</b>（≤单据日期最近有行情日，
                      采购单日期优先）的价格，产地一致优先；人工销售价填写后销售单价直接用它。
                    </small>
                  </div>
                  <button
                    className={`text-button market-rerun${recalibrating ? ' is-running' : ''}`}
                    onClick={() => void recalibrateMaterials()}
                    disabled={recalibrating || materialRows.length === 0}
                  >
                    <RefreshCw size={14} />
                    {recalibrating ? '校准中…' : 'AI 校准'}
                  </button>
                </div>
                <div className="table-scroll">
                  <table className="data-table market-table">
                    <thead>
                      <tr>
                        <th>材料</th>
                        <th>采购吨数</th>
                        <th>采购吨价</th>
                        <th title="材料级人工价优先，其次按批次定价口径推算（毛利率=采购吨价÷(1−X%)，上浮=网价+上浮）；按采购吨数加权，与台账采购毛利同源">销售单价</th>
                        <th>送货销售金额</th>
                        <th>网价绑定</th>
                        <th>锁定网价（取价日）</th>
                        <th>操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {materialRows.map((row) => (
                        <tr
                          key={row.id}
                          className={row.status === 'unbound' || row.status === 'duplicate' ? 'row-unmatched' : undefined}
                        >
                          <td className="market-product">
                            {materialLabel(row)}
                            <span className="market-match-detail">
                              {' '}{row.purchaseRows} 采购行 / {row.deliveryRows} 送货行
                            </span>
                          </td>
                          <td>{row.purchaseTons != null ? quantity(row.purchaseTons) : '—'}</td>
                          <td className="market-price-cell">{row.purchasePrice != null ? money(row.purchasePrice, 0) : '—'}</td>
                          <td className="market-price-cell">
                            {row.salesPrice != null
                              ? <>{money(row.salesPrice, 0)}
                                  <small>
                                    {row.salesPriceBasis === 'manual' ? ' · 人工价'
                                      : row.salesPriceBasis === 'margin' ? ' · 毛利率'
                                        : row.salesPriceBasis === 'uplift' ? ' · 上浮' : ''}
                                  </small>
                                </>
                              : <small className="amount-pending">
                                  {row.salesPriceBasis === 'no_policy' ? '未设口径'
                                    : row.salesPriceBasis === 'cost_pending' ? '待采购价'
                                      : row.salesPriceBasis === 'net_pending' ? '待网价' : '未算'}
                                </small>}
                          </td>
                          <td>{row.deliveryAmount != null ? money(row.deliveryAmount, 0) : '—'}</td>
                            <td className="market-spec">
                              {row.quoteSpecName
                                ? `${row.quoteProductName || ''} ${row.quoteSpecName}`.trim()
                                : '未绑定'}
                              {row.quoteSpecName && (
                                <small>
                                  {row.status === 'manual' ? ' · 人工' : ' · 自动'}
                                  {row.origin ? ` · ${row.origin}` : ''}
                                </small>
                              )}
                            </td>
                          <td className="market-price-cell">
                            {row.lockedNetPrice != null
                              ? <>
                                  {money(row.lockedNetPrice, 0)}
                                  {row.manualQuotePrice != null
                                    ? <small> · 人工网价</small>
                                    : (row.quoteDate && <small> · {row.quoteDate}</small>)}
                                </>
                              : (row.quoteSpecName
                                ? <small>当日无此规格行情</small>
                                : '—')}
                          </td>
                          <td>
                            <div className="market-row-actions">
                              {row.quoteSpecName && (
                                <button className="text-button" onClick={() => {
                                  setEditMaterialQuote(row);
                                  // 只预填人工价：留空表示"没人工定价过"，避免误把行情价钉成人工价
                                  setEditMaterialPrice(row.manualQuotePrice != null
                                    ? String(row.manualQuotePrice) : '');
                                }}>
                                  改价
                                </button>
                              )}
                              <button className="text-button" onClick={() => {
                                setBindMaterialRow(row);
                                setBindProduct(row.quoteProductCode || '');
                                setBindSpec(row.quoteSpecName || '');
                                setBindManualPrice(row.manualSalesPrice != null ? String(row.manualSalesPrice) : '');
                              }}>
                                {row.quoteSpecName ? '改绑定' : '绑定'}
                              </button>
                            </div>
                          </td>
                        </tr>
                      ))}
                      {materialRows.length === 0 && (
                        <tr>
                          <td colSpan={8} className="market-empty-cell">
                            本批次还没有归并出材料（确认采购单/送货单后自动生成）
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </section>
            </>
          )}
        </div>
      )}

      {/* Tab2 全量行情 */}
      {tab === 'quotes' && (
        <div className="market-quotes">
          <div className="market-filter-bar">
            <MarketSelect
              label="分类"
              value={quoteCategory}
              placeholder="全部分类"
              options={[{ value: '', label: '全部分类' }, ...categoryOptions]}
              onChange={(value) => {
                setQuoteCategory(value);
                setQuoteProduct('');
              }}
              width="140px"
            />
            <MarketSelect
              label="产品"
              value={quoteProduct}
              placeholder="全部产品"
              options={[
                { value: '', label: '全部产品' },
                ...(meta?.products || [])
                  .filter((p) => !quoteCategory || p.category === quoteCategory)
                  .map((p) => ({ value: p.code, label: p.name }))
              ]}
              onChange={setQuoteProduct}
              width="150px"
            />
            <MarketSelect
              label="城市"
              value={quoteCity}
              placeholder="全部城市"
              options={[{ value: '', label: '全部城市' }, ...cityOptions]}
              onChange={setQuoteCity}
              width="140px"
            />
            <MarketSelect
              label="产地"
              value={quoteOrigin}
              placeholder="全部产地"
              options={[{ value: '', label: '全部产地' }, ...originOptions]}
              onChange={setQuoteOrigin}
              width="150px"
            />
            <label className="market-search">
              <Search size={15} />
              <input
                placeholder="搜索规格 / 材质，如 Φ25 或 HRB400E"
                value={quoteKeyword}
                onChange={(e) => setQuoteKeyword(e.target.value)}
              />
            </label>
            <span className="market-date-chip">
              {meta ? `最新 ${meta.latestDate}` : ''}
            </span>
          </div>

          <section className="panel">
            <div className="panel-heading">
              <div>
                <span className="eyebrow">最新一日行情</span>
                <h2>全量市场价格</h2>
              </div>
              {quotesLoading && <span className="market-loading small">加载中…</span>}
            </div>
            <div className="table-scroll">
              <table className="data-table market-table">
                <thead>
                  <tr>
                    <th>品名</th>
                    <th>规格</th>
                    <th>材质</th>
                    <th>产地</th>
                    <th>城市</th>
                    <th>价格（元/吨）</th>
                    <th>前日价差</th>
                    <th>数据日期</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {quotes.map((q) => (
                    <tr key={q.id}>
                      <td className="market-product">
                        {q.productName}
                        {q.source === 'mysteel' && <span className="market-ai-tag">权威</span>}
                      </td>
                      <td>{q.spec || q.specName.split(/\s+/)[1] || '—'}</td>
                      <td>{q.grade || '—'}</td>
                      <td>{q.origin || '—'}</td>
                      <td>{q.city}</td>
                      <td className="market-price-cell">{q.price == null ? '—' : yuan(q.price)}</td>
                      <td>
                        <span className={`market-change ${changeClass(q.priceChange)}`}>
                          {q.priceChange != null && q.priceChange > 0 && <ArrowUp size={13} />}
                          {q.priceChange != null && q.priceChange < 0 && <ArrowDown size={13} />}
                          {changeText(q.priceChange)}
                        </span>
                      </td>
                      <td className="market-source">{q.quoteDate}</td>
                      <td>
                        <button className="text-button" onClick={() => {
                          setEditQuote(q);
                          setEditPrice(q.price != null ? String(q.price) : '');
                        }}>
                          改价
                        </button>
                      </td>
                    </tr>
                  ))}
                  {quotes.length === 0 && !quotesLoading && (
                    <tr>
                      <td colSpan={9} className="market-empty-cell">暂无行情数据，请等待爬虫入库</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </section>
        </div>
      )}

      {/* 材料级改价弹层：钉权威价 + 全链路刷新 */}
      {editMaterialQuote && (
        <div className="market-modal-backdrop" onClick={() => setEditMaterialQuote(null)}>
          <div className="market-modal" onClick={(e) => e.stopPropagation()}>
            <div className="market-modal-head">
              <h3>材料改价（本材料网价）</h3>
              <button onClick={() => setEditMaterialQuote(null)} aria-label="关闭">
                <XCircle size={18} />
              </button>
            </div>
            <p className="market-modal-material">
              {materialLabel(editMaterialQuote)}
              {editMaterialQuote.quoteDate && (
                <small> · 取价日 {editMaterialQuote.quoteDate}</small>
              )}
            </p>
            <div className="market-field">
              <span>本材料网价（元/吨）</span>
              <input
                type="number"
                min="0"
                step="any"
                className="market-price-input"
                placeholder={editMaterialQuote.lockedNetPrice != null
                  ? `当前行情价 ${money(editMaterialQuote.lockedNetPrice, 0)}（留空=用行情价）`
                  : '留空=按行情库取价'}
                value={editMaterialPrice}
                onChange={(event) => setEditMaterialPrice(event.target.value)}
              />
              <small className="market-field-hint">
                只改这一条材料的网价（优先级高于行情库，取价、销售价都按它算），
                也只重算本批次——<b>不会写进行情库、不影响其它批次或共用同一规格的其它材料</b>。
                留空保存 = 清空人工网价，恢复按行情价取价。要改「市场行情」请用行情 Tab 的改价。
              </small>
            </div>
            <div className="market-modal-actions">
              <button className="secondary-button" onClick={() => setEditMaterialQuote(null)}>取消</button>
              <button
                className="primary-button compact"
                onClick={() => void confirmMaterialQuoteUpdate()}
                disabled={savingMaterialPrice}
              >
                <CheckCircle2 size={15} />
                {savingMaterialPrice ? '保存并刷新中…' : '保存并同步'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 行情改价弹层（爬取/权威均可改，保存后全链路刷新受影响批次） */}
      {editQuote && (
        <div className="market-modal-backdrop" onClick={() => setEditQuote(null)}>
          <div className="market-modal" onClick={(e) => e.stopPropagation()}>
            <div className="market-modal-head">
              <h3>行情改价</h3>
              <button onClick={() => setEditQuote(null)} aria-label="关闭">
                <XCircle size={18} />
              </button>
            </div>
            <p className="market-modal-material">
              {editQuote.quoteDate} · {editQuote.productName} {editQuote.spec || editQuote.specName}
              {' · '}{editQuote.city}{editQuote.origin ? ` · ${editQuote.origin}` : ''}
            </p>
            <div className="market-field">
              <span>价格（元/吨）</span>
              <input
                type="number"
                min="0"
                step="any"
                className="market-price-input"
                value={editPrice}
                onChange={(event) => setEditPrice(event.target.value)}
              />
              <small className="market-field-hint">
                保存后，取价日解析到该日、且绑定了这个规格的批次会自动按新价重锁重算
                （人工绑定/人工销售价不动）；与截图导入走同一条刷新通道。
              </small>
            </div>
            <div className="market-modal-actions">
              <button className="secondary-button" onClick={() => setEditQuote(null)}>取消</button>
              <button
                className="primary-button compact"
                onClick={() => void confirmQuoteUpdate()}
                disabled={savingQuote}
              >
                <CheckCircle2 size={15} />
                {savingQuote ? '保存并刷新中…' : '保存并同步'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 材料网价绑定弹层 */}
      {bindMaterialRow && (
        <div className="market-modal-backdrop" onClick={() => setBindMaterialRow(null)}>
          <div className="market-modal" onClick={(e) => e.stopPropagation()}>
            <div className="market-modal-head">
              <h3>材料网价绑定</h3>
              <button onClick={() => setBindMaterialRow(null)} aria-label="关闭">
                <XCircle size={18} />
              </button>
            </div>
            <p className="market-modal-material">{materialLabel(bindMaterialRow)}</p>
            <div className="market-field">
              <span>行情产品</span>
              <MarketSelect
                label=""
                value={bindProduct}
                placeholder="— 选择产品 —"
                options={productOptions}
                onChange={(next) => {
                  // 换产品必须清掉旧规格，否则会带着上一个产品的规格去绑定
                  setBindProduct(next);
                  setBindSpec('');
                }}
                width="100%"
              />
            </div>
            <div className="market-field">
              <span>行情规格</span>
              <MarketSelect
                label=""
                value={bindSpec}
                placeholder={
                  !bindProduct
                    ? '— 请先选择产品 —'
                    : bindSpecOptions.length === 0
                      ? '该产品暂无行情规格'
                      : '— 选择规格 —'
                }
                disabled={!bindProduct || bindSpecOptions.length === 0}
                options={specBindOptions(bindSpecOptions, bindSpec)}
                onChange={setBindSpec}
                width="100%"
              />
              {bindProduct && (
                <small className="market-field-hint">
                  候选 = 该材料取价日
                  {bindMaterialRow.quoteDate ? `（${bindMaterialRow.quoteDate}）` : ''}及之前 8 个行情日内的规格；
                  标「最近有价」的是当天没价、只有别的日子有价，绑定后这个取价日仍取不到价。
                </small>
              )}
            </div>
            <div className="market-field">
              <span>人工销售价（选填，元/吨）</span>
              <input
                type="number"
                min="0"
                step="any"
                className="market-price-input"
                placeholder="留空则按批次口径自动推算"
                value={bindManualPrice}
                onChange={(event) => setBindManualPrice(event.target.value)}
              />
              <small className="market-field-hint">
                填写后该材料的销售单价直接用它（人工定价，优先于网价+上浮 / 毛利率倒推）；
                清空恢复自动。修改采购吨价请到采购单，运费在批次口径维护。
              </small>
            </div>
            <small className="market-field-hint">
              绑定钉在这个材料上：采购单与送货单的取价共用这一条，人工绑定不会被自动匹配覆盖。
              保存后本批次会立即按材料绑定重锁。
            </small>
            <div className="market-modal-actions">
              <button className="secondary-button" onClick={() => setBindMaterialRow(null)}>
                取消
              </button>
              <button
                className="primary-button compact"
                onClick={() => void confirmMaterialBind()}
                disabled={!bindProduct || !bindSpec}
              >
                <CheckCircle2 size={15} />
                确认绑定
              </button>
            </div>
          </div>
        </div>
      )}

      {toast && <div className="market-toast">{toast}</div>}
    </div>
  );
}

