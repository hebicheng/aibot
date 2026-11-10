// 全局配置：
// 1) 开发环境默认走本机 127.0.0.1:8090（微信开发者工具 + 本地后端直连即可）。
// 2) 若后端在别的机器，可在「开发者工具 → Storage」写入 __api_base__ 临时覆盖，无需改代码；
//    例如 put 一个字符串 "http://192.168.x.x:8090"。
// 3) 真机 / 体验版 / 正式版：微信只允许 https + 已备案域名，且必须在小程序后台
//    配置 request / uploadFile 合法域名。请把 production.API_BASE 改成正式域名。
const ENV = {
  development: {
    API_BASE: 'http://192.168.1.19:8090'
  },
  production: {
    API_BASE: 'https://aibot.sicnu.ac.cn'
  }
};

const CURRENT = 'production';

// 运行时覆盖：开发者工具 Storage 里设置 __api_base__ 即可指向任意可达后端。
function resolveApiBase() {
  let override = '';
  try {
    override = wx.getStorageSync('__api_base__') || '';
  } catch (e) {
    override = '';
  }
  return override && String(override).startsWith('http') ? override : ENV[CURRENT].API_BASE;
}

module.exports = {
  API_BASE: resolveApiBase(),
  TOKEN_KEY: 'auth_token',
  USER_KEY: 'auth_user'
};
