const pending = new Map(), listeners = new Set();
let sequence = 0;
function deliver(data) {
  if (data.id !== undefined) {
    const item = pending.get(data.id); if (!item) return;
    pending.delete(data.id); clearTimeout(item.timer);
    data.error ? item.reject(new Error(data.error)) : item.resolve(data.result);
  }
  if (data.event) {
    const event = data.event;
    if(event.type === 'sdk-event') window.postMessage({forgePluginEvent:{name:event.name,value:event.value,metadata:{sourcePluginId:event.sourcePluginId,threadId:event.threadId}}},location.origin);
    if(event.type === 'ui-contribution') contribution(event);
    if(event.type === 'sdk-output') output(event.value);
    for (const listener of listeners) listener(event);
  }
}
function contribution(event) {
  const key='forge-contribution-'+event.surface+'-'+event.item.id;
  document.getElementById(key)?.remove();
  if(event.remove) return;
  let bar=document.getElementById('forge-contributions');
  if(!bar) { bar=document.createElement('nav'); bar.id='forge-contributions'; bar.setAttribute('aria-label','插件操作'); document.body.append(bar); }
  const button=document.createElement('button');button.id=key;button.textContent=event.item.title;
  button.onclick=()=> event.item.command
    ? window.postMessage({forgePluginCommand:{id:++sequence,command:event.item.command,args:{}}},location.origin)
    : window.CodexForge.panels.open(event.item.panel);
  bar.append(button);
}
function output(value) {
  document.getElementById('forge-output')?.remove();
  const section=document.createElement('details');section.id='forge-output';section.open=true;
  const summary=document.createElement('summary');summary.textContent='插件输出';section.append(summary);
  for(const item of value?.items || []) { const article=document.createElement('article'),title=document.createElement('strong'),text=document.createElement('pre');title.textContent=item.title;text.textContent=item.text;article.append(title,text);section.append(article); }
  document.body.append(section);
  window.postMessage({forgePluginEvent:{name:'output.changed',value}},location.origin);
}
window.forgeAndroidReply = deliver;
window.fastCanvas = {
  request(method, args = {}) {
    if (!window.ForgeAndroid) return Promise.reject(new Error('此页面需要在 OpenMinis 中打开'));
    return new Promise((resolve, reject) => {
      const id = ++sequence;
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('请求超时，请重试')); }, 120000);
      pending.set(id, { resolve, reject, timer });
      window.ForgeAndroid.postMessage(JSON.stringify({ id, method, args }));
    });
  },
  subscribe(callback) { listeners.add(callback); return () => listeners.delete(callback); },
};
// Keep the public Forge API 2 SDK transport separate from built-in service calls.
window.addEventListener('message', async event => {
  if (event.source !== window || !event.data?.forgePluginRequest) return;
  const {id,method,args} = event.data.forgePluginRequest;
  try { const result = await window.fastCanvas.request('sdk', {method,args}); window.postMessage({forgePluginReply:{id,result}}, location.origin); }
  catch (error) { window.postMessage({forgePluginReply:{id,error:error.message}}, location.origin); }
});

