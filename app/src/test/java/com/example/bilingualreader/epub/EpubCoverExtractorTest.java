package com.example.bilingualreader.epub;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import org.junit.*;

public class EpubCoverExtractorTest {
  private static final byte[] PNG =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j5WQAAAAASUVORK5CYII=");
  private File source;

  private File fixture(
      String metadata, String manifest, String guide, Map<String, byte[]> resources)
      throws Exception {
    source = File.createTempFile("epub-cover-", ".epub");
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put(
        "META-INF/container.xml",
        ("<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile"
             + " full-path='OPS/book.opf'/></rootfiles></container>")
            .getBytes(StandardCharsets.UTF_8));
    entries.put(
        "OPS/book.opf",
        ("<package xmlns='http://www.idpf.org/2007/opf' version='3.0'><metadata"
             + " xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>Cover test</dc:title>"
                + metadata
                + "</metadata><manifest><item id='body' href='body.xhtml'"
                + " media-type='application/xhtml+xml'/>"
                + manifest
                + "</manifest><spine><itemref idref='body'/></spine>"
                + guide
                + "</package>")
            .getBytes(StandardCharsets.UTF_8));
    entries.put(
        "OPS/body.xhtml",
        "<html><body><h1>First chapter</h1><p>Original body.</p></body></html>"
            .getBytes(StandardCharsets.UTF_8));
    entries.putAll(resources);
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(source))) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    return source;
  }

  @After
  public void cleanup() throws Exception {
    if (source != null) Files.deleteIfExists(source.toPath());
  }

  private void assertCover(File file) throws Exception {
    EpubCoverExtractor.Cover found = EpubCoverExtractor.find(file);
    assertNotNull(found);
    assertArrayEquals(PNG, found.bytes);
    assertEquals("png", found.extension);
    EpubParser.Result parsed = new EpubParser().parse(file, "stable-book-id", "Test");
    assertArrayEquals(PNG, parsed.cover);
    assertEquals(1, parsed.chapters.size());
    assertEquals("Original body.", parsed.paragraphs.get(1).getOriginalText());
  }

  @Test
  public void epub3PropertyAndMimeMismatch() throws Exception {
    assertCover(
        fixture(
            "",
            "<item id='art' href='art.bin' properties='cover-image'"
                + " media-type='application/octet-stream'/>",
            "",
            Collections.singletonMap("OPS/art.bin", PNG)));
  }

  @Test
  public void epub2Metadata() throws Exception {
    assertCover(
        fixture(
            "<meta name='cover' content='art'/>",
            "<item id='art' href='art.png' media-type='image/png'/>",
            "",
            Collections.singletonMap("OPS/art.png", PNG)));
  }

  @Test
  public void guideCoverPageWithRelativeEncodedPath() throws Exception {
    Map<String, byte[]> files = new HashMap<>();
    files.put(
        "OPS/pages/front.xhtml",
        "<html><body><img src='../images/book%20art.png'/></body></html>"
            .getBytes(StandardCharsets.UTF_8));
    files.put("OPS/images/book art.png", PNG);
    assertCover(
        fixture(
            "",
            "",
            "<guide><reference type='cover' href='pages/front.xhtml#cover'/></guide>",
            files));
  }

  @Test
  public void svgWrapperInsideCoverPage() throws Exception {
    Map<String, byte[]> files = new HashMap<>();
    files.put(
        "OPS/front.xhtml",
        "<html><body><svg><image xlink:href='image.png'/></svg></body></html>"
            .getBytes(StandardCharsets.UTF_8));
    files.put("OPS/image.png", PNG);
    assertCover(
        fixture(
            "<meta name='cover' content='wrapper'/>",
            "<item id='wrapper' href='front.xhtml' media-type='application/xhtml+xml'/>",
            "",
            files));
  }

  @Test
  public void namedCoverFallback() throws Exception {
    assertCover(
        fixture(
            "",
            "<item id='picture' href='images/cover.png' media-type='image/png'/>",
            "",
            Collections.singletonMap("OPS/images/cover.png", PNG)));
  }

  @Test
  public void missingDeclaredCoverDoesNotBreakText() throws Exception {
    File file =
        fixture(
            "<meta name='cover' content='broken'/>",
            "<item id='broken' href='missing.png' media-type='image/png'/>",
            "",
            Collections.emptyMap());
    assertNull(EpubCoverExtractor.find(file));
    assertNull(new EpubParser().parse(file, "id", "Test").cover);
  }

  @Test
  public void ordinaryIllustrationIsNotChosenAsCover() throws Exception {
    assertNull(
        EpubCoverExtractor.find(
            fixture(
                "",
                "<item id='illustration' href='scene.png' media-type='image/png'/>",
                "",
                Collections.singletonMap("OPS/scene.png", PNG))));
  }

  @Test
  public void remoteImageAndTraversalAreIgnored() throws Exception {
    Map<String, byte[]> files =
        Collections.singletonMap(
            "OPS/front.xhtml",
            "<html><img src='https://example.invalid/picture.png'/><img src='../../escape.png'/></html>"
                .getBytes(StandardCharsets.UTF_8));
    assertNull(
        EpubCoverExtractor.find(
            fixture("", "", "<guide><reference type='cover' href='front.xhtml'/></guide>", files)));
  }

  @Test
  public void encryptedCoverIsIgnored() throws Exception {
    Map<String, byte[]> files = new HashMap<>();
    files.put("OPS/art.png", PNG);
    files.put(
        "META-INF/encryption.xml",
        "<encryption><EncryptedData><CipherData><CipherReference URI='OPS/art.png'/></CipherData></EncryptedData></encryption>"
            .getBytes(StandardCharsets.UTF_8));
    assertNull(
        EpubCoverExtractor.find(
            fixture(
                "",
                "<item id='art' href='art.png' properties='cover-image' media-type='image/png'/>",
                "",
                files)));
  }
}
