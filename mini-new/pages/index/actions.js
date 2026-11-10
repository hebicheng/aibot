// 主页面扩展方法集：数据筛选、业务数据抽屉、台账抽屉、创建表单、账号设置。
// 通过 Object.assign 合并进 Page，方法内 this 即页面实例。
const api = require('../../utils/api');
const { clearSession, downloadFile } = require('../../utils/request');
const { compactMoney, decorateRecord, fieldMeta, kpiSize, money, number, quantity, textWidth } = require('../../utils/domain');
const {
  NAV_ITEMS,
  EDITABLE_DOC_TYPES,
  DATA_SCOPE_FILTERS,
  DATA_STATUS_FILTERS,
  LEDGER_SCOPE_FILTERS,
  LEDGER_KIND_FILTERS,
  buildBatchName,
  parseAmount,
  parseAmountOrNull,
  today
} = require('./constants');

const EMPTY_DATA_SUMMARY = {
  total: 0,
  pending: 0,
  discrepancy: 0,
  confirmed: 0,
  amountText: '¥0.00',
  scopeLabel: '当前批次'
};

const EMPTY_LEDGER_SUMMARY = {
  batchCount: 0,
  purchaseText: '¥0.00',
  unpaidText: '¥0.00',
  invoiceGapText: '¥0.00',
  outstandingText: '¥0.00',
  urgentCount: 0
};

function withActive(items, activeValue) {
  return items.map((item) => ({ ...item, active: item.value === activeValue }));
}

function parseStructuredDocument(document) {
  try {
    return document && document.structuredData ? JSON.parse(document.structuredData) : {};
  } catch (error) {
    return {};
  }
}

// 送货单采购运费（transportFee/freight，原「运输费用」字段）展示值：无则空串
function deliveryFreightValue(document) {
  const structured = parseStructuredDocument(document);
  const value = structured.transportFee != null
    ? structured.transportFee
    : structured.freight;
  return value != null && number(value) > 0 ? String(number(value)) : '';
}

function normalized(value) {
  return String(value || '').trim().toLowerCase();
}

// 表头样式映射：web LedgerTableSchema style token → mini th class（wxss 既有类名）
const HEADER_STYLE_CLASS = {
  'head-group-context': 'ledger-grp-ctx',
  'head-group-category': 'ledger-grp-cat',
  'head-group-material': 'ledger-grp-mat',
  'head-group-purchase-finance': 'ledger-grp-pay',
  'head-group-sales': 'ledger-grp-sal',
  'head-group-status': 'ledger-grp-sts',
  'head-group-action': 'ledger-grp-act',
  'head-context': 'th-ctx',
  'head-category': 'th-ctx',
  'head-material': 'th-mat',
  'head-group-request': 'th-req',
  'head-group-payment': 'th-pay',
  'head-group-invoice': 'th-inv',
  'head-sales': 'th-sal',
  'head-sales-subgroup': 'th-s-sub',
  'head-sales-leaf': 'th-leaf',
  'head-request': 'th-req',
  'head-payment': 'th-pay',
  'head-invoice': 'th-inv',
  'head-status': 'th-sticky',
  'head-action': 'th-act',
  'sticky-project': 'th-ctx',
  'sticky-batch': 'th-ctx',
  'sticky-action': 'th-act'
};

function styleClassOf(style) {
  const tokens = String(style || '').split(/\s+/).filter(Boolean);
  for (const token of tokens) {
    if (HEADER_STYLE_CLASS[token]) return HEADER_STYLE_CLASS[token];
  }
  return '';
}

/**
 * 把后端 LedgerTableSchema 三层表头转换为 mini 的三行 grid 渲染数组
 * （grid 无 rowspan：rowSpan>1 的列在下层行以同宽占位格补齐，视觉连续）。
 * 第 3 行叶子按第 2 层 rowSpan=1 的子组位置依次填入。
 */
function processLedgerHeader(rows) {
  if (!Array.isArray(rows) || rows.length < 3) return null;
  const wrap = (cells, startIndex) => cells.map((cell, index) => ({
    idx: `${startIndex}-${index}`,
    label: cell.label || '',
    colSpan: Math.max(Number(cell.colSpan) || 1, 1),
    cls: styleClassOf(cell.style)
  }));
  const row1 = wrap(rows[0]);
  const row2Source = rows[1];
  const leafQueue = rows[2];
  let leafCursor = 0;
  const row2 = [];
  const row3 = [];
  row2Source.forEach((cell, index) => {
    const colSpan = Math.max(Number(cell.colSpan) || 1, 1);
    const rowSpan = Math.max(Number(cell.rowSpan) || 1, 1);
    row2.push({
      idx: `1-${index}`,
      label: cell.label || '',
      colSpan,
      cls: styleClassOf(cell.style)
    });
    if (rowSpan >= 2) {
      // 纵向合并列：第 3 行以同宽占位
      for (let i = 0; i < colSpan; i += 1) {
        row3.push({ idx: `2-${index}-${i}`, label: '', colSpan: 1, cls: styleClassOf(cell.style) });
      }
    } else {
      // 子组列：第 3 行由其叶子单元格填充
      for (let i = 0; i < colSpan; i += 1) {
        const leaf = leafQueue[leafCursor] || null;
        leafCursor += 1;
        row3.push({
          idx: `2-${index}-${i}`,
          label: leaf ? (leaf.label || '') : '',
          colSpan: 1,
          cls: leaf ? styleClassOf(leaf.style) : styleClassOf(cell.style)
        });
      }
    }
  });
  return { row1, row2, row3 };
}

function currentBatchRecords(records, currentBatch) {
  return currentBatch ? records.filter((record) => record.document.batchId === currentBatch.id) : [];
}

function projectOverview(projects) {
  const totalBatches = projects.reduce((sum, project) => sum + project.batches.length, 0);
  const activeProjects = projects.filter((project) => project.statusLabel !== '已完成').length;
  const completedProjects = projects.filter((project) => project.statusLabel === '已完成').length;
  return {
    projectCount: projects.length,
    batchCount: totalBatches,
    activeProjects,
    completedProjects
  };
}

function buildDataSummary(records, currentBatch, scope) {
  const scoped = scope === 'batch' ? currentBatchRecords(records, currentBatch) : records;
  const pending = scoped.filter((record) =>
    ['pending', 'recognizing'].includes(record.document.status)
    && record.document.duplicateDecision !== 'skip').length;
  const discrepancy = scoped.filter((record) => record.document.status === 'discrepancy').length;
  const confirmed = scoped.filter((record) =>
    ['matched', 'archived'].includes(record.document.status)
    && record.document.duplicateDecision !== 'skip').length;
  const amount = scoped.reduce((sum, record) =>
    sum + (record.document.duplicateDecision === 'skip' ? 0 : number(record.document.amount)), 0);
  return {
    total: scoped.length,
    pending,
    discrepancy,
    confirmed,
    amountText: compactMoney(amount),
    scopeLabel: scope === 'batch' ? '当前批次' : '整个项目'
  };
}

function buildLedgerSummary(groups) {
  const totals = groups.reduce((summary, group) => ({
    purchase: summary.purchase + number(group.purchase),
    requested: summary.requested + number(group.requested),
    paid: summary.paid + number(group.paid),
    unpaid: summary.unpaid + Math.max(number(group.purchase) - number(group.paid), 0),
    invoice: summary.invoice + number(group.invoice),
    sales: summary.sales + number(group.sales),
    received: summary.received + number(group.received),
    outstanding: summary.outstanding + number(group.outstanding),
    urgentCount: summary.urgentCount + (group.exceptions && group.exceptions.length ? 1 : 0)
  }), {
    purchase: 0,
    requested: 0,
    paid: 0,
    unpaid: 0,
    invoice: 0,
    sales: 0,
    received: 0,
    outstanding: 0,
    urgentCount: 0
  });
  // 毛利 = 销售总金额（含销售运费） - 采购及运输（与 web KPI 同口径）
  const profit = totals.sales > 0 ? totals.sales - totals.purchase : 0;
  const texts = {
    purchaseText: money(totals.purchase),
    paidText: money(totals.paid),
    unpaidText: money(totals.unpaid),
    requestedText: money(totals.requested),
    invoiceText: money(totals.invoice),
    salesText: money(totals.sales),
    receivedText: money(totals.received),
    outstandingText: money(totals.outstanding),
    profitText: money(profit)
  };
  const sized = {};
  Object.keys(texts).forEach((key) => {
    sized[key.replace('Text', 'Size')] = kpiSize(texts[key]);
  });
  return {
    batchCount: groups.length,
    // 9 个 KPI（采购侧 5 + 销售侧 4，对齐 web ReconciliationLedgerPage）
    ...texts,
    ...sized,
    // 旧字段保留兼容
    invoiceGapText: money(Math.max(totals.purchase - totals.invoice, 0)),
    urgentCount: totals.urgentCount
  };
}

function buildNavItems(state) {
  const currentProject = state.currentProject;
  const unresolved = state.stats ? number(state.stats.pendingCount) : 0;
  const ledgerUrgent = (state.displayedLedgerGroups || state.ledgerGroups || [])
    .filter((group) => group.exceptions && group.exceptions.length).length;
  const dataCount = (state.filteredRecords || []).length;
  return NAV_ITEMS.map((item) => {
    let badge = '';
    if (item.key === 'workspace' && unresolved > 0) badge = String(unresolved);
    if (item.key === 'projects' && currentProject && currentProject.batches.length) badge = String(currentProject.batches.length);
    if (item.key === 'data' && dataCount > 0) badge = String(dataCount);
    if (item.key === 'ledger' && ledgerUrgent > 0) badge = String(ledgerUrgent);
    if (item.key === 'profile' && state.user && state.user.companyName) badge = '已设';
    return { ...item, badge };
  });
}

function buildNavGroups(navItems) {
  return {
    workbench: ['workspace', 'projects', 'data', 'ledger', 'market'].map((k) => navItems.find((i) => i.key === k)).filter(Boolean),
    manage: ['profile'].map((k) => navItems.find((i) => i.key === k)).filter(Boolean)
  };
}

module.exports = {
  // 表头动态接入后端 LedgerTableSchema（单一来源，防止本地硬编码漂移）
  ensureLedgerHeader() {
    if (this.data.ledgerHeaderRows) return;
    api.ledgerColumns().then((rows) => {
      const header = processLedgerHeader(rows);
      if (header) this.setData({ ledgerHeaderRows: header });
    }).catch(() => {});
  },

  refreshNavigation(partial) {
    const state = Object.assign({}, this.data, partial || {});
    const navItems = buildNavItems(state);
    this.setData({ navItems, navGroups: buildNavGroups(navItems) });
  },

  syncPageSummaries() {
    const projectMetrics = projectOverview(this.data.projects || []);
    const profileOverview = {
      companyReady: Boolean(this.data.user && this.data.user.companyName),
      currentProjectName: this.data.currentProject ? this.data.currentProject.name : '',
      currentBatchName: this.data.currentBatch ? this.data.currentBatch.name : '',
      pendingCount: number(this.data.stats && this.data.stats.pendingCount),
      ledgerIssueCount: (this.data.ledgerGroups || []).filter((group) => group.exceptions && group.exceptions.length).length,
      recordCount: (this.data.records || []).length
    };
    this.setData({ projectMetrics, profileOverview });
    this.refreshNavigation({ projectMetrics, profileOverview });
  },

  /* ---------------- 业务数据筛选 ---------------- */
  setDataType(event) {
    const dataType = event.currentTarget.dataset.value || '';
    const dataTypes = this.data.dataTypes.map((item) => ({ ...item, active: item.value === dataType }));
    this.setData({ dataType, dataTypes });
    this.applyDataFilter();
  },

  setDataScope(event) {
    const dataScope = event.currentTarget.dataset.value || 'batch';
    this.setData({
      dataScope,
      dataScopes: withActive(DATA_SCOPE_FILTERS, dataScope)
    });
    this.applyDataFilter();
  },

  setDataStatus(event) {
    const dataStatus = event.currentTarget.dataset.value || 'all';
    this.setData({
      dataStatus,
      dataStatuses: withActive(DATA_STATUS_FILTERS, dataStatus)
    });
    this.applyDataFilter();
  },

  updateDataQuery(event) {
    this.setData({ dataQuery: event.detail.value });
    this.applyDataFilter();
  },

  clearDataQuery() {
    this.setData({ dataQuery: '' });
    this.applyDataFilter();
  },

  applyDataFilter() {
    const { records, dataType, dataQuery, dataTypes, dataScope, dataStatus, currentBatch } = this.data;
    const query = dataQuery.trim().toLowerCase();
    const activeFilter = dataTypes.find((item) => item.value === dataType);
    const allowedTypes = activeFilter ? activeFilter.types : [];
    const scopedRecords = dataScope === 'batch'
      ? currentBatchRecords(records, currentBatch)
      : records.slice();
    const filteredRecords = scopedRecords.filter((record) => {
      const matchesType = !dataType || allowedTypes.includes(record.document.docType);
      const matchesStatus = dataStatus === 'all'
        || (dataStatus === 'pending' && ['pending', 'recognizing'].includes(record.document.status))
        || (dataStatus === 'discrepancy' && record.document.status === 'discrepancy')
        || (dataStatus === 'confirmed' && ['matched', 'archived'].includes(record.document.status));
      const haystack = [
        record.document.docCode,
        record.document.fileName,
        record.document.supplierName,
        record.typeLabel,
        record.projectName,
        record.batchName,
        ...(record.items || []).map((item) => `${item.materialName} ${item.spec}`)
      ].join(' ').toLowerCase();
      return matchesType && matchesStatus && (!query || haystack.includes(query));
    }).sort((left, right) => {
      const statusWeight = (value) => ({
        discrepancy: 0,
        pending: 1,
        recognizing: 2,
        matched: 3,
        archived: 4
      })[value] || 9;
      const batchBias = currentBatch && left.document.batchId === currentBatch.id ? -1 : 0;
      const otherBias = currentBatch && right.document.batchId === currentBatch.id ? -1 : 0;
      if (batchBias !== otherBias) return batchBias - otherBias;
      if (statusWeight(left.document.status) !== statusWeight(right.document.status)) {
        return statusWeight(left.document.status) - statusWeight(right.document.status);
      }
      return normalized(right.document.docDate || right.document.updatedAt || right.document.id)
        .localeCompare(normalized(left.document.docDate || left.document.updatedAt || left.document.id));
    });
    const dataSummary = buildDataSummary(records, currentBatch, dataScope);
    this.setData({ filteredRecords, dataSummary });
    this.refreshNavigation({ filteredRecords, dataSummary });
  },

  // 台账范围已对齐 web：只显示当前批次（不再提供 当前项目/全部批次 切换）
  setLedgerScope() {
    this.applyLedgerFilter();
  },

  // 台账 - 按业务分类筛选（全部 / 材料 / 运输，对齐设计稿筛选条）
  setLedgerKind(event) {
    const ledgerKind = event.currentTarget.dataset.value || '';
    const ledgerKinds = LEDGER_KIND_FILTERS.map((item) => (
      { ...item, active: item.value === ledgerKind }
    ));
    this.setData({ ledgerKind, ledgerKinds });
    this.applyLedgerFilter();
  },

  // 台账 - 筛选条右侧按钮：范围已固定为当前批次，入口保留但提示口径
  toggleLedgerAdvancedFilter() {
    wx.showModal({
      title: '台账范围',
      content: '对账台账只显示当前所在批次的数据，切换批次请在项目页选择。',
      showCancel: false,
      confirmText: '知道了',
      confirmColor: '#2f66f6'
    });
  },

  // 台账 - 切换单个批次的展开/收起（同步“展开全部/收起全部”状态）
  toggleLedgerGroup(event) {
    const id = event.currentTarget.dataset.id;
    const expandedGroupIds = { ...(this.data.expandedGroupIds || {}) };
    expandedGroupIds[id] = !expandedGroupIds[id];
    const groups = this.data.displayedLedgerGroups || [];
    const allExpanded = groups.length > 0 && groups.every((group) => expandedGroupIds[group.id]);
    this.setData({ expandedGroupIds, allExpanded });
  },

  // 台账 - 展开/收起全部批次
  toggleAllLedgerGroups() {
    const allExpanded = !this.data.allExpanded;
    const expandedGroupIds = {};
    if (allExpanded) {
      (this.data.displayedLedgerGroups || []).forEach((group) => {
        expandedGroupIds[group.id] = true;
      });
    }
    this.setData({ allExpanded, expandedGroupIds });
  },

  // 台账 - 行点击（占位：可后续打开该行的请款/付款明细）
  openLedgerRow(event) {
    // 暂不打开抽屉，由 "编辑" 按钮触发具体操作
  },

  // 台账 - 行内编辑（复用现有 openLedger 打开批次对账抽屉）
  editLedgerRow(event) {
    const batchId = event.currentTarget.dataset.batchId;
    if (batchId) {
      this.openLedger({ currentTarget: { dataset: { id: batchId } } });
    }
  },

  // 台账 - 月结利息计算明细
  showInterestDetail(event) {
    const id = event.currentTarget.dataset.id;
    const group = (this.data.displayedLedgerGroups || []).find((item) => item.id === id);
    if (!group || !group.interestDetails || !group.interestDetails.length) return;
    const lines = group.interestDetails.map((detail) => {
      const amount = detail.amount == null ? '' : `，本金 ${money(detail.amount)}`;
      const rate = detail.rate == null ? '' : `，月息 ${(detail.rate * 100).toFixed(1)}%`;
      const days = detail.days == null ? '' : `，计息 ${detail.days} 天`;
      const interest = detail.interest == null ? '' : ` → ${money(detail.interest)}`;
      return `${detail.note || ''}${amount}${rate}${days}${interest}`;
    });
    lines.push(`合计利息 ${group.interestText}`);
    wx.showModal({
      title: '月结加价利息明细',
      content: lines.join('\n'),
      showCancel: false,
      confirmText: '知道了',
      confirmColor: '#2f66f6'
    });
  },

  // 台账导出：与 web 共用后端 xlsx 接口（保留表头分组、合并单元格与样式）
  exportLedger() {
    const currentProject = this.data.currentProject || {};
    const query = currentProject.id ? '?projectId=' + encodeURIComponent(currentProject.id) : '';
    const filePath = `${wx.env.USER_DATA_PATH}/ledger-${Date.now()}.xlsx`;
    wx.showLoading({ title: '正在生成台账', mask: true });
    downloadFile({ url: '/api/reports/ledger/export' + query, filePath })
      .then((savedPath) => {
        wx.hideLoading();
        wx.openDocument({
          filePath: savedPath,
          fileType: 'xlsx',
          showMenu: true,
          fail: () => this.toast('文件已保存，但无法直接预览，请在聊天或文件中查看')
        });
      })
      .catch((error) => {
        wx.hideLoading();
        this.toast((error && error.message) || '台账导出失败');
      });
  },

  // 台账 33 列自适应宽度（对齐 web LedgerTableSchema：金额+采购单数量+送货数量+送达状态+总额），
  // 按表头 + 数据实际文本估算，产出 grid-template-columns
  buildLedgerColTemplate(groups) {
    const columns = [
      { head: '项目', min: 140, font: 24, text: (g, r) => [[g.projectName, 24]] },
      { head: '批次', min: 140, font: 20, text: (g, r) => [[g.batchName, 25], [g.dateText, 20]] },
      { head: '分类', min: 90, font: 20, text: (g, r) => [[r.kindLabel, 19]] },
      { head: '材料 / 费用', min: 120, font: 20, text: (g, r) => [[r.material, 25]] },
      { head: '规格 / 单号', min: 120, font: 20, text: (g, r) => [[r.spec, 24]] },
      { head: '供应商 / 承运单位', min: 150, font: 20, text: (g, r) => [[r.supplier, 24]] },
      { head: '采购 / 运输金额', min: 140, font: 20, text: (g, r) => [[r.purchaseAmountText, 25]] },
      { head: '采购单数量', min: 110, font: 20, text: (g, r) => [[r.purchaseQuantityText, 24]] },
      { head: '送货单数量', min: 110, font: 20, text: (g, r) => [[r.deliveredText, 24]] },
      { head: '送达状态', min: 100, font: 20, text: (g, r) => [[r.deliveryStatus, 22]] },
      { head: '总额', min: 140, font: 20, text: (g, r) => [[g.totalAmountText, 25]] },
      { head: '请款金额', min: 130, font: 20, text: (g, r) => [[r.requestedText, 25]] },
      { head: '付款日期', min: 120, font: 20, text: (g, r) => [[r.paymentDate, 24]] },
      { head: '收款单位', min: 120, font: 20, text: (g, r) => [[r.payee, 24]] },
      { head: '付款金额', min: 130, font: 20, text: (g, r) => {
          const lines = [[r.paidText, 25]];
          if (r.unpaid > 0) lines.push([`未付 ${r.unpaidText}`, 20]);
          return lines;
        } },
      { head: '开票日期', min: 120, font: 20, text: (g, r) => [[r.invoiceDate, 24]] },
      { head: '开票金额', min: 130, font: 20, text: (g, r) => [[r.invoiceText, 25]] },
      { head: '税率', min: 60, font: 20, text: (g, r) => [[r.taxRate, 24]] },
      { head: '销售订单号', min: 120, font: 20, text: (g, r) => [[g.salesOrder, 24]] },
      { head: '销售总金额', min: 130, font: 20, text: (g, r) => [[g.salesText, 25]] },
      { head: '开票日期', min: 120, font: 20, text: (g, r) => [[g.salesInvoiceDate, 24]] },
      { head: '开票金额', min: 130, font: 20, text: (g, r) => [[g.salesInvoiceAmountText, 25]] },
      { head: '税率', min: 60, font: 20, text: (g, r) => [[g.salesTaxRate, 24]] },
      { head: '已收款金额', min: 130, font: 20, text: (g, r) => [[g.receivedText, 25]] },
      { head: '未回款金额', min: 150, font: 20, text: (g, r) => g.isMonthlySettlement
          ? [[g.outstandingText, 25], [g.interestSummary || '', 18]]
          : [[g.outstandingText, 25]] },
      { head: '到款日期', min: 120, font: 20, text: (g, r) => [[g.receiptDate, 24]] },
      { head: '要求回款日期', min: 130, font: 20, text: (g, r) => [[g.requiredReceiptDate, 24]] },
      { head: '月结利息', min: 130, font: 20, text: (g, r) => g.isMonthlySettlement
          ? [[g.interestText || '¥0.00', 25], [g.interest > 0 ? `超${g.overdueFlag ? '3个月' : ''}${g.overdueFlag && g.amountCapFlag ? '·' : ''}${g.amountCapFlag ? '300万' : ''}` : '', 18]]
          : [['—', 22]] },
      { head: '毛利', min: 120, font: 20, text: (g, r) => [[g.profitText, 25]] },
      { head: '毛利率', min: 90, font: 20, text: (g, r) => [[g.marginText, 24]] },
      { head: '项目经理', min: 100, font: 20, text: (g, r) => [[g.projectManager, 24]] },
      { head: '状态', min: 100, font: 20, text: (g, r) => [[g.statusLabel, 22]] },
      { head: '操作', min: 90, font: 20, text: (g, r) => [['编辑', 21]] }
    ];
    const widths = columns.map((col) => {
      let max = textWidth(col.head, col.font);
      for (const group of groups) {
        for (const row of group.rows) {
          for (const [text, font] of col.text(group, row)) {
            max = Math.max(max, textWidth(text, font));
          }
        }
      }
      return Math.ceil(Math.max(max, col.min) + 24); // 左右内边距 + 安全余量
    });
    return {
      ledgerColWidths: widths,
      ledgerColTemplate: widths.map((w) => `${w}rpx`).join(' ')
    };
  },

  applyLedgerFilter() {
    const { ledgerGroups, ledgerKind, currentProject, currentBatch } = this.data;
    // 范围对齐 web：只显示当前所在批次的台账
    let displayedLedgerGroups = currentBatch
      ? ledgerGroups.filter((group) => group.id === currentBatch.id)
      : ledgerGroups.filter((group) => currentProject && group.projectId === currentProject.id);
    // 按业务分类筛选：每组只保留匹配的 rows（材料 / 运输）；
    // 并为每行标记合并边界（isLastRow / isLastMaterial），供表格 1:1 模拟 web 的 rowspan 合并。
    displayedLedgerGroups = displayedLedgerGroups.map((group) => {
      let rows = (group.rows || []).slice();
      if (ledgerKind) {
        rows = rows.filter((row) => row.kind === ledgerKind);
      }
      if (!rows.length) return null;
      const total = rows.length;
      const enriched = rows.map((row, index) => ({
        ...row,
        isLastRow: index === total - 1,
        isLastMaterial: row.kind === 'material'
          && (index === total - 1 || rows[index + 1].kind !== 'material')
      }));
      return { ...group, rows: enriched, rowsTotal: total, rowCount: total };
    }).filter(Boolean);
    displayedLedgerGroups.sort((left, right) => {
      const leftCurrent = currentBatch && left.id === currentBatch.id ? -2 : 0;
      const rightCurrent = currentBatch && right.id === currentBatch.id ? -2 : 0;
      if (leftCurrent !== rightCurrent) return leftCurrent - rightCurrent;
      const leftUrgent = left.exceptions && left.exceptions.length ? -1 : 0;
      const rightUrgent = right.exceptions && right.exceptions.length ? -1 : 0;
      if (leftUrgent !== rightUrgent) return leftUrgent - rightUrgent;
      return normalized(right.dateText || right.batchName).localeCompare(normalized(left.dateText || left.batchName));
    });
    const ledgerSummary = buildLedgerSummary(displayedLedgerGroups);
    const currentLedgerFocus = currentBatch
      ? displayedLedgerGroups.find((group) => group.id === currentBatch.id) || null
      : null;
    const colWidths = this.buildLedgerColTemplate(displayedLedgerGroups);
    // 台账条目默认展开
    const expandedGroupIds = {};
    (displayedLedgerGroups || []).forEach((group) => {
      expandedGroupIds[group.id] = true;
    });
    this.setData({
      displayedLedgerGroups,
      ledgerSummary,
      currentLedgerFocus,
      expandedGroupIds,
      allExpanded: true,
      ...colWidths
    });
    this.refreshNavigation({ displayedLedgerGroups, ledgerSummary, currentLedgerFocus });
  },

  /* ---------------- 业务数据详情抽屉 ---------------- */
  async openDocument(event) {
    const id = event.currentTarget.dataset.id;
    let activeDocument = this.data.records.find((item) => item.document.id === id);
    if (!activeDocument) {
      wx.showLoading({ title: '读取业务资料' });
      try {
        const record = await api.documentDetail(id);
        activeDocument = decorateRecord(record, this.data.projects, this.data.batches);
      } catch (error) {
        this.toast(error.message || '业务资料暂时无法打开');
        return;
      } finally {
        wx.hideLoading();
      }
    }
    this.setData({
      overlay: 'document',
      activeDocument,
      documentSection: 'data',
      documentEditing: false,
      documentDraft: this.createDocumentDraft(activeDocument)
    });
  },

  createDocumentDraft(record) {
    const document = record.document;
    const fields = fieldMeta(document.docType);
    const typeIndex = Math.max(EDITABLE_DOC_TYPES.findIndex((item) => item.value === document.docType), 0);
    return {
      ...fieldMeta(document.docType),
      docType: document.docType,
      typeIndex,
      typeLabel: EDITABLE_DOC_TYPES[typeIndex].label,
      supplierName: document.supplierName || '',
      docCode: document.docCode || '',
      docDate: document.docDate || '',
      amount: String(number(document.amount) || ''),
      taxRate: record.taxRate || '',
      netAmount: String(record.netAmount || ''),
      taxAmount: String(record.taxAmount || ''),
      expenseCategory: record.expenseCategory || 'material',
      // 送货单销售侧字段：承运单位/采购运费存 structuredData，销售运费是实体列
      isDelivery: document.docType === 'delivery_note',
      carrierName: String(parseStructuredDocument(document).carrierName || ''),
      transportFee: deliveryFreightValue(document),
      salesFreight: document.salesFreight != null ? String(document.salesFreight) : '',
      showFinance: ['payment_request', 'payment', 'purchase_invoice', 'sales_invoice', 'invoice'].includes(document.docType),
      items: (record.items || []).map((item, index) => ({
        id: item.id || `draft-${index}`,
        materialName: item.materialName || '',
        spec: item.spec || '',
        origin: item.origin || '',
        packageQuantity: String(item.packageQuantity || ''),
        packageUnit: item.packageUnit || '件',
        unitWeight: String(item.unitWeight || ''),
        totalWeight: String(item.totalWeight || ''),
        weightUnit: item.weightUnit || '吨',
        pricingQuantity: String(item.pricingQuantity || item.quantity || ''),
        pricingUnit: item.pricingUnit || item.unit || '吨',
        unitPrice: String(item.unitPrice || ''),
        amount: String(item.amount || ''),
        quantityText: item.quantityText,
        packageText: item.packageText,
        weightText: item.weightText,
        amountText: item.amountText,
        // 只读展示（小程序本轮不做改价，故不放进可提交的 salesUnitPrice 字段）
        salesUnitPriceText: item.salesUnitPrice != null
          ? `${money(item.salesUnitPrice)}（${item.salesUnitPriceManual ? '手动' : '自动'}）`
          : '-'
      })),
    itemTotal: (record.items || []).reduce((sum, item) => sum + parseAmount(item.amount), 0),
    itemTotalText: money((record.items || []).reduce((sum, item) => sum + parseAmount(item.amount), 0)),
    amountText: money(parseAmount(document.amount)),
    amountMismatch: document.docType === 'supply_order'
      && (record.items || []).length > 0
      && Math.abs(parseAmount(document.amount) - (record.items || []).reduce((sum, item) => sum + parseAmount(item.amount), 0)) > 0.01,
    amountMismatchDecision: '',
    missingFields: (() => {
      const list = [];
      if (!String(document.supplierName || '').trim()) list.push(fields.partyLabel);
      if (!String(document.docDate || '').trim()) list.push(fields.dateLabel);
      if (!(parseAmount(document.amount) > 0)) list.push(fields.amountLabel);
      return list;
    })(),
    missingFieldText: (() => {
      const list = [];
      if (!String(document.supplierName || '').trim()) list.push(fields.partyLabel);
      if (!String(document.docDate || '').trim()) list.push(fields.dateLabel);
      if (!(parseAmount(document.amount) > 0)) list.push(fields.amountLabel);
      return list.join('、');
    })()
  };
},

// 编辑过程中实时重算派生字段：明细合计、金额差异、待补充项。
syncDocumentDerived() {
  const draft = this.data.documentDraft;
  if (!draft) return;
  const itemTotal = draft.items.reduce((sum, item) => sum + parseAmount(item.amount), 0);
  const amount = parseAmount(draft.amount);
  const amountMismatch = draft.docType === 'supply_order'
    && draft.items.length > 0
    && Math.abs(amount - itemTotal) > 0.01;
  const missingList = [];
  if (!String(draft.supplierName || '').trim()) missingList.push(draft.partyLabel);
  if (!String(draft.docDate || '').trim()) missingList.push(draft.dateLabel);
  if (!(amount > 0)) missingList.push(draft.amountLabel);
  this.setData({
    'documentDraft.itemTotal': itemTotal,
    'documentDraft.itemTotalText': money(itemTotal),
    'documentDraft.amountText': money(amount),
    'documentDraft.amountMismatch': amountMismatch,
    'documentDraft.missingFields': missingList,
    'documentDraft.missingFieldText': missingList.join('、')
  });
},

  setDocumentSection(event) {
    this.setData({ documentSection: event.currentTarget.dataset.section });
  },

  beginDocumentEdit() {
    this.setData({ documentEditing: true });
  },

  cancelDocumentEdit() {
    this.setData({
      documentEditing: false,
      documentDraft: this.createDocumentDraft(this.data.activeDocument)
    });
  },

  updateDocumentField(event) {
    const field = event.currentTarget.dataset.field;
    this.setData({ [`documentDraft.${field}`]: event.detail.value });
    this.syncDocumentDerived();
  },

  changeDocumentType(event) {
    const typeIndex = Number(event.detail.value);
    const docType = EDITABLE_DOC_TYPES[typeIndex].value;
    const fields = fieldMeta(docType);
    this.setData({
      'documentDraft.typeIndex': typeIndex,
      'documentDraft.docType': docType,
      'documentDraft.typeLabel': EDITABLE_DOC_TYPES[typeIndex].label,
      'documentDraft.partyLabel': fields.partyLabel,
      'documentDraft.codeLabel': fields.codeLabel,
      'documentDraft.dateLabel': fields.dateLabel,
      'documentDraft.amountLabel': fields.amountLabel,
      'documentDraft.supportsItems': fields.supportsItems,
      'documentDraft.isInvoice': fields.isInvoice,
      'documentDraft.isPurchaseFinance': fields.isPurchaseFinance,
      'documentDraft.showFinance': ['payment_request', 'payment', 'purchase_invoice', 'sales_invoice', 'invoice'].includes(docType)
    });
    this.syncDocumentDerived();
  },

  // 业务类型分段选择器点击（对齐 Web doc-type-editor 的 chip 点击）。
  changeDocumentTypeByValue(event) {
    if (this.data.activeDocument && this.data.activeDocument.statusLabel === '已入库') return;
    const value = event.currentTarget.dataset.value;
    const typeIndex = EDITABLE_DOC_TYPES.findIndex((item) => item.value === value);
    if (typeIndex < 0) return;
    this.changeDocumentType({ detail: { value: String(typeIndex) } });
  },

  updateDocumentDate(event) {
    this.setData({ 'documentDraft.docDate': event.detail.value });
    this.syncDocumentDerived();
  },

  changeExpenseCategory(event) {
    this.setData({ 'documentDraft.expenseCategory': event.detail.value ? 'transport' : 'material' });
  },

  updateDocumentItem(event) {
    const index = Number(event.currentTarget.dataset.index);
    const field = event.currentTarget.dataset.field;
    const items = this.data.documentDraft.items.slice();
    items[index] = { ...items[index], [field]: event.detail.value };
    if (field === 'pricingQuantity' || field === 'unitPrice') {
      const quantityValue = parseAmount(items[index].pricingQuantity);
      const unitPrice = parseAmount(items[index].unitPrice);
      if (quantityValue > 0 && unitPrice > 0) {
        items[index].amount = String(Number((quantityValue * unitPrice).toFixed(2)));
      }
    }
    this.setData({ 'documentDraft.items': items });
    this.syncDocumentDerived();
  },

  addDocumentItem() {
    const items = this.data.documentDraft.items.slice();
    items.push({
      id: `draft-${Date.now()}`,
      materialName: '', spec: '', origin: '',
      packageQuantity: '', packageUnit: '件',
      unitWeight: '', totalWeight: '', weightUnit: '吨',
      pricingQuantity: '', pricingUnit: '吨',
      unitPrice: '', amount: ''
    });
    this.setData({ 'documentDraft.items': items });
    this.syncDocumentDerived();
  },

  removeDocumentItem(event) {
    const index = Number(event.currentTarget.dataset.index);
    const items = this.data.documentDraft.items.filter((_, itemIndex) => itemIndex !== index);
    this.setData({ 'documentDraft.items': items });
    this.syncDocumentDerived();
  },

  // 编辑中的金额差异处理（对齐 Web amountMismatch 两个快捷决策）。
  setAmountMismatchDecision(event) {
    const decision = event.currentTarget.dataset.decision;
    const draft = this.data.documentDraft;
    const patch = { 'documentDraft.amountMismatchDecision': decision };
    if (decision === 'sync_items') {
      const itemTotal = draft.items.reduce((sum, item) => sum + parseAmount(item.amount), 0);
      patch['documentDraft.amount'] = String(itemTotal);
    }
    this.setData(patch, () => this.syncDocumentDerived());
  },

  saveDocument() {
    const { activeDocument, documentDraft } = this.data;
    if (!activeDocument || !documentDraft) return;
    const itemTotal = documentDraft.items.reduce((sum, item) => sum + parseAmount(item.amount), 0);
    const amount = parseAmount(documentDraft.amount);
    const amountMismatch = documentDraft.docType === 'supply_order'
      && documentDraft.items.length > 0
      && Math.abs(amount - itemTotal) > 0.01;

    const persist = (amountMismatchDecision) => {
      // 空值语义对齐 web：空串提交 null（= 不修改），禁止用 0 覆盖原值
      const edits = {
        docType: documentDraft.docType,
        supplierName: documentDraft.supplierName,
        docCode: documentDraft.docCode,
        docDate: documentDraft.docDate,
        amount: amountMismatchDecision === 'sync_items'
          ? itemTotal
          : parseAmountOrNull(documentDraft.amount),
        taxRate: documentDraft.taxRate,
        netAmount: parseAmountOrNull(documentDraft.netAmount),
        taxAmount: parseAmountOrNull(documentDraft.taxAmount),
        expenseCategory: documentDraft.expenseCategory,
        // 送货单销售侧字段（后端 mergeDeliveryNoteFields 消费；空值=null 清除）
        ...(documentDraft.docType === 'delivery_note' ? {
          carrierName: documentDraft.carrierName || '',
          transportFee: documentDraft.transportFee === '' ? null : parseAmountOrNull(documentDraft.transportFee),
          salesFreight: documentDraft.salesFreight === '' ? null : parseAmountOrNull(documentDraft.salesFreight)
        } : {}),
        ...(amountMismatchDecision ? { amountMismatchDecision } : {}),
        items: documentDraft.items.map((item) => ({
          materialName: item.materialName,
          spec: item.spec,
          origin: item.origin || null,
          packageQuantity: parseAmountOrNull(item.packageQuantity),
          packageUnit: item.packageUnit,
          unitWeight: parseAmountOrNull(item.unitWeight),
          totalWeight: parseAmountOrNull(item.totalWeight),
          weightUnit: item.weightUnit,
          pricingQuantity: parseAmountOrNull(item.pricingQuantity),
          pricingUnit: item.pricingUnit,
          quantity: parseAmountOrNull(item.pricingQuantity),
          unit: item.pricingUnit,
          unitPrice: parseAmountOrNull(item.unitPrice),
          amount: parseAmountOrNull(item.amount)
        }))
      };
      this.runDocumentAction(
        () => api.editDocument(activeDocument.document.id, edits),
        '业务数据已更新，相关汇总已同步'
      ).then((success) => {
        if (success) this.closeOverlay();
      });
    };

    if (!amountMismatch) {
      persist();
      return;
    }
    // 编辑中已在卡片内选择过差异处理方式，则直接采用，不再弹窗。
    if (documentDraft.amountMismatchDecision) {
      persist(documentDraft.amountMismatchDecision);
      return;
    }
    wx.showActionSheet({
      alertText: `单据总额 ${money(amount)} 与明细合计 ${money(itemTotal)} 不一致`,
      itemList: ['同步为明细合计', '保留单据总额并登记差异'],
      success: ({ tapIndex }) => persist(tapIndex === 0 ? 'sync_items' : 'keep_source')
    });
  },

  confirmActiveDocument() {
    const activeDocument = this.data.activeDocument;
    if (!activeDocument) return;
    this.runDocumentAction(
      () => api.confirmDocument(activeDocument.document.id),
      '单据已确认并写入当前批次台账'
    ).then((success) => {
      if (success) this.closeOverlay();
    });
  },

  previewSource() {
    const activeDocument = this.data.activeDocument;
    if (!activeDocument) return;
    const document = activeDocument.document;
    if (['manual', 'text', 'sms'].includes(document.source)) {
      this.toast('该资料来自文本录入，无独立附件');
      return;
    }
    wx.showLoading({ title: '准备原始资料' });
    api.documentAccessUrl(document.id)
      .then((result) => {
        if (document.source === 'image') {
          wx.previewImage({ urls: [result.url], current: result.url });
          return null;
        }
        return new Promise((resolve, reject) => {
          wx.downloadFile({
            url: result.url,
            success: (response) => {
              if (response.statusCode !== 200) {
                reject(new Error('原始资料下载失败'));
                return;
              }
              wx.openDocument({
                filePath: response.tempFilePath,
                fileType: /\.xlsx$/i.test(result.fileName) ? 'xlsx'
                  : /\.xls$/i.test(result.fileName) ? 'xls' : 'pdf',
                showMenu: true,
                success: resolve,
                fail: reject
              });
            },
            fail: reject
          });
        });
      })
      .catch((error) => this.toast(error.message || '原始资料暂时无法访问'))
      .finally(() => wx.hideLoading());
  },

  /* ---------------- 对账单导出（台账页入口，日期范围） ---------------- */
  openReconExport() {
    const now = new Date();
    const monthStart = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`;
    const iso = (date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
    this.setData({
      overlay: 'reconExport',
      drawer: false,
      reconExportForm: { start: monthStart, end: iso(now) }
    });
  },

  updateReconExportDate(event) {
    this.setData({ [`reconExportForm.${event.currentTarget.dataset.field}`]: event.detail.value });
  },

  async downloadReconciliationExport() {
    const { currentProject } = this.data;
    const form = this.data.reconExportForm;
    if (!currentProject || !currentProject.id) {
      this.toast('请先选择项目');
      return;
    }
    if (form.start > form.end) {
      this.toast('开始日期不能晚于结束日期');
      return;
    }
    const filePath = `${wx.env.USER_DATA_PATH}/reconciliation-${Date.now()}.xlsx`;
    wx.showLoading({ title: '正在生成对账单', mask: true });
    try {
      const savedPath = await downloadFile({
        url: api.reconciliationExportUrl(currentProject.id, form.start, form.end),
        filePath
      });
      wx.hideLoading();
      wx.openDocument({
        filePath: savedPath,
        fileType: 'xlsx',
        showMenu: true,
        fail: () => this.toast('对账单已保存，但无法直接预览')
      });
      this.closeOverlay();
    } catch (error) {
      wx.hideLoading();
      this.toast((error && error.message) || '对账单导出失败');
    }
  },

  /* ---------------- 请款明细（对齐 web requestedDetail 弹框：材料/运输两维度按笔次） ---------------- */
  openRequestedDetail(event) {
    const { batchId, kind, batchName } = event.currentTarget.dataset;
    const group = (this.data.displayedLedgerGroups || this.data.ledgerGroups || [])
      .find((item) => item.id === batchId);
    if (!group) return;
    const entries = kind === 'transport' ? group.transportRequested : group.materialRequested;
    const total = entries.reduce((sum, entry) => sum + number(entry.amount), 0);
    this.setData({
      overlay: 'requestedDetail',
      activeRequested: {
        kind,
        title: kind === 'transport' ? '运输费用请款明细' : '材料采购请款明细',
        subtitle: `${batchName || group.batchName} · 累计 ${money(total)}`,
        entries
      }
    });
  },

  /* ---------------- 台账抽屉 ---------------- */
  // 销售金额明细（网价锁定值，对齐 web salesDetail 弹框）：含销售运费条目与人工修正
  async openSalesDetail(event) {
    const batchId = event.currentTarget.dataset.batchId;
    const batchName = event.currentTarget.dataset.batchName || '';
    if (!batchId) return;
    this.setData({
      overlay: 'salesDetail',
      activeSales: { batchId, batchName, loading: true, rows: [], totalText: '-' }
    });
    try {
      const rows = await api.salesDetail(batchId);
      const decorated = rows.map((row, index) => {
        const isFreight = row.rowType === 'sales_freight';
        const pricingUnit = row.pricingUnit || '';
        const quoteSource = [row.quoteSpecName, row.quoteSource].filter(Boolean).join(' · ');
        return {
          ...row,
          rowKey: `${row.mappingId || 'freight'}-${row.documentId}-${index}`,
          isFreight,
          docDateText: row.docDate || '-',
          materialText: isFreight ? '销售运费' : (row.materialName || '未标注材料'),
          specText: isFreight ? '' : (row.spec || ''),
          quoteText: quoteSource || '',
          quotePriceText: row.quotePrice != null ? money(row.quotePrice) : '-',
          salesUnitPriceText: row.salesUnitPrice != null ? money(row.salesUnitPrice) : '-',
          quantityText: row.pricingQuantity != null ? `${quantity(row.pricingQuantity)}${pricingUnit}` : '-',
          amountText: row.amount != null ? money(row.amount) : '-'
        };
      });
      const total = decorated.reduce((sum, row) => sum + (row.amount != null ? number(row.amount) : 0), 0);
      this.setData({
        'activeSales.rows': decorated,
        'activeSales.totalText': money(total),
        'activeSales.loading': false
      });
    } catch (error) {
      this.setData({ 'activeSales.loading': false });
      this.toast(error.message || '销售明细加载失败');
    }
  },

  openLedger(event) {
    const activeLedger = this.data.ledgerGroups.find((item) => item.id === event.currentTarget.dataset.id);
    if (!activeLedger) return;
    this.setData({
      overlay: 'ledger',
      activeLedger,
      ledgerEditing: false,
      ledgerDraft: {
        salesOrder: activeLedger.salesOrder || '',
        salesAmount: String(activeLedger.sales || ''),
        salesAmountText: money(activeLedger.sales || 0),
        receiptDate: activeLedger.receiptDate || '',
        requiredReceiptDate: activeLedger.requiredReceiptDate || '',
        projectManager: activeLedger.projectManager || '',
        isMonthlySettlement: Boolean(activeLedger.isMonthlySettlement)
      }
    });
    this.syncLedgerDerived();
  },

  // 台账编辑：进入/退出编辑态（对齐 Web 行内编辑的触发/取消）。
  beginLedgerEdit() {
    this.setData({ ledgerEditing: true });
  },

  cancelLedgerEdit() {
    const activeLedger = this.data.activeLedger;
    if (!activeLedger) return;
    this.setData({
      ledgerEditing: false,
      ledgerDraft: {
        salesOrder: activeLedger.salesOrder || '',
        salesAmount: String(activeLedger.sales || ''),
        salesAmountText: money(activeLedger.sales || 0),
        receiptDate: activeLedger.receiptDate || '',
        requiredReceiptDate: activeLedger.requiredReceiptDate || '',
        projectManager: activeLedger.projectManager || '',
        isMonthlySettlement: Boolean(activeLedger.isMonthlySettlement)
      }
    });
    this.syncLedgerDerived();
  },

  // 月结开关（对齐 Web 编辑态 checkbox）
  toggleMonthlySettlement(event) {
    this.setData({ 'ledgerDraft.isMonthlySettlement': event.detail.value });
  },

  // 编辑销售金额时实时预览毛利/率（对齐 Web 改销售金额即预览）。
  syncLedgerDerived() {
    const draft = this.data.ledgerDraft;
    const ledger = this.data.activeLedger;
    if (!draft || !ledger) return;
    const sales = parseAmount(draft.salesAmount);
    const cost = parseAmount(ledger.purchaseAmount);
    const profit = sales > 0 ? sales - cost : 0;
    const margin = sales > 0 ? (profit / sales) * 100 : 0;
    this.setData({
      'ledgerDraft.previewProfit': profit,
      'ledgerDraft.previewProfitText': money(profit),
      'ledgerDraft.previewMargin': margin,
      'ledgerDraft.previewMarginText': `${margin.toFixed(1)}%`
    });
  },

  updateLedgerField(event) {
    this.setData({ [`ledgerDraft.${event.currentTarget.dataset.field}`]: event.detail.value });
    this.syncLedgerDerived();
  },

  updateLedgerDate(event) {
    this.setData({ [`ledgerDraft.${event.currentTarget.dataset.field}`]: event.detail.value });
    this.syncLedgerDerived();
  },

  async saveLedger() {
    const { activeLedger, ledgerDraft, busy } = this.data;
    if (!activeLedger || busy) return;
    this.setData({ busy: true });
    try {
      await api.updateLedgerBatch(activeLedger.id, {
        salesOrder: ledgerDraft.salesOrder,
        salesAmount: parseAmount(ledgerDraft.salesAmount),
        receiptDate: ledgerDraft.receiptDate || null,
        requiredReceiptDate: ledgerDraft.requiredReceiptDate || null,
        projectManager: ledgerDraft.projectManager,
        isMonthlySettlement: Boolean(ledgerDraft.isMonthlySettlement)
      });
      await this.refreshAfterMutation();
      this.setData({ ledgerEditing: false });
      this.closeOverlay();
      this.toast('销售与回款信息已更新，毛利已重新计算');
    } catch (error) {
      this.toast(error.message || '批次信息更新失败');
    } finally {
      this.setData({ busy: false });
    }
  },

  /* ---------------- 创建项目 / 批次 ---------------- */
  projectFormDefaults() {
    return {
      name: '',
      customerName: '',
      batchName: buildBatchName(today(), '首批'),
      businessDate: today(),
      materialSummary: ''
    };
  },

  batchFormDefaults(project) {
    const currentProject = project || this.data.currentProject;
    const batchCount = currentProject ? currentProject.batches.length : 0;
    const suffix = batchCount === 0 ? '首批' : `第${batchCount + 1}批`;
    return {
      name: buildBatchName(today(), suffix),
      businessDate: today(),
      materialSummary: this.data.currentBatch ? this.data.currentBatch.materialSummary || '' : '',
      remark: ''
    };
  },

  openCreateProject() {
    this.setData({
      overlay: 'createProject',
      drawer: false,
      newProjectForm: this.projectFormDefaults()
    });
  },

  openCreateBatch() {
    if (!this.data.currentProject) {
      this.openCreateProject();
      return;
    }
    this.setData({
      overlay: 'createBatch',
      drawer: false,
      newBatchForm: this.batchFormDefaults(this.data.currentProject)
    });
  },

  updateProjectForm(event) {
    const field = event.currentTarget.dataset.field;
    const value = event.detail.value;
    const next = { ...this.data.newProjectForm, [field]: value };
    if (field === 'businessDate') {
      next.batchName = buildBatchName(value, '首批');
    }
    this.setData({ newProjectForm: next });
  },

  updateProjectDate(event) {
    const businessDate = event.detail.value;
    this.setData({
      newProjectForm: {
        ...this.data.newProjectForm,
        businessDate,
        batchName: buildBatchName(businessDate, '首批')
      }
    });
  },

  updateBatchForm(event) {
    this.setData({ [`newBatchForm.${event.currentTarget.dataset.field}`]: event.detail.value });
  },

  updateBatchDate(event) {
    const businessDate = event.detail.value;
    const project = this.data.currentProject;
    const suffix = !project || project.batches.length === 0 ? '首批' : `第${project.batches.length + 1}批`;
    this.setData({
      newBatchForm: {
        ...this.data.newBatchForm,
        businessDate,
        name: buildBatchName(businessDate, suffix)
      }
    });
  },

  /* ---------------- 项目 / 批次编辑（对齐 web：编辑按钮复用表单，改名零回写） ---------------- */
  openEditProject() {
    const project = this.data.currentProject;
    if (!project) return;
    this.setData({
      overlay: 'editProject',
      drawer: false,
      editForm: { id: project.id, name: project.name || '', customerName: project.customerName || '' }
    });
  },

  updateEditForm(event) {
    this.setData({ [`editForm.${event.currentTarget.dataset.field}`]: event.detail.value });
  },

  updateEditDate(event) {
    this.setData({ 'editForm.businessDate': event.detail.value });
  },

  async saveEditProject() {
    const form = this.data.editForm;
    if (!form.name || !form.name.trim()) {
      this.toast('请输入项目名称');
      return;
    }
    this.setData({ busy: true });
    try {
      await api.updateProject(form.id, {
        name: form.name.trim(),
        customerName: (form.customerName || '').trim()
      });
      await this.loadBaseData(form.id, this.data.currentBatch ? this.data.currentBatch.id : undefined);
      this.closeOverlay();
      this.toast('项目信息已更新');
    } catch (error) {
      this.toast(error.message || '项目更新失败');
    } finally {
      this.setData({ busy: false });
    }
  },

  openEditBatch(event) {
    const batchId = event.currentTarget.dataset.id;
    const project = this.data.currentProject;
    const batch = project && project.batches.find((item) => item.id === batchId);
    if (!batch) return;
    this.setData({
      overlay: 'editBatch',
      drawer: false,
      editForm: {
        id: batch.id,
        name: batch.name || '',
        businessDate: batch.businessDate || '',
        materialSummary: batch.materialSummary || ''
      }
    });
  },

  async saveEditBatch() {
    const form = this.data.editForm;
    if (!form.name || !form.name.trim() || !form.materialSummary || !form.materialSummary.trim()) {
      this.toast('请填写批次名称和材料摘要');
      return;
    }
    this.setData({ busy: true });
    try {
      await api.updateBatch(form.id, {
        name: form.name.trim(),
        businessDate: form.businessDate || null,
        materialSummary: form.materialSummary.trim()
      });
      await this.loadBaseData(
        this.data.currentProject ? this.data.currentProject.id : undefined,
        this.data.currentBatch && this.data.currentBatch.id === form.id ? form.id : undefined
      );
      this.closeOverlay();
      this.toast('批次信息已更新');
    } catch (error) {
      this.toast(error.message || '批次更新失败');
    } finally {
      this.setData({ busy: false });
    }
  },

  async createProject() {
    const form = this.data.newProjectForm;
    const duplicateProject = (this.data.projects || []).some((project) =>
      normalized(project.name) === normalized(form.name));
    if (!form.name.trim() || !form.batchName.trim() || !form.materialSummary.trim()) {
      this.toast('请完整填写项目、首批名称和材料摘要');
      return;
    }
    if (form.name.trim().length < 2) {
      this.toast('项目名称至少填写 2 个字');
      return;
    }
    if (duplicateProject) {
      this.toast('项目名称已存在，请调整后再创建');
      return;
    }
    this.setData({ busy: true });
    try {
      const project = await api.createProject({
        name: form.name.trim(),
        customerName: form.customerName.trim()
      });
      const batch = await api.createBatch({
        projectId: project.id,
        name: form.batchName.trim(),
        businessDate: form.businessDate,
        materialSummary: form.materialSummary.trim()
      });
      await this.loadBaseData(project.id, batch.id);
      this.setData({ newProjectForm: this.projectFormDefaults() });
      this.closeOverlay();
      this.toast('项目与首个批次已创建');
    } catch (error) {
      this.toast(error.message || '创建项目失败');
    } finally {
      this.setData({ busy: false });
    }
  },

  async createBatch() {
    const form = this.data.newBatchForm;
    const project = this.data.currentProject;
    const duplicateBatch = project && project.batches.some((batch) =>
      normalized(batch.name) === normalized(form.name));
    if (!project || !form.name.trim() || !form.materialSummary.trim()) {
      this.toast('请填写批次名称和材料摘要');
      return;
    }
    if (duplicateBatch) {
      this.toast('该项目下已存在同名批次，请调整后再创建');
      return;
    }
    this.setData({ busy: true });
    try {
      const batch = await api.createBatch({
        projectId: project.id,
        name: form.name.trim(),
        businessDate: form.businessDate,
        materialSummary: form.materialSummary.trim(),
        remark: form.remark.trim()
      });
      await this.loadBaseData(project.id, batch.id);
      this.setData({ newBatchForm: this.batchFormDefaults(project) });
      this.closeOverlay();
      this.toast('新批次已创建并切换');
    } catch (error) {
      this.toast(error.message || '创建批次失败');
    } finally {
      this.setData({ busy: false });
    }
  },

  /* ---------------- 账号与企业身份 ---------------- */
  openCompanySettings() {
    this.setData({
      overlay: 'company',
      drawer: false,
      companyDraft: this.data.user ? this.data.user.companyName || '' : ''
    });
  },

  /* ---------------- 每周对账提醒（与 web 系统设置互通，reconciliation.{enabled,weekday,hour}） ---------------- */
  WEEKDAY_LABELS: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'],

  async openReminderSettings() {
    this.setData({ overlay: 'reminder', drawer: false, reminderLoading: true });
    try {
      const settings = await api.userSettings();
      const domain = (settings && settings.reconciliation) || {};
      this.applyReminderState({
        enabled: domain.enabled === true,
        weekday: Math.min(7, Math.max(1, Number(domain.weekday) || 1)),
        hour: Math.min(23, Math.max(0, Number(domain.hour) == null ? 9 : Number(domain.hour) || 9))
      });
    } catch (error) {
      this.applyReminderState({ enabled: false, weekday: 1, hour: 9 });
      this.toast('提醒设置加载失败，已显示默认值');
    } finally {
      this.setData({ reminderLoading: false });
    }
  },

  applyReminderState(state) {
    this.setData({
      reminderDraft: state,
      reminderHourText: `${String(state.hour).padStart(2, '0')}:00`,
      reminderLabel: state.enabled
        ? `每周${this.WEEKDAY_LABELS[state.weekday - 1]} ${String(state.hour).padStart(2, '0')}:00`
        : '已关闭'
    });
  },

  async toggleReminderEnabled(event) {
    const enabled = Boolean(event.detail.value);
    await this.persistReminder({ enabled });
  },

  async changeReminderWeekday(event) {
    const weekday = Number(event.detail.value) + 1;
    await this.persistReminder({ weekday });
  },

  async changeReminderHour(event) {
    const hour = Number(String(event.detail.value || '').slice(0, 2));
    await this.persistReminder({ hour: Number.isFinite(hour) ? hour : 9 });
  },

  async persistReminder(patch) {
    this.setData({ busy: true });
    try {
      const settings = await api.updateUserSettings({ reconciliation: patch });
      const domain = (settings && settings.reconciliation) || {};
      this.applyReminderState({
        enabled: domain.enabled === true,
        weekday: Math.min(7, Math.max(1, Number(domain.weekday) || 1)),
        hour: Math.min(23, Math.max(0, Number(domain.hour) || 9))
      });
      this.toast('提醒设置已保存');
    } catch (error) {
      this.toast((error && error.message) || '提醒设置保存失败');
    } finally {
      this.setData({ busy: false });
    }
  },

  // 拉取积分余额 + 消费记录；个人中心进入时调用（避免余额卡片显示 0），
  // 点击积分卡片打开明细弹层时复用，仅补充 loading 态与已加载的明细。
  async loadBilling() {
    const [credit, logs] = await Promise.all([
      api.billingCredits(),
      api.billingUsageLogs(0, 50)
    ]);
    const periodEnd = credit.periodEnd ? credit.periodEnd.slice(0, 10) : '';
    this.setData({
      billing: {
        balance: credit.balance || 0,
        usedThisMonth: credit.usedThisMonth || 0,
        monthlyQuota: credit.monthlyQuota || 0,
        periodEnd
      },
      billingLogs: (logs.items || []).map((log) => ({
        ...log,
        costCredits: Number(log.costCredits || 0).toFixed(2),
        createdAt: log.createdAt ? log.createdAt.replace('T', ' ').slice(0, 16) : ''
      }))
    });
  },

  async openBilling() {
    this.setData({ billingOpen: true, billingLoading: true });
    try {
      await this.loadBilling();
    } catch (error) {
      this.toast('积分信息加载失败');
      console.error('openBilling failed', error);
    } finally {
      this.setData({ billingLoading: false });
    }
  },

  closeBilling() {
    this.setData({ billingOpen: false });
  },

  noop() {},

  updateCompanyDraft(event) {
    this.setData({ companyDraft: event.detail.value });
  },

  async saveCompany() {
    const companyName = this.data.companyDraft.trim();
    if (!companyName) {
      this.toast('请输入本单位名称');
      return;
    }
    this.setData({ busy: true });
    try {
      const user = await api.updateMe(companyName);
      this.setData({ user, overlay: '' });
      this.syncPageSummaries();
      this.toast('本单位身份已更新');
    } catch (error) {
      this.toast(error.message || '保存失败');
    } finally {
      this.setData({ busy: false });
    }
  },

  logout() {
    wx.showModal({
      title: '退出当前账号',
      content: '退出后将重新通过微信身份登录。',
      confirmText: '退出',
      confirmColor: '#ef4d4d',
      success: async ({ confirm }) => {
        if (!confirm) return;
        try {
          await api.logout();
        } catch (error) {
          // 清理本地会话优先，服务端退出失败不阻塞。
        }
        clearSession();
        this.setData({
          user: null,
          userInitial: '用',
          projects: [],
          currentProject: null,
          currentBatch: null,
          messages: [],
          records: [],
          ledgerGroups: [],
          displayedLedgerGroups: [],
          filteredRecords: [],
          navItems: buildNavItems({})
        });
        this.bootstrap();
      }
    });
  },

  toast(title) {
    wx.showToast({ title, icon: 'none', duration: 2200 });
  }
};
