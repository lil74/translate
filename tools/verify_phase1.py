"""UI acceptance on a disposable emulator. Python 3 + adb; explicit serial required.
--reset clears only the demo app on the selected device. Screenshots go to --output.
"""
import argparse, json, re, subprocess, time
from pathlib import Path
import xml.etree.ElementTree as ET
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--adb', default='adb'); p.add_argument('--serial',required=True)
p.add_argument('--output',default='work/phase1-results'); p.add_argument('--reset',action='store_true')
a=p.parse_args(); out=Path(a.output); out.mkdir(parents=True,exist_ok=True)
pkg='com.example.bilingualreader.debug'; checks=[]
def adb(*cmd,binary=False):
    r=subprocess.run([a.adb,'-s',a.serial,*cmd],capture_output=True,timeout=60,check=True)
    return r.stdout if binary else r.stdout.decode('utf-8',errors='replace')
def tree():
    adb('shell','uiautomator','dump','/sdcard/phase1-ui.xml')
    return ET.fromstring(adb('exec-out','cat','/sdcard/phase1-ui.xml'))
def find(r,rid=None,text=None,desc=None):
    return next((n for n in r.iter('node') if (not rid or n.get('resource-id')==pkg+':id/'+rid)
                 and (not text or text in n.get('text','')) and (not desc or desc in n.get('content-desc',''))),None)
def wait(fn,label):
    end=time.monotonic()+40
    while time.monotonic()<end:
        r=tree()
        if fn(r): return r
        time.sleep(.4)
    raise AssertionError('Timed out: '+label)
def tap(rid=None,text=None,desc=None):
    r=wait(lambda r:find(r,rid,text,desc) is not None,str((rid,text,desc)))
    n=find(r,rid,text,desc); assert n.get('enabled')=='true'
    x,y,xx,yy=map(int,re.findall(r'\d+',n.get('bounds')))
    adb('shell','input','tap',str((x+xx)//2),str((y+yy)//2))
def chapter(i):
    return wait(lambda r:find(r,'chapter_title',i) is not None and find(r,'original') is not None,'chapter '+i)
def shot(name): (out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))
def ok(label): checks.append(label); print('PASS: '+label,flush=True)
try:
    for setting in ['window_animation_scale','transition_animation_scale','animator_duration_scale']:
        adb('shell','settings','put','global',setting,'0')
    if a.reset: adb('shell','pm','clear',pkg)
    adb('shell','am','force-stop',pkg)
    adb('shell','am','start','-W','-n',pkg+'/com.example.bilingualreader.ui.MainActivity')
    wait(lambda r:find(r,text='The Little Journey') is not None,'bookshelf'); shot('01-bookshelf')
    ok('Bookshelf displays demo book')
    tap(text='The Little Journey')
    wait(lambda r:find(r,text='01  A small beginning') is not None and find(r,text='02  Beyond the window') is not None,'contents')
    shot('02-contents'); ok('Contents displays both chapters in reading order')
    tap(text='01  A small beginning'); r=chapter('01')
    assert find(r,'previous').get('enabled')=='false'
    assert find(r,'next').get('enabled')=='true'
    shot('03-reader'); ok('First chapter renders and disables previous boundary')
    tap(rid='next'); r=chapter('02')
    assert find(r,'next').get('enabled')=='false'
    assert find(r,'previous').get('enabled')=='true'
    assert find(r,text='On Saturday, Anna') is not None
    ok('Next chapter replaces text and disables next boundary')
    tap(rid='toc'); tap(rid='continue_reading'); chapter('02')
    ok('Contents then Continue restores second chapter')
    adb('shell','settings','put','system','accelerometer_rotation','0')
    adb('shell','settings','put','system','user_rotation','1'); time.sleep(2)
    assert chapter('02').get('rotation')=='1', 'Landscape rotation did not apply'
    adb('shell','settings','put','system','user_rotation','0'); time.sleep(2)
    assert chapter('02').get('rotation')=='0', 'Portrait rotation did not apply'
    ok('Landscape and portrait retain second chapter and original text')
    tap(rid='previous'); chapter('01'); ok('Previous chapter returns matching text')
    tap(rid='translate')
    wait(lambda r:find(r,'task_status','4 / 4') is not None,'demo translation')
    assert find(tree(),text='【模拟译文】') is not None
    shot('04-bilingual'); ok('Demo translation completes with labelled aligned paragraphs')
    tap(rid='next'); r=chapter('02')
    assert find(r,'task_status','未翻译') is not None
    assert find(r,'task_status','4 / 4') is None
    assert find(r,'translate').get('text')=='翻译本章'
    tap(rid='previous'); chapter('01')
    wait(lambda r:find(r,'task_status','4 / 4') is not None,'cached task restored')
    ok('Chapter without a task resets status; returning restores the matching task')
    # Toolbar overflow is the only ImageView with this accessibility description.
    tap(desc='More options'); tap(text='阅读设置')
    wait(lambda r:find(r,'font') is not None,'settings')
    tap(rid='night'); tap(rid='save')
    wait(lambda r:find(r,'night') is not None and find(r,'night').get('checked')=='true','night theme')
    shot('05-settings-night'); adb('shell','input','keyevent','4'); chapter('01')
    shot('06-reader-night'); ok('Night settings recreate the Activity and return to reader')
    adb('shell','input','keyevent','4')
    wait(lambda r:find(r,'continue_reading') is not None,'contents on back')
    adb('shell','input','keyevent','4')
    wait(lambda r:find(r,text='The Little Journey') is not None,'bookshelf on back')
    ok('System back traverses contents to bookshelf')
    (out/'result.json').write_text(json.dumps({'passed':checks},indent=2),encoding='utf-8')
except Exception as e:
    (out/'result.json').write_text(json.dumps({'passed':checks,'failure':str(e)},indent=2),encoding='utf-8')
    shot('failure'); raise
finally:
    adb('shell','settings','put','system','user_rotation','0')
