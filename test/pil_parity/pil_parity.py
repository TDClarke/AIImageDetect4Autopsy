"""Checks that the Java preprocessing (PilResize) is bit-exact with Pillow.
   python3 pil_parity.py   (needs numpy + Pillow; run from this directory after compiling the core into ../../out)"""
import glob, os, subprocess, numpy as np
from PIL import Image
os.makedirs('imgs', exist_ok=True)
rng = np.random.default_rng(9)
def mk(w, h, mode='RGB'):
    x = np.linspace(0, 1, w)[None, :, None]; y = np.linspace(0, 1, h)[:, None, None]
    b = (np.sin(x*9 + y*5 + np.array([0, 1, 2]))*0.5 + 0.5)*200 + rng.normal(0, 25, (h, w, 3)); b[h//3:h//2, w//4:w//2] = 255
    return Image.fromarray(np.clip(b, 0, 255).astype('uint8'), 'RGB').convert(mode)
for i, (w, h) in enumerate([(333,2999),(900,2000),(2000,3000),(1000,700),(64,4000),(5000,120),(257,258),(4,4),(1,500),(100,80)]):
    mk(w, h).save(f'imgs/rgb{i}.png')
mk(400,300,'L').save('imgs/gray.png'); mk(400,300,'P').save('imgs/pal.png'); mk(500,500,'LA').save('imgs/la.png'); mk(640,480,'RGBA').save('imgs/rgba.png')
subprocess.check_call(['javac', '-d', 'cls', '-sourcepath', '../../src', 'ResizeDump.java'])  # package uk.co.aiimagedetect
subprocess.check_call(['java', '-cp', 'cls', 'uk.co.aiimagedetect.ResizeDump'] + sorted(glob.glob('imgs/*.png')))
bad = 0
for f in sorted(glob.glob('imgs/*.png')):
    ref = np.asarray(Image.open(f).convert('RGB').resize((256, 256), Image.BICUBIC)).reshape(-1).astype(int)
    got = np.frombuffer(open(f + '.java.raw', 'rb').read(), dtype=np.uint8).astype(int)
    d = int(np.abs(ref - got).max()); bad += d > 0; print(f, 'maxdiff', d)
print('ALL BIT-EXACT' if not bad else f'{bad} DIFFER')
