# -*- coding: utf-8 -*-
"""
安卓工程静态检查（无需 Android Studio）：
  1. 收集 res/ 下所有资源定义（layout / drawable / mipmap / string / color / style / id）
  2. 扫描 XML 与 Kotlin 中所有 @type/name 与 R.type.name 引用
  3. 报出解析不到的引用——这类问题会直接导致编译失败
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ROOT, "app", "src", "main", "res")
MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
KT = os.path.join(ROOT, "app", "src", "main", "java", "com", "wanlongzhou", "supply", "MainActivity.kt")

# 安卓框架自带的资源（不需要本工程定义）
FRAMEWORK = {
    "style": {"AppTheme"},
    "android": set(),
}

defined = {}   # type -> set(name)


def add(t, n):
    defined.setdefault(t, set()).add(n)


# 1) 按目录收集：res/<type>[-qualifier]/<name>.xml
if os.path.isdir(RES):
    for d in sorted(os.listdir(RES)):
        p = os.path.join(RES, d)
        if not os.path.isdir(p):
            continue
        rtype = d.split("-")[0]
        for f in os.listdir(p):
            if f.endswith(".xml"):
                add(rtype, f[:-4])

# 2) 扫描 values/*.xml 里的 <string>/<color>/<style>/<dimen> 定义与 @+id 定义
id_defs = set()
for root, _, files in os.walk(RES):
    for f in files:
        if not f.endswith(".xml"):
            continue
        fp = os.path.join(root, f)
        txt = open(fp, encoding="utf-8").read()
        for m in re.finditer(r'@\+id/([\w]+)', txt):
            id_defs.add(m.group(1))
        for tag, rtype in (("string", "string"), ("color", "color"),
                           ("style", "style"), ("dimen", "dimen"), ("bool", "bool")):
            for m in re.finditer(r'<%s\s+name="([^"]+)"' % tag, txt):
                add(rtype, m.group(1))
defined["id"] = id_defs

# 3) 收集引用
refs = []   # (来源, type, name)


def scan_refs(txt, source):
    # XML: @type/name 与 @android:xxx/name
    for m in re.finditer(r'(?<![\w.@])@(?!android)([\w]+)/([\w]+)', txt):
        refs.append((source, m.group(1), m.group(2)))
    for m in re.finditer(r'@android:([\w]+)/([\w]+)', txt):
        refs.append((source, "android:" + m.group(1), m.group(2)))
    # Kotlin: R.type.name
    for m in re.finditer(r'\bR\.([\w]+)\.([\w]+)', txt):
        refs.append((source, m.group(1), m.group(2)))


for root, _, files in os.walk(os.path.join(ROOT, "app", "src", "main", "res")):
    for f in files:
        if f.endswith(".xml"):
            fp = os.path.join(root, f)
            scan_refs(open(fp, encoding="utf-8").read(), os.path.relpath(fp, ROOT))

if os.path.exists(MANIFEST):
    scan_refs(open(MANIFEST, encoding="utf-8").read(), "AndroidManifest.xml")
if os.path.exists(KT):
    scan_refs(open(KT, encoding="utf-8").read(), "MainActivity.kt")

# 4) 校验
errors = []
for source, rtype, name in refs:
    if rtype.startswith("android:"):
        continue                      # 框架资源，跳过
    if rtype == "id" and name in id_defs:
        continue
    if name in defined.get(rtype, set()):
        continue
    # @+id 形式定义本身也算合法
    if rtype == "id" and ("@+id/%s" % name) in "".join(
            open(os.path.join(RES, "layout", f), encoding="utf-8").read()
            for f in os.listdir(os.path.join(RES, "layout")) if f.endswith(".xml")):
        continue
    errors.append("未定义引用  %-22s -> @%s/%s" % (source, rtype, name))

print("=" * 60)
print("资源定义统计：")
for t in sorted(defined):
    print("  %-10s %d  -> %s" % (t, len(defined[t]), ", ".join(sorted(defined[t]))))
print("-" * 60)
print("引用总数：%d" % len(refs))
if errors:
    print("发现 %d 个无法解析的引用：" % len(errors))
    for e in dict.fromkeys(errors):
        print("  [X] " + e)
    sys.exit(1)
else:
    print("[OK] 所有资源引用均可解析")
    sys.exit(0)
