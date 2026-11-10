import type { ApiBusinessDataRecord, ApiDocumentItem } from './api';
import { materialCategory } from './materialCategory';

export type DeliveryStatus = '待送达' | '部分送达' | '已送达' | '超供送达' | '待匹配' | '待核对';

export type DeliveryMatchDetail = {
  key: string;
  materialName: string;
  spec: string;
  purchaseQuantity: number;
  purchaseUnit: string;
  deliveredPackages: number;
  deliveredPackageUnit: string;
  deliveredWeight: number;
  deliveredWeightUnit: string;
  status: DeliveryStatus;
};

export type BatchDeliverySummary = {
  status: DeliveryStatus;
  deliveryDocumentCount: number;
  expectedMaterialCount: number;
  deliveredMaterialCount: number;
  completionRate: number;
  unmatchedDeliveryItemCount: number;
  detailsByMaterial: Map<string, DeliveryMatchDetail>;
};

const normalize = (value?: string) => (value || '').replace(/\s+/g, '').toLowerCase();

/** 台账/送达匹配的聚合键 = 材料大类（2026-09-21 起，规格不再参与分组）。 */
export const materialKey = (name?: string, _spec?: string) =>
  normalize(materialCategory(name));

export const isConfirmed = (record: ApiBusinessDataRecord) =>
  ['matched', 'archived'].includes(record.document.status)
  && record.document.duplicateDecision !== 'skip';

export const isWeightUnit = (unit?: string) =>
  ['吨', 't', 'ton', 'kg', '千克', '公斤'].includes(normalize(unit));

export const weightInTons = (value: number, unit?: string) => {
  const normalizedUnit = normalize(unit);
  return ['kg', '千克', '公斤'].includes(normalizedUnit) ? value / 1000 : value;
};

const purchaseQuantity = (item: ApiDocumentItem) =>
  Number(item.pricingQuantity || item.quantity || 0);

const purchaseUnit = (item: ApiDocumentItem) => item.pricingUnit || item.unit || '';

const deliveredPackages = (item: ApiDocumentItem) =>
  Number(item.packageQuantity || item.quantity || 0);

/**
 * 送货单只确认货物是否实际送达，不能用送货金额作为完成依据。
 * 同一材料按大类汇总（多张采购单/送货单累加）后，再以采购计价单位选择数量或重量比较。
 */
export function summarizeBatchDelivery(records: ApiBusinessDataRecord[]): BatchDeliverySummary {
  const confirmedRecords = records.filter(isConfirmed);
  const supplyRecords = confirmedRecords.filter((record) => record.document.docType === 'supply_order');
  const deliveryRecords = confirmedRecords.filter((record) => record.document.docType === 'delivery_note');
  const detailsByMaterial = new Map<string, DeliveryMatchDetail>();
  const deliveryKeys = new Set<string>();

  for (const supply of supplyRecords) {
    for (const item of supply.items) {
      const key = materialKey(item.materialName, item.spec);
      const current = detailsByMaterial.get(key) || {
        key,
        materialName: materialCategory(item.materialName),
        spec: '',
        purchaseQuantity: 0,
        purchaseUnit: purchaseUnit(item),
        deliveredPackages: 0,
        deliveredPackageUnit: '',
        deliveredWeight: 0,
        deliveredWeightUnit: '吨',
        status: '待送达' as DeliveryStatus
      };
      current.purchaseQuantity += purchaseQuantity(item);
      current.purchaseUnit = current.purchaseUnit || purchaseUnit(item);
      detailsByMaterial.set(key, current);
    }
  }

  for (const delivery of deliveryRecords) {
    for (const item of delivery.items) {
      const key = materialKey(item.materialName, item.spec);
      deliveryKeys.add(key);
      const current = detailsByMaterial.get(key);
      if (!current) continue;
      current.deliveredPackages += deliveredPackages(item);
      current.deliveredPackageUnit = current.deliveredPackageUnit || item.packageUnit || item.unit || '';
      current.deliveredWeight += weightInTons(Number(item.totalWeight || 0), item.weightUnit);
      detailsByMaterial.set(key, current);
    }
  }

  const details = Array.from(detailsByMaterial.values());
  let deliveredMaterialCount = 0;
  for (const detail of details) {
    const expected = detail.purchaseQuantity;
    const delivered = isWeightUnit(detail.purchaseUnit)
      ? detail.deliveredWeight
      : detail.deliveredPackages;
    // 超供送达：先导送货单时，送达数量可能超过采购单数量（2026-09-21 增加状态）
    detail.status = delivered <= 0
      ? '待送达'
      : expected > 0 && delivered > expected + 0.0001
        ? '超供送达'
        : expected > 0 && delivered + 0.0001 < expected ? '部分送达' : '已送达';
    if (detail.status === '已送达' || detail.status === '超供送达') deliveredMaterialCount += 1;
  }

  const expectedMaterialCount = details.length;
  const unmatchedDeliveryItemCount = Array.from(deliveryKeys)
    .filter((key) => !detailsByMaterial.has(key)).length;
  const hasDeliveryData = deliveryRecords.some((record) => record.items.some((item) =>
    deliveredPackages(item) > 0 || Number(item.totalWeight || 0) > 0));
  const hasMatchedDelivery = details.some((detail) =>
    detail.deliveredPackages > 0 || detail.deliveredWeight > 0);

  let status: DeliveryStatus = '待送达';
  if (expectedMaterialCount === 0) {
    status = hasDeliveryData ? '待核对' : '待送达';
  } else if (unmatchedDeliveryItemCount > 0 && !hasMatchedDelivery) {
    status = '待匹配';
  } else if (deliveredMaterialCount === expectedMaterialCount) {
    status = '已送达';
  } else if (hasMatchedDelivery) {
    status = '部分送达';
  } else if (unmatchedDeliveryItemCount > 0) {
    status = '待匹配';
  }

  return {
    status,
    deliveryDocumentCount: deliveryRecords.length,
    expectedMaterialCount,
    deliveredMaterialCount,
    completionRate: expectedMaterialCount > 0
      ? Math.round(deliveredMaterialCount / expectedMaterialCount * 1000) / 10
      : 0,
    unmatchedDeliveryItemCount,
    detailsByMaterial
  };
}
