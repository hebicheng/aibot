// 统一请求封装：注入 token、解 ApiResponse 信封、401 自动重登、错误 toast。
const config = require('../config');

let loginPromise = null;

function getToken() {
  return wx.getStorageSync(config.TOKEN_KEY) || '';
}

function setSession(loginResponse) {
  if (loginResponse && loginResponse.token) {
    wx.setStorageSync(config.TOKEN_KEY, loginResponse.token);
    wx.setStorageSync(config.USER_KEY, loginResponse.user || {});
  }
  return loginResponse;
}

function clearSession() {
  wx.removeStorageSync(config.TOKEN_KEY);
  wx.removeStorageSync(config.USER_KEY);
}

// 微信登录换取后端 token（去重：并发只发起一次）
function login() {
  if (loginPromise) return loginPromise;
  loginPromise = new Promise((resolve, reject) => {
    wx.login({
      success: (res) => {
        if (!res.code) {
          reject(new Error('wx.login 未返回 code'));
          return;
        }
        rawRequest({
          url: '/api/auth/wechat-login',
          method: 'POST',
          data: { code: res.code },
          skipAuth: true
        }).then((data) => {
          resolve(setSession(data));
        }).catch(reject);
      },
      fail: reject
    });
  }).finally(() => {
    loginPromise = null;
  });
  return loginPromise;
}

// 裸请求：不做 401 重试，仅解信封
function rawRequest({ url, method = 'GET', data, header = {}, skipAuth = false }) {
  const token = getToken();
  return new Promise((resolve, reject) => {
    wx.request({
      url: config.API_BASE + url,
      method,
      data,
      header: Object.assign(
        { 'content-type': 'application/json' },
        !skipAuth && token ? { Authorization: 'Bearer ' + token } : {},
        header
      ),
      success: (res) => {
        const body = res.data || {};
        if (res.statusCode === 401) {
          const err = new Error('UNAUTHORIZED');
          err.statusCode = 401;
          reject(err);
          return;
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && body.success) {
          resolve(body.data);
        } else {
          const err = new Error(body.message || ('请求失败(' + res.statusCode + ')'));
          err.statusCode = res.statusCode;
          reject(err);
        }
      },
      fail: (err) => reject(new Error(err.errMsg || '网络错误'))
    });
  });
}

// 业务请求：401 自动重登一次后重试
function request(options) {
  return rawRequest(options).catch((err) => {
    if (err.statusCode === 401 && !options.skipAuth && !options._retried) {
      clearSession();
      return login().then(() => request(Object.assign({}, options, { _retried: true })));
    }
    if (!options.silent) {
      wx.showToast({ title: err.message || '请求失败', icon: 'none' });
    }
    throw err;
  });
}

// 文件上传：multipart，附带表单字段
function uploadFile({ url, filePath, name = 'file', formData = {} }) {
  const token = getToken();
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: config.API_BASE + url,
      filePath,
      name,
      formData,
      header: token ? { Authorization: 'Bearer ' + token } : {},
      success: (res) => {
        let body = {};
        try {
          body = JSON.parse(res.data);
        } catch (e) {
          body = {};
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && body.success) {
          resolve(body.data);
        } else if (res.statusCode === 401) {
          const err = new Error('UNAUTHORIZED');
          err.statusCode = 401;
          reject(err);
        } else {
          reject(new Error(body.message || '上传失败'));
        }
      },
      fail: (err) => reject(new Error(err.errMsg || '上传失败'))
    });
  });
}

function uploadFileWithRetry(options) {
  return uploadFile(options).catch((err) => {
    if (err.statusCode === 401 && !options._retried) {
      clearSession();
      return login().then(() => uploadFileWithRetry(Object.assign({}, options, { _retried: true })));
    }
    wx.showToast({ title: err.message || '上传失败', icon: 'none' });
    throw err;
  });
}

// 文件下载（带鉴权）：用于台账导出等返回二进制文件的接口
function downloadFile({ url, filePath }) {
  const token = getToken();
  return new Promise((resolve, reject) => {
    wx.downloadFile({
      url: config.API_BASE + url,
      header: token ? { Authorization: 'Bearer ' + token } : {},
      filePath,
      success: (res) => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(res.filePath || res.tempFilePath);
        } else if (res.statusCode === 401) {
          const err = new Error('UNAUTHORIZED');
          err.statusCode = 401;
          reject(err);
        } else {
          reject(new Error('下载失败(' + res.statusCode + ')'));
        }
      },
      fail: (err) => reject(new Error(err.errMsg || '下载失败'))
    });
  });
}

function ensureLogin() {
  if (getToken()) return Promise.resolve(wx.getStorageSync(config.USER_KEY));
  return login().then((data) => (data ? data.user : null));
}

module.exports = {
  request,
  downloadFile,
  login,
  ensureLogin,
  uploadFile: uploadFileWithRetry,
  getToken,
  clearSession
};
