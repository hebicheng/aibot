// 轻量图标组件：内联 SVG（data-uri），可配置颜色与尺寸，保证各机型清晰一致。
const PATHS = {
  menu: '<path d="M4 7h16M4 12h16M4 17h16" />',
  back: '<path d="M15 5l-7 7 7 7" />',
  close: '<path d="M6 6l12 12M18 6L6 18" />',
  search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
  send: '<path d="M12 20V5M6 11l6-6 6 6"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  camera: '<path d="M4 8h3l1.5-2h7L17 8h3v11H4z"/><circle cx="12" cy="13" r="3.2"/>',
  image: '<rect x="4" y="5" width="16" height="14" rx="2"/><circle cx="9" cy="10" r="1.6"/><path d="M5 17l4.5-4 3 2.5L16 12l3 3"/>',
  file: '<path d="M7 3h7l5 5v13H7z"/><path d="M14 3v5h5"/>',
  chevronRight: '<path d="M9 6l6 6-6 6"/>',
  chevronDown: '<path d="M6 9l6 6 6-6"/>',
  chevronUp: '<path d="M6 15l6-6 6 6"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  edit: '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M13.5 6.5l4 4"/>',
  refresh: '<path d="M20 11a8 8 0 10-1.8 6.4M20 20v-5h-5"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 20c1.5-4 5-6 8-6s6.5 2 8 6"/>',
  robot: '<rect x="5" y="8" width="14" height="10" rx="3"/><path d="M12 8V4M9 13h.01M15 13h.01"/>',
  doc: '<path d="M7 3h7l5 5v13H7z"/><path d="M14 3v5h5M9 13h6M9 17h6"/>',
  truck: '<path d="M3 7h11v9H3zM14 10h4l3 3v3h-7z"/><circle cx="7" cy="18" r="1.8"/><circle cx="17" cy="18" r="1.8"/>',
  cash: '<rect x="3" y="6" width="18" height="12" rx="2"/><circle cx="12" cy="12" r="2.6"/>',
  invoice: '<path d="M6 3h12v18l-3-2-3 2-3-2-3 2z"/><path d="M9 8h6M9 12h6"/>',
  cart: '<path d="M3 5h2l2 11h11l2-8H6"/><circle cx="9" cy="19" r="1.4"/><circle cx="17" cy="19" r="1.4"/>',
  bill: '<path d="M6 3h9l3 3v15l-2.5-1.5L13 21l-2.5-1.5L8 21 5.5 19.5 6 3z"/><path d="M9 8h6M9 12h4"/>',
  records: '<rect x="4" y="4" width="16" height="16" rx="2"/><path d="M8 9h8M8 13h8M8 17h5"/>',
  cluster: '<circle cx="6" cy="6" r="2.4"/><circle cx="18" cy="6" r="2.4"/><circle cx="12" cy="18" r="2.4"/><path d="M7.6 7.6L11 15M16.4 7.6L13 15"/>',
  layers: '<path d="M12 3l9 5-9 5-9-5 9-5z"/><path d="M3.5 13L12 17.7 20.5 13"/><path d="M3.5 17L12 21.7 20.5 17"/>',
  service: '<circle cx="12" cy="12" r="8"/><path d="M9.5 10a2.5 2.5 0 114 2c-1 .7-1.5 1.2-1.5 2M12 17h.01"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2"/>',
  logout: '<path d="M14 4h5v16h-5"/><path d="M10 8l-4 4 4 4M6 12h9"/>',
  sparkle: '<path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"/>',
  photo: '<rect x="4" y="5" width="16" height="14" rx="2"/><circle cx="9" cy="10" r="1.6"/><path d="M5 17l4.5-4 3 2.5L16 12l3 3"/>',
  question: '<circle cx="12" cy="12" r="8"/><path d="M9.5 10a2.5 2.5 0 114 2c-1 .7-1.5 1.2-1.5 2M12 17h.01"/>',
  warning: '<path d="M12 4l9 15H3z"/><path d="M12 10v4M12 17h.01"/>',
  clock: '<circle cx="12" cy="12" r="8"/><path d="M12 8v4l3 2"/>',
  filter: '<path d="M4 6h16l-6 7v5l-4 2v-7z"/>',
  download: '<path d="M12 4v10M7 10l5 5 5-5M5 19h14"/>',
  move: '<path d="M5 9l-3 3 3 3M9 5l3-3 3 3M19 9l3 3-3 3M15 19l-3 3-3-3"/><path d="M2 12h20M12 2v20"/>',
  trash: '<path d="M5 7h14M9 7V4h6v3M6 7l1 13h10l1-13"/>',
  trend: '<path d="M4 18l5-6 4 3 7-9"/><path d="M15 6h5v5"/>'
};

const ALIAS = {
  'orders-o': 'doc',
  'logistics': 'truck',
  'bill-o': 'bill',
  'balance-pay': 'cash',
  'cash-back-record-o': 'cash',
  'description-o': 'invoice',
  'records-o': 'records',
  'photo-o': 'photo',
  'question-o': 'question',
  'service-o': 'service',
  'cluster-o': 'cluster',
  'user-circle-o': 'user'
};

function buildSrc(name, color, stroke) {
  const key = ALIAS[name] || name;
  const body = PATHS[key] || PATHS.doc;
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="${color}" stroke-width="${stroke}" stroke-linecap="round" stroke-linejoin="round">${body}</svg>`;
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

Component({
  properties: {
    name: { type: String, value: 'doc' },
    color: { type: String, value: '#1f2329' },
    size: { type: Number, value: 40 },
    stroke: { type: Number, value: 1.8 }
  },
  data: { src: '' },
  observers: {
    'name, color, stroke'(name, color, stroke) {
      this.setData({ src: buildSrc(name, color, stroke) });
    }
  },
  lifetimes: {
    attached() {
      this.setData({ src: buildSrc(this.data.name, this.data.color, this.data.stroke) });
    }
  }
});
