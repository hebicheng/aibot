// 扫码登录确认页：用户扫 Web 端小程序码进入此页，确认后通知后端完成登录。
const config = require('../../config');

Page({
  data: {
    state: '',
    code: '',
    status: 'idle', // idle | confirming | success | error
    error: '',
    safeTop: 0, // 状态栏高度（自定义导航需手动避开）
    devPreview: false // 开发者工具直接预览（无扫码参数）标记
  },

  onLoad(options) {
    // 自定义导航：用状态栏高度给页面顶部留白，避免内容被状态栏遮挡/露黑边
    try {
      const info = (wx.getWindowInfo && wx.getWindowInfo()) || wx.getSystemInfoSync();
      if (info && info.statusBarHeight) this.setData({ safeTop: info.statusBarHeight });
    } catch (e) { /* 忽略，默认 0 */ }

    // 小程序码的 scene 参数即 state（需 decodeURIComponent）
    const scene = options.scene || options.state || '';
    const state = decodeURIComponent(scene);
    if (state) {
      this.setData({ state });
      // 提前获取 wx.login code，供确认时使用
      wx.login({
        success: (res) => {
          if (res.code) this.setData({ code: res.code });
        }
      });
    } else {
      // 开发者工具直接预览（未扫码，无 scene 参数）：用占位 state 渲染布局，便于检查 UI/安全区
      this.setData({ state: 'devpreview0000000000000000000000ab', devPreview: true });
    }
  },

  confirmLogin() {
    const { state, code, status } = this.data;
    if (!state || !code || status === 'confirming') return;
    this.setData({ status: 'confirming' });
    wx.request({
      url: config.API_BASE + '/api/auth/wechat-qr/confirm',
      method: 'POST',
      data: { state, code },
      header: { 'content-type': 'application/json' },
      success: (res) => {
        const body = res.data || {};
        if (res.statusCode >= 200 && res.statusCode < 300 && body.success) {
          this.setData({ status: 'success' });
        } else {
          this.setData({ status: 'error', error: body.message || '确认失败，请重试' });
        }
      },
      fail: () => {
        this.setData({ status: 'error', error: '网络异常，请检查后端服务后重试' });
      }
    });
  },

  copyState() {
    // 联调辅助：复制 state 便于排查
    wx.setClipboardData({ data: this.data.state });
  },

  goHome() {
    // 扫码进入的是新的页面栈，用 reLaunch 直接回到小程序首页
    wx.reLaunch({ url: '/pages/index/index' });
  }
});
