import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
const repo = path.resolve(import.meta.dirname, '..');
const source = path.join(repo, 'src/android/forge-ui');
const require = createRequire(path.join(source, 'package.json'));
const {build} = require('esbuild');
const out = path.join(repo, 'src/android/app/src/main/assets/forge/ui');
fs.mkdirSync(out, {recursive:true});
const result = await build({entryPoints:[path.join(source,'app.jsx')],outfile:path.join(out,'app.js'),bundle:true,minify:true,metafile:true,format:'iife',target:['chrome80'],define:{'process.env.NODE_ENV':'"production"'},legalComments:'linked'});
for (const file of ['index.html','forge-sdk.js']) fs.copyFileSync(path.join(source,file),path.join(out,file));
let notices = 'OpenMinis Forge UI: adapted from Codex Forge 0.3.2 (MIT).\n\n';
const packages = new Set();
for (const input of Object.keys(result.metafile.inputs)) {
  const absolute = path.resolve(input), marker = path.sep+'node_modules'+path.sep, at = absolute.lastIndexOf(marker);
  if (at<0) continue;
  const parts = absolute.slice(at+marker.length).split(path.sep), count = parts[0].startsWith('@')?2:1;
  packages.add(path.join(absolute.slice(0,at+marker.length),...parts.slice(0,count)));
}
for(const root of packages) {
  const pkg=JSON.parse(fs.readFileSync(path.join(root,'package.json'),'utf8'));
  notices+=`${pkg.name} ${pkg.version} (${pkg.license})\n`;
  const license=fs.readdirSync(root).find(file=>/^licen[cs]e/i.test(file));
  if(license) notices+=fs.readFileSync(path.join(root,license),'utf8')+'\n\n';
}
fs.writeFileSync(path.join(out,'THIRD-PARTY-NOTICES.txt'),notices);
console.log('Forge Android UI built: '+out);
