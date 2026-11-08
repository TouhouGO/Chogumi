import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { root, at, bindingKey, json } from './refresh.mjs';

export const modelRoot = path.resolve(root, '../../core/network/src/main/kotlin/io/github/touhougo/chogumi/core/network/model');
export const unions = {
  'v0.InfoboxValueDto': ['InfoboxTextDto', 'InfoboxListDto'],
  'v0.InfoboxEntryDto': ['InfoboxKeyValueDto', 'InfoboxOnlyValueDto'],
  'v0.ErrorDetailsDto': ['ErrorDetailsTextDto', 'ErrorDetailsObjectDto'],
  'p1.RaKuenTopicDto': ['RaKuenGroupTopicDto', 'RaKuenSubjectTopicDto', 'RaKuenEpisodeDto', 'RaKuenCharacterDto', 'RaKuenPersonDto'],
};
const rename = {
  v0: { UserSubjectCollection: 'UserCollectionDto', UserSubjectCollectionModifyPayload: 'CollectionModifyDto', v0_subject_relation: 'RelatedSubjectDto', v0_RelatedSubject: 'MonoRelatedSubjectDto' },
  p1: { SubjectRating: 'P1RatingDto', SlimSubject: 'P1SlimSubjectDto', SlimUser: 'P1UserDto', TrendingSubject: 'P1TrendingSubjectDto' },
};
function splitTop(text) {
  const result = []; let start = 0, depth = 0, quote = false;
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '"' && text[i - 1] !== '\\') quote = !quote;
    if (!quote) {
      if ('(<['.includes(text[i])) depth++;
      if (')>]'.includes(text[i])) depth--;
      if (text[i] === ',' && depth === 0) { result.push(text.slice(start, i)); start = i + 1; }
    }
  }
  result.push(text.slice(start)); return result.filter(v => v.trim());
}
export function parseModels() {
  const result = new Map();
  for (const source of ['v0', 'p1']) for (const name of fs.readdirSync(path.join(modelRoot, source)).filter(n => n.endsWith('.kt'))) {
    const file = path.join(modelRoot, source, name);
    const text = fs.readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
    const re = /(?:data\s+|value\s+)?class\s+(\w+)(?:<([^>]+)>)?\s*\(/g;
    for (const match of text.matchAll(re)) {
      let i = match.index + match[0].length, depth = 1, end = i;
      while (depth && end < text.length) { if (text[end] === '(') depth++; if (text[end] === ')') depth--; end++; }
      const fields = splitTop(text.slice(i, end - 1)).filter(f => /\bval\b/.test(f) && !f.includes('@kotlinx.serialization.Transient')).map(field => {
        const wire = field.match(/@SerialName\("([^"]+)"\)/)?.[1];
        const property = field.match(/\bval\s+(`[^`]+`|\w+)\s*:\s*([\s\S]+)/);
        if (!property) throw new Error(`无法解析字段：${field}`);
        const [type] = property[2].split('=');
        return { name: wire ?? property[1].replaceAll('`', ''), type: type.trim().replaceAll(/\s/g, ''), optional: property[2].includes('=') && !field.includes('@Required') };
      });
      result.set(`${source}.${match[1]}`, { source, name: match[1], fqcn: `io.github.touhougo.chogumi.core.network.model.${source}.${match[1]}`, file: path.relative(path.resolve(root, '../..'), file), parameters: match[2]?.split(',').map(v => v.trim()) ?? [], fields, inline: text.slice(Math.max(0, match.index - 50), match.index + 15).includes('@JvmInline') });
    }
    for (const match of text.matchAll(/class\s+(EmptyObjectDto)\s*(?:\n|$)/g)) result.set(`${source}.${match[1]}`, { source, name: match[1], fqcn: `io.github.touhougo.chogumi.core.network.model.${source}.${match[1]}`, file: path.relative(path.resolve(root, '../..'), file), parameters: [], fields: [] });
  }
  return result;
}
export function createChecker(documents, models = parseModels()) {
  function resolve(source, schema) {
    if (schema.$ref) return resolve(source, at(documents[source], schema.$ref));
    if (schema.allOf) {
      if (schema.allOf.length === 1) {
        const { allOf, ...siblings } = schema;
        return { ...resolve(source, allOf[0]), ...siblings };
      }
      const result = { type: 'object', properties: {}, required: [] };
      for (const part of schema.allOf) {
        const p = resolve(source, part);
        Object.assign(result.properties, p.properties ?? {});
        result.required.push(...(p.required ?? []));
      }
      result.required = [...new Set(result.required)]; return result;
    }
    return schema;
  }
  function typeInfo(type) {
    const nullable = type.endsWith('?');
    const base = nullable ? type.slice(0, -1) : type;
    const i = base.indexOf('<');
    return { nullable, name: i < 0 ? base : base.slice(0, i), args: i < 0 ? [] : splitTop(base.slice(i + 1, -1)).map(t => t.trim()) };
  }
  function compare(source, schema, type, active = new Set(), projected = false) {
    const s = resolve(source, schema);
    let t = typeInfo(type);
    if (t.name === 'PatchField') return compare(source, s, t.args[0], active, projected);
    if (s.nullable || s.type === 'null' || s.anyOf?.some(v => v.type === 'null')) {
      if (!t.nullable) return '缺少可空类型';
      const other = s.anyOf?.filter(v => v.type !== 'null');
      if (s.type === 'null') return null;
      return compare(source, other ? (other.length === 1 ? other[0] : { anyOf: other }) : { ...s, nullable: false }, type.slice(0, -1), active, projected);
    }
    if (t.nullable) return '合同不可空但类型可空';
    const branches = s.anyOf ?? s.oneOf;
    if (branches) {
      const variants = unions[`${source}.${t.name}`];
      if (variants) return branches.every(b => variants.some(v => compare(source, b, v, active) === null)) ? null : '联合存在未绑定分支';
      return branches.every(b => compare(source, b, type, active) === null) ? null : '联合形状不一致';
    }
    const model = models.get(`${source}.${t.name}`);
    if (model?.inline) return compare(source, s, model.fields[0].type, active, projected);
    const kind = s.type ?? (s.properties || s.additionalProperties ? 'object' : null);
    if (!kind && s.required && projected && model) return s.required.every(name => model.fields.some(f => f.name === name && !f.optional)) ? null : '组合约束 required 不满足';
    if (!kind) return t.name === 'JsonElement' ? null : '自由 JSON 需要明确 JSON 类型';
    if (kind === 'integer') return ['Int', 'Long'].includes(t.name) ? null : '要求整数';
    if (kind === 'number') return ['Double', 'Float'].includes(t.name) ? null : '要求浮点数';
    if (kind === 'boolean') return t.name === 'Boolean' ? null : '要求布尔';
    if (kind === 'string') return t.name === 'String' ? null : '要求字符串';
    if (kind === 'array') return t.name === 'List' ? compare(source, s.items, t.args[0], active) : '要求数组';
    if (kind !== 'object') return `未知 schema 类型 ${kind}`;
    const properties = s.properties ?? {};
    if (t.name === 'Map') {
      if (t.args[0] !== 'String') return 'JSON 字典键必须是字符串';
      if (Object.keys(properties).length) {
        if (source !== 'v0' || !Object.keys(properties).every(k => /^(10|[1-9])$/.test(k))) return '已知对象不能由 Map 取代';
        return Object.values(properties).map(v => compare(source, v, t.args[1], active)).find(Boolean) ?? null;
      }
      return compare(source, typeof s.additionalProperties === 'object' ? s.additionalProperties : {}, t.args[1], active);
    }
    if (!model) return `未找到 DTO ${source}.${t.name}`;
    const key = `${source}:${JSON.stringify(s)}:${type}`;
    if (active.has(key)) return null;
    const next = new Set([...active, key]);
    const args = Object.fromEntries(model.parameters.map((p, i) => [p, t.args[i]]));
    const fields = model.fields.map(f => ({ ...f, type: f.type.replace(/\bT\b/g, args.T ?? 'T') }));
    const expected = new Set(Object.keys(properties));
    for (const [name, child] of Object.entries(properties)) {
      const field = fields.find(f => f.name === name);
      if (!field) return `漏字段 ${name}`;
      // 合同不必需 ⇔ Kotlin 侧可空且有默认值。二者必须同时成立或同时不成立。
      const required = s.required?.includes(name) ?? false;
      if (required === field.optional && (!projected || required)) return `${name} 必需性不一致`;
      // 合同非必需时，Kotlin 侧的 `?` 表达的是"字段可缺省"，不是"值可为 null"；
      // 但若合同本身也允许 null，`?` 就同时承载了后者，此时不能剥。
      // 全剥会把「非必需 + 可为 null」错报为"缺少可空类型"。
      const childNullable = child.nullable === true || child.type === 'null'
        || (child.anyOf?.some(b => b.type === 'null') ?? false)
        || (child.oneOf?.some(b => b.type === 'null') ?? false);
      const childType = !required && !childNullable && field.type.endsWith('?')
        ? field.type.slice(0, -1)
        : field.type;
      const error = compare(source, child, childType, next);
      if (error) return `${name}: ${error}`;
    }
    // 已有 User/Episode 为历史兼容保留额外可选字段；明确例外不视为合同字段。
    const extensions = { 'v0.UserDto': ['email', 'reg_time', 'time_offset'], 'v0.EpisodeDto': ['subject_id'] };
    if (!projected) for (const field of fields) if (!expected.has(field.name) && !extensions[`${source}.${t.name}`]?.includes(field.name)) return `额外字段 ${field.name}`;
    return null;
  }
  return { resolve, typeInfo, compare, models };
}

export function buildCoverage() {
  const contracts = JSON.parse(fs.readFileSync(path.join(root, 'generated/contracts.json')));
  const documents = Object.fromEntries(['v0', 'p1'].map(s => [s, JSON.parse(fs.readFileSync(path.join(root, `generated/${s}.bundled.json`)))]));
  const checker = createChecker(documents);
  const bindings = [], missing = [];
  function choose(source, schema, pointer) {
    if (schema.$ref) return choose(source, at(documents[source], schema.$ref), schema.$ref);
    const s = checker.resolve(source, schema);
    if (s.nullable || s.type === 'null' || s.anyOf?.some(b => b.type === 'null')) {
      if (s.type === 'null') return 'JsonElement?';
      const others = s.anyOf?.filter(b => b.type !== 'null');
      const type = choose(source, others ? others[0] : { ...s, nullable: false }, pointer);
      return type ? type + '?' : null;
    }
    if (s.anyOf || s.oneOf) {
      const name = Object.keys(unions).filter(n => n.startsWith(source + '.')).map(n => n.split('.')[1]).find(n => checker.compare(source, s, n) === null);
      if (name) return name;
      const type = choose(source, (s.anyOf ?? s.oneOf)[0], pointer);
      return type && checker.compare(source, s, type) === null ? type : null;
    }
    if (s.type === 'array') { const item = choose(source, s.items, `${pointer}/items`); return item ? `List<${item}>` : null; }
    const primitives = { integer: 'Int', number: 'Double', string: 'String', boolean: 'Boolean' };
    if (primitives[s.type]) return primitives[s.type];
    if ((!s.type && !s.properties && !s.additionalProperties) && !s.required) return 'JsonElement';
    if ((s.additionalProperties && !Object.keys(s.properties ?? {}).length) || (s.type === 'object' && !s.properties)) {
      const value = typeof s.additionalProperties === 'object' ? choose(source, s.additionalProperties, `${pointer}/additionalProperties`) : 'JsonElement';
      return value ? `Map<String,${value}>` : null;
    }
    if (source === 'v0' && Object.keys(s.properties ?? {}).length === 10 && Object.keys(s.properties).every(k => /^(10|[1-9])$/.test(k))) return 'Map<String,Int>';
    const componentName = pointer.match(/^#\/components\/schemas\/([^/]+)$/)?.[1];
    const preferred = rename[source][componentName] ?? (componentName ? `${componentName}Dto` : null);
    if (preferred && checker.compare(source, s, preferred) === null) return preferred;
    const candidates = [...checker.models.values()].filter(m => m.source === source && !m.parameters.length && !m.inline).map(m => m.name);
    for (const candidate of candidates) if (checker.compare(source, s, candidate) === null) return candidate;
    const props = s.properties ?? {};
    if (props.data?.type === 'array' && props.total && Object.keys(props).every(k => ['data', 'total', 'limit', 'offset'].includes(k))) {
      const item = choose(source, props.data.items, `${pointer}/properties/data/items`);
      const type = item ? `PagedDto<${item}>` : null;
      if (type && checker.compare(source, s, type) === null) return type;
    }
    // allOf 子对象是父 DTO 的字段投影；此处只在有明确父组合时使用。
    if (/\/allOf\/\d+$/.test(pointer)) {
      const parent = at(documents[source], pointer.replace(/\/allOf\/\d+$/, ''));
      const type = choose(source, parent, pointer.replace(/\/allOf\/\d+$/, ''));
      if (type && checker.compare(source, s, type, new Set(), true) === null) return type;
    }
    return null;
  }
  for (const entry of contracts.schemas) {
    const type = choose(entry.source, entry.schema, entry.pointer);
    if (!type) { missing.push(entry); continue; }
    const info = checker.typeInfo(type);
    const projected = /\/allOf\/\d+$/.test(entry.pointer) && !entry.schema.$ref;
    let error = checker.compare(entry.source, entry.schema, type, new Set(), projected);
    if (entry.schema.required && !entry.schema.type && !entry.schema.properties && !entry.schema.$ref && !entry.schema.allOf) error = null;
    if (error) { missing.push({ ...entry, error }); continue; }
    const builtins = { Int: 'kotlin.Int', Long: 'kotlin.Long', Double: 'kotlin.Double', Boolean: 'kotlin.Boolean', String: 'kotlin.String', List: 'kotlin.collections.List', Map: 'kotlin.collections.Map', JsonElement: 'kotlinx.serialization.json.JsonElement' };
    const fqcn = builtins[info.name] ?? `io.github.touhougo.chogumi.core.network.model.${entry.source}.${info.name}`;
    let reason = projected ? 'allOf 子结构投影到完整父 DTO，必须性由完整父结构验证' : '同 host 具类型 serializer；字段、可空性、必需性及递归元素静态检查';
    if (entry.schema.$ref) reason = `显式 schema alias：${entry.schema.$ref}`;
    else if (entry.schema.enum) reason = '开放后端枚举按原始标量保留未知值，不默认为已知值；允许值原文保存在合同';
    else if (info.name === 'Map') reason = 'JSON 字符串键字典；若为评分则保留文本分数键；值类型递归绑定';
    else if (info.name === 'JsonElement') reason = entry.schema.type === 'null' ? 'null 联合分支；由父字段的 nullable serializer 处理' : '原始合同确为开放 JSON；不得用于替代已知对象';
    bindings.push({ source: entry.source, host: contracts.sources.find(s => s.id === entry.source).host, version: entry.source, document: entry.document, pointer: entry.pointer, usage: entry.usage, fqcn, kotlinType: type, serializer: type, projected, reason });
  }
  return { format: 1, bindings, missing, checker, contracts };
}
export function validateCoverage(coverage, contracts) {
  const expected = new Set(contracts.schemas.map(bindingKey));
  const seen = new Set();
  for (const binding of coverage.bindings) {
    const key = bindingKey(binding);
    if (seen.has(key)) throw new Error(`重复绑定 ${key}`);
    seen.add(key);
    if (!expected.has(key)) throw new Error(`未知或错源绑定 ${key}`);
    const source = contracts.sources.find(s => s.id === binding.source);
    if (binding.host !== source.host || binding.version !== source.version) throw new Error(`host/version 绑定错误 ${key}`);
    if (binding.fqcn.includes('.model.') && !binding.fqcn.includes(`.model.${binding.source}.`)) throw new Error(`DTO 绑错 host ${key}`);
    if (!binding.reason) throw new Error(`缺少绑定理由 ${key}`);
  }
  for (const key of expected) if (!seen.has(key)) throw new Error(`漏绑定 ${key}`);
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const result = buildCoverage();
  if (process.argv.includes('--write')) fs.writeFileSync(path.join(root, 'coverage.json'), json({ format: result.format, bindings: result.bindings }));
  if (result.missing.length) {
    console.error(`未覆盖 ${result.missing.length}/${result.contracts.schemas.length} schema pointers`);
    for (const m of result.missing) console.error(`${m.source} ${m.pointer} ${m.error ?? ''}`);
    process.exitCode = 1;
  } else {
    const saved = JSON.parse(fs.readFileSync(path.join(root, 'coverage.json')));
    validateCoverage(saved, result.contracts);
    if (JSON.stringify(saved.bindings) !== JSON.stringify(result.bindings)) throw new Error('coverage.json 与当前手工 DTO 不一致');
    console.log(`绑定检查：${result.bindings.length}/${result.contracts.schemas.length} schema pointers，${result.checker.models.size} 手工 DTO 声明`);
  }
}
