// Keep this bootstrap compatible with older WebViews, even when app.js cannot parse.
(function () {
  var completed = false;
  var status = document.getElementById('forge-startup');
  var message = document.getElementById('forge-startup-message');
  var retry = document.getElementById('forge-startup-retry');
  function report(method, code) {
    if (window.ForgeAndroid) {
      window.ForgeAndroid.postMessage(JSON.stringify({ id: 0, method: method, args: { code: code } }));
    }
  }
  function failed(code) {
    if (completed) return;
    clearTimeout(timer);
    status.hidden = false;
    message.textContent = '插件启动失败，请重试；仍无法打开时请更新 Android System WebView。';
    retry.hidden = false;
    report('startup-error', code);
  }
  var timer = setTimeout(function () { failed('timeout'); }, 15000);
  retry.onclick = function () { window.location.reload(); };
  window.addEventListener('error', function (event) {
    if (event.target && event.target.tagName === 'SCRIPT') failed('resource');
    else if (event.error || event.message) failed('script');
  }, true);
  window.forgeStartup = {
    ready: function () {
      completed = true;
      clearTimeout(timer);
      status.hidden = true;
      report('ready', '');
    },
    fail: function () { completed = false; failed('render'); }
  };
}());
