import subprocess, xml.etree.ElementTree as ET, re, time, struct
from pathlib import Path

def run(*args):
    return subprocess.check_output(['adb', *args]).decode('utf-8', errors='replace')

def ui():
    for _ in range(4):
        result = run('shell','uiautomator','dump','/sdcard/settings-check.xml')
        if 'dumped' in result:
            return ET.fromstring(run('shell','cat','/sdcard/settings-check.xml'))
        time.sleep(.3)
    raise RuntimeError('Could not capture a fresh Android UI hierarchy')

def texts():
    return [n.get('text') for n in ui().iter('node') if n.get('text')]

def screen():
    png = subprocess.check_output(['adb','exec-out','screencap','-p'])
    return struct.unpack('>II', png[16:24])

def tap(text, search=False):
    width, height = screen()
    for attempt in range(24 if search else 1):
        nodes=[n for n in ui().iter('node') if n.get('text')==text or n.get('content-desc')==text]
        if nodes:
            node=nodes[-1]
            bounds=list(map(int,re.findall(r'\d+',node.get('bounds'))))
            if bounds[3] > bounds[1]:
                run('shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2))
                time.sleep(.6)
                return
        run('shell','input','swipe',str(width//2),str(height*3//4),str(width//2),str(height*2//5),'350')
    raise RuntimeError('Missing '+text+'; visible: '+str(texts()))

def shot(name):
    out=Path('app/build/tmp/settings'); out.mkdir(parents=True,exist_ok=True)
    (out/(name+'.png')).write_bytes(subprocess.check_output(['adb','exec-out','screencap','-p']))

def top():
    width, height = screen()
    for _ in range(8): run('shell','input','swipe',str(width//2),str(height//3),str(width//2),str(height*4//5),'80')
