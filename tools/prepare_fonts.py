#!/usr/bin/env python3
"""Builds app/src/main/assets/fonts/ from the original font files (not part of the sources).

Usage: python3 tools/prepare_fonts.py SRC_DIR
SRC_DIR holds the files named in FONTS below (OneUI-Lockscreen-Font-EXT APK assets, the iOS 26 numeric
fonts and Geist / Geist Mono from github.com/vercel/geist-font). Needs fontTools (pip install fonttools).

Every font is cut down to Basic Latin (the clock only draws digits and ':'), variations and OpenType features
are kept. A few fonts get extra treatment, see SPECIAL.
"""
import os
import shutil
import struct
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'fonts')

# asset id -> source file
FONTS = {
    'oppo_clock': 'LOCKSCREENOPPOBigClock.ttf',
    'harmony_bold': 'LOCKSCREENHarmonyOSSansSuperBold.ttf',
    'mi_serif': 'LOCKSCREENMiSerifF.ttf',
    'nothing_ntype': 'LOCKSCREENNType82.ttf',
    'nothing_ndot': 'LOCKSCREENNdot55.ttf',
    'nothing_thin': 'LOCKSCREENNothingOSThinNoColon.ttf',
    'moto_milky': 'LOCKSCREENMotoMilkyStackedRegular.ttf',
    'pixel_inflate': 'LOCKSCREENGooglePixelInflate.ttf',
    'bodoni_moda': 'LOCKSCREENBodoniModa.ttf',
    'dm_serif': 'LOCKSCREENDMSerifDisplay.ttf',
    'gravitas_one': 'LOCKSCREENGravitasOne-Regular.ttf',
    'chamberi': 'LOCKSCREENChamberiSuperDisplayBold.ttf',
    'geist': 'Geist-VF.ttf',
    'geist_mono': 'GeistMono-VF.ttf',
    'creato': 'LOCKSCREENCreatoDisplayRegular.ttf',
    'creato_bold': 'LOCKSCREENCreatoDisplayBold.ttf',
    'poppins': 'LOCKSCREENPoppinsRegular.ttf',
    'comfortaa': 'LOCKSCREENComfortaaRegular.ttf',
    'badeen': 'LOCKSCREENBadeenDisplayRegular.ttf',
    'cookierun': 'LOCKSCREENCookieRunBold.ttf',
    'monoton': 'LOCKSCREENMonoton.ttf',
    'riviera': 'LOCKSCREENRivieraRegular.ttf',
    'sf_pro': 'SFNumeric-VF.otf',
    'sf_rounded': 'SFRoundedNumeric-VF.otf',
    'sf_soft': 'SFSoftNumeric-VF.otf',
    'sf_compact_soft': 'SFCompactSoft-VF.otf',
    'sf_stencil': 'SFStencilNumeric-VF.otf',
    'sf_rail': 'SFRailNumeric-VF.otf',
    'sf_rail_rounded': 'SFRailRoundedNumeric-VF.otf',
    'sf_adaptive': 'SFAdaptiveNumeric-VF.otf',
    'sf_adaptive_soft': 'SFAdaptiveSoftNumeric-VF.otf',
    'new_york': 'NewYorkNumeric-VF.otf',
    'new_york_soft': 'NewYorkSoftNumeric-VF.otf',
    'adt_slab': 'ADTSlabNumeric-VF.otf',
    'adt_slab_soft': 'ADTSlabSoftNumeric-VF.otf',
}

UNICODES = list(range(0x20, 0x7F)) + [0xA0, 0x2007, 0x2236]


def pin(axis, value):
    """OPPO Big Clock: the HGHT axis stretches the digits far below the baseline; fix it at one height.

    The original line metrics (descent -3.5 em) are made for the tallest digits; they are fitted to the digits of
    the chosen height, otherwise the clock's text view becomes several times taller than the digits.
    """
    def run(font):
        font = instancer.instantiateVariableFont(font, {axis: value})
        from fontTools.pens.boundsPen import BoundsPen
        gs = font.getGlyphSet()
        cmap = font.getBestCmap()
        top, bottom = 0, 0
        for ch in '0123456789:':
            pen = BoundsPen(gs)
            gs[cmap[ord(ch)]].draw(pen)
            if pen.bounds:
                bottom = min(bottom, pen.bounds[1])
                top = max(top, pen.bounds[3])
        upm = font['head'].unitsPerEm
        asc, desc = int(top + 0.05 * upm), int(bottom - 0.05 * upm)
        hhea, os2 = font['hhea'], font['OS/2']
        hhea.ascent, hhea.descent, hhea.lineGap = asc, desc, 0
        os2.sTypoAscender, os2.sTypoDescender, os2.sTypoLineGap = asc, desc, 0
        os2.usWinAscent, os2.usWinDescent = asc, -desc
        return font
    return run


def relabel_wght(lo, default, hi):
    """SF Compact Soft has a 0..2 weight axis. Renaming the user range keeps the normalized design space."""
    def run(font):
        for a in font['fvar'].axes:
            if a.axisTag == 'wght':
                old = (a.minValue, a.defaultValue, a.maxValue)
                a.minValue, a.defaultValue, a.maxValue = lo, default, hi
        for inst in font['fvar'].instances:
            v = inst.coordinates.get('wght')
            if v is not None:
                t = 0 if old[2] == old[0] else (v - old[0]) / (old[2] - old[0])
                inst.coordinates['wght'] = lo + t * (hi - lo)
        if 'STAT' in font:
            del font['STAT']
        return font
    return run


# Size normalisation: every font is made to look as large as vivo's own clock fonts at the same text size.
# vivo's clock fonts (vivoSansClock*VF) have digits 0.72 em tall, line metrics +0.93 / -0.24 em.
TARGET_DIGIT_HEIGHT = 0.72
MAX_DIGIT_ADVANCE = 0.80      # very wide fonts are scaled by width instead, so the clock does not overflow
ASCENT, DESCENT, PAD = 0.93, 0.24, 0.05


def digit_box(font):
    """Digits at the default instance: top, bottom (em, y up) and mean advance (em)."""
    from fontTools.pens.boundsPen import BoundsPen
    upem = font['head'].unitsPerEm
    gs = font.getGlyphSet()
    cmap = font.getBestCmap()
    top, bottom, adv = 0, 0, 0
    for ch in '0123456789':
        g = cmap[ord(ch)]
        pen = BoundsPen(gs)
        gs[g].draw(pen)
        if pen.bounds:
            bottom = min(bottom, pen.bounds[1])
            top = max(top, pen.bounds[3])
        adv += font['hmtx'][g][0]
    return top / upem, bottom / upem, adv / 10 / upem


def scale_for(top, bottom, adv):
    """Same rule as FontNormalizer.scaleFor in the app (used for the user's fonts)."""
    height = top - bottom if -bottom > 0.1 * top else top     # digits that hang below the baseline count fully
    s = TARGET_DIGIT_HEIGHT / height
    if adv * s > MAX_DIGIT_ADVANCE:
        s = MAX_DIGIT_ADVANCE / adv
    s = max(0.5, min(2.0, s))
    return 1.0 if abs(s - 1) < 0.02 else s


def align_clock_metrics(path):
    """Limit padding in the clock-only copy to clock glyphs, also at weight-axis extremes.

    Android uses head.yMin/yMax for FontMetrics.top/bottom. Unrelated glyphs with very tall
    accents otherwise add padding and shift the clock. Their outlines are not removed; the
    bounds in this copy deliberately describe only clock characters.
    """
    from fontTools.pens.boundsPen import BoundsPen
    font = TTFont(path, lazy=False)
    upem = font['head'].unitsPerEm
    cmap = font.getBestCmap()
    locations = [None]
    if 'fvar' in font:
        for axis in font['fvar'].axes:
            if axis.axisTag == 'wght':
                locations += [{'wght': axis.minValue}, {'wght': axis.maxValue}]
    top, bottom = 0, 0
    for location in locations:
        gs = font.getGlyphSet(location=location)
        for ch in '0123456789:：٫٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹':
            name = cmap.get(ord(ch))
            if name is None:
                continue
            pen = BoundsPen(gs)
            gs[name].draw(pen)
            if pen.bounds:
                bottom = min(bottom, pen.bounds[1])
                top = max(top, pen.bounds[3])
    import math
    asc = min(32767, math.ceil(max(ASCENT * upem, top + PAD * upem)))
    desc = max(-32768, math.floor(min(-DESCENT * upem, bottom - PAD * upem)))
    # Patch bytes directly: re-saving with fontTools would recalculate the global bounds.
    with open(path, 'r+b') as out:
        raw = out.read(12)
        count = struct.unpack_from('>H', raw, 4)[0]
        records = out.read(count * 16)
        tables = {records[i:i+4]: struct.unpack_from('>II', records, i+8)
                  for i in range(0, len(records), 16)}
        head, _ = tables[b'head']
        out.seek(head + 38); out.write(struct.pack('>h', desc))
        out.seek(head + 42); out.write(struct.pack('>h', asc))
        if b'hhea' in tables:
            out.seek(tables[b'hhea'][0] + 4)
            out.write(struct.pack('>hhh', asc, desc, 0))
        if b'OS/2' in tables and tables[b'OS/2'][1] >= 78:
            out.seek(tables[b'OS/2'][0] + 68)
            out.write(struct.pack('>hhhHH', asc, desc, 0, asc, -desc))
    font.close()


def normalize(path, fid):
    """Scales the whole font by changing unitsPerEm (outlines, advances, kerning and variations stay as they are,
    Android scales them all by the new em) and gives it vivo's line metrics."""
    font = TTFont(path)
    top, bottom, adv = digit_box(font)
    s = scale_for(top, bottom, adv)
    head = font['head']
    old = head.unitsPerEm
    new = max(16, min(16384, round(old / s)))
    s = old / new
    head.unitsPerEm = new
    asc = round(max(ASCENT, top * s + PAD) * new)
    desc = round(min(-DESCENT, bottom * s - PAD) * new)
    hhea, os2 = font['hhea'], font['OS/2']
    hhea.ascent, hhea.descent, hhea.lineGap = asc, desc, 0
    os2.sTypoAscender, os2.sTypoDescender, os2.sTypoLineGap = asc, desc, 0
    os2.usWinAscent, os2.usWinDescent = asc, -desc
    font.save(path)
    align_clock_metrics(path)
    return s


SPECIAL = {
    'oppo_clock': pin('HGHT', 1),
    'sf_compact_soft': relabel_wght(100, 900, 900),
}


def main(src):
    os.makedirs(OUT, exist_ok=True)
    for fid, name in FONTS.items():
        path = os.path.join(src, name)
        ext = os.path.splitext(name)[1].lower()
        out = os.path.join(OUT, fid + ext)
        font = TTFont(path)
        if fid in SPECIAL:
            font = SPECIAL[fid](font)
        try:
            opt = subset.Options()
            opt.layout_features = ['*']
            opt.name_IDs = ['*']
            opt.name_languages = ['*']
            opt.notdef_outline = True
            opt.hinting = True
            opt.drop_tables = ['DSIG']
            s = subset.Subsetter(opt)
            s.populate(unicodes=UNICODES)
            s.subset(font)
            font.save(out)
            TTFont(out).getBestCmap()
        except Exception as e:  # a few tiny fonts have slightly broken gvar data: ship them as they are
            print('  keep original:', fid, e)
            if fid in SPECIAL:
                SPECIAL[fid](TTFont(path)).save(out)
            else:
                shutil.copy(path, out)
        scale = normalize(out, fid)
        print('%-20s %7d bytes  scale %.3f' % (fid, os.path.getsize(out), scale))


if __name__ == '__main__':
    main(sys.argv[1])
