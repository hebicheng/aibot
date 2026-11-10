// 主页面静态配置与纯函数工具。
const { DOC_TYPES } = require('../../utils/domain');

const NAV_ITEMS = [
  {
    key: 'workspace',
    label: '智能助理',
    desc: 'AI 问答 · OCR 识别 · 采购助手',
    icon: 'robot',
    tone: 'blue',
    badgeTone: 'orange',
    iconSrc: 'data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%2024%2024%22%3E%3Crect%20x%3D%225%22%20y%3D%228%22%20width%3D%2214%22%20height%3D%2210%22%20rx%3D%223%22%20fill%3D%22%232f66f6%22%2F%3E%3Ccircle%20cx%3D%2212%22%20cy%3D%224.5%22%20r%3D%221.6%22%20fill%3D%22%232f66f6%22%2F%3E%3Crect%20x%3D%2211.2%22%20y%3D%224.5%22%20width%3D%221.6%22%20height%3D%224%22%20rx%3D%220.8%22%20fill%3D%22%232f66f6%22%2F%3E%3Ccircle%20cx%3D%229%22%20cy%3D%2213%22%20r%3D%221.2%22%20fill%3D%22%23fff%22%2F%3E%3Ccircle%20cx%3D%2215%22%20cy%3D%2213%22%20r%3D%221.2%22%20fill%3D%22%23fff%22%2F%3E%3C%2Fsvg%3E'
  },
  {
    key: 'projects',
    label: '项目批次',
    desc: '项目管理 · 批次跟踪',
    icon: 'layers',
    tone: 'blue',
    badgeTone: 'blue',
    iconSrc: 'data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%2024%2024%22%3E%3Crect%20x%3D%225%22%20y%3D%225%22%20width%3D%2214%22%20height%3D%226%22%20rx%3D%221.6%22%20fill%3D%22%232f66f6%22%2F%3E%3Crect%20x%3D%225%22%20y%3D%2213%22%20width%3D%2214%22%20height%3D%226%22%20rx%3D%221.6%22%20fill%3D%22%232f66f6%22%20opacity%3D%220.78%22%2F%3E%3C%2Fsvg%3E'
  },
  {
    key: 'data',
    label: '业务数据',
    desc: '订单 · 合同 · 送货 · 收付款',
    icon: 'cluster',
    tone: 'green',
    badgeTone: 'blue',
    iconSrc: 'data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%2024%2024%22%3E%3Crect%20x%3D%225%22%20y%3D%2212%22%20width%3D%223.5%22%20height%3D%227%22%20rx%3D%220.8%22%20fill%3D%22%2319a974%22%2F%3E%3Crect%20x%3D%2210.25%22%20y%3D%227%22%20width%3D%223.5%22%20height%3D%2212%22%20rx%3D%220.8%22%20fill%3D%22%2319a974%22%2F%3E%3Crect%20x%3D%2215.5%22%20y%3D%2210%22%20width%3D%223.5%22%20height%3D%229%22%20rx%3D%220.8%22%20fill%3D%22%2319a974%22%2F%3E%3C%2Fsvg%3E'
  },
  {
    key: 'ledger',
    label: '对账台账',
    desc: '对账管理 · 台账生成 · 发票管理',
    icon: 'records',
    tone: 'orange',
    badgeTone: 'orange',
    iconSrc: 'data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%2024%2024%22%3E%3Crect%20x%3D%225%22%20y%3D%224%22%20width%3D%2214%22%20height%3D%2216%22%20rx%3D%222%22%20fill%3D%22%23f28a3a%22%2F%3E%3Crect%20x%3D%228%22%20y%3D%228%22%20width%3D%228%22%20height%3D%221.5%22%20rx%3D%220.75%22%20fill%3D%22%23fff%22%2F%3E%3Crect%20x%3D%228%22%20y%3D%2212%22%20width%3D%226%22%20height%3D%221.5%22%20rx%3D%220.75%22%20fill%3D%22%23fff%22%2F%3E%3C%2Fsvg%3E'
  },
  {
    key: 'market',
    label: '市场行情',
    desc: '价格对比 · 行情查询',
    icon: 'trend',
    tone: 'cyan',
    badgeTone: 'cyan',
    iconSrc: 'data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%2024%2024%22%3E%3Cpath%20d%3D%22M4%2018l5-6%204%203%207-9%22%20stroke%3D%22%231aa6b8%22%20stroke-width%3D%222.4%22%20fill%3D%22none%22%20stroke-linecap%3D%22round%22%20stroke-linejoin%3D%22round%22%2F%3E%3Ccircle%20cx%3D%2217%22%20cy%3D%227%22%20r%3D%221.8%22%20fill%3D%22%231aa6b8%22%2F%3E%3Ccircle%20cx%3D%2220%22%20cy%3D%224%22%20r%3D%221.3%22%20fill%3D%22%231aa6b8%22%2F%3E%3C%2Fsvg%3E'
  },
  {
    key: 'profile',
    label: '个人中心',
    desc: '账号管理 · 偏好设置 · 安全中心',
    icon: 'user',
    tone: 'purple',
    badgeTone: '',
    iconSrc: 'data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%2024%2024%22%3E%3Ccircle%20cx%3D%2212%22%20cy%3D%228.5%22%20r%3D%223.3%22%20fill%3D%22%238b5cf6%22%2F%3E%3Cpath%20d%3D%22M5.5%2019c1.6-3.4%204.4-5.2%206.5-5.2s4.9%201.8%206.5%205.2%22%20stroke%3D%22%238b5cf6%22%20stroke-width%3D%222.4%22%20fill%3D%22none%22%20stroke-linecap%3D%22round%22%2F%3E%3C%2Fsvg%3E'
  }
];

const DRAWER_ICON_SRC = {
  settings: 'data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%2024%2024%22%3E%3Ccircle%20cx%3D%2212%22%20cy%3D%2212%22%20r%3D%222.8%22%20fill%3D%22%2372809a%22%2F%3E%3Cpath%20d%3D%22M12%203v2.2M12%2018.8V21M3%2012h2.2M18.8%2012H21M5.7%205.7l1.6%201.6M16.7%2016.7l1.6%201.6M18.3%205.7l-1.6%201.6M7.7%2016.7l-1.6%201.6%22%20stroke%3D%22%2372809a%22%20stroke-width%3D%221.9%22%20stroke-linecap%3D%22round%22%20fill%3D%22none%22%2F%3E%3C%2Fsvg%3E',
  building: 'data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%2024%2024%22%3E%3Crect%20x%3D%226%22%20y%3D%225%22%20width%3D%2212%22%20height%3D%2214%22%20rx%3D%221.6%22%20fill%3D%22%232f66f6%22%2F%3E%3Crect%20x%3D%228.5%22%20y%3D%228.2%22%20width%3D%222.3%22%20height%3D%222.3%22%20rx%3D%220.5%22%20fill%3D%22%23fff%22%2F%3E%3Crect%20x%3D%2213.2%22%20y%3D%228.2%22%20width%3D%222.3%22%20height%3D%222.3%22%20rx%3D%220.5%22%20fill%3D%22%23fff%22%2F%3E%3Crect%20x%3D%228.5%22%20y%3D%2212.3%22%20width%3D%222.3%22%20height%3D%222.3%22%20rx%3D%220.5%22%20fill%3D%22%23fff%22%2F%3E%3Crect%20x%3D%2213.2%22%20y%3D%2212.3%22%20width%3D%222.3%22%20height%3D%222.3%22%20rx%3D%220.5%22%20fill%3D%22%23fff%22%2F%3E%3C%2Fsvg%3E',
  switch: 'data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%2024%2024%22%3E%3Cpath%20d%3D%22M4%208h11M15%208l-3-3M15%208l-3%203M20%2016H9M9%2016l3%203M9%2016l3-3%22%20stroke%3D%22%23666%22%20stroke-width%3D%221.8%22%20stroke-linecap%3D%22round%22%20stroke-linejoin%3D%22round%22%2F%3E%3C%2Fsvg%3E'
};

const PAGE_META = {
  workspace: { title: '智能助理', sub: 'AI 生成内容仅供参考，请核实关键信息' },
  projects: { title: '项目与批次', sub: '按项目查看进度，在批次中管理业务' },
  data: { title: '数据中心', sub: '查看、修改已入库的业务数据，并核对对应原始资料。' },
  ledger: { title: '项目批次对账台账', sub: '按材料和运输分别核对采购、付款、发票及销售回款。' },
  market: { title: '市场行情', sub: '每日市场行情 · 与批次采购价对比，掌握价差' },
  profile: { title: '个人中心', sub: '企业身份与账号安全' }
};

const EDITABLE_DOC_TYPES = DOC_TYPES.filter((item) => item.value);

// 类型筛选对齐 web DataCenterPage（请款/销项发票/结算资料/原始凭证全覆盖）
const DATA_FILTERS = [
  { label: '全部', value: '', types: [] },
  { label: '采购单', value: 'supply_order', types: ['supply_order'] },
  { label: '配送·送货', value: 'delivery_note', types: ['delivery_note'] },
  { label: '运输费用', value: 'transport_note', types: ['transport_note'] },
  { label: '请款', value: 'payment_request', types: ['payment_request'] },
  { label: '付款', value: 'payment', types: ['payment'] },
  { label: '收款', value: 'receipt', types: ['receipt'] },
  { label: '进项发票', value: 'invoice', types: ['invoice', 'purchase_invoice'] },
  { label: '销项发票', value: 'sales_invoice', types: ['sales_invoice'] },
  { label: '结算资料', value: 'sales_settlement', types: ['sales_settlement', 'settlement_unknown'] },
  { label: '原始凭证', value: 'evidence_only', types: ['evidence_only'] }
];

const DATA_SCOPE_FILTERS = [
  { label: '当前批次', value: 'batch' },
  { label: '整个项目', value: 'project' }
];

const DATA_STATUS_FILTERS = [
  { label: '全部状态', value: 'all' },
  { label: '待确认', value: 'pending' },
  { label: '有差异', value: 'discrepancy' },
  { label: '已入库', value: 'confirmed' }
];

const LEDGER_SCOPE_FILTERS = [
  { label: '当前项目', value: 'project' },
  { label: '全部批次', value: 'all' }
];

// 对账台账 - 按业务分类筛选（全部 / 材料 / 运输），对齐设计稿筛选条
const LEDGER_KIND_FILTERS = [
  { label: '全部', value: '' },
  { label: '材料', value: 'material' },
  { label: '运输', value: 'transport' }
];

// 聊天页快捷操作胶囊（豆包风格）：点击后引导上传对应类型资料。
const QUICK_ACTIONS = [
  { key: 'supply', label: '采购单', icon: 'doc', hint: '请按采购单识别这份资料' },
  { key: 'delivery', label: '送货单', icon: 'truck', hint: '请按送货单识别这份资料' },
  { key: 'payment', label: '付款单', icon: 'cash', hint: '请按付款记录识别这份资料，并标明付款单位与付款日期' },
  { key: 'invoice', label: '发票', icon: 'invoice', hint: '请按进项发票识别这份资料，并核对税率与不含税金额' }
];

function today() {
  const now = new Date();
  const pad = (part) => String(part).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

function fileName(path, fallback) {
  const clean = String(path || '').split('?')[0];
  return clean.split('/').pop() || fallback || '业务资料';
}

function buildBatchName(dateValue, suffix) {
  const raw = dateValue || today();
  const label = suffix || '批次';
  return `${raw} ${label}`;
}

function parseAmount(value) {
  const parsed = Number(String(value || '').replace(/[^\d.-]/g, ''));
  return Number.isFinite(parsed) ? parsed : 0;
}

// 空值语义对齐 web：空串/非法输入 → null（表示「不修改」，禁止用 0 覆盖原值）
function parseAmountOrNull(value) {
  const raw = String(value == null ? '' : value).trim();
  if (!raw) return null;
  const parsed = Number(raw.replace(/[^\d.-]/g, ''));
  return Number.isFinite(parsed) ? parsed : null;
}

module.exports = {
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
  buildBatchName,
  fileName,
  parseAmount,
  parseAmountOrNull
};
