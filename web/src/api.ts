const API_BASE = import.meta.env.VITE_API_BASE || '';

type ApiEnvelope<T> = {
  success: boolean;
  data: T;
  message?: string;
};

export type ApiProject = {
  id: string;
  name: string;
  customerName: string;
  status: string;
  progress: number;
  /** 采购合同总额（与台账同源，含采购运费成本） */
  purchaseAmount: number;
  /** 总付款 */
  paidAmount: number;
  /** 总收票（进项发票） */
  invoiceAmount: number;
  /** 未付款 = 采购合同总额 − 总付款 */
  unpaidPurchase: number;
  /** 未收发票 = 总付款 − 总收票 */
  uninvoicedPurchase: number;
  /** 销售合同总额（与台账同源，含客户承担运费） */
  salesAmount: number;
  /** 总开票（销项发票） */
  salesInvoiceAmount: number;
  /** 总收款 */
  receivedAmount: number;
  /** 剩余应收 = max(销售合同总额 − 总收款, 0) */
  outstanding: number;
  /** 应收差额 = 销售合同总额 − 总收款，可为负（多收） */
  differenceAmount: number;
  /** 未开票 = 销售合同总额 − 总开票 */
  uninvoicedSales: number;
  profitAmount: number;
  marginRate: number;
  batchCount: number;
  /** 项目级业务默认口径（批次没单独设置的字段用它） */
  businessHints?: Record<string, Record<string, unknown>>;
};

export type ApiUserProfile = {
  id: string;
  nickname: string;
  avatarUrl?: string;
  companyName: string;
  roleName: string;
  /** 真实登录账号是否超级用户（切换身份后仍为 true） */
  superAdmin?: boolean;
  /** 正在以他人身份浏览时的超管本人信息 */
  actingAs?: { id: string; nickname: string } | null;
};

export type ApiAdminUser = {
  id: string;
  username?: string | null;
  nickname: string;
  companyName?: string | null;
  roleName?: string | null;
  projectCount: number;
  batchCount: number;
  documentCount: number;
  createdAt?: string;
  lastActiveAt?: string;
};

export type ApiLoginResponse = {
  token: string;
  expiresAt: string;
  user: ApiUserProfile;
};

export type ApiBatchHintScope = {
  supplyFreightPerTrip?: number | null;
  supplyFreightPerTon?: number | null;
  salesFreightPerTrip?: number | null;
  salesFreightPerTon?: number | null;
  pricingMode?: 'uplift' | 'margin' | null;
  upliftAmount?: number | null;
  marginRate?: number | null;
  rawText?: string | null;
  updatedAt?: string | null;
};

export type ApiBatch = {
  id: string;
  projectId: string;
  projectName: string;
  name: string;
  businessDate?: string;
  materialSummary?: string;
  status: string;
  progress: number;
  purchaseAmount: number;
  salesAmount: number;
  profitAmount: number;
  marginRate: number;
  remark?: string;
  businessHints?: Record<string, ApiBatchHintScope>;
  /** 是否已对账（人工勾选） */
  reconciled?: boolean;
  /** 已收款 = Σ 项目收款在本批次上的分摊 */
  receivedAmount?: number;
  receiptStatus?: ReceiptStatus;
};

export type ReceiptStatus = '未收款' | '部分收款' | '已收款';

/** 公司往来：本公司与供货商的采购 / 进项发票汇总 */
export type ApiCompanyTransactionOverview = {
  purchaseAmount: number;
  invoicedAmount: number;
  uninvoicedAmount: number;
  supplierCount: number;
  invoiceCount: number;
  pendingInvoiceCount: number;
  supplyCount: number;
  pendingSupplyCount: number;
  suppliers: Array<{
    supplierName: string;
    purchaseAmount: number;
    invoicedAmount: number;
    uninvoicedAmount: number;
    invoiceCount: number;
  }>;
  invoices: Array<{
    id: string;
    invoiceNo?: string | null;
    supplierName: string;
    invoiceDate?: string | null;
    amount: number;
    taxAmount?: number | null;
    taxRate?: string | null;
    status: string;
    confirmed: boolean;
    duplicatePending: boolean;
    projectId?: string | null;
    projectName?: string | null;
    batchId?: string | null;
    batchName?: string | null;
    fileName?: string | null;
    hasFile: boolean;
    /** manual=公司往来页手动上传 / assistant=智能助理中识别 */
    uploadSource: 'manual' | 'assistant';
    remark?: string | null;
  }>;
};

export type ApiReceiptAllocation = {
  batchId: string;
  batchName: string;
  material: string;
  amount: number;
};

export type ApiReceipt = {
  id: string;
  receiptDate: string;
  amount: number;
  payer?: string;
  remark?: string;
  /** manual=手动登记 / document=智能助理收款单 */
  source: 'manual' | 'document';
  allocations: ApiReceiptAllocation[];
};

export type ApiReceiptBatch = {
  batchId: string;
  batchName: string;
  businessDate?: string;
  material: string;
  salesAmount: number;
  receivedAmount: number;
  remainingAmount: number;
  receiptStatus: ReceiptStatus;
  reconciled: boolean;
};

export type ApiMaterialReceiptSummary = {
  material: string;
  salesAmount: number;
  receivedAmount: number;
  differenceAmount: number;
  batchCount: number;
};

export type ApiProjectReceiptOverview = {
  projectId: string;
  salesAmount: number;
  receivedAmount: number;
  differenceAmount: number;
  receipts: ApiReceipt[];
  batches: ApiReceiptBatch[];
  materials: ApiMaterialReceiptSummary[];
};

export type ApiReceiptSuggestion = {
  amount: number;
  allocations: ApiReceiptAllocation[];
  unallocatedAmount: number;
};

export type ReceiptSavePayload = {
  receiptDate: string;
  amount: number;
  payer?: string;
  remark?: string;
  /** amount 全部为空 = 后端按销售顺序自动分摊；全部有值 = 手动分摊（合计须等于收款金额） */
  allocations: Array<{ batchId: string; amount?: number | null }>;
};

export type ApiDocumentItem = {
  id: string;
  materialName: string;
  spec: string;
  origin?: string | null;
  packageQuantity?: number | null;
  packageUnit?: string;
  unitWeight?: number | null;
  totalWeight?: number | null;
  weightUnit?: string;
  pricingQuantity?: number | null;
  pricingUnit?: string;
  unit: string;
  quantity?: number | null;
  unitPrice?: number | null;
  netPrice?: number | null;
  // 采购单销售价（元/计价单位）：salesUnitPrice=最终取用值，Auto=按批次口径推算值，
  // Manual=true 表示人工指定过（口径变更不再跟随）
  salesUnitPrice?: number | null;
  salesUnitPriceAuto?: number | null;
  salesUnitPriceManual?: boolean;
  amount: number;
};

export type ApiLedgerHeaderCell = {
  label: string;
  rowSpan: number;
  colSpan: number;
  style: string;
  exportable: boolean;
};

export type CardAction = {
  id: string;
  actionKey: string;
  label: string;
  style: 'primary' | 'secondary';
  enabled: boolean;
};

export type DocumentCardPayload = {
  schemaVersion: string;
  cardType: 'document_review';
  cardId: string;
  status: 'needs_review' | 'queued' | 'applied' | 'duplicate_skipped' | 'deleted';
  missingFields?: string[];
  document: {
    id: string;
    docType: string;
    docTypeLabel: string;
    docCode?: string;
    supplierName?: string;
    docDate?: string;
    amount: number;
    confidence?: number;
    fileName?: string;
    source?: string;
    guidanceText?: string;
    // AI 识别的备注/用途（请款等卡片展示，选填可编辑）
    remark?: string;
    targetLedgerId?: string;
    targetDocumentId?: string;
    processingMode?: 'archive_only' | 'normal';
    detectedDocType?: string;
    detectedDocTypeLabel?: string;
    requestedDocType?: string;
    typeOverride?: boolean;
    taxRate?: string;
    expenseCategory?: 'material' | 'transport';
    invoiceDirection?: 'purchase' | 'sales' | 'unknown';
    netAmount?: number;
    taxAmount?: number;
    requiredReceiptDate?: string;
    // 当前业务类型的必填字段（后端下发，与后端校验规则保持一致）
    requiredFields?: string[];
    // 发票双方名称，用于核对与纠正进/销项方向
    sellerName?: string;
    buyerName?: string;
    // 送货单（销售侧发货单）：客户名称、单据上标注的运输费用与承运单位（选填，人工补录）
    customerName?: string;
    transportFee?: number | null;
    // 销售运费（客户承担，进台账销售总金额）；采购运费=transportFee（进运输费用行）
    salesFreight?: number | null;
    carrierName?: string;
    // 送货单销售定价（2026-09-21）：pricingNote 为后端生成的定价说明，
    // pricingMode=uplift（网价+X）或 margin（网价/(1-X%)）
    pricingMode?: 'uplift' | 'margin' | null;
    upliftAmount?: number | null;
    marginRate?: number | null;
    pricingNote?: string;
    items: ApiDocumentItem[];
  };
  amountDiscrepancy?: {
    sourceAmount: number;
    itemTotal: number;
    difference: number;
    decision?: 'sync_items' | 'keep_source' | '';
  };
  actions: CardAction[];
};

export type DuplicateCardPayload = {
  schemaVersion: string;
  cardType: 'duplicate_review';
  cardId: string;
  status: 'needs_review' | 'duplicate_skipped';
  reason: 'same_hash' | 'same_filename' | 'same_business';
  document: DocumentCardPayload['document'];
  existingDocument?: {
    id: string;
    docTypeLabel: string;
    docCode?: string;
    fileName?: string;
    supplierName?: string;
    docDate?: string;
    amount: number;
  };
  actions: CardAction[];
};

export type ClarificationCardPayload = {
  schemaVersion?: string;
  cardType?: 'clarification';
  cardId?: string;
  status?: string;
  /** 追问关联的资料 ID：补充答案时复用该资料的原始附件，无需重新上传 */
  documentId?: string;
  answered?: boolean;
  question?: string;
  requestedType?: string;
  detectedType?: string;
  options?: string[];
  missingFields?: string[];
  document?: DocumentCardPayload['document'];
  actions?: CardAction[];
};

export type ReconciliationExportCardPayload = {
  schemaVersion: string;
  cardType: 'reconciliation_export';
  /** chat=聊天立即导出 / user_reminder=用户预约到点 / weekly=系统周提醒 */
  source: string;
  projectId: string;
  /** 预填的数据日期范围（卡片内始终可改） */
  start?: string | null;
  end?: string | null;
  projectName?: string;
  customerName?: string;
  summary?: {
    deliveries: number;
    salesAmount: number;
  };
};

export type OperationResultPayload = {
  schemaVersion: string;
  cardType: 'operation_result';
  operation: string;
  status: 'success';
  summary: string;
  reference: {
    documentId: string;
    docType: string;
    docTypeLabel: string;
    docCode?: string;
    fileName?: string;
    supplierName?: string;
    docDate?: string;
    amount: number;
  };
};


export type ApiChatMessage = {
  id: string;
  sender: 'USER' | 'AI';
  messageType: 'TEXT' | 'FILE' | 'CARD';
  businessType: string;
  content: string;
  fileName?: string;
  status: string;
  projectId?: string;
  projectName?: string;
  batchId?: string;
  batchName?: string;
  payload: Record<string, unknown>;
  createdAt: string;
  creditsUsed?: number;
};

export type ApiCreditOverview = {
  balance: number;
  monthlyQuota: number;
  totalUsed: number;
  periodMonth?: string;
  periodStart?: string;
  periodEnd?: string;
  usedThisMonth: number;
};

export type ApiUsageLog = {
  id: string;
  businessType: string;
  model?: string;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  costCredits: number;
  createdAt?: string;
};

export type ApiDocument = {
  id: string;
  projectId: string;
  batchId: string;
  docCode?: string;
  docType: string;
  supplierName?: string;
  amount: number;
  docDate?: string;
  status: string;
  confidence?: number;
  source: string;
  fileName?: string;
  fileUrl?: string;
  fileObjectKey?: string;
  sourceText?: string;
  guidanceText?: string;
  aiSummary?: string;
  remark?: string;
  structuredData?: string;
  duplicateReason?: string;
  duplicateDecision?: 'continue' | 'skip';
  targetLedgerId?: string;
  targetDocumentId?: string;
  ledgerApplied?: boolean;
  // 送货单销售运费（客户承担，进台账销售总金额）
  salesFreight?: number | null;
  // 送货单销售定价（2026-09-21）：台账页据此汇总「销售定价」列
  pricingMode?: 'uplift' | 'margin' | null;
  upliftAmount?: number | null;
  marginRate?: number | null;
  pricingNote?: string;
  vehicleNo?: string;
  updatedAt?: string;
};

export type ApiBusinessDataRecord = {
  document: ApiDocument;
  items: ApiDocumentItem[];
};

export type ApiLedgerRow = {
  id: string;
  projectId: string;
  batchId: string;
  sourceDocumentId?: string;
  purchaseDate?: string;
  material?: string;
  supplier?: string;
  purchaseAmount: number;
  freight: number;
  deliveryAmount: number;
  deliveredPackageQuantity: number;
  deliveredPackageUnit?: string;
  deliveredWeight: number;
  deliveredWeightUnit?: string;
  requestedAmount: number;
  paidAmount: number;
  unpaidAmount: number;
  invoiceAmount: number;
  taxRate?: string;
  salesOrder?: string;
  salesAmount: number;
  salesInvoiceDate?: string;
  salesInvoiceAmount: number;
  salesTaxRate?: string;
  receivedAmount: number;
  outstanding: number;
  receiptDate?: string;
  requiredReceiptDate?: string;
  isMonthlySettlement?: boolean;
  grossProfit?: number;
  grossMargin?: number;
  // 采购毛利额/毛利率（批次级，后端现算瞬时值；undefined=无口径或无命中行；成本含运费）
  purchaseMarginAmount?: number;
  purchaseMarginRate?: number;
  // 采购毛利算不出的原因：no_policy=缺定价口径 no_supply=无已确认采购单 no_priced_row=采购明细缺吨数或金额
  purchaseMarginNote?: string;
  // 采购运费成本（批次级）：运输单合计 > 送货单采购运费 > 批次运费口径推算
  purchaseFreightCost?: number;
  // 客户承担的送货运费（批次级）：只进毛利额，不进毛利率分母（销售额已含运费）
  purchaseSalesFreight?: number;
  // 待采购价的送货明细行数（批次级）：这些行金额为 0，未计入销售总金额
  deliveryCostPendingCount?: number;
  // 采购毛利差额（批次级）：按采购量估算货款 − 按送货量实际货款；undefined=还没有送货单
  purchaseMarginGap?: number;
  // 运费明细行（批次级）：口径/运输单/单据写明并列累加，运输费用行的构成明细直接用它
  freightItems?: Array<{
    id: string;
    side: 'purchase' | 'sales';
    source: 'policy_ton' | 'policy_trip' | 'transport_note' | 'document_stated';
    sourceDocumentId?: string;
    tons?: number;
    unitPrice?: number;
    amount: number;
    note?: string;
    /** 承运单位（V28 起存在运费行上，可人工覆盖；口径行可能为空） */
    carrierName?: string;
    carrierManual?: boolean;
  }>;
  projectManager?: string;
  status: string;
  // 月结加价利息（时点值，后端每次现算）
  interest?: number;
  interestDetails?: Array<{
    type: string;
    date?: string;
    amount?: number;
    days?: number;
    rate?: number;
    interest?: number;
    note?: string;
  }>;
  receiptEntries?: Array<{ date: string; amount: number }>;
  overdueFlag?: boolean;
  amountCapFlag?: boolean;
  interestSummary?: string;
  // 台账采购金额已是用户确认后的价（keep_source 取单据总额、sync_items 取明细合计），不再单独下发差异
};

/** 超级用户「以他人身份查看」时生效的目标用户 id；普通用户带这个头后端会忽略。 */
const ACT_AS_KEY = 'ai_bot_act_as';

export function getActAsUserId(): string {
  return localStorage.getItem(ACT_AS_KEY) || '';
}

export function setActAsUserId(userId: string) {
  if (userId) {
    localStorage.setItem(ACT_AS_KEY, userId);
  } else {
    localStorage.removeItem(ACT_AS_KEY);
  }
}

function actAsHeader(): Record<string, string> {
  const actAs = getActAsUserId();
  return actAs ? { 'X-Act-As': actAs } : {};
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = localStorage.getItem('ai_bot_token');
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      ...(init?.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...actAsHeader(),
      ...init?.headers
    }
  });

  const body = (await response.json()) as ApiEnvelope<T>;
  if (!response.ok || !body.success) {
    if (response.status === 401 && !path.startsWith('/api/auth/')) {
      localStorage.removeItem('ai_bot_token');
      window.dispatchEvent(new Event('ai-bot-auth-expired'));
    }
    throw new Error(body.message || '请求失败');
  }
  return body.data;
}

export const api = {
  hasSession: () => Boolean(localStorage.getItem('ai_bot_token')),
  // 公开登录配置：后端 qr-env-version 为 develop 时，Web 端开放账号密码登录
  authConfig: () => request<{ qrEnvVersion: string }>('/api/auth/config'),
  async login(username: string, password: string) {
    const result = await request<ApiLoginResponse>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password })
    });
    localStorage.setItem('ai_bot_token', result.token);
    setActAsUserId('');
    return result;
  },
  async logout() {
    try {
      await request<void>('/api/auth/logout', { method: 'POST' });
    } finally {
      localStorage.removeItem('ai_bot_token');
      setActAsUserId('');
    }
  },
  async devLogin() {
    const result = await request<{ token: string }>('/api/auth/wechat-login', {
      method: 'POST',
      body: JSON.stringify({ code: 'web-dev', nickname: '高翔' })
    });
    localStorage.setItem('ai_bot_token', result.token);
    setActAsUserId('');
    return result;
  },
  // 超级用户：全平台用户清单（切换查看身份的候选项）
  adminUsers: (query?: string) =>
    request<ApiAdminUser[]>(
      `/api/admin/users${query ? `?query=${encodeURIComponent(query)}` : ''}`
    ),
  // 扫码登录：创建扫码会话（state + 小程序码 data URL + 普通二维码 data URL）
  wechatQr: () =>
    request<{
      state: string;
      qrImageDataUrl: string;
      plainQrImageDataUrl: string;
      page: string;
      envVersion: string;
      error?: string;
    }>('/api/auth/wechat-qr'),
  // 轮询扫码状态：pending / success / expired
  wechatQrStatus: (state: string) =>
    request<{ status: string; token?: string; user?: ApiUserProfile }>(
      `/api/auth/wechat-qr/status?state=${encodeURIComponent(state)}`
    ),
  me: () => request<ApiUserProfile>('/api/me'),
  /** 修改个人资料：只传要改的字段（昵称 / 单位名称）。 */
  updateMe: (payload: { nickname?: string; companyName?: string }) =>
    request<ApiUserProfile>('/api/me', {
      method: 'PUT',
      body: JSON.stringify(payload)
    }),
  dashboard: () => request('/api/dashboard'),
  projects: (query = '') =>
    request<ApiProject[]>(`/api/projects${query ? `?query=${encodeURIComponent(query)}` : ''}`),
  createProject: (payload: { name: string; customerName: string }) =>
    request<ApiProject>('/api/projects', { method: 'POST', body: JSON.stringify(payload) }),
  updateProject: (projectId: string, payload: { name: string; customerName: string }) =>
    request<ApiProject>(`/api/projects/${encodeURIComponent(projectId)}`,
      { method: 'PUT', body: JSON.stringify(payload) }),
  deleteProject: (projectId: string) =>
    request<void>(`/api/projects/${encodeURIComponent(projectId)}`, { method: 'DELETE' }),
  batches: (projectId?: string) =>
    request<ApiBatch[]>(`/api/batches${projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''}`),
  createBatch: (payload: {
    projectId: string;
    name: string;
    businessDate: string;
    materialSummary: string;
    remark?: string;
  }) => request<ApiBatch>('/api/batches', { method: 'POST', body: JSON.stringify(payload) }),
  updateBatch: (batchId: string, payload: {
    name?: string;
    businessDate?: string;
    materialSummary?: string;
    businessHints?: Record<string, unknown> | null;
    reconciled?: boolean;
  }) => request<ApiBatch>(`/api/batches/${encodeURIComponent(batchId)}`,
    { method: 'PUT', body: JSON.stringify(payload) }),
  // ---- 项目收款（一笔收款关联多个批次）----
  receiptOverview: (projectId: string) =>
    request<ApiProjectReceiptOverview>(`/api/projects/${encodeURIComponent(projectId)}/receipts`),
  receiptSuggest: (projectId: string, amount: number, batchIds?: string[], excludeReceiptId?: string) => {
    const params = new URLSearchParams({ amount: String(amount) });
    (batchIds || []).forEach((id) => params.append('batchIds', id));
    if (excludeReceiptId) params.set('excludeReceiptId', excludeReceiptId);
    return request<ApiReceiptSuggestion>(
      `/api/projects/${encodeURIComponent(projectId)}/receipts/suggest?${params.toString()}`);
  },
  createReceipt: (projectId: string, payload: ReceiptSavePayload) =>
    request<ApiReceipt>(`/api/projects/${encodeURIComponent(projectId)}/receipts`,
      { method: 'POST', body: JSON.stringify(payload) }),
  updateReceipt: (projectId: string, receiptId: string, payload: ReceiptSavePayload) =>
    request<ApiReceipt>(
      `/api/projects/${encodeURIComponent(projectId)}/receipts/${encodeURIComponent(receiptId)}`,
      { method: 'PUT', body: JSON.stringify(payload) }),
  deleteReceipt: (projectId: string, receiptId: string) =>
    request<void>(
      `/api/projects/${encodeURIComponent(projectId)}/receipts/${encodeURIComponent(receiptId)}`,
      { method: 'DELETE' }),
  deleteBatch: (batchId: string) =>
    request<void>(`/api/batches/${encodeURIComponent(batchId)}`, { method: 'DELETE' }),
  batchDetail: (batchId: string) => request(`/api/batches/${batchId}`),
  chatMessages: (projectId: string, batchId: string) =>
    request<ApiChatMessage[]>(
      `/api/chat/messages?projectId=${encodeURIComponent(projectId)}&batchId=${encodeURIComponent(batchId)}`
    ),
  sendChat: (text: string, projectId: string, batchId: string) =>
    request<{ appended: ApiChatMessage[] }>('/api/chat/send', {
      method: 'POST',
      body: JSON.stringify({ text, projectId, batchId })
    }),
  uploadDocument: (file: File, projectId: string, batchId: string, guidance?: string) => {
    const form = new FormData();
    form.append('file', file);
    form.append('projectId', projectId);
    form.append('batchId', batchId);
    if (guidance) form.append('guidance', guidance);
    return request<{ appended: ApiChatMessage[] }>('/api/chat/upload', { method: 'POST', body: form });
  },
  billingCredits: () => request<ApiCreditOverview>('/api/billing/credits'),
  billingUsageLogs: (page = 0, size = 20) =>
    request<{ items: ApiUsageLog[]; total: number }>(
      `/api/billing/usage-logs?page=${page}&size=${size}`
    ),
  documents: (projectId?: string, batchId?: string) => {
    const params = new URLSearchParams();
    if (projectId) params.set('projectId', projectId);
    if (batchId) params.set('batchId', batchId);
    return request<ApiDocument[]>(`/api/documents?${params.toString()}`);
  },
  businessData: (projectId?: string, batchId?: string) => {
    const params = new URLSearchParams();
    if (projectId) params.set('projectId', projectId);
    if (batchId) params.set('batchId', batchId);
    return request<ApiBusinessDataRecord[]>(`/api/documents/data?${params.toString()}`);
  },
  documentDetail: (documentId: string) =>
    request<ApiBusinessDataRecord>(`/api/documents/${documentId}`),
  confirmDocument: (documentId: string) =>
    request<ApiDocument>(`/api/documents/${documentId}/confirm`, { method: 'POST' }),
  editDocument: (documentId: string, edits: Record<string, unknown>) =>
    request<ApiDocument>(`/api/documents/${documentId}`, {
      method: 'PUT',
      body: JSON.stringify(edits)
    }),
  deleteDocument: (documentId: string) =>
    request<void>(`/api/documents/${documentId}`, { method: 'DELETE' }),
  duplicateDecision: (documentId: string, decision: 'continue' | 'skip') =>
    request<ApiDocument>(`/api/documents/${documentId}/duplicate-decision`, {
      method: 'POST',
      body: JSON.stringify({ decision })
    }),
  // 公司往来：projectId 为空统计全部项目
  companyTransactions: (projectId?: string) =>
    request<ApiCompanyTransactionOverview>(
      `/api/company-transactions${projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''}`),
  documentAccessUrl: (documentId: string) =>
    request<{ url: string; fileName: string; source: string }>(
      `/api/documents/${documentId}/access-url`
    ),
  salesDetail: (batchId: string) =>
    request<SalesDetailRow[]>(
      `/api/documents/sales-detail?batchId=${encodeURIComponent(batchId)}`
    ),
  // 人工改价：更新某条行情的价格，后端按其产品+规格刷新受影响批次（发起批次同步、其余后台）
  marketQuoteUpdate: (quoteId: string, price: number, currentBatchId?: string) =>
    request<MarketImportOutcome>(`/api/market/quotes/${encodeURIComponent(quoteId)}`, {
      method: 'PUT',
      body: JSON.stringify({ price, currentBatchId })
    }),
  // 材料清单：批次内每种材料一行（身份 + 网价绑定 + 采购/送货两侧聚合 + 锁定网价口径）
  materials: (batchId: string) =>
    request<MaterialRow[]>(`/api/market/materials?batchId=${encodeURIComponent(batchId)}`),
  // 材料级改价：把该材料取价日的绑定规格价钉成权威价（发起批次同步、其余后台）
  materialQuoteUpdate: (materialId: string, price: number) =>
    request<MarketImportOutcome>(`/api/market/materials/${encodeURIComponent(materialId)}/quote-price`, {
      method: 'PUT',
      body: JSON.stringify({ price })
    }),
  // 人工绑定/解绑材料级网价（manual 永不被自动流程覆盖），绑定后后端立即重锁批次
  bindMaterial: (payload: MaterialBindRequest) =>
    request<void>('/api/market/materials/bind', {
      method: 'PUT',
      body: JSON.stringify(payload)
    }),
  // 手动校准：AI 判同新写法 + 重绑 + 重算销售价（同步返回摘要）
  recalibrateMaterials: (batchId: string) =>
    request<MaterialRecalibrateResult>(
      `/api/market/materials/recalibrate?batchId=${encodeURIComponent(batchId)}`,
      { method: 'POST' }
    ),
  // 行情卡确认/放弃（卡片按钮专用）：返回追加到聊天流的结果消息
  confirmQuoteCard: (messageId: string) =>
    request<{ appended: ApiChatMessage[] }>(
      `/api/chat/quote-cards/${encodeURIComponent(messageId)}/confirm`,
      { method: 'POST' }
    ),
  discardQuoteCard: (messageId: string) =>
    request<{ appended: ApiChatMessage[] }>(
      `/api/chat/quote-cards/${encodeURIComponent(messageId)}/discard`,
      { method: 'POST' }
    ),
  // 行情待确认卡编辑：核对/修正 AI 抽取的行情行后保存，再点「确认导入行情」落库
  updateQuoteCard: (messageId: string, payload: Record<string, unknown>) =>
    request<void>(`/api/chat/quote-cards/${encodeURIComponent(messageId)}`, {
      method: 'PUT',
      body: JSON.stringify(payload)
    }),
  // 追问续接：针对待澄清资料补充答案（复用已保存的原始附件）
  clarifyDocument: (documentId: string, answer: string) =>
    request<{ appended: ApiChatMessage[] }>('/api/chat/clarify', {
      method: 'POST',
      body: JSON.stringify({ documentId, answer })
    }),
  // 台账导出：后端生成 xlsx（保留表头分组、合并单元格与样式），web 与小程序共用。
  // batchId 传入时只导出该批次；不传保持项目级导出（小程序兼容）。
  async downloadLedger(projectId?: string, batchId?: string) {
    const token = localStorage.getItem('ai_bot_token');
    const params = new URLSearchParams();
    if (projectId) params.set('projectId', projectId);
    if (batchId) params.set('batchId', batchId);
    const query = params.size > 0 ? `?${params.toString()}` : '';
    const response = await fetch(`${API_BASE}/api/reports/ledger/export${query}`, {
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...actAsHeader() }
    });
    if (!response.ok) {
      throw new Error('台账导出失败，请稍后重试');
    }
    const blob = await response.blob();
    const disposition = response.headers.get('Content-Disposition') || '';
    const matched = /filename\*=UTF-8''([^;]+)/.exec(disposition);
    const fileName = matched ? decodeURIComponent(matched[1]) : '项目批次对账台账.xlsx';
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = fileName;
    anchor.click();
    URL.revokeObjectURL(url);
    return fileName;
  },
  // 对账单导出：模板渲染（送货单明细 + 项目累计 + 回款累计），按日期范围一个 sheet
  // material 传入时只导出批次材料为该值的批次（如「螺纹钢」），不传导出全部
  async downloadReconciliation(projectId: string, start: string, end: string, material?: string) {
    const token = localStorage.getItem('ai_bot_token');
    const params = new URLSearchParams({ projectId, start, end });
    if (material) params.set('material', material);
    const response = await fetch(`${API_BASE}/api/reports/reconciliation/export?${params.toString()}`, {
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...actAsHeader() }
    });
    if (!response.ok) {
      const payload = await response.json().catch(() => null);
      throw new Error(payload?.message || '对账单导出失败，请稍后重试');
    }
    const blob = await response.blob();
    const disposition = response.headers.get('Content-Disposition') || '';
    const matched = /filename\*=UTF-8''([^;]+)/.exec(disposition);
    const fileName = matched ? decodeURIComponent(matched[1]) : '对账单.xlsx';
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = fileName;
    anchor.click();
    URL.revokeObjectURL(url);
    return fileName;
  },
  // 用户偏好设置（user_settings JSON）：GET 整份 / PUT 深合并
  async getUserSettings(): Promise<Record<string, unknown>> {
    return request<Record<string, unknown>>('/api/me/settings');
  },
  async updateUserSettings(patch: Record<string, unknown>): Promise<Record<string, unknown>> {
    return request<Record<string, unknown>>('/api/me/settings', {
      method: 'PUT',
      body: JSON.stringify(patch)
    });
  },
  // 对账单卡片实时汇总：项目/客户信息 + 指定日期范围的张数与锁定销售额（改日期即重取）
  async getReconciliationSummary(projectId: string, start: string, end: string, material?: string): Promise<{
    projectName?: string;
    customerName?: string;
    deliveries: number;
    salesAmount: number;
  }> {
    const params = new URLSearchParams({ projectId, start, end });
    if (material) params.set('material', material);
    return request(`/api/reports/reconciliation/summary?${params.toString()}`);
  },
  // 对账单可选材料：项目内各批次填写的材料（去重）
  reconciliationMaterials: (projectId: string) =>
    request<string[]>(`/api/reports/reconciliation/materials?projectId=${encodeURIComponent(projectId)}`),
  ledger: (projectId?: string, batchId?: string) => {
    const params = new URLSearchParams();
    if (projectId) params.set('projectId', projectId);
    if (batchId) params.set('batchId', batchId);
    return request<ApiLedgerRow[]>(`/api/documents/ledger?${params.toString()}`);
  },
  // 台账表头定义：后端 LedgerTableSchema 单一来源下发，页面渲染与导出共用同一结构
  ledgerColumns: () =>
    request<ApiLedgerHeaderCell[][]>('/api/reports/ledger/columns'),
  updateLedgerBatch: (batchId: string, edits: {
    salesOrder?: string;
    salesAmount?: number;
    receiptDate?: string;
    requiredReceiptDate?: string;
    projectManager?: string;
    isMonthlySettlement?: boolean;
  }) => request<ApiLedgerRow[]>(`/api/documents/ledger/batch/${batchId}`, {
    method: 'PUT',
    body: JSON.stringify(edits)
  }),
  /** 人工改运费行承运单位（按行改：运费行 id → 承运单位，空串=清空）。 */
  updateFreightCarriers: (batchId: string, carriers: Record<string, string>) =>
    request<ApiLedgerRow[]>(
      `/api/documents/ledger/batch/${batchId}/freight-carriers`,
      { method: 'PUT', body: JSON.stringify(carriers) }
    ),
  report: (projectId?: string, batchId?: string) => {
    const params = new URLSearchParams({ scope: batchId ? 'batch' : 'project' });
    if (projectId) params.set('projectId', projectId);
    if (batchId) params.set('batchId', batchId);
    return request(`/api/reports/finance?${params.toString()}`);
  },
  // ---------- 市场行情 ----------
  marketQuotes: (params: {
    date?: string;
    category?: string;
    productCode?: string;
    city?: string;
    origin?: string;
    keyword?: string;
  }) => {
    const query = new URLSearchParams();
    if (params.date) query.set('date', params.date);
    if (params.category) query.set('category', params.category);
    if (params.productCode) query.set('productCode', params.productCode);
    if (params.city) query.set('city', params.city);
    if (params.origin) query.set('origin', params.origin);
    if (params.keyword) query.set('keyword', params.keyword);
    return request<MarketQuoteRow[]>(`/api/market/quotes?${query.toString()}`);
  },
  marketMeta: () => request<MarketMeta>('/api/market/meta'),
  marketCompare: (batchId: string, city?: string, origin?: string) => {
    const params = new URLSearchParams({ batchId });
    if (city) params.set('city', city);
    if (origin) params.set('origin', origin);
    return request<BatchCompare>(`/api/market/compare?${params.toString()}`);
  },
  marketMappings: (batchId: string) =>
    request<MarketMappingRow[]>(`/api/market/mappings?batchId=${encodeURIComponent(batchId)}`),
  marketManualMapping: (payload: {
    materialId: string;
    productCode?: string;
    specName?: string;
    origin?: string;
    city?: string;
    status: string;
    /** 人工锁定行情价（元/吨，选填；填写时以该价为准并重算销售单价） */
    quotePrice?: number;
  }) => request<void>('/api/market/mappings', { method: 'PUT', body: JSON.stringify(payload) }),
  marketRunMatch: (batchId: string) =>
    request<number>(`/api/market/match/run?batchId=${encodeURIComponent(batchId)}`, { method: 'POST' }),
  // 指定产品的行情规格候选（人工绑定用）。按「用户选中的产品」实时拉取，
  // 与 mapping.candidates（按材料推导产品码预计算）无关。
  /**
   * 规格候选：spec=规格名，sources=该规格在该日有哪些来源渠道（mysteel 权威 / gtgqw 爬取），
   * latestDate=该规格最近有价日，onQuoteDay=是否在材料取价日当天有价（当天有价才真能取到价）。
   * materialId 决定日期口径（该材料的取价日），不传按今天解析。
   */
  marketProductSpecs: (code: string, materialId?: string) =>
    request<Array<{
      spec: string;
      sources?: string[];
      price?: number;
      latestDate?: string;
      onQuoteDay?: boolean;
    }>>(
      `/api/market/products/${encodeURIComponent(code)}/specs`
      + (materialId ? `?materialId=${encodeURIComponent(materialId)}` : '')
    ),
  marketCrawlLatest: () => request<MarketCrawlLogRow[]>('/api/market/crawl/latest')
};

// ---------- 市场行情类型 ----------
export type MarketImportOutcome = {
  imported: number;
  skipped: number;
  city: string | null;
  quoteDate: string | null;
  refreshedBatches: number;
  /** 转后台异步刷新的批次数（发起批次之外的） */
  backgroundBatches: number;
};

export type MarketQuoteRow = {
  id: string;
  quoteDate: string;
  productCode: string;
  source?: string;
  productName: string;
  specName: string;
  spec?: string;
  grade?: string;
  origin?: string;
  city: string;
  price: number | null;
  priceChange: number | null;
  detailUrl?: string;
};

export type MarketMeta = {
  latestDate: string;
  categories: string[];
  products: { code: string; category: string; name: string; sourceUrl: string; isNav: boolean }[];
  cities: string[];
  origins: string[];
};

export type MarketCompareRow = {
  materialId: string;
  materialName: string;
  materialSpec: string;
  unit: string;
  quantity: number;
  businessPrice: number;
  matchStatus: string;
  matchMethod?: string;
  confidence?: number;
  productCode?: string;
  productName?: string;
  marketSpecName?: string;
  origin?: string;
  city?: string;
  quoteDate?: string;
  marketPrice: number | null;
  // 锁定行情：送货单确认时按 ≤送货单日期 最近有行情日锁定的价格（销售单价的依据）
  lockedDate?: string;
  lockedPrice?: number | null;
  // 锁定行情产地：产地命中为产地名，多产地均价为「均价(N条)」
  lockedOrigin?: string | null;
  diff: number | null;
  diffRate: number | null;
};

export type BatchCompare = {
  batchId: string;
  batchName: string;
  totalMaterials: number;
  matchedMaterials: number;
  unmatchedMaterials: number;
  totalBusinessAmount: number;
  totalMarketAmount: number;
  totalDiff: number;
  avgDiffRate: number | null;
  materials: MarketCompareRow[];
};

// 台账销售金额明细行（送货单 × 明细行，网价锁定值）
export type SalesDetailRow = {
  mappingId: string | null;
  documentId: string;
  docCode: string;
  docDate: string | null;
  materialName: string;
  spec: string;
  quoteSource: string | null;
  quoteSpecName: string | null;
  quoteDate: string | null;
  quotePrice: number | null;
  /** 采购价（元/吨）：毛利率口径的定价基数，来自同批次采购单；null=没匹配到 */
  costPrice: number | null;
  /** 采购价匹配结果：exact/origin/weighted=已匹配；spec_missing=采购单没这个规格；no_supply=批次还没有采购单 */
  costLevel: string | null;
  uplift: number | null;
  salesUnitPrice: number | null;
  pricingQuantity: number | null;
  pricingUnit: string | null;
  amount: number | null;
  priceBasis: 'quote' | 'cost' | 'cost_pending' | 'manual' | 'fallback_amount' | 'unmatched' | string;
  status: string;
  // 该行采用的定价方式说明（毛利率X% / 网价+X），历史单据可能为 null
  pricingNote: string | null;
  // item=材料明细行；sales_freight=销售运费条目（该送货单填写了销售运费时追加）
  rowType: 'item' | 'sales_freight';
};

// 材料清单行（一个材料一个入口，2026-11-04）：身份 + 网价绑定 + 采购/送货两侧聚合
export type MaterialRow = {
  id: string;
  category: string;
  grade: string | null;
  diameter: string | null;
  lengthM: string | null;
  origin: string | null;
  matchLevel: string | null;
  matchSource: string | null;
  quoteProductCode: string | null;
  quoteProductName: string | null;
  quoteSpecName: string | null;
  quoteSource: string | null;
  supplyItemId: string | null;
  // 材料级人工销售价（元/吨）：非空时锁价优先于口径推算
  manualSalesPrice: number | null;
  purchaseTons: number | null;
  purchasePrice: number | null;
  purchaseRows: number;
  deliveryTons: number | null;
  deliveryAmount: number | null;
  deliveryRows: number;
  // 锁定网价口径（2026-11-04）：真实取价日（≤单据日期最近有行情日）+ 绑定规格当天的网价
  quoteDate: string | null;
  lockedNetPrice: number | null;
  // 销售单价（2026-11-06）：材料级人工价 ＞ 批次口径推算，逐行算完按采购吨数加权；
  // 与台账采购毛利的估算货款同源，销价 × 采购吨数 ≈ 估算货款
  salesPrice: number | null;
  // 销价口径：有值 manual/margin/uplift；无值 no_policy（未设口径）/ cost_pending（缺采购价）/ net_pending（缺网价）
  salesPriceBasis: string | null;
  // manual=人工绑定 auto=自动绑定 unbound=未绑定 duplicate=同材料多条采购行（需人工核）
  status: string;
};

export type MaterialBindRequest = {
  materialId: string;
  productCode?: string;
  specName?: string;
  status: 'manual' | 'unmatched';
  // 人工销售价（元/吨，选填）：非空=人工定价优先于口径推算；null=恢复自动
  manualSalesPrice?: number | null;
};

export type MaterialRecalibrateResult = {
  status: string;
  writeups: number;
  mapped: number;
  created: number;
  credits: number;
  message: string | null;
};

export type MarketMappingRow = {
  id?: string;
  materialId: string;
  materialName: string;
  materialSpec: string;
  productCode?: string;
  productName?: string;
  specName?: string;
  origin?: string;
  city?: string;
  matchMethod?: string;
  confidence?: number;
  status: string;
  /** 锁定取价的行情日期（送货单载体） */
  quoteDate?: string;
  /** 锁定行情价（元/吨；人工改价行=人工输入价） */
  quotePrice?: number | null;
};

export type MarketCrawlLogRow = {
  id: string;
  runStart: string;
  runEnd: string;
  status: string;
  productCode?: string;
  quoteDate?: string;
  rowsFound: number;
  rowsUpserted: number;
  rowsSkipped: number;
  errorMsg?: string;
};
