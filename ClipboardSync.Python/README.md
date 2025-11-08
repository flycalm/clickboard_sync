# 剪贴板同步工具 - Python 版 Windows 端

这是一个使用 Python 实现的简化版 Windows 端程序，无需安装 .NET SDK。

## 快速开始

### 方法一：使用批处理文件（推荐）

1. 双击运行 `启动.bat`
2. 程序会自动安装依赖并启动
3. 点击"启动服务"按钮

### 方法二：手动运行

```bash
# 1. 安装依赖
pip install -r requirements.txt

# 2. 运行程序
python clipboard_sync.py
```

## 依赖要求

- Python 3.8 或更高版本
- Pillow (图片处理)
- pywin32 (Windows 剪贴板访问)

## 功能特性

- ✅ 监听 Windows 剪贴板图片变化
- ✅ TCP Socket 服务器 (端口 5150-5169)
- ✅ UDP 设备发现广播 (端口 5149)
- ✅ Base64 图片编码传输
- ✅ 友好的 GUI 界面

## 使用说明

1. 运行程序后点击"启动服务"
2. 在 Android 设备上打开剪贴板同步 APP
3. Android 端会自动发现 Windows 设备
4. 在 Windows 上截图 (Win + Shift + S)
5. 图片自动同步到 Android 剪贴板

## 注意事项

1. **防火墙**: 首次运行可能需要允许程序通过防火墙
2. **同一网络**: 确保 Windows 和 Android 在同一 WiFi
3. **Python 版本**: 需要 Python 3.8+

## 故障排除

### 提示"未检测到 Python"
- 下载并安装 Python: https://www.python.org/downloads/
- 安装时勾选"Add Python to PATH"

### 依赖安装失败
```bash
# 升级 pip
python -m pip install --upgrade pip

# 重新安装依赖
pip install Pillow pywin32
```

### 剪贴板监听不工作
- 确保使用 PNG 格式截图
- 重启程序重试

## 相比 .NET 版本的优势

- ✅ 无需安装 .NET SDK
- ✅ 跨平台 (理论上可在 macOS/Linux 运行)
- ✅ 代码简单易懂
- ✅ 依赖少，安装快
