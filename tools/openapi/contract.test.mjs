import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { root, indexDocument } from './refresh.mjs';
import { createChecker, parseModels, validateCoverage } from './check-coverage.mjs';
const docs = Object.fromEntries(['v0', 'p1'].map(s => [s, JSON.parse(fs.readFileSync(path.join(root, `generated/${s}.bundled.json`)))]));
const contracts = JSON.parse(fs.readFileSync(path.join(root, 'generated/contracts.json')));
const coverage = JSON.parse(fs.readFileSync(path.join(root, 'coverage.json')));
const sourcesMetadata = JSON.parse(fs.readFileSync(path.join(root, 'sources.json')));
const packageMetadata = JSON.parse(fs.readFileSync(path.join(root, 'package.json')));
const lockMetadata = JSON.parse(fs.readFileSync(path.join(root, 'package-lock.json')));

const requiredContractTools = ['openapi-typescript', 'yaml'];
const assertToolVersions = (tools, dependencies, packages) => {
  assert.ok(Object.keys(tools).length > 0, 'sources.tools 不能为空');
  for (const name of requiredContractTools) assert.ok(Object.hasOwn(tools, name), `sources.tools 缺少 ${name}`);
  for (const [name, version] of Object.entries(tools)) {
    assert.equal(dependencies[name], version, `${name} 的 package.json 版本与 sources.json 不一致`);
    assert.equal(packages[`node_modules/${name}`]?.version, version, `${name} 的 lock 版本与 sources.json 不一致`);
  }
};

test('合同工具版本与 package、lock 一致', () => {
  assertToolVersions(sourcesMetadata.tools, packageMetadata.dependencies, lockMetadata.packages);
});

test('额外的 package dependency 不冒充合同工具版本漂移', () => {
  const tools = { 'openapi-typescript': '7.13.0', yaml: '2.9.1' };
  const packages = { packages: Object.fromEntries(Object.entries(tools).map(([name, version]) => [`node_modules/${name}`, { version }])) };
  assertToolVersions(tools, { ...tools, unrelated: '1.0.0' }, packages.packages);
});

test('合同工具真实版本漂移仍被拒绝', () => {
  const tools = { 'openapi-typescript': '7.13.0', yaml: '2.9.1' };
  const packages = Object.fromEntries(Object.entries(tools).map(([name, version]) => [`node_modules/${name}`, { version }]));
  assert.throws(
    () => assertToolVersions(tools, { ...tools, yaml: '2.8.2' }, packages),
    /yaml.*package.json/,
  );
});

test('缺少合同生成工具也仍被拒绝', () => {
  const tools = { 'openapi-typescript': '7.13.0', yaml: '2.9.1' };
  const packages = Object.fromEntries(Object.entries(tools).map(([name, version]) => [`node_modules/${name}`, { version }]));
  assert.throws(
    () => assertToolVersions({ yaml: tools.yaml }, tools, packages),
    /缺少 openapi-typescript/,
  );
});

test('全量基线、完整绑定与 host 版本一致', () => {
  assert.equal(contracts.operations.length, 271);
  assert.equal(contracts.schemas.length, 3544);
  validateCoverage(coverage, contracts);
  assert.equal(new Set(coverage.bindings.map(b => b.source)).size, 2);
});
test('缺绑定、重复绑定和错 host 都被拒绝', () => {
  assert.throws(() => validateCoverage({ bindings: coverage.bindings.slice(1) }, contracts), /漏绑定/);
  assert.throws(() => validateCoverage({ bindings: [...coverage.bindings, coverage.bindings[0]] }, contracts), /重复绑定/);
  const wrong = structuredClone(coverage);
  wrong.bindings[0].host = 'https://next.bgm.tv';
  assert.throws(() => validateCoverage(wrong, contracts), /host/);
  wrong.bindings[0] = { ...coverage.bindings[0], fqcn: 'io.github.touhougo.chogumi.core.network.model.p1.SubjectDto' };
  assert.throws(() => validateCoverage(wrong, contracts), /绑错 host/);
});
test('评分对象与数组不得错源交换', () => {
  const checker = createChecker(docs);
  assert.equal(checker.compare('v0', docs.v0.components.schemas.Subject.properties.rating, 'RatingDto'), null);
  assert.equal(checker.compare('p1', docs.p1.components.schemas.SubjectRating, 'P1RatingDto'), null);
  assert.match(checker.compare('p1', { type: 'array', items: { type: 'integer' } }, 'Map<String,Int>'), /数组/);
  assert.match(checker.compare('v0', docs.v0.components.schemas.Subject.properties.rating.properties.count, 'List<Int>'), /DTO/);
});
test('漏字段、错误必需性与错误 nullable 是可检测的负例', () => {
  const models = parseModels();
  const subject = models.get('p1.P1SlimSubjectDto');
  subject.fields = subject.fields.filter(f => f.name !== 'interest');
  assert.match(createChecker(docs, models).compare('p1', docs.p1.components.schemas.SlimSubject, 'P1SlimSubjectDto'), /漏字段 interest/);
  const rating = models.get('p1.P1RatingDto');
  rating.fields.find(f => f.name === 'rank').optional = true;
  assert.match(createChecker(docs, models).compare('p1', docs.p1.components.schemas.SubjectRating, 'P1RatingDto'), /必需性/);
  const source = models.get('p1.TimelineSourceDto');
  // 合同非必需，但 Kotlin 侧既不可空又无默认值 → 缺字段时反序列化会失败
  const url = source.fields.find(f => f.name === 'url');
  url.type = 'String';
  url.optional = false;
  assert.match(createChecker(docs, models).compare('p1', docs.p1.components.schemas.TimelineSource, 'TimelineSourceDto'), /必需性不一致/);
});
test('可选非空字段合法：合同非必需 + Kotlin 可空默认值 = 缺字段标记', () => {
  // 「可省略但不可显式为 null」由结构推导（合同非必需 + Kotlin 可空带默认值），不依赖注解：
  // 推导错了，合法字段会被误判为「合同不可空但类型可空」。
  const checker = createChecker(docs);
  // SlimSubject.images 非必需，DTO 为 `val images: SubjectImagesDto? = null`
  assert.equal(checker.compare('p1', docs.p1.components.schemas.SlimSubject, 'P1SlimSubjectDto'), null);
  // TimelineSource.url 非必需，DTO 为 `val url: String? = null`
  assert.equal(checker.compare('p1', docs.p1.components.schemas.TimelineSource, 'TimelineSourceDto'), null);
});
test('合同必需但 Kotlin 可空必须报错', () => {
  const models = parseModels();
  const slim = models.get('p1.P1SlimSubjectDto');
  slim.fields.find(f => f.name === 'name').type = 'String?';
  slim.fields.find(f => f.name === 'name').optional = true;
  assert.match(createChecker(docs, models).compare('p1', docs.p1.components.schemas.SlimSubject, 'P1SlimSubjectDto'), /必需性|可空/);
});
test('required nullable、整数枚举和 PATCH 三态仍留在合同', () => {
  const checker = createChecker(docs);
  const progress = docs.p1.components.schemas.ProgressItem;
  assert.ok(progress.required.includes('lastUnwatchedEp'));
  assert.ok(progress.properties.lastUnwatchedEp.anyOf.some(s => s.type === 'null'));
  assert.equal(checker.compare('p1', { type: 'integer', enum: [1, 2] }, 'Int'), null);
  const patch = checker.models.get('p1.ExpectedSubjectRevisionDto');
  assert.ok(patch.fields.every(f => f.type.startsWith('PatchField<') && f.optional));
});
test('递归引用索引不会无限展开，悬空引用必须失败', () => {
  const recursive = { components: { schemas: { Node: { type: 'object', properties: { next: { $ref: '#/components/schemas/Node' } } } } }, paths: {} };
  assert.equal(indexDocument('v0', 'recursive.json', recursive).schemas.length, 2);
  recursive.components.schemas.Node.properties.next.$ref = '#/components/schemas/Missing';
  assert.throws(() => indexDocument('v0', 'recursive.json', recursive), /悬空/);
});
test('memo 按对象而不是外层 cat 的伪 discriminator 表达', () => {
  const memo = docs.p1.components.schemas.TimelineMemo;
  assert.equal(memo.type, 'object');
  assert.equal(memo.discriminator, undefined);
  assert.equal(memo.properties.cat, undefined);
  assert.deepEqual(Object.keys(memo.properties), ['daily', 'wiki', 'subject', 'progress', 'status', 'blog', 'index', 'mono']);
});
