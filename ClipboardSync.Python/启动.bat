@echo off
chcp 65001 >nul
echo ================================
echo   剪贴板同步工具 - Windows 端
echo ================================
echo.

REM 检查 Python 是否安装
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未检测到 Python，请先安装 Python 3.8 或更高版本
    echo 下载地址: https://www.python.org/downloads/
    pause
    exit /b 1
)

echo [1/3] 检测 Python 版本...
python --version

echo.
echo [2/3] 安装依赖包...
pip install -r requirements.txt

if %errorlevel% neq 0 (
    echo.
    echo [警告] 依赖安装失败，尝试升级 pip...
    python -m pip install --upgrade pip
    pip install -r requirements.txt
)

echo.
echo [3/3] 启动程序...
echo.
echo ================================
echo   服务已启动，请点击"启动服务"按钮
echo ================================
echo.

python clipboard_sync.py

if %errorlevel% neq 0 (
    echo.
    echo [错误] 程序运行出错
    pause
)
