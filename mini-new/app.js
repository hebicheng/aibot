// 应用入口：全局层级基线与初始化。
App({
  globalData: {
    systemInfo: null,
    launchTs: Date.now()
  },

  onLaunch() {
    try {
      this.globalData.systemInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    } catch (error) {
      this.globalData.systemInfo = null;
    }

    // 小程序版本更新：发布新版本后，已打开的旧版不会自动刷新，
    // 需主动检测并提示用户重启以应用最新包。开发者工具/本地预览不会触发更新。
    this.checkUpdate();
  },

  checkUpdate() {
    if (!wx.getUpdateManager) return; // 基础库过低，跳过
    const updateManager = wx.getUpdateManager();
    updateManager.onCheckForUpdate((res) => {
      if (res.hasUpdate) {
        wx.showToast({ title: '发现新版本，准备更新', icon: 'none', duration: 1500 });
      }
    });
    updateManager.onUpdateReady(() => {
      wx.showModal({
        title: '更新提示',
        content: '新版本已就绪，重启后生效',
        confirmText: '立即重启',
        success: (res) => {
          if (res.confirm) updateManager.applyUpdate(); // 重启应用新版本
        }
      });
    });
    updateManager.onUpdateFailed(() => {
      wx.showToast({ title: '更新失败，请杀掉小程序重试', icon: 'none' });
    });
  }
});
