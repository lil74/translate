"""Generate original, disposable EPUB fixtures for the parser smoke check."""
import argparse
import base64
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile


def write_epub(path, *, toc=True, text=True, encrypted=None, div_only=False):
    opf = '''<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="uid">urn:fixture:epub2</dc:identifier>
<dc:title>EPUB 2 compatibility fixture</dc:title>
<dc:creator>First Author</dc:creator><dc:creator>Second Author</dc:creator>
<dc:language>en</dc:language><meta name="cover" content="cover"/>
</metadata><manifest>
<item id="one" href="Text/second.xhtml" media-type="application/xhtml+xml"/>
<item id="two" href="Text/first.xhtml" media-type="application/xhtml+xml"/>
<item id="cover" href="Images/cover.png" media-type="image/png"/>
<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
</manifest><spine toc="ncx"><itemref idref="one"/><itemref idref="two"/></spine></package>'''
    ncx = '''<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>
<navPoint id="a"><navLabel><text>Opening</text></navLabel><content src="Text/second.xhtml"/>
<navPoint id="anchor"><navLabel><text>Middle</text></navLabel><content src="Text/second.xhtml#middle"/></navPoint></navPoint>
<navPoint id="b"><navLabel><text>Ending</text></navLabel><content src="Text/first.xhtml"/></navPoint>
</navMap></ncx>'''
    first = '<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Opening</h1><p>Before.</p><p id="middle">After <em>the turn</em>.</p></body></html>'
    second = '<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Ending</h1><p>Finish.</p></body></html>'
    if not text:
        first = second = '<html xmlns="http://www.w3.org/1999/xhtml"><body><img src="../Images/cover.png"/></body></html>'
    if div_only:
        first = '<html xmlns="http://www.w3.org/1999/xhtml"><body><div>First visible block.</div><div>Second visible block.</div></body></html>'
    cover = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a8O8AAAAASUVORK5CYII=')
    with ZipFile(path, 'w') as z:
        z.writestr('mimetype', 'application/epub+zip', compress_type=ZIP_STORED)
        z.writestr('META-INF/container.xml', '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>', compress_type=ZIP_DEFLATED)
        z.writestr('OPS/package.opf', opf, compress_type=ZIP_DEFLATED)
        if toc:
            z.writestr('OPS/toc.ncx', ncx, compress_type=ZIP_DEFLATED)
        z.writestr('OPS/Text/second.xhtml', first, compress_type=ZIP_DEFLATED)
        z.writestr('OPS/Text/first.xhtml', second, compress_type=ZIP_DEFLATED)
        z.writestr('OPS/Images/cover.png', cover, compress_type=ZIP_DEFLATED)
        if encrypted:
            z.writestr('META-INF/encryption.xml', '<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData><CipherData><CipherReference URI="' + encrypted + '"/></CipherData></EncryptedData></encryption>', compress_type=ZIP_DEFLATED)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    write_epub(args.output / 'synthetic-epub2.epub')
    write_epub(args.output / 'synthetic-no-toc.epub', toc=False)
    write_epub(args.output / 'synthetic-no-text.epub', text=False)
    write_epub(args.output / 'synthetic-div-only.epub', div_only=True)
    write_epub(args.output / 'synthetic-font-obfuscation.epub', encrypted='OPS/Fonts/font.otf')
    write_epub(args.output / 'synthetic-drm.epub', encrypted='OPS/Text/second.xhtml')
    (args.output / 'broken.epub').write_bytes(b'not an EPUB')
