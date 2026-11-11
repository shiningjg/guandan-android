#!/usr/bin/env python3
"""生成掼蛋 App 启动图标（Android adaptive icon + 各密度 legacy 图标）。

设计：深绿牌桌底色，两张交叠的白色扑克牌，前牌中央一个红色「掼」字，
左上角红桃 ♥、右下角黑桃 ♠ 角标，金色细描边。

输出：
  res/mipmap-*/ic_launcher_foreground.png   adaptive 前景（108dp 画布）
  res/mipmap-*/ic_launcher.png              legacy 方形（48dp）
  res/mipmap-*/ic_launcher_round.png        legacy 圆形（48dp）
背景色由 res/values/colors.xml 的 ic_launcher_background 提供，
adaptive 组合关系见 res/mipmap-anydpi-v26/*.xml。

用法：python3 tools/gen_icon.py
依赖：Pillow、Noto Sans CJK SC、DejaVu Sans Bold
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"

CJK_FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
CJK_INDEX = 2  # Noto Sans CJK SC
SYMBOL_FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

# 4 倍超采样，108dp 画布 → 432px
S = 4
CANVAS = 108 * S

BG_DARK = (9, 68, 45, 255)       # #09442D 深绿牌桌
CARD_BACK = (236, 238, 232, 255)
CARD_FRONT = (253, 252, 248, 255)
GOLD = (226, 190, 105, 255)
GOLD_DIM = (168, 148, 96, 255)
RED = (196, 42, 42, 255)
INK = (26, 62, 46, 255)

# adaptive 前景为 108dp；legacy 图标为 48dp
DENSITY = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}


def d(value_dp: float) -> int:
    return int(round(value_dp * S))


def font(path: str, size_dp: float, index: int = 0) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(path, d(size_dp), index=index)


def blank() -> Image.Image:
    return Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))


def card(w_dp: float, h_dp: float, angle: float, fill, outline, outline_w: float,
         decorator=None) -> Image.Image:
    """画一张可旋转的扑克牌（单独图层，便于做交叠与倾斜）。"""
    layer = blank()
    dr = ImageDraw.Draw(layer)
    cx = cy = CANVAS / 2
    box = [cx - d(w_dp / 2), cy - d(h_dp / 2), cx + d(w_dp / 2), cy + d(h_dp / 2)]
    dr.rounded_rectangle(box, radius=d(5), fill=fill, outline=outline, width=max(1, d(outline_w)))
    if decorator:
        decorator(dr, box)
    if angle:
        layer = layer.rotate(angle, resample=Image.Resampling.BICUBIC, expand=False)
    return layer


def foreground() -> Image.Image:
    fg = blank()

    # 后牌：略左、略下，倾斜 +13°，只露出边缘，营造「两副牌 / 多张牌」层次
    back = card(40, 54, 13, CARD_BACK, GOLD_DIM, 0.8)
    fg.paste(back, (d(-7), d(4)), back)

    def decorate(dr: ImageDraw.ImageDraw, box) -> None:
        f_big = font(CJK_FONT, 33, index=CJK_INDEX)
        f_sym = font(SYMBOL_FONT, 8)
        cx = (box[0] + box[2]) / 2
        cy = (box[1] + box[3]) / 2
        dr.text((cx, cy - d(1)), "掼", font=f_big, fill=RED, anchor="mm")
        # 花色角标（旋转前绘制，会随牌一起倾斜）
        dr.text((box[0] + d(6), box[1] + d(7)), "♥", font=f_sym, fill=RED, anchor="mm")
        dr.text((box[2] - d(6), box[3] - d(7)), "♠", font=f_sym, fill=INK, anchor="mm")

    # 前牌：略右、略上，倾斜 -8°，金色描边
    front = card(44, 58, -8, CARD_FRONT, GOLD, 1.4, decorator=decorate)
    fg.paste(front, (d(3), d(-1)), front)
    return fg


def shrink_to_safe_zone(img: Image.Image, factor: float) -> Image.Image:
    """adaptive 图标会被 launcher 裁成中心直径 66dp 的圆形（安全区）。

    原始牌面（含倾斜与偏移）最远角约 44dp 半径，直接给系统会切掉牌角，
    因此前景整体缩到 0.72 倍（约 32dp 半径），保证圆形/方圆形遮罩下都完整。
    """
    w = int(CANVAS * factor)
    small = img.resize((w, w), Image.Resampling.LANCZOS)
    out = blank()
    off = (CANVAS - w) // 2
    out.paste(small, (off, off), small)
    return out


def legacy_background(size: int, round_icon: bool) -> Image.Image:
    bg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    dr = ImageDraw.Draw(bg)
    r = size * (0.5 if round_icon else 0.22)
    dr.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=BG_DARK)
    # 一圈很淡的金色内环，呼应牌桌
    ring_r = size * 0.435
    dr.ellipse(
        [size / 2 - ring_r, size / 2 - ring_r, size / 2 + ring_r, size / 2 + ring_r],
        outline=(226, 190, 105, 70), width=max(1, int(size * 0.012)),
    )
    return bg


def main() -> None:
    fg = foreground()                          # 原尺寸，供 legacy 图标使用
    fg_adaptive = shrink_to_safe_zone(fg, 0.72)  # 收缩到 adaptive 安全区

    for name, scale in DENSITY.items():
        out = RES / f"mipmap-{name}"
        out.mkdir(parents=True, exist_ok=True)

        # adaptive 前景：108dp 画布，内容已收缩到圆形安全区内
        fg_size = int(round(108 * scale))
        fg_adaptive.resize((fg_size, fg_size), Image.Resampling.LANCZOS).save(
            out / "ic_launcher_foreground.png"
        )

        # legacy：截取前景中心 76dp（内容安全包含在内）再缩放，避免牌面显得过小
        crop = 76 * S
        c0 = (CANVAS - crop) // 2
        content = fg.crop((c0, c0, c0 + crop, c0 + crop))
        icon_size = int(round(48 * scale))
        content = content.resize((icon_size, icon_size), Image.Resampling.LANCZOS)

        square = legacy_background(icon_size, round_icon=False)
        square.alpha_composite(content)
        square.convert("RGB").save(out / "ic_launcher.png")

        circle = legacy_background(icon_size, round_icon=True)
        circle.alpha_composite(content)
        circle.convert("RGB").save(out / "ic_launcher_round.png")

        print(f"mipmap-{name}: foreground {fg_size}px, legacy {icon_size}px")

    print("图标资源已生成到", RES)


if __name__ == "__main__":
    main()
