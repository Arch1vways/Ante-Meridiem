#!/usr/bin/env python3
"""Prepare Samsung AOD font assets from an APK (or a ZIP with the APK's contents).

Usage: python3 tools/prepare_samsung_fonts.py /path/to/AODService_v80.apk
Requires fontTools. The input APK is not included in the source archive.
Original outlines, character coverage and all variable axes are retained. Only the
metrics are adjusted to the same clock size as the other fonts. Stretch and
closely repeating fixed/mono variants are deliberately omitted.
"""
import os
import sys
from zipfile import ZipFile
from fontTools.ttLib import TTFont
from prepare_fonts import OUT, normalize

# Stable catalog key -> path in the extracted Samsung AODService_v80 APK.
FONTS = {
    'samsung_capriola': 'assets/fonts/Capriola-Regular.ttf',
    'samsung_clock_classic': 'assets/fonts/Clock-Classic.ttf',
    'samsung_clock_stripe': 'assets/fonts/Clock-Stripe.ttf',
    'samsung_clock_2021': 'assets/fonts/Clock2021.ttf',
    'samsung_modex': 'assets/fonts/ClockModexVF.ttf',
    'samsung_stamp': 'assets/fonts/ClockStampVF.ttf',
    'samsung_trimex': 'assets/fonts/ClockTrimexVF.ttf',
    'samsung_fredericka': 'assets/fonts/FrederickatheGreat-Regular.ttf',
    'samsung_gwnum': 'assets/fonts/GWNum2019M.ttf',
    'samsung_lato': 'assets/fonts/Lato-Regular.ttf',
    'samsung_secnum_regular': 'assets/fonts/NewSECNum-400Regular.ttf',
    'samsung_secnum_semibold': 'assets/fonts/NewSECNum-600SemiBold.ttf',
    'samsung_clock_default': 'assets/fonts/OneUIClockDefaultNumVF.ttf',
    'samsung_clock_compact': 'assets/fonts/OneUISansClockCompactRd.ttf',
    'samsung_sfit': 'assets/fonts/SFitNum.ttf',
    'samsung_fluid_regular': 'assets/fonts/SamsungOneFluid-R.ttf',
    'samsung_fluid_light': 'assets/fonts/SamsungOneFluid-XL.ttf',
    'samsung_thom_browne': 'assets/fonts/thombrowne.otf',
    'samsung_sfit_bold': 'res/D0.ttf',
    'samsung_snum_regular': 'res/PM.ttf',
    'samsung_snum_light': 'res/TF.ttf',
    'samsung_moxie': 'res/bK.ttf',
    'samsung_snum_thin': 'res/ld.ttf',
}


def main(apk):
    os.makedirs(OUT, exist_ok=True)
    with ZipFile(apk) as z:
        for key, name in FONTS.items():
            dest = os.path.join(OUT, key + os.path.splitext(name)[1].lower())
            with open(dest, 'wb') as out:
                out.write(z.read(name))
            scale = normalize(dest, key)
            font = TTFont(dest)
            if any(ord(ch) not in font.getBestCmap() for ch in '0123456789:'):
                raise ValueError(key + ': missing clock glyphs')
            print('%-30s %7d bytes  scale %.3f' % (key, os.path.getsize(dest), scale))


if __name__ == '__main__':
    if len(sys.argv) != 2:
        sys.exit('Usage: python3 tools/prepare_samsung_fonts.py /path/to/AODService_v80.apk')
    main(sys.argv[1])
