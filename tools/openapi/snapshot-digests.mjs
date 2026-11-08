import fs from 'node:fs';
import path from 'node:path';
import { root, hash, json, snapshotFiles } from './refresh.mjs';

// 显式更新快照（raw/ 下的文件）之后重算 sources.json 里的摘要；不会下载或覆盖原始文件。
// 来源的提交、host、基线计数只在 sources.json 里一份，换快照时手改那里；合同工具的版本取 package.json。
const file = path.join(root, 'sources.json');
const current = JSON.parse(fs.readFileSync(file, 'utf8'));
const { dependencies } = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const sources = current.sources.map(source => ({
  ...source,
  files: Object.fromEntries(snapshotFiles(source.id).map(name => [name, hash(fs.readFileSync(path.join(root, name)))])),
}));
const tools = Object.fromEntries(Object.keys(current.tools).map(name => [name, dependencies[name]]));
fs.writeFileSync(file, json({ sources, tools }));
