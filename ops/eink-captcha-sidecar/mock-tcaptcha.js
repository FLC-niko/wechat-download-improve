// Local-only TencentCaptcha browser shim for -2041 integration testing.
// It implements the subset of the official API our verification page uses and
// asks the local sidecar /solve endpoint for a result. It never contacts
// Tencent and never touches a real site captcha.
(function () {
  var solveUrl = window.__MOCK_TCAPTCHA_SOLVE_URL__ || '/solve';

  function resolveHolder(container) {
    if (!container || typeof document === 'undefined') return null;
    if (typeof container === 'string') return document.getElementById(container);
    if (typeof container === 'object' && container.nodeType === 1) return container;
    return null;
  }

  function TencentCaptcha(container, appid, callback, options) {
    if (typeof container === 'string' && typeof appid === 'function') {
      options = callback || {};
      callback = appid;
      appid = container;
      container = null;
    }
    if (typeof appid !== 'string' || appid === '') {
      throw new Error('TencentCaptcha mock: appid is required');
    }
    this._appid = appid;
    this._callback = typeof callback === 'function' ? callback : function () {};
    this._container = container || null;
    this._options = options || {};
  }

  TencentCaptcha.prototype.show = function () {
    var self = this;
    var holder = resolveHolder(this._container);
    if (holder) holder.textContent = '本地 sidecar 模拟验证中（不发往腾讯）…';
    fetch(solveUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        appid: this._appid,
        entry_url: typeof location !== 'undefined' ? location.href : '',
      }),
    })
      .then(function (response) {
        return response.json();
      })
      .then(function (payload) {
        if (holder) holder.textContent = '本地 sidecar 已返回结果';
        var ok = payload && payload.ok === true;
        var result = {
          ret: ok ? 0 : 1,
          ticket: (payload && payload.ticket) || 'trerror-mock',
          randstr: (payload && payload.randstr) || '',
          errorMessage: (payload && payload.error) || '',
          appid: self._appid,
          mock: true,
        };
        setTimeout(function () {
          self._callback(result);
        }, 30);
      })
      .catch(function (error) {
        self._callback({
          ret: 1,
          ticket: 'trerror-mock',
          randstr: '',
          errorMessage: String(error),
          appid: self._appid,
          mock: true,
        });
      });
  };

  TencentCaptcha.prototype.hide = function () {};
  TencentCaptcha.prototype.destroy = function () {};

  window.TencentCaptcha = TencentCaptcha;
})();
