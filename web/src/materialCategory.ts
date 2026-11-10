/**
 * 材料大类归一化（与后端 MaterialCategoryNormalizer 同口径）。
 * 仅用于对账台账的显示聚合：螺纹钢不分型号规格统一归「螺纹钢」，
 * 盘螺、高线、圆钢各自成类；其余材料剥离规格与牌号后按原始名兜底。
 * 识别与原始明细数据不受影响。
 */
export const MATERIAL_CATEGORIES = ['螺纹钢', '盘螺', '高线', '圆钢'];

const GRADE = /hrb\d{3}e?|hpb\d{3}|rrb\d{3}|q\d{3}[a-e]?/gi;
const SPEC = /\d+(\.\d+)?[*x×]\d+|Φ\s*\d+(\.\d+)?|\d+(\.\d+)?\s*mm|\d+(\.\d+)?\s*[m米]|\d+(\.\d+)?/gi;
const LEVEL = /[一二三四1234]\s*级/g;

export function materialCategory(materialName?: string | null): string {
  const raw = (materialName || '').trim();
  if (!raw) return '未标注材料';
  if (raw.includes('盘螺')) return '盘螺';
  if (raw.includes('螺纹')) return '螺纹钢';
  if (raw.includes('高线') || raw.includes('高速线材') || raw.includes('线材')) return '高线';
  if (raw.includes('圆钢')) return '圆钢';
  const cleaned = raw
    .replace(GRADE, ' ')
    .replace(LEVEL, ' ')
    .replace(SPEC, ' ')
    .replace(/[\s（）()\-—_/、,，。.]+/g, '');
  return cleaned || raw;
}
