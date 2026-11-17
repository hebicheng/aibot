const { request, uploadFile } = require('./request');

function query(path, params) {
  const parts = Object.keys(params || {})
    .filter((key) => params[key] !== undefined && params[key] !== null && params[key] !== '')
    .map((key) => `${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`);
  return path + (parts.length ? `?${parts.join('&')}` : '');
}

module.exports = {
  me: () => request({ url: '/api/me' }),

  // 积分与消费明细
  billingCredits: () => request({ url: '/api/billing/credits' }),
  billingUsageLogs: (page = 0, size = 50) =>
    request({ url: query('/api/billing/usage-logs', { page, size }) }),

  updateMe: (companyName) => request({
    url: '/api/me',
    method: 'PUT',
    data: { companyName }
  }),
  // 用户偏好（user_settings JSON，深合并 patch）：每周对账提醒 reconciliation.{enabled,weekday,hour}
  userSettings: () => request({ url: '/api/me/settings' }),
  updateUserSettings: (patch) => request({
    url: '/api/me/settings',
    method: 'PUT',
    data: patch
  }),
  logout: () => request({ url: '/api/auth/logout', method: 'POST' }),

  projects: (keyword) => request({
    url: query('/api/projects', { query: keyword })
  }),
  createProject: (data) => request({
    url: '/api/projects',
    method: 'POST',
    data
  }),
  // 编辑项目（名称/客户），改名零回写（按 id 关联实时取值）
  updateProject: (projectId, data) => request({
    url: `/api/projects/${projectId}`,
    method: 'PUT',
    data
  }),
  // 编辑批次（业务日期/材料摘要）
  updateBatch: (batchId, data) => request({
    url: `/api/batches/${batchId}`,
    method: 'PUT',
    data
  }),
  // 台账销售金额明细（网价锁定值，只读；人工改价统一在行情中心操作）
  salesDetail: (batchId) => request({
    url: query('/api/documents/sales-detail', { batchId })
  }),
  deleteProject: (projectId) => request({
    url: `/api/projects/${projectId}`,
    method: 'DELETE',
    silent: true
  }),
  batches: (projectId) => request({
    url: query('/api/batches', { projectId })
  }),
  createBatch: (data) => request({
    url: '/api/batches',
    method: 'POST',
    data
  }),
  deleteBatch: (batchId) => request({
    url: `/api/batches/${batchId}`,
    method: 'DELETE',
    silent: true
  }),

  chatMessages: (projectId, batchId) => request({
    url: query('/api/chat/messages', { projectId, batchId })
  }),
  sendChat: (text, projectId, batchId) => request({
    url: '/api/chat/send',
    method: 'POST',
    data: { text, projectId, batchId }
  }),
  uploadDocument: (filePath, projectId, batchId, guidance) => uploadFile({
    url: '/api/chat/upload',
    filePath,
    name: 'file',
    formData: {
      projectId,
      batchId,
      ...(guidance ? { guidance } : {})
    }
  }),

  documents: (projectId, batchId) => request({
    url: query('/api/documents', { projectId, batchId })
  }),
  businessData: (projectId, batchId) => request({
    url: query('/api/documents/data', { projectId, batchId })
  }),
  documentDetail: (documentId) => request({
    url: `/api/documents/${documentId}`
  }),
  confirmDocument: (documentId) => request({
    url: `/api/documents/${documentId}/confirm`,
    method: 'POST'
  }),
  editDocument: (documentId, edits) => request({
    url: `/api/documents/${documentId}`,
    method: 'PUT',
    data: edits
  }),
  duplicateDecision: (documentId, decision) => request({
    url: `/api/documents/${documentId}/duplicate-decision`,
    method: 'POST',
    data: { decision }
  }),
  // 追问续接：针对待澄清资料补充答案（复用已保存的原始附件，与 web 同接口）
  clarifyDocument: (documentId, answer) => request({
    url: '/api/chat/clarify',
    method: 'POST',
    data: { documentId, answer }
  }),
  // 对账单导出：实时汇总（按送货单日期范围）+ xlsx 下载；material 为空 = 全部材料
  reconciliationMaterials: (projectId) => request({
    url: query('/api/reports/reconciliation/materials', { projectId })
  }),
  reconciliationSummary: (projectId, start, end, material) => request({
    url: query('/api/reports/reconciliation/summary', { projectId, start, end, material })
  }),
  reconciliationExportUrl: (projectId, start, end, material) =>
    query('/api/reports/reconciliation/export', { projectId, start, end, material }),
  documentAccessUrl: (documentId) => request({
    url: `/api/documents/${documentId}/access-url`
  }),
  ledger: (projectId, batchId) => request({
    url: query('/api/documents/ledger', { projectId, batchId })
  }),
  // 台账表头定义（后端 LedgerTableSchema 单一来源下发，三层结构）
  ledgerColumns: () => request({ url: '/api/reports/ledger/columns' }),
  updateLedgerBatch: (batchId, edits) => request({
    url: `/api/documents/ledger/batch/${batchId}`,
    method: 'PUT',
    data: edits
  }),

  // ---------- 市场行情 ----------
  marketMeta: () => request({ url: '/api/market/meta' }),
  marketQuotes: (params) => request({
    url: query('/api/market/quotes', params)
  }),
  marketCompare: (batchId, city, origin) => request({
    url: query('/api/market/compare', { batchId, city, origin })
  }),
  marketMappings: (batchId) => request({
    url: query('/api/market/mappings', { batchId })
  }),
  marketManualMapping: (payload) => request({
    url: '/api/market/mappings',
    method: 'PUT',
    data: payload
  }),
  marketRunMatch: (batchId) => request({
    url: query('/api/market/match/run', { batchId }),
    method: 'POST'
  }),
  marketProductSpecs: (productCode) => request({
    url: `/api/market/products/${productCode}/specs`
  })
};
