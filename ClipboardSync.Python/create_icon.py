from PIL import Image, ImageDraw

# 创建 256x256 的图标
icon_size = 256
icon = Image.new('RGBA', (icon_size, icon_size), (0, 0, 0, 0))
draw = ImageDraw.Draw(icon)

# 绘制圆形背景
draw.ellipse([16, 16, 240, 240], fill='#1E88E5')

# 绘制左边的剪贴板
draw.rectangle([60, 50, 120, 180], fill='white', outline='white', width=6)
draw.rectangle([75, 40, 105, 55], fill='white', outline='white')
draw.ellipse([85, 42, 95, 52], fill='#1E88E5')

# 绘制右边的剪贴板
draw.rectangle([136, 76, 196, 206], fill='white', outline='white', width=6)
draw.rectangle([151, 66, 181, 81], fill='white', outline='white')
draw.ellipse([161, 68, 171, 78], fill='#1E88E5')

# 绘制同步箭头 - 从左到右
draw.polygon([115, 110, 135, 110, 135, 100, 155, 120, 135, 140, 135, 130, 115, 130], fill='#4CAF50')

# 绘制同步箭头 - 从右到左
draw.polygon([141, 160, 121, 160, 121, 170, 101, 150, 121, 130, 121, 140, 141, 140], fill='#2196F3')

# 保存为 ICO 格式
icon.save('icon.ico', format='ICO', sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])
print("图标已生成: icon.ico")
