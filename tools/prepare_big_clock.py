#!/usr/bin/env python3
"""Rebuild the Big Clock static glyph masks and marker font from the supplied Samsung AOD APK.
Usage: python3 tools/prepare_big_clock.py AODService.apk
Requires Android aapt, numpy, OpenCV and fontTools. The APK is not included.
Masks reproduce the static path gradient approximately, not Samsung's animated GLSL.
"""
import os, re, sys, subprocess, xml.etree.ElementTree as ET
from zipfile import ZipFile
from pathlib import Path
import cv2
import numpy as np
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from PIL import Image

ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/'app/src/main/assets'
aapt=os.environ.get('AAPT','aapt')
apk=sys.argv[1]
root=ET.fromstring(ZipFile(apk).read('assets/motion_clock/numeric_data/big_clock.xml'))
s=subprocess.check_output([aapt,'dump','--values','resources',apk],text=True,encoding='utf-8')
vals={}
for m in re.finditer(r'^        resource [^\n]*?:array/(big_clock_num_\d+_(?:vertices|control_points)): <bag>\n(.*?)(?=^        resource |\Z)',s,re.M|re.S):
 vals[m[1]]=[tuple(map(float,x.split(','))) for x in re.findall(r'\(string8\) "([^"]+)"',m[2])]
assert len(vals)==20,len(vals)
(OUT/'big_clock').mkdir(exist_ok=True)
# 480px/em; digit cell is .56em wide and .72em high. Coordinates use Samsung's y-up geometry.
S=2; EM=480; W=round(.56*EM)*S; H=round(.72*EM)*S; radius=.08*EM*S
masks=[]
for el in root:
 ch=el.findtext('number'); vertices=vals[el.findtext('vertices')]; cp=vals[el.findtext('control_points')]; kinds=el.findtext('segment_list'); segments=[]
 for i,k in enumerate(kinds):
  a=np.array(vertices[i]); b=np.array(vertices[i+1]); t=np.linspace(0,1,61)[:,None]
  if k=='0': points=a*(1-t)+b*t
  elif k=='1': points=a*(1-t)**2+2*(1-t)*t*np.array(cp[2*i])+t*t*b
  else: points=a*(1-t)**3+3*(1-t)**2*t*np.array(cp[2*i])+3*(1-t)*t*t*np.array(cp[2*i+1])+t**3*b
  segments.append(points)
 lengths=np.array([np.linalg.norm(np.diff(pts,axis=0),axis=1).sum() for pts in segments]); fractions=np.r_[0,np.cumsum(lengths)]/sum(lengths)
 mask=np.zeros((H,W),dtype=np.uint8)
 for j,pts in enumerate(segments):
  for i,point in enumerate(pts[1:],1):
   t=i/60; start=max(.1,fractions[j]) if fractions[j]==0 else fractions[j]
   brightness=round(255 * abs(start+t*(fractions[j+1]-start))**.7)
   x=round(W/2 + point[0]*EM*S); y=round(H/2 - point[1]*EM*S); r=round(radius)
   x1=max(0,x-r-1); x2=min(W,x+r+2); y1=max(0,y-r-1);y2=min(H,y+r+2)
   tmp=np.zeros((y2-y1,x2-x1),np.uint8);cv2.circle(tmp,(x-x1,y-y1),r,brightness,-1,cv2.LINE_AA)
   np.maximum(mask[y1:y2,x1:x2],tmp,out=mask[y1:y2,x1:x2])
 im=Image.fromarray(mask).resize((W//S,H//S),Image.Resampling.LANCZOS)
 rgba=Image.new('RGBA',im.size,(255,255,255,0));rgba.putalpha(im);rgba.save(OUT/'big_clock'/f'{ch}.png',optimize=True)
 masks.append((int(ch),np.asarray(im)))

# A real fallback/marker font with the same silhouettes, so clocks remain legible when the hook cannot draw.
units=1000; order=['.notdef','space','colon']+[f'd{i}' for i in range(10)]; glyphs={}; metrics={}
def blank(): return TTGlyphPen(None).glyph()
glyphs['.notdef']=blank();glyphs['space']=blank();metrics['.notdef']=(560,0);metrics['space']=(280,0)
pen=TTGlyphPen(None)
for cy in (235,535):
 pts=[(140+55*np.cos(t),cy+55*np.sin(t)) for t in np.linspace(0,-2*np.pi,24,endpoint=False)]
 pen.moveTo(tuple(map(round,pts[0])))
 for pt in pts[1:]:pen.lineTo(tuple(map(round,pt)))
 pen.closePath()
glyphs['colon']=pen.glyph();metrics['colon']=(280,0)
for n,img in masks:
 # Any visible stroke: contours give a monochrome approximation of the Samsung paths.
 binary=(img>35).astype(np.uint8)*255
 contours,_=cv2.findContours(binary,cv2.RETR_LIST,cv2.CHAIN_APPROX_SIMPLE)
 pen=TTGlyphPen(None)
 for contour in contours:
  approx=cv2.approxPolyDP(contour,0.45,True).reshape(-1,2)
  if len(approx)<3:continue
  pts=[(round(x / img.shape[1]*560),round((img.shape[0]-y)/img.shape[0]*720)) for x,y in approx]
  pen.moveTo(pts[0])
  for pt in pts[1:]:pen.lineTo(pt)
  pen.closePath()
 glyphs[f'd{n}']=pen.glyph();metrics[f'd{n}']=(560,0)
fb=FontBuilder(units,isTTF=True);fb.setupGlyphOrder(order);fb.setupCharacterMap({32:'space',58:'colon',**{48+i:f'd{i}' for i in range(10)}})
fb.setupGlyf(glyphs);fb.setupHorizontalMetrics(metrics);fb.setupHorizontalHeader(ascent=930,descent=-240,lineGap=0)
fb.setupNameTable({'familyName':'Ante Big Clock Marker','styleName':'Regular','uniqueFontIdentifier':'Ante Big Clock Marker','fullName':'Ante Big Clock Marker','psName':'AnteBigClockMarker'})
fb.setupOS2(sTypoAscender=930,sTypoDescender=-240,usWinAscent=930,usWinDescent=240);fb.setupPost();fb.setupMaxp()
font=OUT/'fonts/samsung_big_clock.ttf';fb.save(font)
sys.path.insert(0,str(ROOT/'tools'));from prepare_fonts import align_clock_metrics
align_clock_metrics(font)
print('Generated marker font and ten static gradient masks')
