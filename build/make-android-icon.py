#!/usr/bin/env python3
"""生成安卓启动图标（各分辨率 PNG）。

为什么不用矢量图（vector drawable）：
  矢量图在理论上更好，但要配自适应图标（adaptive icon）才不出问题，
  而那需要额外的 XML 和更高的 API 要求 —— 对自用应用是白添麻烦。
  直接生成 PNG 最省事，各版本安卓都正常显示。

用法：python build/make-android-icon.py
"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")

# 各密度对应的图标边长（标准启动图标尺寸）
SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

BRAND = (47, 143, 91)          # 和 styles.xml 里的主题色一致
FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyhbd.ttc",   # 微软雅黑 Bold
    r"C:\Windows\Fonts\msyh.ttc",     # 微软雅黑
    r"C:\Windows\Fonts\simhei.ttf",   # 黑体
    r"C:\Windows\Fonts\simsun.ttc",   # 宋体
]


def find_font(size: int):
    from PIL import ImageFont

    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                continue
    return ImageFont.load_default()


def main():
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        print("需要 Pillow。请先安装：pip install pillow")
        return 1

    made = []
    for folder, side in SIZES.items():
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)

        # 画一个圆角方块底色 + 白字（放大会比较清楚，最后再缩）
        scale = 4
        big = side * scale
        img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
        draw = ImageDraw.Draw(img)

        # 圆角半径按图标约 22% —— 接近安卓自适应的观感
        radius = int(big * 0.22)
        draw.rounded_rectangle([0, 0, big - 1, big - 1], radius=radius, fill=BRAND)

        # 中间放一个"词"字
        font = find_font(int(big * 0.56))
        text = "词"
        try:
            box = draw.textbbox((0, 0), text, font=font)
            tw, th = box[2] - box[0], box[3] - box[1]
            tx = (big - tw) / 2 - box[0]
            ty = (big - th) / 2 - box[1]
        except AttributeError:
            tw, th = draw.textsize(text, font=font)
            tx, ty = (big - tw) / 2, (big - th) / 2
        draw.text((tx, ty), text, font=font, fill=(255, 255, 255, 255))

        out = img.resize((side, side), Image.LANCZOS)
        path = os.path.join(d, "ic_launcher.png")
        out.save(path, "PNG")
        made.append((folder, side, os.path.getsize(path)))

    print("已生成启动图标：")
    for folder, side, size in made:
        print(f"  {folder:<20} {side:>3}x{side:<3}  {size:>6} 字节")
    return 0


if __name__ == "__main__":
    sys.exit(main())
