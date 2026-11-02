import React, { useCallback, useEffect, useRef, useState } from 'react';
import './skills.css';

const agents = { openminis: 'OpenMinis' };
const message = error => String(error?.message || error).replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '');
export default function SkillsPanel({ api, mode, onMode, onClose, threadId }) {
  const [data, setData] = useState(null), [query, setQuery] = useState(''), [pack, setPack] = useState('');
  const agent = 'openminis';
  const [scope, setScope] = useState(threadId ? 'session' : 'global'), [pane, setPane] = useState('list');
  const effectiveScope = threadId && scope === 'session' ? 'session' : 'global';
  const scopeArgs = { agent, scope: effectiveScope, ...(effectiveScope === 'session' ? { threadId } : {}) };
  const contextKey = `${effectiveScope}:${threadId || ''}`, currentContext = useRef(contextKey);
  currentContext.current = contextKey;
  const [selected, setSelected] = useState(''), [resource, setResource] = useState('SKILL.md'), [resources, setResources] = useState([]), [preview, setPreview] = useState(null);
  const [error, setError] = useState(''), [busy, setBusy] = useState(false), [reading, setReading] = useState(false), [enabledOnly, setEnabledOnly] = useState(false);
  const generation = useRef(0), previewGeneration = useRef(0);
  const refresh = useCallback(async () => {
    const request = ++generation.current;
    try {
      const next = await api.request('skills_list', { query, pack, ...scopeArgs, enabledOnly });
      if (request !== generation.current) return;
      setData(next); setSelected(current => next.skills.some(skill => skill.id === current) ? current : next.skills[0]?.id || ''); setError('');
    } catch (failure) { if (request === generation.current) setError(message(failure)); }
  }, [api, query, pack, effectiveScope, threadId, enabledOnly]);
  useEffect(() => { refresh(); return () => { generation.current += 1; }; }, [refresh]);
  useEffect(() => api.subscribe?.(event => { if (event?.type === 'skills' || event?.type === 'skills-changed' || (event?.type === 'refresh' && (!event.pluginId || event.pluginId === 'forge.skills'))) refresh(); }), [api, refresh]);
  useEffect(() => { setResource('SKILL.md'); setPreview(null); setResources([]); }, [selected, effectiveScope, threadId]);
  useEffect(() => {
    if (!selected) return;
    let current = true;
    api.request('skills_files', { id: selected, ...scopeArgs, requireEnabled: false }).then(next => { if (current) setResources(next.files); }).catch(failure => { if (current) setError(message(failure)); });
    return () => { current = false; };
  }, [api, selected, effectiveScope, threadId]);
  useEffect(() => {
    const request = ++previewGeneration.current;
    setPreview(null);
    if (!selected) { setReading(false); return; }
    setReading(true);
    api.request('skills_read', { id: selected, path: resource, ...scopeArgs, requireEnabled: false, limit: 30000 }).then(next => { if (request === previewGeneration.current) setPreview(next); }).catch(failure => { if (request === previewGeneration.current) setError(message(failure)); }).finally(() => { if (request === previewGeneration.current) setReading(false); });
    return () => { previewGeneration.current += 1; };
  }, [api, selected, resource, effectiveScope, threadId]);
  async function toggle(skill, enabled = !skill.enabled) {
    if (busy) return;
    setBusy(true); setError('');
    generation.current += 1;
    const context = contextKey;
    try { await api.request('skills_set', { id: skill.id, enabled, ...scopeArgs, expectedRevision: data.revision }); if (currentContext.current === context) await refresh(); }
    catch (failure) { if (currentContext.current === context) { await refresh(); setError(message(failure)); } }
    finally { setBusy(false); }
  }
  async function more() {
    if (reading || preview?.nextOffset === null || !preview) return;
    const request = previewGeneration.current;
    setReading(true);
    try {
      const next = await api.request('skills_read', { id: selected, path: resource, ...scopeArgs, requireEnabled: false, offset: preview.nextOffset, limit: 30000 });
      if (request === previewGeneration.current) setPreview(current => ({ ...next, content: current.content + next.content, offset: 0 }));
    } catch (failure) { if (request === previewGeneration.current) setError(message(failure)); }
    finally { if (request === previewGeneration.current) setReading(false); }
  }
  const skill = data?.skills.find(item => item.id === selected);
  const totalEnabled = data?.packs.reduce((sum, item) => sum + item.enabledCount, 0) || 0;
  return <main className="fast-skills" aria-label="技能包配置">
    <header className="fast-skills-header">
      <div><span className="fast-skills-eyebrow">CODEG · OPENMINIS</span><h1>技能包</h1></div>
      <div className="fast-skills-actions"><button onClick={refresh} aria-label="刷新技能包">↻</button>{onMode && <button onClick={() => onMode(mode === 'expanded' ? 'right' : 'expanded')}>{mode === 'expanded' ? '并排' : '展开'}</button>}{onClose && <button onClick={onClose} aria-label="关闭技能包">×</button>}</div>
    </header>
    <p className="fast-skills-intro">{data ? `${data.inventory.packs} 组 · ${data.inventory.skills} 项技能 · ${data.inventory.resourceFiles} 个参考资源` : '正在读取内置技能…'}。默认停用，OpenMinis 可按需读取已启用技能与资源。</p>
    <div className="fast-skills-controls">
      <label>智能体<select aria-label="技能目标智能体" value={agent} disabled>{Object.entries(agents).map(([id, name]) => <option key={id} value={id}>{name}</option>)}</select></label>
      <label>应用范围<select aria-label="技能启用范围" value={effectiveScope} disabled={busy} onChange={event => setScope(event.target.value)}><option value="global">全局默认</option><option value="session" disabled={!threadId}>当前会话覆盖</option></select></label>
      <label className="fast-skills-only"><input type="checkbox" checked={enabledOnly} onChange={event => setEnabledOnly(event.target.checked)} />仅看已启用（{totalEnabled}）</label>
    </div>
    <input className="fast-skills-search" aria-label="搜索技能" placeholder="搜索技能名称、用途…" value={query} onChange={event => setQuery(event.target.value)} />
    <nav className="fast-skills-packs" aria-label="技能包筛选"><button className={!pack ? 'active' : ''} onClick={() => setPack('')}>全部</button>{data?.packs.map(item => <button key={item.id} className={pack === item.id ? 'active' : ''} onClick={() => setPack(item.id)}>{item.name}<small>{item.count}</small></button>)}</nav>
    {error && <div className="fast-skills-error" role="alert">{error}</div>}
    <nav className="fast-skills-mobile-nav" aria-label="技能页面"><button className={pane === 'list' ? 'active' : ''} onClick={() => setPane('list')}>技能列表</button><button className={pane === 'detail' ? 'active' : ''} onClick={() => setPane('detail')} disabled={!selected}>正文与资源</button></nav>
    <div className="fast-skills-layout" data-pane={pane}>
      <section className="fast-skills-list" aria-label="技能列表">
        {data && !data.skills.length && <p className="fast-skills-empty">没有匹配的技能。</p>}
        {data?.skills.map(item => <article className={`fast-skills-item ${selected === item.id ? 'selected' : ''}`} key={item.id}>
          <button className="fast-skills-select" onClick={() => { setSelected(item.id); setPane('detail'); }} aria-pressed={selected === item.id}><span className="fast-skills-item-title">{item.name}<small>{item.pack}</small></span><span>{item.description}</span><code>{item.id}</code></button>
          <label className="fast-skills-toggle"><input aria-label={`${agents[agent]} 启用 ${item.name}`} type="checkbox" checked={item.enabled} disabled={busy} onChange={() => toggle(item)} /><span>{item.enabled ? '已启用' : '启用'}</span></label>
        </article>)}
      </section>
      <section className="fast-skills-detail" aria-label="技能内容预览">
        {skill ? <>
          <div className="fast-skills-detail-title"><h2>{skill.name}</h2><span>{skill.fileCount} 个文件 · {skill.license}</span></div>
          <p className="fast-skills-compatibility">{skill.compatibility.detail}</p>
          {effectiveScope === 'session' && <div className="fast-skills-inheritance">{skill.inherited ? `继承全局：${skill.globalEnabled ? '启用' : '停用'}` : `本会话覆盖：${skill.enabled ? '启用' : '停用'}`}<button disabled={busy || skill.inherited} onClick={() => toggle(skill, null)}>恢复继承全局</button></div>}
          <div className="fast-skills-model-note">{skill.enabled ? `${agents[agent]} 已可读取此技能。` : '启用后，模型才会通过技能工具读取。'}{skill.enabledAgents.length > 0 && ` 已启用：${skill.enabledAgents.map(id => agents[id]).join('、')}。`}</div>
          <label className="fast-skills-resource-label">文件<select aria-label="技能资源文件" value={resource} onChange={event => setResource(event.target.value)}>{(resources.length ? resources : [{ path: 'SKILL.md' }]).map(file => <option key={file.path} value={file.path}>{file.path}</option>)}</select></label>
          {reading && !preview && <p className="fast-skills-empty">正在读取…</p>}
          {preview && <><p className="fast-skills-model-note">{preview.instructions}</p><pre className="fast-skills-content">{preview.binary ? '二进制资源已保留在本机，读取不会执行资源。' : preview.content}</pre>{preview.nextOffset !== null && preview.nextOffset !== undefined && <button className="fast-skills-more" disabled={reading} onClick={more}>{reading ? '正在读取…' : `继续读取（${preview.nextOffset}/${preview.totalCharacters}）`}</button>}<details className="fast-skills-provenance"><summary>来源与 SHA256</summary><p>{preview.source?.repository}</p><code>{preview.source?.commit}</code><p>{preview.source?.path}</p><code>{preview.sha256}</code></details></>}
        </> : <p className="fast-skills-empty">选择一项技能查看正文、依赖和参考文件。</p>}
      </section>
    </div>
    <footer className="fast-skills-footer">设置与资源保存在手机本机。启用不安装依赖或执行脚本，协作遵循用户授权。</footer>
  </main>;
}
