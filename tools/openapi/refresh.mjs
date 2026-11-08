import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';
import openapiTS, { astToString } from 'openapi-typescript';

export const root = path.dirname(fileURLToPath(import.meta.url));
export const methods = ['get', 'post', 'put', 'patch', 'delete', 'options', 'head', 'trace'];
export const escape = value => value.replaceAll('~', '~0').replaceAll('/', '~1');
export const at = (value, pointer) => pointer.replace(/^#/, '').split('/').slice(1).reduce((v, k) => v?.[k.replaceAll('~1', '/').replaceAll('~0', '~')], value);
export const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
export const json = value => JSON.stringify(value, null, 2) + '\n';
export const bindingKey = ({ source, document, pointer }) => `${source}:${document}:${pointer}`;

/** 某个来源的快照目录（raw/<id>/）下的全部文件，相对本目录、按路径排序。 */
export function snapshotFiles(id) {
  const walk = directory => fs.readdirSync(directory, { withFileTypes: true })
    .flatMap(e => e.isDirectory() ? walk(path.join(directory, e.name)) : [path.join(directory, e.name)]);
  return walk(path.join(root, 'raw', id)).map(file => path.relative(root, file).split(path.sep).join('/')).sort();
}

export function bundle(entry) {
  const cache = new Map();
  const load = file => {
    if (!cache.has(file)) cache.set(file, YAML.parse(fs.readFileSync(file, 'utf8')));
    return cache.get(file);
  };
  function visit(value, file, active = new Set()) {
    if (Array.isArray(value)) return value.map(v => visit(v, file, active));
    if (!value || typeof value !== 'object') return value;
    if (value.$ref) {
      const [relative, fragment = ''] = value.$ref.split('#');
      const targetFile = relative ? path.resolve(path.dirname(file), relative) : file;
      const pointer = '#' + fragment;
      if (targetFile === entry && !relative) return { ...value };
      const key = `${targetFile}:${pointer}`;
      if (active.has(key)) throw new Error(`外部循环引用需要显式重定位：${key}`);
      const target = at(load(targetFile), pointer);
      if (!target) throw new Error(`悬空引用：${key}`);
      return visit({ ...target, ...Object.fromEntries(Object.entries(value).filter(([k]) => k !== '$ref')) }, targetFile, new Set([...active, key]));
    }
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, visit(v, file, active)]));
  }
  return visit(load(entry), entry);
}

export function indexDocument(source, document, doc) {
  const schemas = [];
  const operations = [];
  const visited = new Set();
  function walk(schema, pointer, usage) {
    if (visited.has(pointer)) return;
    visited.add(pointer);
    if (!schema || typeof schema !== 'object') throw new Error(`非法 schema：${pointer}`);
    schemas.push({ source, document, pointer, usage, schema });
    if (schema.$ref && at(doc, schema.$ref) === undefined) throw new Error(`悬空引用：${schema.$ref}`);
    for (const [k, value] of Object.entries(schema.properties ?? {})) walk(value, `${pointer}/properties/${escape(k)}`, usage);
    for (const key of ['items', 'additionalProperties']) if (schema[key] && typeof schema[key] === 'object') walk(schema[key], `${pointer}/${key}`, usage);
    for (const key of ['allOf', 'anyOf', 'oneOf']) (schema[key] ?? []).forEach((s, i) => walk(s, `${pointer}/${key}/${i}`, usage));
  }
  for (const [name, schema] of Object.entries(doc.components?.schemas ?? {})) walk(schema, `#/components/schemas/${escape(name)}`, 'component');
  for (const [url, item] of Object.entries(doc.paths)) {
    for (const method of methods) {
      const op = item[method];
      if (!op) continue;
      const pointer = `#/paths/${escape(url)}/${method}`;
      const roots = [];
      const add = (s, p, usage) => { roots.push(p); walk(s, p, usage); };
      for (const [i, p] of [...(item.parameters ?? []), ...(op.parameters ?? [])].entries()) {
        const param = p.$ref ? at(doc, p.$ref) : p;
        if (param.schema) add(param.schema, `${pointer}/parameters/${i}/schema`, `${param.in}:${param.name}`);
      }
      for (const [media, content] of Object.entries(op.requestBody?.content ?? {})) if (content.schema) add(content.schema, `${pointer}/requestBody/content/${escape(media)}/schema`, 'request');
      for (const [status, response] of Object.entries(op.responses ?? {})) {
        const resolved = response.$ref ? at(doc, response.$ref) : response;
        for (const [media, content] of Object.entries(resolved.content ?? {})) if (content.schema) add(content.schema, `${pointer}/responses/${status}/content/${escape(media)}/schema`, `response:${status}`);
        for (const [header, h] of Object.entries(resolved.headers ?? {})) if (h.schema) add(h.schema, `${pointer}/responses/${status}/headers/${escape(header)}/schema`, `header:${header}`);
      }
      operations.push({ source, document, method: method.toUpperCase(), path: url, operationId: op.operationId ?? null, summary: op.summary ?? '', security: op.security ?? doc.security ?? [], schemas: roots });
    }
  }
  return { schemas, operations };
}

export async function refresh(check = false) {
  const sources = JSON.parse(fs.readFileSync(path.join(root, 'sources.json')));
  const outputs = new Map();
  const contracts = { format: 1, sources: sources.sources, schemas: [], operations: [] };
  for (const source of sources.sources) {
    // 快照目录里的每个文件都要登记摘要：没登记的文件也能被 $ref 读进来，却不受摘要保护
    const unlisted = snapshotFiles(source.id).filter(file => !Object.hasOwn(source.files, file));
    if (unlisted.length) throw new Error(`原始文件没有登记摘要：${unlisted.join('、')}`);
    for (const [file, expected] of Object.entries(source.files)) {
      if (hash(fs.readFileSync(path.join(root, file))) !== expected) throw new Error(`原始文件摘要变化：${file}`);
    }
    const doc = bundle(path.join(root, source.entry));
    const overlay = JSON.parse(fs.readFileSync(path.join(root, `overlays/${source.id}.json`)));
    for (const action of overlay.actions) {
      const segments = action.pointer.split('/').slice(1).map(k => k.replaceAll('~1', '/').replaceAll('~0', '~'));
      const last = segments.pop();
      let parent = doc;
      for (const key of segments) parent = parent[key];
      if (!action.reason) throw new Error('overlay 必须记录理由');
      if (action.op === 'remove') delete parent[last];
      else parent[last] = action.value;
    }
    const document = `generated/${source.id}.bundled.json`;
    const indexed = indexDocument(source.id, document, doc);
    const counts = { paths: Object.keys(doc.paths).length, operations: indexed.operations.length, schemas: Object.keys(doc.components.schemas).length };
    if (JSON.stringify(counts) !== JSON.stringify(source.baseline)) throw new Error(`基线变更：${source.id} ${json(counts)}`);
    contracts.schemas.push(...indexed.schemas);
    contracts.operations.push(...indexed.operations);
    outputs.set(document, json(doc));
    outputs.set(`generated/${source.id}-api.d.ts`, astToString(await openapiTS(doc)));
  }
  outputs.set('generated/contracts.json', json(contracts));
  const capabilities = JSON.parse(fs.readFileSync(path.join(root, 'overlays/capabilities.json')));
  outputs.set('generated/capabilities.json', json(contracts.operations.map(op => ({ ...op, ...capabilities.operations[`${op.source}:${op.method}:${op.path}`], verification: '仅快照与源码静态核对；未进行真实网络请求' }))));
  let differences = 0;
  for (const [file, content] of outputs) {
    const target = path.join(root, file);
    if (check) {
      if (!fs.existsSync(target) || fs.readFileSync(target, 'utf8') !== content) { console.error(`派生产物不同：${file}`); differences++; }
    } else { fs.mkdirSync(path.dirname(target), { recursive: true }); fs.writeFileSync(target, content); }
  }
  if (differences) throw new Error(`${differences} 份派生产物需要刷新`);
  console.log(`合同${check ? '一致性检查' : '刷新'}：${contracts.operations.length} operations，${contracts.schemas.length} schema pointers`);
  return contracts;
}
if (process.argv[1] === fileURLToPath(import.meta.url)) await refresh(process.argv.includes('--check'));
