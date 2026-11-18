const api = require('../../utils/api');
const { ensureLogin, downloadFile } = require('../../utils/request');
const {
  buildLedgerGroups,
  compactMoney,
  decorateCardMessage,
  decorateProject,
  decorateRecord,
  money,
  toneColor
} = require('../../utils/domain');
const {
  NAV_ITEMS,
  DRAWER_ICON_SRC,
  PAGE_META,
  EDITABLE_DOC_TYPES,
  DATA_FILTERS,
  DATA_SCOPE_FILTERS,
  DATA_STATUS_FILTERS,
  LEDGER_SCOPE_FILTERS,
  LEDGER_KIND_FILTERS,
  QUICK_ACTIONS,
  today,
  fileName
} = require('./constants');
const actions = require('./actions');

// 底部输入区布局：随快捷胶囊 / 附件预览高度变化，动态计算聊天滚动区下边界。
// 注意：CSS 尺寸单位是 rpx，需按当前屏宽换算成 px；否则在 375pt 设备上会高估约 2 倍，
// 导致聊天滚动区 bottom 过大，输入框上方出现一大块空白。
function composerLayout(navHeight, safeBottom, hasAttachment) {
  const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
  const factor = (info.windowWidth || 375) / 750; // 每 rpx 对应 px
  const padTop = 14 * factor;
  const padBottom = safeBottom + 6;             // px（含安全区，仅留最小呼吸感）
  const attach = hasAttachment ? (84 + 12) * factor : 0; // 附件卡 + 其下外边距
  const quick = (45 + 12) * factor;             // 快捷胶囊 + 其下外边距
  const inputRow = 72 * factor;                 // 输入行
  const composerHeight = padTop + attach + quick + inputRow + padBottom;
    return {
      workspaceScrollStyle: `top:${navHeight}px;bottom:${composerHeight}px`,
      composerStyle: `bottom:0;padding-bottom:${padBottom}px`
    };
}

const initialNavItems = NAV_ITEMS.map((item) => ({ ...item, badge: '' }));

const pageObject = {
  data: {
    navItems: initialNavItems,
    navGroups: {
      workbench: ['workspace', 'projects', 'data', 'ledger', 'market'].map((k) => initialNavItems.find((i) => i.key === k)).filter(Boolean),
      manage: ['profile'].map((k) => initialNavItems.find((i) => i.key === k)).filter(Boolean),
      system: []
    },
    drawerIconSrc: DRAWER_ICON_SRC,
    quickActions: QUICK_ACTIONS,
    activeTab: 'workspace',
    pageMeta: PAGE_META.workspace,
    drawer: false,
    loading: true,
    refreshing: false,
    busy: false,
    error: '',
    bootstrapError: '',
    user: null,
    userInitial: '用',
    currentProjectHint: '请先创建项目',
    projects: [],
    batches: [],
    currentProject: null,
    currentBatch: null,
    messages: [],
    records: [],
    filteredRecords: [],
    ledgerRows: [],
    ledgerGroups: [],
    displayedLedgerGroups: [],
    currentLedgerFocus: null,
    nextSteps: [],
    nextStepsRest: [],
    nextStepsOpen: false,
    batchClosure: [],
    batchMatchSummary: null,
    batchSummary: null,
    batchDocuments: [],
    unresolvedCount: 0,
    projectMetrics: {
      projectCount: 0,
      batchCount: 0,
      activeProjects: 0,
      completedProjects: 0
    },
    profileOverview: {
      companyReady: false,
      currentProjectName: '',
      currentBatchName: '',
      pendingCount: 0,
      ledgerIssueCount: 0,
      recordCount: 0
    },
    billing: {
      balance: 0,
      usedThisMonth: 0,
      monthlyQuota: 0,
      periodEnd: ''
    },
    billingLogs: [],
    billingOpen: false,
    billingLoading: false,
    stats: {
      recordCount: 0,
      pendingCount: 0,
      purchaseText: '¥0.00',
      paidText: '¥0.00',
      unresolvedText: '0'
    },
    dataSummary: {
      total: 0,
      pending: 0,
      discrepancy: 0,
      confirmed: 0,
      amountText: '¥0.00',
      scopeLabel: '当前批次'
    },
    dataTypes: DATA_FILTERS.map((item, index) => ({ ...item, active: index === 0 })),
    dataScopes: DATA_SCOPE_FILTERS.map((item, index) => ({ ...item, active: index === 0 })),
    dataStatuses: DATA_STATUS_FILTERS.map((item, index) => ({ ...item, active: index === 0 })),
    dataType: '',
    dataScope: 'batch',
    dataStatus: 'all',
    dataQuery: '',
    ledgerScopes: LEDGER_SCOPE_FILTERS.map((item, index) => ({ ...item, active: index === 0 })),
    ledgerScope: 'project',
    ledgerKind: '',
    ledgerKinds: LEDGER_KIND_FILTERS.map((item, index) => ({ ...item, active: index === 0 })),
    expandedGroupIds: {},
    allExpanded: false,
    ledgerColWidths: [],
    ledgerColTemplate: '',
    ledgerSummary: {
      batchCount: 0,
      purchaseText: '¥0.00',
      paidText: '¥0.00',
      unpaidText: '¥0.00',
      requestedText: '¥0.00',
      invoiceText: '¥0.00',
      invoiceGapText: '¥0.00',
      outstandingText: '¥0.00',
      urgentCount: 0
    },
    pendingFile: null,
    composerText: '',
    composerCursor: 0,
    canSend: false,
    scrollIntoView: '',
    overlay: '',
    contextQuery: '',
    contextProjects: [],
    contextBatches: [],
    contextTempProjectId: '',
    contextTempBatchId: '',
    tempProjectName: '',
    activeDocument: null,
    documentSection: 'data',
    documentEditing: false,
    documentDraft: null,
    editableDocTypes: EDITABLE_DOC_TYPES,
    activeLedger: null,
    ledgerDraft: null,
    ledgerEditing: false,
    activeSales: null,
    activeRequested: null,
    reconExportForm: { start: '', end: '', materialIndex: 0 },
    reconMaterialOptions: ['全部材料'],
    editForm: null,
    ledgerHeaderRows: null,
    // 每周对账提醒（与 web 系统设置互通）
    weekdayOptions: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'],
    reminderDraft: { enabled: false, weekday: 1, hour: 9 },
    reminderHourText: '09:00',
    reminderLabel: '未设置',
    newProjectForm: {
      name: '',
      customerName: '',
      batchName: `${today()} 首批`,
      businessDate: today(),
      materialSummary: ''
    },
    newBatchForm: {
      name: `${today()} 批次`,
      businessDate: today(),
      materialSummary: '',
      remark: ''
    },
    companyDraft: '',
    safeTop: 44,
    navHeight: 92,
    safeBottom: 0,
    keyboardHeight: 0,
    capsuleWidth: 0,
    headerStyle: '',
    workspaceScrollStyle: '',
    pageScrollStyle: '',
    composerStyle: '',
    /* ---------------- 市场行情 ---------------- */
    marketTab: 'compare',
    marketMeta: null,
    marketCompare: null,
    marketMappingList: [],
    marketCity: '杭州',
    marketCityIndex: 0,
    marketCities: [],
    marketCompareOrigin: '',
    marketCompareOriginIndex: 0,
    marketCompareOriginLabel: '默认产地',
    marketLoading: false,
    marketCategory: '',
    marketCategoryIndex: 0,
    marketCategories: [],
    marketProduct: '',
    marketProductIndex: 0,
    marketProductOptions: [],
    marketQuery: '',
    marketCategoryLabel: '全部分类',
    marketProductLabel: '全部产品',
    marketQuoteCity: '',
    marketQuoteCityIndex: 0,
    marketQuoteCityLabel: '全部城市',
    marketQuoteOrigin: '',
    marketQuoteOriginIndex: 0,
    marketQuoteOriginLabel: '全部产地',
    marketOrigins: [],
    marketLatestDate: '',
    marketQuoteGroups: [],
    marketBindVisible: false,
    marketBindMaterial: null,
    marketBindProduct: '',
    marketBindProductLabel: '',
    marketBindSpec: '',
    marketBindSpecLabel: '',
    marketProducts: [],
    marketBindSpecs: [],
    marketBindProductIndex: -1,
    marketBindSpecIndex: -1,
    marketBindPrice: '',
  },

  onLoad() {
    this.initLayout();
    this.bootstrap();
  },

  onReady() {
    // 首屏渲染后实测输入器高度，校正聊天区下边界
    wx.nextTick(() => this.measureComposer());
  },

  onShow() {
    // 从确认页（navigateBack）或其它视图返回工作台时，重建布局与滚动位置。
    // wx:if 重建会导致键盘态残留、scroll-into-view 同值不触发滚动。
    if (this.data.activeTab === 'workspace') {
      this.relayoutWorkspace();
    }
  },

  onPullDownRefresh() {
    Promise.resolve(this.refreshAll()).finally(() => wx.stopPullDownRefresh());
  },

  initLayout() {
    const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    const capsule = wx.getMenuButtonBoundingClientRect
      ? wx.getMenuButtonBoundingClientRect()
      : { top: 48, bottom: 80, height: 32, left: 320, right: 360 };
    const safeTop = info.statusBarHeight || 44;
    const navHeight = Math.max(capsule.bottom + 12, safeTop + 52);
    const safeBottom = info.safeArea ? Math.max(info.screenHeight - info.safeArea.bottom, 0) : 0;
    const capsuleWidth = Math.max(info.windowWidth - capsule.left + 16, 104);
    this.setData({
      safeTop,
      navHeight,
      safeBottom,
      capsuleWidth,
      headerStyle: `height:${navHeight}px;padding-top:${safeTop}px;--status-bar:${safeTop}px`,
      pageScrollStyle: `top:${navHeight}px;bottom:0`,
      ...composerLayout(navHeight, safeBottom, false)
    });
  },

  async bootstrap() {
    this.setData({ loading: true, error: '', bootstrapError: '' });
    try {
      await ensureLogin();
      const user = await api.me();
      this.setData({
        user,
        userInitial: (user.nickname || '用').slice(0, 1),
        companyDraft: user.companyName || ''
      });
      await this.loadBaseData();
      // 台账表头从后端 LedgerTableSchema 动态拉取（失败回退本地硬编码表头）
      this.ensureLedgerHeader();
      // 个人中心积分余额卡片进入即拉取，避免显示初始 0（失败静默，不阻塞主流程）。
      this.loadBilling().catch((error) => console.error('loadBilling failed', error));
      // 每周对账提醒：进入即拉取（失败静默，个人中心菜单显示默认值）
      if (this.openReminderSettings) {
        api.userSettings().then((settings) => {
          const domain = (settings && settings.reconciliation) || {};
          this.applyReminderState({
            enabled: domain.enabled === true,
            weekday: Math.min(7, Math.max(1, Number(domain.weekday) || 1)),
            hour: Math.min(23, Math.max(0, Number(domain.hour) || 9))
          });
        }).catch(() => {});
      }
    } catch (error) {
      // 初始化失败 → 全屏错误态（区别于局部操作失败，后者只进聊天区提示）
      this.setData({ bootstrapError: error.message || '加载失败，请稍后重试' });
    } finally {
      this.setData({ loading: false });
    }
  },

  async loadBaseData(preferredProjectId, preferredBatchId) {
    const [rawProjects, rawBatches] = await Promise.all([api.projects(), api.batches()]);
    const projects = rawProjects.map((project) => decorateProject(project, rawBatches));
    let currentProject = projects.find((item) => item.id === preferredProjectId);
    if (!currentProject) currentProject = projects.find((item) => item.batches.length) || projects[0] || null;
    let currentBatch = currentProject
      ? currentProject.batches.find((item) => item.id === preferredBatchId)
      : null;
    if (!currentBatch && currentProject) currentBatch = currentProject.batches[0] || null;

    this.setData({
      projects,
      batches: rawBatches,
      currentProject,
      currentBatch,
      currentProjectHint: currentProject ? `添加到 ${currentProject.name}` : '请先创建项目',
      contextProjects: projects,
      contextBatches: currentProject ? currentProject.batches : []
    });
    this.syncPageSummaries();

    if (currentProject && currentBatch) {
      await this.refreshContext();
    } else {
      this.setData({
        messages: [],
        records: [],
        filteredRecords: [],
        ledgerRows: [],
        ledgerGroups: [],
        displayedLedgerGroups: [],
        currentLedgerFocus: null,
        nextSteps: [],
        nextStepsRest: [],
        batchClosure: [],
        batchMatchSummary: null,
        batchSummary: null,
        batchDocuments: [],
        unresolvedCount: 0
      });
      this.applyDataFilter();
      this.applyLedgerFilter();
    }
  },

  async refreshContext() {
    const { currentProject, currentBatch, projects, batches } = this.data;
    if (!currentProject || !currentBatch) return;
    const requestId = (this.contextRequestId || 0) + 1;
    const projectId = currentProject.id;
    const batchId = currentBatch.id;
    this.contextRequestId = requestId;
    const [rawMessages, rawRecords, ledgerRows] = await Promise.all([
      api.chatMessages(projectId, batchId),
      api.businessData(projectId),
      api.ledger(projectId)
    ]);
    if (
      requestId !== this.contextRequestId
      || !this.data.currentProject
      || !this.data.currentBatch
      || this.data.currentProject.id !== projectId
      || this.data.currentBatch.id !== batchId
    ) return;
    const messages = rawMessages.map(decorateCardMessage);
    const records = rawRecords.map((record) => decorateRecord(record, projects, batches));
    const ledgerGroups = buildLedgerGroups(rawRecords, ledgerRows, projects, batches);
    const currentRecords = records.filter((record) => record.document.batchId === currentBatch.id);
    const pendingCount = currentRecords.filter((record) =>
      ['pending', 'discrepancy', 'recognizing'].includes(record.document.status)
      && record.document.duplicateDecision !== 'skip').length;
    const currentLedger = ledgerGroups.find((group) => group.id === currentBatch.id);
    const nextSteps = this.buildNextSteps(currentRecords, currentLedger);
    // 关联单据 / 待办直接复用当前批次记录，避免重复请求（/api/documents 返回裸 Document，结构与此处不同）
    const unresolvedDocuments = currentRecords.filter((record) =>
      ['pending', 'discrepancy', 'recognizing'].includes(record.document.status)
      && record.document.duplicateDecision !== 'skip');

    this.setData({
      messages,
      records,
      ledgerRows,
      ledgerGroups,
      nextSteps,
      nextStepsRest: nextSteps.slice(1),
      batchClosure: this.buildBatchClosure(currentBatch, currentLedger),
      batchMatchSummary: this.buildBatchMatchSummary(currentBatch, currentLedger, currentRecords),
      batchSummary: this.buildBatchSummary(currentLedger, unresolvedDocuments.length),
      batchDocuments: currentRecords.slice(0, 4),
      unresolvedCount: unresolvedDocuments.length,
      stats: {
        recordCount: currentRecords.length,
        pendingCount,
        purchaseText: currentLedger ? currentLedger.purchaseText : compactMoney(currentBatch.purchaseAmount),
        paidText: currentLedger ? currentLedger.paidText : '¥0.00',
        unresolvedText: String(pendingCount)
      }
    });
    this.applyDataFilter();
    this.applyLedgerFilter();
    this.syncPageSummaries();
    // 市场行情：切换批次后若已进入市场页，刷新对比
    if (this.data.activeTab === 'market') {
      this.loadMarketCompare();
    }
    this.scrollToLatest(messages.length);
  },

  async refreshAll() {
    if (this.data.refreshing) return;
    this.setData({ refreshing: true });
    try {
      const projectId = this.data.currentProject && this.data.currentProject.id;
      const batchId = this.data.currentBatch && this.data.currentBatch.id;
      await this.loadBaseData(projectId, batchId);
      // 全局刷新时同步刷新市场行情
      if (this.data.activeTab === 'market') {
        this.ensureMarketLoaded();
      }
    } catch (error) {
      this.toast(error.message || '刷新失败');
    } finally {
      this.setData({ refreshing: false });
    }
  },

  buildNextSteps(records, ledger) {
    const confirmedTypes = {};
    records.forEach((record) => {
      if (['matched', 'archived'].includes(record.document.status)
        && record.document.duplicateDecision !== 'skip') {
        confirmedTypes[record.document.docType] = true;
      }
    });
    const steps = [];
    if (!confirmedTypes.supply_order) {
      steps.push({ key: 'supply', icon: 'doc', title: '导入采购单', detail: '建立采购基础；同批次可导入多张', hint: '请按采购单识别这份资料' });
    } else if (!confirmedTypes.delivery_note) {
      steps.push({ key: 'delivery', icon: 'truck', title: '补充送货单', detail: '核对实际到货数量和重量；可导入多张', hint: '请按送货单识别这份资料' });
    }
    if (confirmedTypes.supply_order && !confirmedTypes.transport_note) {
      steps.push({ key: 'transport', icon: 'truck', title: '补充运输费用', detail: '记录承运单位、金额与运输日期', hint: '请按运输费用识别这份资料' });
    }
    if (confirmedTypes.supply_order && !confirmedTypes.payment_request) {
      steps.push({ key: 'request', icon: 'bill', title: '补充请款记录', detail: '回填请款单位、金额和申请日期', hint: '请按请款单识别这份资料' });
    }
    if (ledger && ledger.unpaid > 0) {
      steps.push({ key: 'payment', icon: 'cash', title: '补充付款记录', detail: `当前仍未付款 ${ledger.unpaidText}`, hint: '请按付款记录识别这份资料' });
    }
    if (ledger && ledger.invoice < ledger.purchase) {
      steps.push({ key: 'invoice', icon: 'invoice', title: '补充进项发票', detail: `当前仍缺进项票 ${money(ledger.purchase - ledger.invoice)}`, hint: '请按进项发票识别这份资料' });
    }
    return steps;
  },

  // 票款货匹配面板（对齐 Web match-panel）
  buildBatchMatchSummary(batch, ledger, records) {
    if (!batch) return null;
    const confirmed = records.filter((record) =>
      ['matched', 'archived'].includes(record.document.status)
      && record.document.duplicateDecision !== 'skip');
    const supply = confirmed.filter((record) => record.document.docType === 'supply_order');
    const delivery = confirmed.filter((record) => record.document.docType === 'delivery_note');
    const invoice = confirmed.filter((record) =>
      ['purchase_invoice', 'invoice'].includes(record.document.docType));
    const payment = confirmed.filter((record) => record.document.docType === 'payment');
    const purchase = ledger ? ledger.purchase : Number(batch.purchaseAmount || 0);
    const paid = ledger ? ledger.paid : 0;
    const invoiceAmt = ledger ? ledger.invoice : 0;
    const delivered = ledger ? ledger.delivered : false;
    const materialRows = ledger ? ledger.materialRows : [];
    const deliveryRate = materialRows.length
      ? (materialRows.filter((item) => item.deliveryStatus === '已送达' || item.deliveryStatus === '超供送达').length / materialRows.length * 100)
      : 0;
    const invoiceRate = purchase > 0 ? Math.min(invoiceAmt / purchase * 100, 100) : 0;
    const paymentRate = purchase > 0 ? Math.min(paid / purchase * 100, 100) : 0;
    const step = (key, label, icon, count, detail, done) => ({
      key,
      label,
      icon,
      detail: `${count}份 · ${detail}`,
      done,
      color: done ? toneColor('green') : '#9ba7ba'
    });
    return {
      steps: [
        step('supply', '采购单', 'doc', supply.length, compactMoney(purchase), purchase > 0),
        step('invoice', '进项发票', 'invoice', invoice.length, compactMoney(invoiceAmt), invoiceAmt > 0),
        step('payment', '付款', 'cash', payment.length, compactMoney(paid), paid > 0),
        step('delivery', '送货单', 'truck', delivery.length, delivered ? '已送达' : '待送达', delivered)
      ],
      matchedCount: [purchase > 0, invoiceAmt > 0, paid > 0, delivered].filter(Boolean).length,
      deliveryRateText: `${deliveryRate.toFixed(1)}%`,
      invoiceRateText: `${invoiceRate.toFixed(1)}%`,
      paymentRateText: `${paymentRate.toFixed(1)}%`
    };
  },

  // 批次对账摘要面板（对齐 Web batch-summary-panel）
  buildBatchSummary(ledger, unresolvedCount) {
    if (!ledger) return null;
    const alerts = [];
    if (unresolvedCount > 0) alerts.push(`${unresolvedCount}份单据需要处理`);
    if (ledger.pendingDeliveryCount > 0) alerts.push(`${ledger.pendingDeliveryCount}项材料待交付`);
    if (ledger.unpaid > 0) alerts.push(`待付款 ${ledger.unpaidText}`);
    if (ledger.invoiceGap > 0) alerts.push(`缺进项票 ${ledger.invoiceGapText}`);
    const alert = alerts.length
      ? {
        icon: 'warning',
        tone: 'orange',
        color: toneColor('orange'),
        text: alerts[0],
        sub: 'AI已完成字段提取，等待人工确认或处置。'
      }
      : null;
    return {
      purchaseText: ledger.purchaseText,
      unpaidText: ledger.unpaidText,
      invoiceGapText: ledger.invoiceGapText,
      receivedText: ledger.receivedText,
      alert
    };
  },

  // 当前批次业务闭环（对齐 Web batch-timeline 六节点，金额取台账聚合真实值）
  buildBatchClosure(batch, ledger) {
    if (!batch) return [];
    const purchase = ledger ? ledger.purchase : Number(batch.purchaseAmount || 0);
    const paid = ledger ? ledger.paid : 0;
    const invoice = ledger ? ledger.invoice : 0;
    const salesInvoice = ledger ? ledger.salesInvoice || 0 : 0;
    const received = ledger ? ledger.received : 0;
    const materialRows = ledger && ledger.materialRows ? ledger.materialRows : [];
    const deliveredAll = materialRows.length > 0 && Boolean(ledger && ledger.delivered);
    const deliveredSome = !deliveredAll
      && materialRows.some((item) => item.deliveryStatus !== '待送达');
    const deliveryValue = !materialRows.length
      ? '待采购确认'
      : deliveredAll
        ? (materialRows.some((item) => item.deliveryStatus === '超供送达') ? '超供送达' : '已送达')
        : deliveredSome
          ? `部分送达·待${ledger.pendingDeliveryCount}类`
          : '尚未送达';
    return [
      { key: 'purchase', label: '采购确认', value: purchase > 0 ? compactMoney(purchase) : '待导入采购单', done: purchase > 0 },
      { key: 'invoice', label: '进项发票', value: compactMoney(invoice), done: invoice > 0 },
      { key: 'payment', label: '付款', value: compactMoney(paid), done: paid > 0 },
      { key: 'delivery', label: '到货确认', value: deliveryValue, done: deliveredAll || deliveredSome },
      { key: 'salesInvoice', label: '销项发票', value: compactMoney(salesInvoice), done: salesInvoice > 0 },
      { key: 'receipt', label: '收款', value: compactMoney(received), done: received > 0 }
    ];
  },

  /* ---------------- 导航 / 抽屉 / 上下文 ---------------- */
  setTab(event) {
    const activeTab = event.currentTarget.dataset.key;
    const leavingWorkspace = this.data.activeTab === 'workspace' && activeTab !== 'workspace';
    this.setData({ activeTab, pageMeta: PAGE_META[activeTab], overlay: '', drawer: false });
    if (activeTab === 'workspace') {
      this.relayoutWorkspace();
    } else if (leavingWorkspace) {
      // 离开工作台时收起键盘态，避免返回后输入器浮空、遮挡消息流
      this.setData({
        keyboardHeight: 0,
        composerStyle: `bottom:0;padding-bottom:${this.data.safeBottom + 6}px`
      });
    }
    if (activeTab === 'market') {
      this.ensureMarketLoaded();
    }
  },

  toggleDrawer() {
    this.setData({ drawer: !this.data.drawer });
  },

  closeDrawer() {
    this.setData({ drawer: false });
  },

  openContext() {
    const tempProject = this.data.currentProject;
    this.setData({
      overlay: 'context',
      drawer: false,
      contextQuery: '',
      contextProjects: this.data.projects,
      contextTempProjectId: tempProject ? tempProject.id : '',
      contextTempBatchId: this.data.currentBatch ? this.data.currentBatch.id : '',
      contextBatches: tempProject ? tempProject.batches : [],
      tempProjectName: tempProject ? tempProject.name : ''
    });
  },

  updateContextQuery(event) {
    const value = event.detail.value.trim().toLowerCase();
    const contextProjects = this.data.projects.filter((project) =>
      `${project.name} ${project.customerName || ''}`.toLowerCase().includes(value));
    this.setData({ contextQuery: event.detail.value, contextProjects });
  },

  // 右栏：正式应用项目 + 批次，刷新上下文（选择器浮层保持打开）
  async chooseContextBatch(event) {
    const batch = this.data.contextBatches.find((item) => item.id === event.currentTarget.dataset.id);
    if (!batch) return;
    const project = this.data.projects.find((item) => item.id === this.data.contextTempProjectId);
    if (!project) return;
    const previousProject = this.data.currentProject;
    const previousBatch = this.data.currentBatch;
    this.setData({
      currentProject: project,
      currentBatch: batch,
      contextTempBatchId: batch.id,
      currentProjectHint: `添加到 ${project.name}`,
      loading: true
    });
    try {
      await this.refreshContext();
    } catch (error) {
      this.setData({
        currentProject: previousProject,
        currentBatch: previousBatch,
        contextBatches: previousProject ? previousProject.batches : [],
        currentProjectHint: previousProject ? `添加到 ${previousProject.name}` : '请先创建项目'
      });
      this.toast(error.message || '切换批次失败');
    } finally {
      this.setData({ loading: false });
    }
  },

  // 左栏：点击项目即直接切换（提交项目 + 默认首个批次到全局），选择器浮层保持打开
  async chooseContextProject(event) {
    const project = this.data.projects.find((item) => item.id === event.currentTarget.dataset.id);
    if (!project) return;
    const batch = project.batches[0] || null;
    const previousProject = this.data.currentProject;
    const previousBatch = this.data.currentBatch;
    this.setData({
      currentProject: project,
      currentBatch: batch,
      contextTempProjectId: project.id,
      contextTempBatchId: batch ? batch.id : '',
      contextBatches: project.batches,
      tempProjectName: project.name,
      currentProjectHint: `添加到 ${project.name}`,
      loading: true
    });
    try {
      await this.refreshContext();
    } catch (error) {
      this.setData({
        currentProject: previousProject,
        currentBatch: previousBatch,
        contextBatches: previousProject ? previousProject.batches : [],
        currentProjectHint: previousProject ? `添加到 ${previousProject.name}` : '请先创建项目'
      });
      this.toast(error.message || '切换项目失败');
    } finally {
      this.setData({ loading: false });
    }
  },

  // 联动选择器中新建批次：先提交临时项目，再打开创建浮层
  openCreateBatchFromContext() {
    const project = this.data.projects.find((item) => item.id === this.data.contextTempProjectId) || this.data.currentProject;
    if (!project) {
      this.openCreateProject();
      return;
    }
    if (!this.data.currentProject || this.data.currentProject.id !== project.id) {
      this.setData({
        currentProject: project,
        currentBatch: project.batches[0] || null,
        contextBatches: project.batches,
        currentProjectHint: `添加到 ${project.name}`
      });
      this.refreshContext().catch(() => {});
    }
    this.openCreateBatch();
  },

  // 项目页：切换项目（联动首个批次）
  async selectProject(event) {
    const project = this.data.projects.find((item) => item.id === event.currentTarget.dataset.id);
    if (!project) return;
    if (this.data.currentProject && this.data.currentProject.id === project.id) {
      this.setData({ drawer: false });
      return;
    }
    const previousProject = this.data.currentProject;
    const previousBatch = this.data.currentBatch;
    this.setData({
      currentProject: project,
      currentBatch: project.batches[0] || null,
      contextBatches: project.batches,
      currentProjectHint: `添加到 ${project.name}`,
      drawer: false,
      loading: true
    });
    try {
      if (project.batches[0]) {
        await this.refreshContext();
      } else {
        this.setData({
          messages: [],
          records: [],
          filteredRecords: [],
          ledgerRows: [],
          ledgerGroups: [],
          displayedLedgerGroups: [],
          currentLedgerFocus: null,
          nextSteps: [],
          nextStepsRest: [],
          batchClosure: [],
          batchMatchSummary: null,
          batchSummary: null,
          batchDocuments: [],
          unresolvedCount: 0,
          stats: {
            recordCount: 0,
            pendingCount: 0,
            purchaseText: '¥0.00',
            paidText: '¥0.00',
            unresolvedText: '0'
          }
        });
        this.applyDataFilter();
        this.applyLedgerFilter();
        this.syncPageSummaries();
      }
    } catch (error) {
      this.setData({
        currentProject: previousProject,
        currentBatch: previousBatch,
        contextBatches: previousProject ? previousProject.batches : [],
        currentProjectHint: previousProject ? `添加到 ${previousProject.name}` : '请先创建项目'
      });
      this.toast(error.message || '切换项目失败');
    } finally {
      this.setData({ loading: false });
    }
  },

  // 项目页：切换当前批次
  async selectBatch(event) {
    const { currentProject, currentBatch } = this.data;
    const batch = currentProject
      ? currentProject.batches.find((item) => item.id === event.currentTarget.dataset.id)
      : null;
    if (!batch || (currentBatch && currentBatch.id === batch.id)) return;
    this.setData({ currentBatch: batch, loading: true });
    try {
      await this.refreshContext();
    } catch (error) {
      this.toast(error.message || '切换批次失败');
    } finally {
      this.setData({ loading: false });
    }
  },

  // 删除项目（逻辑删除）：级联隐藏项目下全部批次，数据保留可审计
  deleteProject(event) {
    const id = event.currentTarget.dataset.id;
    const project = this.data.projects.find((item) => item.id === id);
    if (!project) return;
    const batchCount = project.batches ? project.batches.length : 0;
    wx.showModal({
      title: '删除项目',
      content: `「${project.name}」及其下 ${batchCount} 个批次将被删除。全部批次、收款及分摊一起删除，无需手动解除关联。其他单据、台账从平台隐藏，原始附件保留；界面不支持恢复已删除的收款关联。`,
      confirmText: '删除',
      confirmColor: '#e5484d',
      success: async (res) => {
        if (!res.confirm) return;
        try {
          await api.deleteProject(id);
          this.toast('项目及其下批次已删除');
          await this.loadBaseData();
        } catch (error) {
          this.toast(error.message || '删除项目失败');
        }
      }
    });
  },

  // 删除批次（逻辑删除）：删除后重算所属项目金额统计
  deleteBatch(event) {
    const id = event.currentTarget.dataset.id;
    const projectId = event.currentTarget.dataset.projectId;
    // 切换器里批次挂在「临时选中项目」下；项目页批次行则归属当前项目
    const project = projectId
      ? this.data.projects.find((item) => item.id === projectId)
      : this.data.currentProject;
    if (!project) return;
    const batch = project.batches.find((item) => item.id === id);
    if (!batch) return;
    wx.showModal({
      title: '删除批次',
      content: `「${batch.name}」将被删除。整笔关联收款同时删除，其他关联批次已收款同步减少，相关收款的全部分摊及关联一并删除。其采购金额与进度不再计入「${project.name}」统计，关联单据、台账从平台隐藏（数据保留可审计）。`,
      confirmText: '删除',
      confirmColor: '#e5484d',
      success: async (res) => {
        if (!res.confirm) return;
        try {
          await api.deleteBatch(id);
          this.toast('批次已删除，项目统计已更新');
          await this.loadBaseData(project.id);
        } catch (error) {
          this.toast(error.message || '删除批次失败');
        }
      }
    });
  },

  gotoWorkspace() {
    this.setData({ activeTab: 'workspace', pageMeta: PAGE_META.workspace });
    this.scrollToLatest(this.data.messages.length);
  },

  closeOverlay() {
    this.setData({ overlay: '', documentEditing: false, activeDocument: null, activeLedger: null, activeSales: null, activeRequested: null });
  },

  stopTap() {},

  /* ---------------- 输入区 / 附件 ---------------- */
  updateComposer(event) {
    const composerText = event.detail.value;
    const composerCursor = typeof event.detail.cursor === 'number' ? event.detail.cursor : composerText.length;
    this.setData({ composerText, composerCursor, canSend: Boolean(composerText.trim() || this.data.pendingFile) });
  },

  // 回车不再发送（避免中文输入法组字时误发）：回车 = 在光标处插入换行。
  // 发送统一走蓝色发送按钮，避免 IME 组合中按回车被当成发送。
  insertNewline(event) {
    const pos = typeof event.detail.cursor === 'number' ? event.detail.cursor : this.data.composerCursor;
    const text = this.data.composerText;
    const next = text.slice(0, pos) + '\n' + text.slice(pos);
    const nextCursor = pos + 1;
    this.setData({
      composerText: next,
      composerCursor: nextCursor,
      canSend: Boolean(next.trim() || this.data.pendingFile)
    });
  },

  openAttachmentMenu() {
    this.setData({ drawer: false });
    wx.showActionSheet({
      itemList: ['拍照识别', '从相册选择', '从聊天记录提取', '手动输入记录'],
      success: ({ tapIndex }) => {
        if (tapIndex === 0) this.chooseImage('camera');
        if (tapIndex === 1) this.chooseImage('album');
        if (tapIndex === 2) this.chooseFromChat();
        if (tapIndex === 3) this.startManualEntry();
      }
    });
  },

  // 业务数据页头部「智能导入」按钮：复用附件菜单（拍照 / 相册 / PDF / 手动）。
  smartImport() {
    this.openAttachmentMenu();
  },

  // 业务数据页筛选条右侧汉堡按钮：占位入口，后续接扩展筛选（状态 / 项目范围）。
  toggleAdvancedFilter() {
    wx.showActionSheet({
      itemList: ['全部状态', '仅待确认', '仅有差异', '仅已入库'],
      success: ({ tapIndex }) => {
        const map = ['all', 'pending', 'discrepancy', 'confirmed'];
        this.setData({
          dataStatus: map[tapIndex] || 'all',
          dataStatuses: DATA_STATUS_FILTERS.map((item) => ({ ...item, active: item.value === (map[tapIndex] || 'all') }))
        });
        this.applyDataFilter();
      }
    });
  },

  startManualEntry() {
    const prefix = this.data.composerText.trim();
    this.setData({
      activeTab: 'workspace',
      pageMeta: PAGE_META.workspace,
      composerText: prefix || '请识别这条业务记录：',
      canSend: true
    });
  },

  chooseImage(sourceType) {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: [sourceType],
      success: ({ tempFiles }) => {
        const file = tempFiles[0];
        this.setPendingFile({
          path: file.tempFilePath,
          name: fileName(file.tempFilePath, `业务图片-${Date.now()}.jpg`),
          sizeText: `${Math.max(file.size / 1024, 0.1).toFixed(1)} KB`,
          kind: 'image'
        });
      }
    });
  },

  // 从微信聊天记录提取文件（图片 / PDF / Excel / Word 等任意类型），
  // 提取后与拍照/相册一致：进入 pendingFile → 发送时走 api.uploadDocument 统一存储与识别。
  chooseFromChat() {
    wx.chooseMessageFile({
      count: 1,
      type: 'all',
      success: ({ tempFiles }) => {
        const file = tempFiles[0];
        const name = file.name || fileName(file.path, '聊天文件');
        const lower = name.toLowerCase();
        this.setPendingFile({
          path: file.path,
          name,
          sizeText: `${Math.max(file.size / 1024, 0.1).toFixed(1)} KB`,
          kind: /\.(png|jpe?g|gif|webp|heic|bmp)$/i.test(lower) ? 'image'
            : /\.xlsx?$/i.test(lower) ? 'excel'
            : 'document'
        });
      }
    });
  },

  setPendingFile(pendingFile) {
    this.setData({
      pendingFile,
      canSend: true,
      activeTab: 'workspace',
      pageMeta: PAGE_META.workspace,
      drawer: false,
      ...composerLayout(this.data.navHeight, this.data.safeBottom, true)
    });
    wx.nextTick(() => this.measureComposer());
    this.scrollToLatest(this.data.messages.length);
  },

  removePendingFile() {
    this.setData({
      pendingFile: null,
      canSend: Boolean(this.data.composerText.trim()),
      ...composerLayout(this.data.navHeight, this.data.safeBottom, false)
    });
    wx.nextTick(() => this.measureComposer());
  },

  previewPendingFile() {
    const file = this.data.pendingFile;
    if (!file) return;
    if (file.kind === 'image') {
      wx.previewImage({ urls: [file.path], current: file.path });
      return;
    }
    wx.openDocument({ filePath: file.path, showMenu: true });
  },

  quickAction(event) {
    const item = QUICK_ACTIONS.find((entry) => entry.key === event.currentTarget.dataset.key);
    if (!item) return;
    const composerText = item.hint;
    this.setData({ composerText, composerCursor: composerText.length, canSend: true });
    this.openAttachmentMenu();
  },

  prepareNextStep(event) {
    const step = this.data.nextSteps.find((item) => item.key === event.currentTarget.dataset.key);
    if (!step) return;
    const composerText = step.hint;
    this.setData({ composerText, composerCursor: composerText.length, canSend: true });
    this.openAttachmentMenu();
  },

  toggleNextSteps() {
    this.setData({ nextStepsOpen: !this.data.nextStepsOpen });
  },

  async sendPlainText(text, businessType) {
    const { currentProject, currentBatch, busy } = this.data;
    if (busy || !currentProject || !currentBatch || !String(text || '').trim()) return;
    const content = String(text).trim();
    const optimistic = decorateCardMessage({
      id: `local-${Date.now()}`,
      sender: 'USER',
      messageType: 'TEXT',
      businessType: businessType || 'free_text',
      content,
      status: 'done',
      payload: {},
      createdAt: new Date().toISOString()
    });
    this.setData({
      messages: [...this.data.messages, optimistic],
      composerText: '',
      canSend: false,
      busy: true,
      error: ''
    });
    this.scrollToLatest(this.data.messages.length + 1);
    try {
      await api.sendChat(content, currentProject.id, currentBatch.id);
      await this.refreshAfterMutation(false);
    } catch (error) {
      const messages = this.data.messages.map((message) =>
        message.id === optimistic.id
          ? { ...message, failed: true, retryPayload: { kind: 'text', content } }
          : message);
      this.setData({ messages, error: error.message || '发送失败' });
    } finally {
      this.setData({ busy: false });
    }
  },

  async sendMessage() {
    const { composerText, pendingFile, currentProject, currentBatch, busy } = this.data;
    if (busy || !currentProject || !currentBatch || (!composerText.trim() && !pendingFile)) return;
    const text = composerText.trim();
    if (!pendingFile) {
      await this.sendPlainText(text);
      return;
    }
    const optimistic = decorateCardMessage({
      id: `local-${Date.now()}`,
      sender: 'USER',
      messageType: pendingFile ? 'FILE' : 'TEXT',
      businessType: pendingFile ? 'upload' : 'free_text',
      content: pendingFile ? `上传单据：${pendingFile.name}` : text,
      fileName: pendingFile ? pendingFile.name : '',
      status: 'done',
      payload: pendingFile && text ? { guidanceText: text } : {},
      createdAt: new Date().toISOString()
    });
    this.setData({
      messages: [...this.data.messages, optimistic],
      composerText: '',
      composerCursor: 0,
      pendingFile: null,
      canSend: false,
      busy: true,
      error: '',
      ...composerLayout(this.data.navHeight, this.data.safeBottom, false)
    });
    wx.nextTick(() => this.measureComposer());
    this.scrollToLatest(this.data.messages.length + 1);

    try {
      await api.uploadDocument(pendingFile.path, currentProject.id, currentBatch.id, text || undefined);
      await this.refreshAfterMutation(false);
    } catch (error) {
      const messages = this.data.messages.map((message) =>
        message.id === optimistic.id
          ? {
            ...message,
            failed: true,
            // 保留文件与说明，供失败消息「重试」复用（避免重新选择文件、重写说明）
            retryPayload: {
              kind: 'file',
              filePath: pendingFile.path,
              fileName: pendingFile.name,
              guidance: text
            }
          }
          : message);
      this.setData({ messages, error: error.message || '发送失败' });
    } finally {
      this.setData({ busy: false });
    }
  },

  // 失败消息重试：文本重新发送，文件重新上传（保留原附件与处理说明）。
  async retryFailedMessage(event) {
    const id = event.currentTarget.dataset.id;
    const message = this.data.messages.find((item) => item.id === id);
    const payload = message && message.retryPayload;
    const { currentProject, currentBatch, busy } = this.data;
    if (!payload || busy || !currentProject || !currentBatch) return;
    this.setData({ busy: true, error: '' });
    const reset = this.data.messages.map((item) =>
      item.id === id ? { ...item, failed: false, retryPayload: null } : item);
    this.setData({ messages: reset });
    try {
      if (payload.kind === 'file') {
        await api.uploadDocument(payload.filePath, currentProject.id, currentBatch.id, payload.guidance || undefined);
      } else {
        await api.sendChat(payload.content, currentProject.id, currentBatch.id);
      }
      await this.refreshAfterMutation(false);
    } catch (error) {
      const messages = this.data.messages.map((item) =>
        item.id === id ? { ...item, failed: true, retryPayload: payload } : item);
      this.setData({ messages, error: error.message || '重试失败' });
    } finally {
      this.setData({ busy: false });
    }
  },

  // 失败消息删除：移除本地乐观消息，不触发任何服务端操作。
  deleteFailedMessage(event) {
    const id = event.currentTarget.dataset.id;
    this.setData({ messages: this.data.messages.filter((item) => item.id !== id) });
  },

  scrollToLatest(count) {
    const target = count ? 'list-end' : 'chat-start';
    // 先清空 scroll-into-view，下次赋值才能重新触发滚动（WeChat 对相同值不会二次滚动）。
    // 切回工作台时 scroll-view 随 wx:if 重建，旧值已失效，必须强制重滚。
    this.setData({ scrollIntoView: '' }, () => {
      setTimeout(() => this.setData({ scrollIntoView: target }), 30);
    });
  },

  // 工作台视图（wx:if 块）被销毁重建后的统一复位：清残留键盘态、重测输入器高度、复位滚动。
  relayoutWorkspace() {
    if (!this.data.currentProject || !this.data.currentBatch) return;
    // 回到工作台即视为键盘收起，清除可能的残留键盘高度，避免输入器浮空
    this.setData({
      keyboardHeight: 0,
      composerStyle: `bottom:0;padding-bottom:${this.data.safeBottom + 6}px`
    });
    // 多次测量以适配 wx:if 重建后首次布局可能未稳定（rect 偶发为 0 或偏小）
    this.measureComposer();
    wx.nextTick(() => this.measureComposer());
    setTimeout(() => this.measureComposer(), 80);
    this.scrollToLatest(this.data.messages.length);
  },

  // 实测底部输入器真实高度，校正聊天滚动区下边界，彻底消除上方的空白缝隙。
  // 缓存高度 _composerH，供键盘弹起时同步抬升聊天区下边界。
  measureComposer() {
    if (!this.data.currentProject || !this.data.currentBatch) return;
    // 注意：本页是 Page（非自定义组件），workspace.wxml 经 <include> 进入页面 DOM，
    // 因此必须用页面级 query（不带 .in(this)）。.in(this) 用于自定义组件，
    // 在真机部分基础库下会查不到节点导致量高失败、回退到不准的 rpx 估算，从而遮挡消息流。
    const q = wx.createSelectorQuery();
    q.select('.composer-wrap').boundingClientRect((rect) => {
      if (!rect || !rect.height) return;
      const bottom = Math.ceil(rect.height);
      this._composerH = bottom;
      const kb = this.data.keyboardHeight || 0;
      const cur = this.data.workspaceScrollStyle || '';
      const next = `top:${this.data.navHeight}px;bottom:${bottom + kb}px`;
      if (cur !== next) this.setData({ workspaceScrollStyle: next });
    }).exec();
  },

  // 软键盘弹起/收起：把固定输入器整体抬到键盘之上，并同步聊天区下边界，避免被遮挡。
  onKeyboardHeight(event) {
    const h = Math.max(event.detail && event.detail.height ? event.detail.height : 0, 0);
    if (h === this.data.keyboardHeight) return;
    const safeBottom = this.data.safeBottom;
    this.setData({
      keyboardHeight: h,
      composerStyle: `bottom:${h}px;padding-bottom:${safeBottom + 6}px`
    });
    const H = this._composerH || 0;
    if (H) {
      this.setData({ workspaceScrollStyle: `top:${this.data.navHeight}px;bottom:${H + h}px` });
    }
  },

  async refreshAfterMutation(reloadBase = true) {
    const projectId = this.data.currentProject && this.data.currentProject.id;
    const batchId = this.data.currentBatch && this.data.currentBatch.id;
    if (reloadBase) {
      await this.loadBaseData(projectId, batchId);
    } else {
      await this.refreshContext();
    }
  },

  async runDocumentAction(action, successText) {
    if (this.data.busy) return false;
    this.setData({ busy: true, error: '' });
    try {
      await action();
      await this.refreshAfterMutation();
      this.toast(successText);
      return true;
    } catch (error) {
      this.setData({ error: error.message || '操作失败' });
      this.toast(error.message || '操作失败');
      return false;
    } finally {
      this.setData({ busy: false });
    }
  },

  /* ---------------- 聊天卡片快捷操作 ---------------- */
  findCardMessage(id) {
    return this.data.messages.find((message) =>
      message.payload && message.payload.document && message.payload.document.id === id);
  },

  confirmCard(event) {
    const id = event.currentTarget.dataset.id;
    const message = this.findCardMessage(id);
    // 守卫：禁用态（缺字段 / 待匹配 / 已处理）不触发确认
    if (message && message.payload && !message.payload.canConfirm) return;
    this.runDocumentAction(() => api.confirmDocument(id), '单据已确认，相关台账已同步更新');
  },

  duplicateDecision(event) {
    const { id, decision } = event.currentTarget.dataset;
    const message = this.findCardMessage(id);
    // 守卫：已归档的重复单据不重复处理
    if (message && message.payload && message.payload.locked) return;
    this.runDocumentAction(
      () => api.duplicateDecision(id, decision),
      decision === 'skip' ? '已作为重复单据归档' : '已保留为新的待确认单据'
    );
  },

  resolveAmountMismatch(event) {
    const { id, decision } = event.currentTarget.dataset;
    this.runDocumentAction(
      () => api.editDocument(id, { amountMismatchDecision: decision }),
      decision === 'sync_items' ? '已同步为明细合计' : '已保留单据金额并登记差异'
    );
  },

  // 发票方向 / 结算归属聚焦确认（对齐 Web focused-confirmation）：直接改 docType。
  resolveFocusType(event) {
    const { id, type } = event.currentTarget.dataset;
    if (!id || !type) return;
    const label = type === 'purchase_invoice' ? '进项发票'
      : type === 'sales_invoice' ? '销项发票'
      : type === 'sales_settlement' ? '销售结算资料'
      : '原始凭证';
    this.runDocumentAction(
      () => api.editDocument(id, { docType: type }),
      `已确认按${label}处理`
    );
  },

  // 澄清卡选项（对齐 Web）：payload.documentId 存在时直连 /api/chat/clarify 复用原附件，
  // 不再强制重新上传；无关联资料时回退为预填处理说明 + 重新上传。
  async chooseClarification(event) {
    const answer = event.currentTarget.dataset.answer;
    const docId = event.currentTarget.dataset.docId;
    if (!answer) return;
    if (!docId || this.data.busy) {
      this.setData({
        activeTab: 'workspace',
        pageMeta: PAGE_META.workspace,
        composerText: `请按"${answer}"处理这份资料：`,
        composerCursor: `请按"${answer}"处理这份资料：`.length,
        canSend: true
      });
      this.openAttachmentMenu();
      return;
    }
    await this.runDocumentAction(
      () => api.clarifyDocument(docId, answer),
      '已按你的选择继续处理该资料'
    );
  },

  /* ---------------- 对账单导出卡片（对齐 Web ReconciliationExportCard） ---------------- */
  // 日期修改：更新消息卡片里的范围并实时刷新汇总
  async updateReconRange(event) {
    const { msgId, field } = event.currentTarget.dataset;
    const value = event.detail.value;
    const message = this.findCardMessage(msgId);
    if (!message || !value) return;
    const start = field === 'start' ? value : (message.payload.reconStart || '');
    const end = field === 'end' ? value : (message.payload.reconEnd || '');
    this.patchCardMessage(msgId, { reconStart: start, reconEnd: end });
    await this.refreshReconSummary(msgId);
  },

  // 材料下拉：首次点开时拉取项目批次材料，选中后刷新汇总
  async loadReconMaterials(event) {
    const msgId = event.currentTarget.dataset.msgId;
    const message = this.findCardMessage(msgId);
    if (!message || (message.payload.reconMaterialOptions || []).length > 1) return;
    try {
      const list = await api.reconciliationMaterials(message.payload.projectId);
      this.patchCardMessage(msgId, { reconMaterialOptions: ['全部材料'].concat(list || []) });
    } catch (error) {
      // 拉取失败保留「全部材料」
    }
  },

  async updateReconMaterial(event) {
    const msgId = event.currentTarget.dataset.msgId;
    const message = this.findCardMessage(msgId);
    if (!message) return;
    const index = Number(event.detail.value) || 0;
    const options = message.payload.reconMaterialOptions || ['全部材料'];
    this.patchCardMessage(msgId, { reconMaterialIndex: index, reconMaterial: index > 0 ? options[index] : '' });
    await this.refreshReconSummary(msgId);
  },

  async refreshReconSummary(msgId) {
    const message = this.findCardMessage(msgId);
    if (!message) return;
    const { reconStart: start, reconEnd: end, reconMaterial: material, projectId } = message.payload;
    if (!start || !end || start > end) return;
    this.patchCardMessage(msgId, { reconSummaryText: '正在统计...' });
    try {
      const summary = await api.reconciliationSummary(projectId, start, end, material || '');
      this.patchCardMessage(msgId, {
        reconSummaryText: `${summary.projectName || '当前项目'} · ${material || '全部材料'} · 送货单 ${summary.deliveries} 张 · 对账金额 ${summary.salesAmount != null ? money(summary.salesAmount) : '-'}`
      });
    } catch (error) {
      this.patchCardMessage(msgId, { reconSummaryText: '汇总加载失败，请重试' });
    }
  },

  async downloadReconciliation(event) {
    const msgId = event.currentTarget.dataset.msgId;
    const message = this.findCardMessage(msgId);
    if (!message || this.data.busy) return;
    const payload = message.payload;
    if (payload.reconStart && payload.reconEnd && payload.reconStart > payload.reconEnd) {
      this.toast('开始日期不能晚于结束日期');
      return;
    }
    this.patchCardMessage(msgId, { reconDownloading: true });
    const filePath = `${wx.env.USER_DATA_PATH}/reconciliation-${Date.now()}.xlsx`;
    try {
      const savedPath = await downloadFile({
        url: api.reconciliationExportUrl(payload.projectId, payload.reconStart, payload.reconEnd, payload.reconMaterial || ''),
        filePath
      });
      this.patchCardMessage(msgId, { reconDownloading: false });
      wx.openDocument({
        filePath: savedPath,
        fileType: 'xlsx',
        showMenu: true,
        fail: () => this.toast('对账单已下载，可在文件中查看')
      });
    } catch (error) {
      this.patchCardMessage(msgId, { reconDownloading: false });
      this.toast((error && error.message) || '对账单导出失败，请稍后重试');
    }
  },

  // 局部更新某条消息的卡片 payload（消息列表 setData 路径定位）
  patchCardMessage(msgId, patch) {
    const messages = (this.data.messages || []).map((message) => {
      if (message.id !== msgId) return message;
      return { ...message, payload: { ...message.payload, ...patch } };
    });
    this.setData({ messages });
  },

  /* ---------------- 市场行情 ---------------- */
  setMarketTab(event) {
    const tab = event.currentTarget.dataset.tab;
    if (tab === this.data.marketTab) return;
    this.setData({ marketTab: tab });
    if (tab === 'compare') {
      this.loadMarketCompare();
    } else {
      this.loadMarketQuotes();
    }
  },

  // Tab1：加载批次对比（依赖当前批次与筛选城市/产地）
  async loadMarketCompare() {
    const { currentBatch, marketCity, marketCompareOrigin } = this.data;
    if (!currentBatch) {
      this.setData({ marketCompare: null });
      return;
    }
    this.setData({ marketLoading: true });
    try {
      const [compare, mappings] = await Promise.all([
        api.marketCompare(currentBatch.id, marketCity, marketCompareOrigin || undefined),
        api.marketMappings(currentBatch.id)
      ]);
      this.setData({
        marketCompare: this.decorateMarketCompare(compare),
        marketMappingList: mappings
      });
    } catch (error) {
      this.toast(error.message || '对比数据加载失败');
    } finally {
      this.setData({ marketLoading: false });
    }
  },

  // Tab2：加载全量行情（筛选 + 搜索）
  async loadMarketQuotes() {
    const { marketCategory, marketProduct, marketQuoteCity, marketQuoteOrigin, marketQuery, marketMeta } = this.data;
    this.setData({ marketLoading: true });
    try {
      // 默认城市筛选：进入 Tab2 未选城市时自动用「杭州」作默认，避免全量压垮渲染
      const effectiveCity = marketQuoteCity || '杭州';
      const cityChanged = marketQuoteCity !== effectiveCity;
      const quotes = await api.marketQuotes({
        category: marketCategory || undefined,
        productCode: marketProduct || undefined,
        city: effectiveCity,
        origin: marketQuoteOrigin || undefined,
        keyword: marketQuery || undefined
      });
      // 限制单次渲染条数，超出截断并提示
      const capped = quotes.slice(0, 600);
      this.setData({
        marketQuoteGroups: this.groupMarketQuotes(capped, effectiveCity),
        ...(cityChanged ? { marketQuoteCity: effectiveCity, marketQuoteCityLabel: effectiveCity } : {})
      });
      if (quotes.length > capped.length) {
        this.toast('行情数据较多，已展示前 600 条，请用筛选缩小范围');
      }
      if (!marketMeta) {
        api.marketMeta().then((meta) => {
          const latestDate = meta && meta.latestDate ? String(meta.latestDate).slice(0, 10) : '';
          const cities = (meta && meta.cities) || [];
          const origins = (meta && meta.origins) || [];
          const categories = ['全部分类'].concat((meta && meta.categories) || []);
          this.setData({
            marketMeta: meta,
            marketLatestDate: latestDate,
            marketCities: cities,
            marketOrigins: origins,
            marketCityIndex: Math.max(cities.indexOf(this.data.marketCity), 0),
            marketCompareOriginIndex: Math.max(origins.indexOf(this.data.marketCompareOrigin), 0),
            marketCategories: categories,
            marketProductOptions: this.buildProductOptions(this.data.marketCategory)
          });
        }).catch(() => {});
      }
    } catch (error) {
      this.toast(error.message || '行情加载失败');
    } finally {
      this.setData({ marketLoading: false });
    }
  },

  refreshMarket() {
    if (this.data.marketTab === 'compare') {
      this.loadMarketCompare();
    } else {
      this.loadMarketQuotes();
    }
  },

  // 进入市场行情 Tab 时，若还没加载过则加载
  ensureMarketLoaded() {
    if (this.data.marketMeta) return;
    api.marketMeta().then((meta) => {
      const latestDate = meta && meta.latestDate ? String(meta.latestDate).slice(0, 10) : '';
      const cities = (meta && meta.cities) || [];
      const origins = (meta && meta.origins) || [];
      const categories = ['全部分类'].concat((meta && meta.categories) || []);
      this.setData({
        marketMeta: meta,
        marketLatestDate: latestDate,
        marketCities: cities,
        marketOrigins: origins,
        marketCityIndex: Math.max(cities.indexOf(this.data.marketCity), 0),
        marketCompareOriginIndex: Math.max(origins.indexOf(this.data.marketCompareOrigin), 0),
        marketCategories: categories,
        marketCategoryIndex: 0,
        marketProductOptions: this.buildProductOptions(''),
        marketQuoteCityIndex: 0,
        marketQuoteOriginIndex: 0
      });
    }).catch(() => {});
    if (this.data.marketTab === 'compare') {
      this.loadMarketCompare();
    } else {
      this.loadMarketQuotes();
    }
  },

  // 装饰对比数据（KPI + 材料行）
  decorateMarketCompare(compare) {
    if (!compare) return null;
    const diffTone = (value) => (value == null || value === 0) ? '' : (value > 0 ? 'tone-red' : 'tone-green');
    const rateText = (value) => (value == null) ? '—' : `${value > 0 ? '+' : ''}${value}%`;
    const materials = (compare.materials || []).map((row) => {
      const isMatched = row.matchStatus === 'auto' || row.matchStatus === 'manual';
      const business = Number(row.businessPrice || 0);
      const market = row.marketPrice == null ? null : Number(row.marketPrice);
      const diff = row.diff == null ? null : Number(row.diff);
      const diffRate = row.diffRate == null ? null : Number(row.diffRate);
      // 匹配行情展示：产品名 + 规格（去产品名前缀）
      let marketLabel = '';
      if (row.marketSpecName) {
        marketLabel = row.marketSpecName.startsWith(row.productName || '____')
          ? row.marketSpecName.slice((row.productName || '').length)
          : row.marketSpecName;
      }
      return {
        materialId: row.materialId,
        materialName: row.materialName,
        materialSpec: row.materialSpec,
        unit: row.unit || '',
        qtyText: String(row.quantity != null ? row.quantity : '—'),
        businessText: compactMoney(business),
        marketText: market == null ? '—' : compactMoney(market),
        marketPrice: market,
        diffText: diff == null ? '—' : (diff > 0 ? '+' : '') + compactMoney(diff),
        diffRateText: rateText(diffRate),
        diffTone: diffTone(diff),
        isMatched,
        matchStatusLabel: row.matchStatus === 'manual' ? '人工绑定'
          : row.matchStatus === 'ai_fallback' ? 'AI 兜底' : '自动匹配',
        matchMethod: row.matchMethod,
        marketLabel,
        origin: row.origin || '',
        quoteDateText: row.quoteDate ? String(row.quoteDate).slice(0, 10) : '',
        city: row.city || this.data.marketCity,
        // 锁定行情（送货单确认时的取价依据）与最新行情（quoteDate/marketPrice）独立展示，不混格
        lockedDateText: row.lockedDate ? String(row.lockedDate).slice(0, 10) : '',
        lockedPriceText: row.lockedPrice != null ? compactMoney(Number(row.lockedPrice)) : '',
        lockedOriginText: row.lockedOrigin || '',
        // 人工锁定行情价（行情中心改价）行标记
        isManualPrice: row.matchMethod === 'manual_price'
      };
    });
    return {
      totalMaterials: compare.totalMaterials,
      matchedMaterials: compare.matchedMaterials,
      unmatchedMaterials: Math.max(compare.totalMaterials - compare.matchedMaterials, 0),
      businessText: compactMoney(compare.totalBusinessAmount),
      marketText: compactMoney(compare.totalMarketAmount),
      diffText: `${compare.totalDiff >= 0 ? '+' : ''}${compactMoney(Math.abs(compare.totalDiff))}`,
      diffTone: diffTone(compare.totalDiff),
      avgRateText: rateText(compare.avgDiffRate),
      materials
    };
  },

  // 全量行情分组：按产品 + 城市分组
  groupMarketQuotes(quotes, city) {
    if (!quotes || !quotes.length) return [];
    const groups = [];
    const index = {};
    quotes.forEach((row) => {
      const key = `${row.productCode}|${row.city || city || ''}`;
      if (!index[key]) {
        const group = {
          productCode: row.productCode,
          productName: row.productName,
          city: row.city || '全部城市',
          count: 0,
          rows: []
        };
        index[key] = group;
        groups.push(group);
      }
      const specTokens = (row.specName || '').split(/\s+/);
      const specLabel = row.spec || (specTokens.length > 1 ? specTokens.slice(1).join(' ') : row.specName || '');
      index[key].rows.push({
        id: row.id,
        specLabel,
        grade: row.grade || '',
        origin: row.origin || '',
        priceText: row.price == null ? '—' : String(row.price).replace(/\B(?=(\d{3})+(?!\d))/g, ','),
        change: row.priceChange == null ? 0 : Number(row.priceChange),
        changeTone: row.priceChange == null || Number(row.priceChange) === 0 ? '' : (Number(row.priceChange) > 0 ? 'tone-red' : 'tone-green')
      });
      index[key].count += 1;
    });
    return groups;
  },

  // 城市选择（对比城市与行情城市共用，picker 原生下拉，无数量限制）
  onMarketCityChange(event) {
    const index = Number(event.detail.value);
    const city = this.data.marketCities[index];
    if (!city) return;
    this.setData({ marketCity: city, marketCityIndex: index });
    this.loadMarketCompare();
  },

  // 对比产地选择：指定产地对对比，无则后端回退其他产地/均价
  onMarketCompareOriginChange(event) {
    const index = Number(event.detail.value);
    const origin = index === 0 ? '' : this.data.marketOrigins[index];
    this.setData({
      marketCompareOrigin: origin,
      marketCompareOriginIndex: index,
      marketCompareOriginLabel: origin || '默认产地'
    });
    this.loadMarketCompare();
  },

  onMarketCategoryChange(event) {
    const index = Number(event.detail.value);
    const category = index === 0 ? '' : this.data.marketCategories[index];
    this.setData({
      marketCategory: category,
      marketCategoryIndex: index,
      marketCategoryLabel: category || '全部分类',
      marketProduct: '',
      marketProductIndex: 0,
      marketProductLabel: '全部产品',
      marketProductOptions: this.buildProductOptions(category)
    });
    this.loadMarketQuotes();
  },

  onMarketProductChange(event) {
    const index = Number(event.detail.value);
    const option = this.data.marketProductOptions[index];
    this.setData({
      marketProduct: option ? option.value : '',
      marketProductIndex: index,
      marketProductLabel: option ? option.label : '全部产品'
    });
    this.loadMarketQuotes();
  },

  onMarketQuoteCityChange(event) {
    const index = Number(event.detail.value);
    const city = index === 0 ? '' : this.data.marketCities[index];
    this.setData({
      marketQuoteCity: city,
      marketQuoteCityIndex: index,
      marketQuoteCityLabel: city || '全部城市'
    });
    this.loadMarketQuotes();
  },

  onMarketQuoteOriginChange(event) {
    const index = Number(event.detail.value);
    const origin = index === 0 ? '' : this.data.marketOrigins[index];
    this.setData({
      marketQuoteOrigin: origin,
      marketQuoteOriginIndex: index,
      marketQuoteOriginLabel: origin || '全部产地'
    });
    this.loadMarketQuotes();
  },

  // 产品下拉选项（全部产品 + 各产品，按分类过滤）
  buildProductOptions(category) {
    const products = ((this.data.marketMeta && this.data.marketMeta.products) || [])
      .filter((p) => !category || p.category === category);
    return [{ value: '', label: '全部产品' }].concat(
      products.map((p) => ({ value: p.code, label: p.name }))
    );
  },

  updateMarketQuery(event) {
    this.setData({ marketQuery: event.detail.value });
    this.loadMarketQuotes();
  },

  clearMarketQuery() {
    this.setData({ marketQuery: '' });
    this.loadMarketQuotes();
  },

  rerunMarketMatch() {
    const { currentBatch } = this.data;
    if (!currentBatch || this.data.marketLoading) return;
    this.setData({ marketLoading: true });
    api.marketRunMatch(currentBatch.id)
      .then(() => this.loadMarketCompare())
      .catch((error) => this.toast(error.message || '重新匹配失败'))
      .finally(() => this.setData({ marketLoading: false }));
  },

  // 打开绑定浮层
  openMarketBind(event) {
    const materialId = event.currentTarget.dataset.materialId;
    const material = (this.data.marketCompare && this.data.marketCompare.materials)
      .find((m) => m.materialId === materialId);
    if (!material) return;
    const mapping = (this.data.marketMappingList || []).find((m) => m.materialId === materialId);
    const metaProducts = (this.data.marketMeta && this.data.marketMeta.products) || [];
    const products = metaProducts.map((p) => ({ value: p.code, label: p.name }));
    const bindProduct = (mapping && mapping.productCode) || '';
    const productIndex = bindProduct ? products.findIndex((p) => p.value === bindProduct) : -1;
    const bindProductLabel = productIndex >= 0 ? products[productIndex].label : '';
    const bindSpec = (mapping && mapping.specName) || '';
    this.setData({
      marketBindVisible: true,
      marketBindMaterial: material,
      marketProducts: products,
      marketBindProduct: bindProduct,
      marketBindProductLabel: bindProductLabel,
      marketBindProductIndex: productIndex,
      marketBindSpec: bindSpec,
      marketBindSpecLabel: bindSpec,
      marketBindSpecs: [],
      marketBindSpecIndex: -1,
      // 预填当前锁定行情价（人工改价输入框）
      marketBindPrice: mapping && mapping.quotePrice != null ? String(mapping.quotePrice) : ''
    });
    // 拉取当前产品的规格候选
    if (bindProduct) {
      api.marketProductSpecs(bindProduct)
        .then((specs) => this.setData({ marketBindSpecs: (specs || []).map((option) => ({
          value: option.spec,
          // 规格候选带来源渠道（mysteel 权威价 / gtgqw 爬取价），绑定时用来区分
          label: option.sources && option.sources.length
            ? `${option.spec} · ${option.sources.join(' / ')}`
            : option.spec
        })) }))
        .catch(() => this.setData({ marketBindSpecs: [] }));
    }
  },

  onBindProductChange(event) {
    const index = Number(event.detail.value);
    const product = this.data.marketProducts[index];
    if (!product) return;
    this.setData({
      marketBindProduct: product.value,
      marketBindProductLabel: product.label,
      marketBindProductIndex: index,
      marketBindSpec: '',
      marketBindSpecLabel: '',
      marketBindSpecIndex: -1,
      marketBindSpecs: []
    });
    // 从后端拉取该产品的规格候选
    api.marketProductSpecs(product.value)
      .then((specs) => this.setData({ marketBindSpecs: (specs || []).map((option) => ({
          value: option.spec,
          // 规格候选带来源渠道（mysteel 权威价 / gtgqw 爬取价），绑定时用来区分
          label: option.sources && option.sources.length
            ? `${option.spec} · ${option.sources.join(' / ')}`
            : option.spec
        })) }))
      .catch(() => this.setData({ marketBindSpecs: [] }));
  },

  onBindSpecChange(event) {
    const index = Number(event.detail.value);
    const spec = this.data.marketBindSpecs[index];
    if (spec) this.setData({ marketBindSpec: spec.value, marketBindSpecLabel: spec.label, marketBindSpecIndex: index });
  },

  // 人工锁定行情价输入（选填；留空按行情库取价）
  onBindPriceInput(event) {
    this.setData({ marketBindPrice: event.detail.value });
  },

  closeMarketBind() {
    this.setData({ marketBindVisible: false, marketBindMaterial: null, marketBindPrice: '' });
  },

  confirmMarketBind() {
    const { marketBindMaterial, marketBindProduct, marketBindSpec, marketBindPrice, marketCity, currentBatch } = this.data;
    if (!marketBindMaterial || !marketBindProduct || !marketBindSpec) return;
    const priceText = String(marketBindPrice || '').trim();
    if (priceText && !(Number(priceText) > 0)) {
      this.toast('锁定行情价必须大于 0');
      return;
    }
    api.marketManualMapping({
      materialId: marketBindMaterial.materialId,
      productCode: marketBindProduct,
      specName: marketBindSpec,
      city: marketCity,
      status: 'manual',
      quotePrice: priceText ? Number(priceText) : undefined
    }).then(() => {
      this.setData({ marketBindVisible: false, marketBindMaterial: null, marketBindPrice: '' });
      this.toast(priceText ? '已按人工价锁定，销售单价与台账已同步' : '绑定已保存');
      this.loadMarketCompare();
    }).catch((error) => this.toast(error.message || '保存失败'));
  }
};

Page(Object.assign(pageObject, actions));
