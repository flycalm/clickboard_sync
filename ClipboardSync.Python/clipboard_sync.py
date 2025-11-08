"""
剪贴板同步工具 - Windows 端
使用 Python 实现,监听剪贴板并同步图片到 Android 设备
"""

import tkinter as tk
from tkinter import ttk, scrolledtext
import threading
import socket
import json
import base64
import time
from datetime import datetime
from io import BytesIO
from PIL import ImageGrab, Image, ImageTk, ImageDraw
import pystray
from pystray import MenuItem as item

# 使用 ctypes 访问 Windows 剪贴板 API (更好的 PyInstaller 兼容性)
import ctypes
from ctypes import wintypes

# Windows 剪贴板常量
CF_TEXT = 1
CF_UNICODETEXT = 13
GMEM_MOVEABLE = 0x0002

# Windows API 函数
user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

OpenClipboard = user32.OpenClipboard
OpenClipboard.argtypes = [wintypes.HWND]
OpenClipboard.restype = wintypes.BOOL

CloseClipboard = user32.CloseClipboard
CloseClipboard.argtypes = []
CloseClipboard.restype = wintypes.BOOL

GetClipboardData = user32.GetClipboardData
GetClipboardData.argtypes = [wintypes.UINT]
GetClipboardData.restype = wintypes.HANDLE

SetClipboardData = user32.SetClipboardData
SetClipboardData.argtypes = [wintypes.UINT, wintypes.HANDLE]
SetClipboardData.restype = wintypes.HANDLE

EmptyClipboard = user32.EmptyClipboard
EmptyClipboard.argtypes = []
EmptyClipboard.restype = wintypes.BOOL

GlobalLock = kernel32.GlobalLock
GlobalLock.argtypes = [wintypes.HGLOBAL]
GlobalLock.restype = wintypes.LPVOID

GlobalUnlock = kernel32.GlobalUnlock
GlobalUnlock.argtypes = [wintypes.HGLOBAL]
GlobalUnlock.restype = wintypes.BOOL

GlobalAlloc = kernel32.GlobalAlloc
GlobalAlloc.argtypes = [wintypes.UINT, ctypes.c_size_t]
GlobalAlloc.restype = wintypes.HGLOBAL

GlobalSize = kernel32.GlobalSize
GlobalSize.argtypes = [wintypes.HGLOBAL]
GlobalSize.restype = ctypes.c_size_t


class ModernUI:
    """现代化 UI 主题配置"""
    BG_COLOR = "#f5f5f5"
    CARD_BG = "#ffffff"
    PRIMARY_COLOR = "#1E88E5"
    SUCCESS_COLOR = "#4CAF50"
    ERROR_COLOR = "#F44336"
    TEXT_COLOR = "#212121"
    SECONDARY_TEXT = "#757575"
    BORDER_COLOR = "#E0E0E0"
    
    TITLE_FONT = ("Microsoft YaHei UI", 20, "bold")
    HEADING_FONT = ("Microsoft YaHei UI", 12, "bold")
    BODY_FONT = ("Microsoft YaHei UI", 10)
    MONO_FONT = ("Consolas", 9)


def get_clipboard_text():
    """从剪贴板获取文本"""
    try:
        if not OpenClipboard(None):
            return None
        
        h_data = GetClipboardData(CF_UNICODETEXT)
        if not h_data:
            CloseClipboard()
            return None
        
        p_data = GlobalLock(h_data)
        if not p_data:
            CloseClipboard()
            return None
        
        try:
            text = ctypes.wstring_at(p_data)
            return text
        finally:
            GlobalUnlock(h_data)
            CloseClipboard()
    except:
        try:
            CloseClipboard()
        except:
            pass
        return None


def set_clipboard_text(text):
    """设置剪贴板文本"""
    try:
        if not OpenClipboard(None):
            return False
        
        EmptyClipboard()
        
        # 分配全局内存
        text_bytes = (text + '\0').encode('utf-16le')
        h_data = GlobalAlloc(GMEM_MOVEABLE, len(text_bytes))
        if not h_data:
            CloseClipboard()
            return False
        
        # 锁定内存并复制数据
        p_data = GlobalLock(h_data)
        if not p_data:
            CloseClipboard()
            return False
        
        ctypes.memmove(p_data, text_bytes, len(text_bytes))
        GlobalUnlock(h_data)
        
        # 设置剪贴板数据
        if not SetClipboardData(CF_UNICODETEXT, h_data):
            CloseClipboard()
            return False
        
        CloseClipboard()
        return True
    except:
        try:
            CloseClipboard()
        except:
            pass
        return False


def is_clipboard_text_available():
    """检查剪贴板是否有文本"""
    try:
        if not OpenClipboard(None):
            return False
        h_data = GetClipboardData(CF_UNICODETEXT)
        CloseClipboard()
        return h_data is not None and h_data != 0
    except:
        try:
            CloseClipboard()
        except:
            pass
        return False


class ClipboardSyncApp:
    def __init__(self, root):
        self.root = root
        self.root.title("剪贴板同步工具")
        self.root.geometry("700x600")
        self.root.configure(bg=ModernUI.BG_COLOR)
        self.root.resizable(False, False)
        
        self.is_running = False
        self.server_socket = None
        self.clients = []
        self.port = 5150
        self.clipboard_monitor_thread = None
        self.last_clipboard_image = None
        self.last_clipboard_text = None
        
        # 系统托盘
        self.tray_icon = None
        self.is_minimized_to_tray = False
        
        # 创建应用图标
        self.create_app_icon()
        
        self.setup_ui()
        
        # 绑定窗口事件
        self.root.protocol('WM_DELETE_WINDOW', self.on_closing)
        
    def create_app_icon(self):
        """创建应用图标"""
        try:
            # 创建 64x64 的图标
            icon_size = 64
            icon = Image.new('RGBA', (icon_size, icon_size), (0, 0, 0, 0))
            draw = ImageDraw.Draw(icon)
            
            # 绘制圆形背景
            draw.ellipse([4, 4, 60, 60], fill='#1E88E5')
            
            # 绘制剪贴板图标
            draw.rectangle([20, 16, 44, 48], fill='white', outline='white', width=2)
            draw.rectangle([26, 12, 38, 18], fill='white', outline='white')
            
            # 绘制同步箭头
            draw.polygon([16, 36, 22, 32, 22, 40], fill='#4CAF50')
            draw.polygon([48, 28, 42, 32, 42, 24], fill='#4CAF50')
            
            # 保存用于托盘图标
            self.tray_icon_image = icon
            
            # 转换为 PhotoImage
            self.icon_photo = ImageTk.PhotoImage(icon)
            
            # 设置窗口图标
            self.root.iconphoto(True, self.icon_photo)
        except Exception as e:
            print(f"创建图标失败: {e}")
            self.tray_icon_image = None
        
    def setup_ui(self):
        """设置用户界面"""
        # 主容器
        main_container = tk.Frame(self.root, bg=ModernUI.BG_COLOR)
        main_container.pack(fill="both", expand=True, padx=20, pady=20)
        
        # 标题栏
        title_frame = tk.Frame(main_container, bg=ModernUI.BG_COLOR)
        title_frame.pack(fill="x", pady=(0, 20))
        
        title_label = tk.Label(
            title_frame,
            text="📋 剪贴板同步工具",
            font=ModernUI.TITLE_FONT,
            bg=ModernUI.BG_COLOR,
            fg=ModernUI.PRIMARY_COLOR
        )
        title_label.pack(side="left")
        
        version_label = tk.Label(
            title_frame,
            text="v1.0.0",
            font=ModernUI.BODY_FONT,
            bg=ModernUI.BG_COLOR,
            fg=ModernUI.SECONDARY_TEXT
        )
        version_label.pack(side="left", padx=10)
        
        # 状态卡片
        self.create_status_card(main_container)
        
        # 控制按钮
        self.create_control_buttons(main_container)
        
        # 日志区域
        self.create_log_area(main_container)
        
    def create_status_card(self, parent):
        """创建状态信息卡片"""
        card_frame = tk.Frame(
            parent,
            bg=ModernUI.CARD_BG,
            relief="flat",
            borderwidth=0
        )
        card_frame.pack(fill="x", pady=(0, 15))
        
        # 添加圆角效果
        card_frame.configure(highlightbackground=ModernUI.BORDER_COLOR, highlightthickness=1)
        
        inner_frame = tk.Frame(card_frame, bg=ModernUI.CARD_BG)
        inner_frame.pack(fill="both", padx=20, pady=15)
        
        # 状态指示器
        status_container = tk.Frame(inner_frame, bg=ModernUI.CARD_BG)
        status_container.pack(fill="x")
        
        self.status_indicator = tk.Label(
            status_container,
            text="●",
            font=("Arial", 20),
            bg=ModernUI.CARD_BG,
            fg=ModernUI.SECONDARY_TEXT
        )
        self.status_indicator.pack(side="left", padx=(0, 10))
        
        self.status_label = tk.Label(
            status_container,
            text="状态: 未启动",
            font=ModernUI.HEADING_FONT,
            bg=ModernUI.CARD_BG,
            fg=ModernUI.TEXT_COLOR,
            anchor="w"
        )
        self.status_label.pack(side="left", fill="x")
        
        # 分隔线
        separator = tk.Frame(inner_frame, bg=ModernUI.BORDER_COLOR, height=1)
        separator.pack(fill="x", pady=10)
        
        # IP 地址
        self.ip_label = tk.Label(
            inner_frame,
            text="🌐 IP地址: --",
            font=ModernUI.BODY_FONT,
            bg=ModernUI.CARD_BG,
            fg=ModernUI.TEXT_COLOR,
            anchor="w"
        )
        self.ip_label.pack(fill="x", pady=5)
        
        # 已连接设备
        self.client_label = tk.Label(
            inner_frame,
            text="📱 已连接设备: 0",
            font=ModernUI.BODY_FONT,
            bg=ModernUI.CARD_BG,
            fg=ModernUI.TEXT_COLOR,
            anchor="w"
        )
        self.client_label.pack(fill="x", pady=5)
        
    def create_control_buttons(self, parent):
        """创建控制按钮"""
        button_frame = tk.Frame(parent, bg=ModernUI.BG_COLOR)
        button_frame.pack(fill="x", pady=(0, 15))
        
        # 启动按钮
        self.start_button = tk.Button(
            button_frame,
            text="▶ 启动服务",
            command=self.start_service,
            font=ModernUI.HEADING_FONT,
            bg=ModernUI.SUCCESS_COLOR,
            fg="white",
            activebackground="#45A049",
            activeforeground="white",
            relief="flat",
            cursor="hand2",
            width=15,
            height=2,
            borderwidth=0
        )
        self.start_button.pack(side="left", expand=True, padx=(0, 10))
        
        # 停止按钮
        self.stop_button = tk.Button(
            button_frame,
            text="■ 停止服务",
            command=self.stop_service,
            font=ModernUI.HEADING_FONT,
            bg=ModernUI.ERROR_COLOR,
            fg="white",
            activebackground="#D32F2F",
            activeforeground="white",
            relief="flat",
            cursor="hand2",
            width=15,
            height=2,
            state="disabled",
            borderwidth=0
        )
        self.stop_button.pack(side="left", expand=True)
        
    def create_log_area(self, parent):
        """创建日志显示区域"""
        log_frame = tk.Frame(
            parent,
            bg=ModernUI.CARD_BG,
            relief="flat",
            borderwidth=0
        )
        log_frame.pack(fill="both", expand=True)
        log_frame.configure(highlightbackground=ModernUI.BORDER_COLOR, highlightthickness=1)
        
        # 日志标题
        log_title = tk.Label(
            log_frame,
            text="📝 运行日志",
            font=ModernUI.HEADING_FONT,
            bg=ModernUI.CARD_BG,
            fg=ModernUI.TEXT_COLOR,
            anchor="w"
        )
        log_title.pack(fill="x", padx=15, pady=(10, 5))
        
        # 日志文本框
        self.log_text = scrolledtext.ScrolledText(
            log_frame,
            height=12,
            font=ModernUI.MONO_FONT,
            bg="#FAFAFA",
            fg=ModernUI.TEXT_COLOR,
            relief="flat",
            borderwidth=0,
            wrap="word"
        )
        self.log_text.pack(fill="both", expand=True, padx=15, pady=(0, 15))
        
    def add_log(self, message):
        """添加日志"""
        timestamp = datetime.now().strftime("%H:%M:%S")
        log_message = f"[{timestamp}] {message}\n"
        self.log_text.insert("1.0", log_message)
        print(log_message.strip())
        
    def get_local_ip(self):
        """获取本机IP地址"""
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except:
            return "127.0.0.1"
            
    def start_service(self):
        """启动服务"""
        self.is_running = True
        self.start_button.config(state="disabled", bg=ModernUI.SECONDARY_TEXT)
        self.stop_button.config(state="normal", bg=ModernUI.ERROR_COLOR)
        
        # 启动 Socket 服务器
        threading.Thread(target=self.start_socket_server, daemon=True).start()
        
        # 启动剪贴板监听
        threading.Thread(target=self.monitor_clipboard, daemon=True).start()
        
        # 启动设备发现广播
        threading.Thread(target=self.start_discovery_broadcast, daemon=True).start()
        
        self.status_label.config(text="状态: 运行中", fg=ModernUI.SUCCESS_COLOR)
        self.status_indicator.config(fg=ModernUI.SUCCESS_COLOR)
        self.add_log("✅ 服务已启动")
        
    def stop_service(self):
        """停止服务"""
        self.is_running = False
        self.start_button.config(state="normal", bg=ModernUI.SUCCESS_COLOR)
        self.stop_button.config(state="disabled", bg=ModernUI.SECONDARY_TEXT)
        
        # 关闭所有客户端连接
        for client in self.clients[:]:
            try:
                client.close()
            except:
                pass
        self.clients.clear()
        
        # 关闭服务器
        if self.server_socket:
            try:
                self.server_socket.close()
            except:
                pass
            self.server_socket = None
            
        self.status_label.config(text="状态: 已停止", fg=ModernUI.SECONDARY_TEXT)
        self.status_indicator.config(fg=ModernUI.SECONDARY_TEXT)
        self.client_label.config(text="📱 已连接设备: 0")
        self.add_log("⛔ 服务已停止")
        
    def start_socket_server(self):
        """启动 TCP Socket 服务器"""
        try:
            self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            
            # 尝试绑定端口
            for port in range(5150, 5170):
                try:
                    self.server_socket.bind(("0.0.0.0", port))
                    self.port = port
                    break
                except:
                    continue
                    
            self.server_socket.listen(5)
            
            local_ip = self.get_local_ip()
            self.ip_label.config(text=f"🌐 IP地址: {local_ip}:{self.port}")
            self.add_log(f"🚀 Socket 服务器已启动，端口: {self.port}")
            
            # 接受客户端连接
            while self.is_running:
                try:
                    self.server_socket.settimeout(1.0)
                    client_socket, address = self.server_socket.accept()
                    self.clients.append(client_socket)
                    self.client_label.config(text=f"📱 已连接设备: {len(self.clients)}")
                    self.add_log(f"✅ 设备已连接: {address[0]}:{address[1]}")
                    
                    # 为每个客户端启动处理线程
                    threading.Thread(
                        target=self.handle_client, 
                        args=(client_socket, address),
                        daemon=True
                    ).start()
                except socket.timeout:
                    continue
                except:
                    break
                    
        except Exception as e:
            self.add_log(f"服务器启动失败: {e}")
            
    def handle_client(self, client_socket, address):
        """处理客户端连接"""
        buffer = ""
        try:
            while self.is_running and client_socket in self.clients:
                # 接收客户端消息
                try:
                    client_socket.settimeout(1.0)
                    data = client_socket.recv(4096)
                    if not data:
                        break
                    
                    # 解析接收到的数据
                    buffer += data.decode('utf-8')
                    while '\n' in buffer:
                        line, buffer = buffer.split('\n', 1)
                        if line.strip():
                            try:
                                message = json.loads(line)
                                self.handle_received_message(message, address)
                            except json.JSONDecodeError:
                                pass
                                
                except socket.timeout:
                    continue
                except Exception as e:
                    break
        except:
            pass
        finally:
            if client_socket in self.clients:
                self.clients.remove(client_socket)
                self.client_label.config(text=f"已连接设备: {len(self.clients)}")
                self.add_log(f"设备已断开: {address[0]}:{address[1]}")
            try:
                client_socket.close()
            except:
                pass
    
    def handle_received_message(self, message, address):
        """处理接收到的消息"""
        try:
            msg_type = message.get("type")
            content_type = message.get("contentType")
            content = message.get("content")
            
            if msg_type == "clipboard" and content_type == "text/plain":
                # 接收到文本,写入系统剪贴板
                self.set_clipboard_text(content)
                preview = content[:30] + "..." if len(content) > 30 else content
                self.add_log(f"收到来自 {address[0]} 的文本: {preview}")
        except Exception as e:
            self.add_log(f"处理消息失败: {e}")
    
    def set_clipboard_text(self, text):
        """设置系统剪贴板文本"""
        try:
            if set_clipboard_text(text):
                # 更新最后的文本,避免重复发送
                self.last_clipboard_text = text
            else:
                self.add_log(f"设置剪贴板失败")
        except Exception as e:
            self.add_log(f"设置剪贴板失败: {e}")
                
    def monitor_clipboard(self):
        """监听剪贴板变化"""
        self.add_log("剪贴板监听已启动")
        
        while self.is_running:
            try:
                # 尝试获取剪贴板中的图片
                image = ImageGrab.grabclipboard()
                
                if image and isinstance(image, Image.Image):
                    # 转换为字节数据
                    buffer = BytesIO()
                    image.save(buffer, format="PNG")
                    image_data = buffer.getvalue()
                    
                    # 检查是否是新图片
                    if image_data != self.last_clipboard_image:
                        self.last_clipboard_image = image_data
                        self.last_clipboard_text = None  # 清空文本记录
                        self.add_log(f"检测到新图片 ({len(image_data) // 1024} KB)")
                        
                        # 发送到所有连接的设备
                        self.send_image_to_clients(image_data)
                else:
                    # 尝试获取剪贴板中的文本
                    if is_clipboard_text_available():
                        text = get_clipboard_text()
                        
                        # 检查是否是新文本
                        if text and text != self.last_clipboard_text and len(text.strip()) > 0:
                            self.last_clipboard_text = text
                            self.last_clipboard_image = None  # 清空图片记录
                            self.add_log(f"检测到新文本 ({len(text)} 字符)")
                            
                            # 发送到所有连接的设备
                            self.send_text_to_clients(text)
                        
            except Exception as e:
                pass
                
            time.sleep(0.5)  # 每0.5秒检查一次
            
    def send_image_to_clients(self, image_data):
        """发送图片到所有客户端"""
        if not self.clients:
            self.add_log("没有已连接的设备")
            return
            
        # 构造消息
        message = {
            "type": "clipboard",
            "contentType": "image/png",
            "content": base64.b64encode(image_data).decode('utf-8'),
            "timestamp": int(time.time() * 1000)
        }
        
        json_data = json.dumps(message) + "\n"
        data_bytes = json_data.encode('utf-8')
        
        # 发送到所有客户端
        disconnected = []
        for client in self.clients:
            try:
                client.sendall(data_bytes)
            except:
                disconnected.append(client)
                
        # 移除断开的客户端
        for client in disconnected:
            if client in self.clients:
                self.clients.remove(client)
                try:
                    client.close()
                except:
                    pass
                    
        self.client_label.config(text=f"已连接设备: {len(self.clients)}")
        
        sent_count = len(self.clients)
        self.add_log(f"已发送图片到 {sent_count} 个设备")
    
    def send_text_to_clients(self, text):
        """发送文本到所有客户端"""
        if not self.clients:
            self.add_log("没有已连接的设备")
            return
            
        # 构造消息
        message = {
            "type": "clipboard",
            "contentType": "text/plain",
            "content": text,
            "timestamp": int(time.time() * 1000)
        }
        
        json_data = json.dumps(message, ensure_ascii=False) + "\n"
        data_bytes = json_data.encode('utf-8')
        
        # 发送到所有客户端
        disconnected = []
        for client in self.clients:
            try:
                client.sendall(data_bytes)
            except:
                disconnected.append(client)
                
        # 移除断开的客户端
        for client in disconnected:
            if client in self.clients:
                self.clients.remove(client)
                try:
                    client.close()
                except:
                    pass
                    
        self.client_label.config(text=f"已连接设备: {len(self.clients)}")
        
        sent_count = len(self.clients)
        preview = text[:30] + "..." if len(text) > 30 else text
        self.add_log(f"已发送文本到 {sent_count} 个设备: {preview}")
        
    def start_discovery_broadcast(self):
        """启动设备发现广播"""
        try:
            broadcast_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            broadcast_socket.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            
            computer_name = socket.gethostname()
            local_ip = self.get_local_ip()
            
            self.add_log("设备发现广播已启动")
            
            while self.is_running:
                try:
                    discovery_message = {
                        "deviceType": "windows",
                        "deviceName": computer_name,
                        "ipAddress": local_ip,
                        "port": self.port,
                        "timestamp": int(time.time() * 1000)
                    }
                    
                    message_bytes = json.dumps(discovery_message).encode('utf-8')
                    broadcast_socket.sendto(message_bytes, ("255.255.255.255", 5149))
                    
                except Exception as e:
                    pass
                    
                time.sleep(5)  # 每5秒广播一次
                
        except Exception as e:
            self.add_log(f"设备发现启动失败: {e}")
    
    def on_closing(self):
        """窗口关闭事件"""
        # 最小化到托盘而不是关闭
        self.minimize_to_tray()
    
    def minimize_to_tray(self):
        """最小化到系统托盘"""
        self.root.withdraw()  # 隐藏窗口
        self.is_minimized_to_tray = True
        
        if self.tray_icon is None and self.tray_icon_image:
            # 创建托盘图标
            menu = pystray.Menu(
                item('显示', self.show_window, default=True),
                item('启动服务', self.start_service_from_tray, visible=lambda item: not self.is_running),
                item('停止服务', self.stop_service_from_tray, visible=lambda item: self.is_running),
                pystray.Menu.SEPARATOR,
                item('退出', self.quit_app)
            )
            
            self.tray_icon = pystray.Icon(
                "clipboard_sync",
                self.tray_icon_image,
                "剪贴板同步工具",
                menu
            )
            
            # 在新线程中运行托盘图标
            threading.Thread(target=self.tray_icon.run, daemon=True).start()
    
    def show_window(self, icon=None, item=None):
        """显示窗口"""
        self.root.after(0, self._show_window)
    
    def _show_window(self):
        """显示窗口(在主线程中执行)"""
        self.root.deiconify()  # 显示窗口
        self.root.lift()  # 置顶
        self.root.focus_force()  # 获取焦点
        self.is_minimized_to_tray = False
    
    def start_service_from_tray(self, icon=None, item=None):
        """从托盘启动服务"""
        if not self.is_running:
            self.root.after(0, self.start_service)
    
    def stop_service_from_tray(self, icon=None, item=None):
        """从托盘停止服务"""
        if self.is_running:
            self.root.after(0, self.stop_service)
    
    def quit_app(self, icon=None, item=None):
        """退出应用"""
        if self.tray_icon:
            self.tray_icon.stop()
        if self.is_running:
            self.stop_service()
        self.root.quit()


def main():
    root = tk.Tk()
    app = ClipboardSyncApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
