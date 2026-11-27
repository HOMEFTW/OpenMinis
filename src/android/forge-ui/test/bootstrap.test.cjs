const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../bootstrap.js'), 'utf8');

function setup() {
  const elements = {
    'forge-startup': {hidden:false},
    'forge-startup-message': {textContent:'正在加载插件…'},
    'forge-startup-retry': {hidden:true},
  };
  const events = {}, replies = [];
  let timeout, cleared = false, reloaded = false;
  const window = {ForgeAndroid:{postMessage:raw=>replies.push(JSON.parse(raw))},
    addEventListener:(type,fn)=>{events[type]=fn;},location:{reload:()=>{reloaded=true;}}};
  vm.runInNewContext(source, {window,document:{getElementById:id=>elements[id]},
    setTimeout:fn=>{timeout=fn;return 1;},clearTimeout:()=>{cleared=true;}});
  return {elements,events,replies,window,timeout:()=>timeout(),cleared:()=>cleared,reloaded:()=>reloaded};
}

test('mounted UI hides startup and acknowledges native readiness',()=>{
  const s=setup();s.window.forgeStartup.ready();
  assert.equal(s.elements['forge-startup'].hidden,true);
  assert.equal(s.replies[0].method,'ready');assert.equal(s.cleared(),true);
});
test('missing bundle shows retry and reports resource failure',()=>{
  const s=setup();s.events.error({target:{tagName:'SCRIPT'}});
  assert.equal(s.elements['forge-startup'].hidden,false);
  assert.equal(s.elements['forge-startup-retry'].hidden,false);
  assert.equal(s.replies[0].args.code,'resource');
  s.elements['forge-startup-retry'].onclick();assert.equal(s.reloaded(),true);
});
test('unsupported script syntax is visible without reflecting exception contents',()=>{
  const s=setup();s.events.error({message:'private data in exception',error:Error('private')});
  assert.match(s.elements['forge-startup-message'].textContent,/插件启动失败/);
  assert.equal(s.replies[0].args.code,'script');
  assert.equal(JSON.stringify(s.replies).includes('private'),false);
});
test('startup timeout does not leave an empty page',()=>{
  const s=setup();s.timeout();
  assert.equal(s.elements['forge-startup-retry'].hidden,false);
  assert.equal(s.replies[0].args.code,'timeout');
});
test('render failure after mounting restores the fallback',()=>{
  const s=setup();s.window.forgeStartup.ready();s.window.forgeStartup.fail();
  assert.equal(s.elements['forge-startup'].hidden,false);
  assert.equal(s.replies.at(-1).args.code,'render');
});
