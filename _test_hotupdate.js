/**
 * 热更新核心逻辑校验
 * 把 PageCache.kt 里的两个关键算法（extractVersion / isNewer）原样移植到 JS，
 * 用【真实的业务 HTML】验证，确保安卓端能正确识别版本并判断是否需要更新。
 *
 * 运行：node _test_hotupdate.js
 */
const fs = require('fs');
const path = require('path');

// ===== 与 PageCache.kt 保持一致的实现 =====
function extractVersion(html) {
  const m = /var\s+APP_VERSION\s*=\s*'([^']+)'/.exec(html);
  return m ? m[1] : null;
}

function isNewer(a, b) {
  if (a === b) return false;
  const ra = /v(\d{8})-(\d+)/.exec(a);
  const rb = /v(\d{8})-(\d+)/.exec(b);
  if (!ra || !rb) return a > b;
  const da = ra[1], db = rb[1];
  if (da !== db) return da > db;
  const na = parseInt(ra[2], 10) || 0;
  const nb = parseInt(rb[2], 10) || 0;
  return na > nb;
}

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  [OK]   ' + name); }
  else { fail++; console.log('  [FAIL] ' + name + (extra ? '  -> ' + extra : '')); }
}

// ===== 1) 用真实页面验证 =====
const candidates = [
  path.join(__dirname, '..', 'hotel_requisition.html'),
  '/tmp/biz.html'
];
const htmlPath = candidates.find(p => fs.existsSync(p));

console.log('=== 1. 真实页面版本号提取 ===');
if (!htmlPath) {
  console.log('  (未找到业务 HTML，跳过真实性校验)');
} else {
  const html = fs.readFileSync(htmlPath, 'utf8');
  const ver = extractVersion(html);
  console.log('  文件: ' + htmlPath);
  console.log('  大小: ' + html.length + ' 字符');
  ok('能从真实页面提取到版本号', !!ver, '提取结果=' + ver);
  ok('版本号格式正确 vYYYYMMDD-nn', /^v\d{8}-\d+$/.test(ver || ''), '实际=' + ver);

  // 必须确认业务页不含运行时注入 —— 这是「可安全缓存」的前提
  const hasInjection = /__SMART_PAGE__\s*=\s*[{[]/.test(html);
  ok('业务页不含 __SMART_PAGE__ 注入（可安全缓存的前提）', !hasInjection);
}

// ===== 2) 热更新判断矩阵 =====
console.log('\n=== 2. 版本比较（决定要不要更新） ===');
const cases = [
  ['v20260901-122', 'v20260901-122', false, '同版本 -> 不更新'],
  ['v20260901-130', 'v20260901-122', true,  '同日升序号 -> 更新'],
  ['v20260901-122', 'v20260901-130', false, '本地更新 -> 绝不回退'],
  ['v20260902-100', 'v20260901-999', true,  '跨天 -> 更新'],
  ['v20260901-999', 'v20260902-100', false, '跨天倒退 -> 不更新'],
  ['v20260901-9',   'v20260901-10',  false, '按数值比较而非字符串(9 < 10)'],
  ['v20260901-10',  'v20260901-9',   true,  '按数值比较(10 > 9)'],
  ['unknown',       'v20260901-122', false, '格式异常 -> 退化字符串比较'],
];
cases.forEach(([a, b, want, desc]) => {
  const got = isNewer(a, b);
  ok(desc, got === want, 'isNewer(' + a + ',' + b + ')=' + got + ' 期望=' + want);
});

// ===== 3) 决定性场景：不能把新版覆盖成旧版 =====
console.log('\n=== 3. 防覆盖回归（最危险的 bug） ===');
let localVer = 'v20260901-130';
const incoming = [
  ['v20260901-122', false],
  ['v20260901-129', false],
  ['v20260901-131', true],
  ['v20260902-1',   true],
];
incoming.forEach(([v, want]) => {
  const wouldSave = isNewer(v, localVer);
  if (wouldSave) localVer = v;
  ok('收到 ' + v + ' -> ' + (wouldSave ? '覆盖' : '忽略'), wouldSave === want);
});

console.log('\n============================');
console.log('通过: ' + pass + ' / 失败: ' + fail);
console.log('============================');
process.exit(fail === 0 ? 0 : 1);
