# 万龙洲供应链申购系统 —— 安卓 App 壳（准原生增强版）· 构建说明

## 这个壳解决什么问题

界面和业务逻辑 **100% 还是原来的网页**（`hotel_requisition.html`），本壳只做「外壳」该做的事：

| 壳做的事 | 解决的现象 |
|---|---|
| 禁用 WebView 网络缓存 | 新单据「闪现一下就消失」、点发货后状态又变回「待发货」 |
| 打开 localStorage | 草稿不丢（网页端草稿机制依赖它） |
| **本地缓存业务页 + 热更新** | **秒开**（540KB 走本地）；**改网页不用重新打包** |
| 启动闪屏 | 点开立刻出 Logo，不再白屏 |
| 原生下拉刷新 | 安卓手势，不用手动清缓存 |
| 原生打印（A4） | 直接调安卓打印框架出单，比网页打印稳 |
| 断网提示页 | 无网络给友好提示 + 重试，而不是一片白 |
| 双击返回退出 | 符合安卓操作习惯 |
| 内置服务器地址配置 | 后期迁 NAS 不用重新打包 App，App 内改地址即可 |

**根因说明**：安卓 WebView 默认会缓存网络请求，把「写库之前」的旧数据返回给页面。桌面 Chrome 和 iOS Safari 缓存策略没这么激进，所以只有安卓复现——这也是为什么电脑和苹果都正常、唯独安卓乱。

---

## 秒开与热更新的原理（改动前必读）

线上是**两层结构**，必须分清，否则容易改出问题：

| 层 | 内容 | 大小 | 能否缓存 |
|---|---|---|---|
| **外壳页** `workbuddy.link/p/<id>` | React 运行时，**注入 `window.__SMART_PAGE__`（数据库 SDK + 登录态）** | 约 4 KB | ❌ **必须每次走网络** |
| **业务页** `.../page/<id>/<rev>/hotel_requisition.html` | 全部界面与业务逻辑 | 约 540 KB | ✅ **可安全缓存** |

已实测：业务页里 `__SMART_PAGE__` 的对象赋值出现 **0 次**，是纯静态文件、不含任何运行时凭证。
所以只缓存业务页**不会影响登录态和数据访问**——这正是「秒开」和「热更新」能同时成立的原因。

**更新流程**：每次页面加载完成后，后台静默拉取业务页 → 提取 `APP_VERSION` → 比本地新才覆盖 → **下次启动生效**（不打断当前操作）。

> ⚠️ 修改拦截逻辑时，务必只匹配 `hotel_requisition.html`。
> 若把外壳页或数据接口也拦了，会拿不到登录态，**系统直接不可用**。

---

## 方式 A：GitHub Actions 云端构建（推荐，不用装任何环境）

适合：不想在电脑上安装几 GB 的 Android Studio。

1. 把 `android-app` **整个目录** 作为一个新的 GitHub 仓库提交推送上去
   （注意：`android-app` 就是仓库根目录，`settings.gradle` 要在最外层）

2. 打开仓库页面 → 顶部 **Actions** 标签

3. 左侧选 **「构建安卓 APK」** → 右侧点 **Run workflow** → 再点绿色确认按钮

4. 等约 5–10 分钟，状态变绿后，点进这次运行记录

5. 页面底部 **Artifacts** 区域会生成 `wanlongzhou-supply-debug`，点它下载

6. 解压得到 `app-debug.apk`，传到安卓手机安装即可
   （安装时若提示「未知来源」，允许一次即可）

> 调试版 APK 已用默认密钥签名，可以直接安装使用。

---

## 方式 B：本地 Android Studio 构建

适合：电脑上已有 Android Studio，或打算长期自己维护。

1. 下载安装 [Android Studio](https://developer.android.com/studio)（装完会自动带 JDK、Android SDK、Gradle）

2. 打开 Android Studio → **File → Open** → 选中 `android-app` 这个目录

3. 首次打开会提示 Gradle Wrapper 缺失 → 点 **OK** 让它自动下载补全，等待同步完成
   （若没提示，可在项目根目录手动执行：`gradle wrapper --gradle-version 8.2`）

   > **国内下载依赖慢 / 一直转圈**：把 `settings.gradle` 里的 `google()` 和 `mavenCentral()`
   > 换成阿里云镜像（`pluginManagement` 和 `dependencyResolutionManagement` 两处都要换）：
   > ```gradle
   > maven { url 'https://maven.aliyun.com/repository/google' }
   > maven { url 'https://maven.aliyun.com/repository/public' }
   > ```
   > Gradle 本体下载慢的话，把 `gradle/wrapper/gradle-wrapper.properties` 里的
   > `distributionUrl` 换成 `https://mirrors.cloud.tencent.com/gradle/gradle-8.2-bin.zip`

4. 手机开启「开发者选项 → USB 调试」并用数据线连电脑，或准备一个模拟器

5. 点顶部绿色三角 **Run** → 选你的手机 → 自动编译安装并打开

6. 要产出可分发 APK：**Build → Build Bundle(s)/APK(s) → Build APK(s)**
   完成后在 `app/build/outputs/apk/debug/app-debug.apk`

---

## 后期迁到 NAS 怎么改

**不用重新打包 App**，两种方式任选：

- **App 内改（最省事）**：打开 App → 右上角菜单 ⋮ → **设置服务器地址** → 填 NAS 上的访问地址（如 `http://192.168.31.x:8080`）→ 保存并打开
- **改默认值重新打包**：编辑 `MainActivity.kt` 里的 `DEFAULT_URL` 常量

> 安卓 9 以上默认禁止明文 http。若 NAS 用的是 http，本工程已在 `AndroidManifest.xml` 里开了 `android:usesCleartextTraffic="true"`，可以直接访问。

---

## 日常使用小贴士

| 操作 | 方法 |
|---|---|
| 数据看着不对 / 怀疑读到旧数据 | 右上角菜单 ⋮ → **刷新（清缓存）** |
| 网页改了版，想立刻用上 | 菜单 ⋮ → **检查页面更新**（提示重启后生效）；或退出 App 重开 |
| 打印当前单据（A4） | 菜单 ⋮ → **打印当前页面（A4）** |
| 下拉刷新 | 页面滚到顶部后，手指下拉 |
| 网页内返回上一页 | 手机返回键（会优先网页内回退） |
| 退出 App | 在首页**连按两次**返回键 |
| 地址填错了想还原 | 菜单 ⋮ → **恢复默认地址** |
| 页面更新后出问题，想回退 | 菜单 ⋮ → **清除本地页面缓存** → 下次启动重新下载 |

> 「刷新（清缓存）」只清网络缓存，**不会清掉草稿**，可以放心点。

## 改网页后要不要重新打包 App？

| 改动内容 | 要不要重新打包 |
|---|---|
| 改业务逻辑 / 界面排版 / 加字段 | **不用** ✅ 下次启动自动生效（或菜单里「检查页面更新」） |
| 加原生能力（打印、扫码、推送等） | 要 |
| 改 App 图标 / 名字 / 包名 / 默认地址 | 要 |

这也正是保留网页架构的最大好处：**业务迭代不用走「打包 → 传手机 → 安装」这一圈**。

---

## 工程结构

```
android-app/
├─ settings.gradle / build.gradle / gradle.properties
├─ gradle/wrapper/gradle-wrapper.properties
├─ .github/workflows/build-apk.yml    ← 云端自动构建
└─ app/
   ├─ build.gradle
   ├─ proguard-rules.pro
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/com/wanlongzhou/supply/MainActivity.kt   ← 核心：WebView 配置 + 原生增强
      ├─ java/com/wanlongzhou/supply/PageCache.kt      ← 秒开与热更新的缓存层
      └─ res/  (layout / values / drawable / mipmap)

（工程根目录下 `_check_res.py` 校验资源引用、`_test_hotupdate.js` 校验热更新版本判断，均不依赖 Android Studio）
```

**改配置主要看 `MainActivity.kt`**，每个关键设置都写了注释说明原因。
**改缓存/更新策略看 `PageCache.kt`**，文件头注释写清了「为什么可以缓存」。
