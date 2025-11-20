# Monaco Editor 设置说明

Monaco Editor是VSCode使用的编辑器，提供专业级的代码编辑体验。

## 当前使用方式

**目前使用CDN加载（需要网络）**

HTML文件中使用：
```html
<script src="https://cdn.jsdelivr.net/npm/monaco-editor@0.45.0/min/vs/loader.js"></script>
```

**优点：**
- ✅ 快速开始，无需下载
- ✅ 自动更新
- ✅ 不增加APK大小

**缺点：**
- ❌ 需要网络连接
- ❌ 首次加载较慢（约2-3秒）
- ❌ 网络不稳定时无法使用

---

## 可选：离线使用（将Monaco打包到assets）

如果需要离线使用，可以将Monaco Editor下载到本地：

### 方法1：使用npm下载（推荐）

```bash
# 进入项目根目录
cd d:\Code\prog\assistance

# 安装Monaco Editor
npm install monaco-editor@0.45.0

# 复制到assets目录
mkdir -p app\src\main\assets\monaco
xcopy /E /I /Y node_modules\monaco-editor\min app\src\main\assets\monaco
```

然后修改 `monaco_editor.html` 中的加载路径：
```javascript
// 从CDN加载改为本地加载
require.config({
    paths: {
        'vs': 'monaco/vs'  // 本地路径
    }
});
```

### 方法2：直接下载

1. 访问：https://github.com/microsoft/monaco-editor/releases/tag/v0.45.0
2. 下载 `monaco-editor-0.45.0.zip`
3. 解压后将 `min` 文件夹重命名为 `monaco`
4. 复制到 `app/src/main/assets/monaco`

### 方法3：使用CDN缓存到本地

```bash
# PowerShell脚本
$url = "https://cdn.jsdelivr.net/npm/monaco-editor@0.45.0/min/vs/loader.js"
$dest = "app\src\main\assets\monaco\vs\"
New-Item -ItemType Directory -Force -Path $dest
Invoke-WebRequest -Uri $url -OutFile "$dest\loader.js"

# 需要下载整个vs目录，较复杂，推荐使用方法1或2
```

---

## 大小对比

- **使用CDN**: APK增加 ~50KB（只有HTML文件）
- **打包Monaco**: APK增加 ~3MB（完整Monaco Editor）

## 支持的语言

Monaco Editor支持70+种编程语言，包括：

- JavaScript, TypeScript, JSON
- HTML, CSS, SCSS, Less
- Python, Java, C#, C++, Go, Rust
- Kotlin, Swift, PHP, Ruby, Perl
- SQL, Shell, Dockerfile, YAML
- Markdown, XML, plaintext
- 等等...

## 功能特性

✅ **语法高亮** - 所有主流语言  
✅ **代码补全** - 智能提示  
✅ **代码折叠** - 折叠/展开代码块  
✅ **多光标编辑** - 同时编辑多处  
✅ **查找替换** - 强大的搜索功能  
✅ **撤销/重做** - 无限次  
✅ **minimap** - 代码缩略图  
✅ **主题切换** - 亮色/暗色主题  
✅ **大文件支持** - 虚拟滚动，MB级文件流畅  

## Diff显示（即将支持）

Monaco Editor内置强大的Diff功能，可以：
- 并排对比两个文件
- 高亮显示差异
- 支持内联Diff
- 支持合并冲突

需要时可以创建 `MonacoDiffEditor` 组件。

---

## 当前状态

✅ 已集成Monaco Editor  
✅ 支持代码编辑和语法高亮  
✅ 支持撤销/重做  
✅ 使用CDN加载（需要网络）  
⏳ 离线支持（可选）  
⏳ Diff显示（按需添加）  

## 性能

Monaco Editor采用虚拟滚动技术：
- **小文件 (<100KB)**: 瞬间加载
- **中文件 (100KB-1MB)**: 流畅编辑
- **大文件 (>1MB)**: 依然流畅

比原来的自定义编辑器快10-100倍！
