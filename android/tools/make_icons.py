#!/usr/bin/env python3
"""KT 칠금점 개통장부 — 레거시 런처 아이콘(ic_launcher, ic_launcher_round) 생성기.

안드로이드 8.0 미만 기기에서 쓰이는 PNG 아이콘을 그린다.
(8.0 이상은 mipmap-anydpi-v26/ic_launcher.xml 의 adaptive 아이콘을 쓴다.)

사용: python3 make_icons.py <res 디렉터리 경로>
"""
import os
import sys

from PIL import Image, ImageDraw

SS = 8  # 슈퍼샘플링 배수
TOP = (0x1D, 0x4E, 0xD8)
BOTTOM = (0x0F, 0x4C, 0x81)
WHITE = (0xFF, 0xFF, 0xFF, 0xFF)

# 글리프 좌표(0~1 정규화). adaptive 아이콘의 전경 벡터와 같은 비율.
K_STEM = [(0.220, 0.330), (0.268, 0.330), (0.268, 0.670), (0.220, 0.670)]
K_UP = [(0.260, 0.520), (0.395, 0.330), (0.452, 0.330), (0.317, 0.520)]
K_DOWN = [(0.317, 0.480), (0.452, 0.670), (0.395, 0.670), (0.260, 0.480)]
T_BAR = [(0.480, 0.330), (0.780, 0.330), (0.780, 0.378), (0.480, 0.378)]
T_STEM = [(0.603, 0.378), (0.657, 0.378), (0.657, 0.670), (0.603, 0.670)]

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def gradient(size):
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x * 0.35 + y * 0.65) / max(1, size - 1)
            px[x, y] = tuple(int(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3))
    return img


def draw_icon(size, round_shape):
    s = size * SS
    base = gradient(s).convert("RGBA")

    # 모양 마스크
    mask = Image.new("L", (s, s), 0)
    md = ImageDraw.Draw(mask)
    if round_shape:
        md.ellipse([0, 0, s - 1, s - 1], fill=255)
    else:
        r = int(s * 0.18)
        md.rounded_rectangle([0, 0, s - 1, s - 1], radius=r, fill=255)

    bg = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    bg.paste(base, (0, 0), mask)

    glyph = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glyph)
    for poly in (K_STEM, K_UP, K_DOWN, T_BAR, T_STEM):
        gd.polygon([(x * s, y * s) for x, y in poly], fill=WHITE)

    out = Image.alpha_composite(bg, glyph)
    return out.resize((size, size), Image.LANCZOS)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    res_dir = sys.argv[1]
    for density, px in DENSITIES.items():
        folder = os.path.join(res_dir, "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)
        draw_icon(px, False).save(os.path.join(folder, "ic_launcher.png"), optimize=True)
        draw_icon(px, True).save(os.path.join(folder, "ic_launcher_round.png"), optimize=True)
        print("wrote", folder, px)
    # 스플래시/스토어용 큰 이미지(참고용, 앱 리소스로는 쓰지 않음)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
