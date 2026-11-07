"""C/M1 standalone verification. No Android SDK or Python third-party packages required."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sha = lambda value: hashlib.sha256(value.encode('utf-8')).hexdigest()


def extract(path, output):
    # This probe is intentionally specific to the provided XHTML book, not B's importer.
    with zipfile.ZipFile(path) as book:
        container = ET.fromstring(book.read('META-INF/container.xml'))
        opf_path = next(e.attrib['full-path'] for e in container.iter()
                        if e.tag.split('}')[-1] == 'rootfile')
        opf = ET.fromstring(book.read(opf_path))
        manifest = {e.attrib['id']: e.attrib['href'] for e in opf.iter()
                    if e.tag.split('}')[-1] == 'item'}
        spine = [manifest[e.attrib['idref']] for e in opf.iter()
                 if e.tag.split('}')[-1] == 'itemref' and e.attrib.get('linear') != 'no']
        resource = 'OEBPS/part0005.xhtml'
        paths = [str(PurePosixPath(opf_path).parent / p) for p in spine]
        spine_index = paths.index(resource)
        body = ET.fromstring(book.read(resource)).find('{*}body')
        blocks = []
        for e in body.iter():
            if e.tag.split('}')[-1] in {'h1', 'h2', 'h3', 'p', 'li', 'blockquote'}:
                if any(c is not e and c.tag.split('}')[-1]
                       in {'h1', 'h2', 'h3', 'p', 'li', 'blockquote'} for c in e.iter()):
                    continue
                text = ' '.join(''.join(e.itertext()).split())
                if text:
                    blocks.append(text)
        # Fail rather than silently applying the manual references to a different edition.
        if blocks[2] != 'He doesn’t know I’m alive.':
            raise ValueError('Unexpected edition/segmentation; review sample selection')
        references = [
            '他根本不知道我的存在。',
            '四十五分钟里，我已经数不清是第几次偷偷朝贾斯廷·科尔看过去了。他好看得让我喉咙发紧。不过，我大概该换个形容词——我的男性朋友们坚持说，男人不喜欢被人说“漂亮”。',
            '可天哪，除了这个词，我实在想不出还能怎么形容他棱角分明的面容和那双深情的棕色眼睛。今天他戴着棒球帽，但我知道帽子下面是什么样：浓密的深色头发，看起来摸上去一定很柔滑，让人忍不住想把手指插进去。',
        ]
        book_id = hashlib.sha256(Path(path).read_bytes()).hexdigest()
        chapter_id = sha(f'{book_id}|{spine_index}|{resource}')
        items = [dict(paragraphId=sha(f'{chapter_id}|{index}'), sourceHash=sha(blocks[index]),
                      text=blocks[index], reference=references[index-2], orderIndex=index)
                 for index in range(2, 5)]
        fixture = dict(bookId=book_id, chapterId=chapter_id, spineIndex=spine_index,
                       resourcePath=resource, paragraphCount=len(blocks), parserVersion=1,
                       referenceKind='manual-reference-not-machine-translation', items=items)
        output.write_text(json.dumps(fixture, ensure_ascii=False, indent=2), encoding='utf-8')
        return fixture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--epub', required=True, type=Path)
    parser.add_argument('--gson', type=Path, help='Existing gson-2.x.jar; otherwise search Gradle cache')
    parser.add_argument('--endpoint', help='Optional real LibreTranslate /translate URL; sends 3 paragraphs')
    args = parser.parse_args()
    gson = args.gson or next((Path.home()/'.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson')
                             .rglob('gson-*.jar'), None)
    if gson is None or not gson.exists():
        parser.error('Supply --gson with a local Gson JAR (already a project dependency)')
    work = ROOT/'work/translation-m1'
    work.mkdir(parents=True, exist_ok=True)
    fixture = extract(args.epub, work/'epub-sample.json')
    # JVM-only compile annotation; never included in the app or shared contracts.
    stub = work/'Nullable.java'
    stub.write_text('package androidx.annotation; public @interface Nullable {}', encoding='utf-8')
    package = ROOT/'app/src/main/java/com/example/bilingualreader'
    sources = [package/'contract/model'/f'{name}.java'
               for name in ['AppException', 'ErrorCode', 'TranslationStatus']]
    sources += [p for p in (package/'contract/translation').glob('*.java')
                if p.name != 'TranslationCoordinator.java']
    sources += list((package/'translation').glob('*.java'))
    sources += [ROOT/'app/src/debug/java/com/example/bilingualreader/translation/FakeTranslationService.java',
                ROOT/'tools/java/TranslationM1Check.java', stub]
    subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', str(gson), '-d', str(work),
                    *map(str, sources)], check=True)
    command = ['java', '-cp', str(work)+os.pathsep+str(gson), 'TranslationM1Check',
               str(work/'epub-sample.json'), str(work/'report.json')]
    if args.endpoint:
        command.append(args.endpoint)
    subprocess.run(command, check=True)
    report = json.loads((work/'report.json').read_text(encoding='utf-8'))
    lines = ['# C 第一阶段本地验证结果', '',
             f"结构与异常验证：{report['checks']} 项通过。", '',
             f"EPUB 第一章 spineIndex={fixture['spineIndex']}，文本块 {fixture['paragraphCount']} 个。",
             '选取 orderIndex=2、3、4 连续三个段落；ID 以本次探针分段为准，接 B 后需比对其解析输出。', '',
             '以下为人工参考译文的 ID 对应验证，不是翻译引擎质量或在线服务验收。', '',
             '| 段落 ID | 原文 | 中文参考 |', '|---|---|---|']
    for item in fixture['items']:
        lines.append(f"| `{item['paragraphId']}` | {item['text']} | {item['reference']} |")
    lines += ['', '真实服务：'+report['realService'], '']
    if 'realOutputs' in report:
        lines += ['## 本次在线译文', '']
        lines += [f"- `{o['paragraphId']}`：{o['translatedText']}" for o in report['realOutputs']]
    (work/'report.md').write_text('\n'.join(lines), encoding='utf-8')
    print('Report:', work/'report.md')


if __name__ == '__main__':
    main()
