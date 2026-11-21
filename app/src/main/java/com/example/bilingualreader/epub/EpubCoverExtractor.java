package com.example.bilingualreader.epub;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.*;
import java.util.zip.ZipFile;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * Extracts only local cover resources; never reparses or replaces chapters and reading progress.
 */
public final class EpubCoverExtractor {
  public static final class Cover {
    public final byte[] bytes;
    public final String extension;

    private Cover(byte[] bytes, String extension) {
      this.bytes = bytes;
      this.extension = extension;
    }
  }

  public static Cover find(File file) throws Exception {
    if (file.length() > 50L * 1024 * 1024) return null;
    try (ZipFile zip = new ZipFile(file)) {
      if (zip.size() > 10000) return null;
      long[] total = {0};
      Document container = EpubParser.xml(EpubParser.read(zip, "META-INF/container.xml", total));
      NodeList roots = container.getElementsByTagNameNS("*", "rootfile");
      if (roots.getLength() == 0) return null;
      String opfPath =
          EpubParser.path("", ((org.w3c.dom.Element) roots.item(0)).getAttribute("full-path"));
      return find(
          zip,
          EpubParser.xml(EpubParser.read(zip, opfPath, total)),
          directory(opfPath),
          EpubParser.encryptedResources(zip, total),
          total);
    }
  }

  static Cover find(ZipFile zip, Document opf, String base, Set<String> encrypted, long[] total) {
    Map<String, String> manifest = new LinkedHashMap<>();
    LinkedHashSet<String> candidates = new LinkedHashSet<>();
    NodeList items = opf.getElementsByTagNameNS("*", "item");
    for (int i = 0; i < items.getLength(); i++) {
      org.w3c.dom.Element item = (org.w3c.dom.Element) items.item(i);
      String resource = localPath(base, item.getAttribute("href"));
      if (resource == null) continue;
      manifest.put(item.getAttribute("id"), resource);
      if (Arrays.asList(item.getAttribute("properties").split("\\s+")).contains("cover-image"))
        candidates.add(resource);
    }
    NodeList metas = opf.getElementsByTagNameNS("*", "meta");
    for (int i = 0; i < metas.getLength(); i++) {
      org.w3c.dom.Element meta = (org.w3c.dom.Element) metas.item(i);
      if ("cover".equalsIgnoreCase(meta.getAttribute("name"))) {
        String value = meta.getAttribute("content");
        String resource =
            manifest.containsKey(value) ? manifest.get(value) : localPath(base, value);
        if (resource != null) candidates.add(resource);
      }
    }
    NodeList references = opf.getElementsByTagNameNS("*", "reference");
    for (int i = 0; i < references.getLength(); i++) {
      org.w3c.dom.Element ref = (org.w3c.dom.Element) references.item(i);
      if (Arrays.asList(ref.getAttribute("type").toLowerCase(Locale.ROOT).split("\\s+"))
          .contains("cover")) {
        String resource = localPath(base, ref.getAttribute("href"));
        if (resource != null) candidates.add(resource);
      }
    }
    // Some older exporters omit metadata and use an explicitly named cover page/image instead.
    for (Map.Entry<String, String> item : manifest.entrySet()) {
      String name = item.getValue().substring(item.getValue().lastIndexOf('/') + 1);
      if (item.getKey().toLowerCase(Locale.ROOT).matches("(?:front[-_]?cover|cover)(?:[-_]?image)?")
          || name.toLowerCase(Locale.ROOT)
              .matches(
                  "(?:front[-_]?cover|cover)(?:[-_]?image)?\\.(?:jpe?g|png|webp|xhtml|html|svg)"))
        candidates.add(item.getValue());
    }
    Set<String> visited = new HashSet<>();
    int count = 0;
    for (String resource : candidates) {
      if (++count > 16 || total[0] > 190L * 1024 * 1024) break;
      Cover cover = resource(zip, resource, encrypted, total, visited, 0);
      if (cover != null) return cover;
    }
    return null;
  }

  private static Cover resource(
      ZipFile zip,
      String path,
      Set<String> encrypted,
      long[] total,
      Set<String> visited,
      int depth) {
    if (depth > 3 || visited.size() >= 32 || !visited.add(path) || encrypted.contains(path))
      return null;
    try {
      if (zip.getEntry(path) == null || zip.getEntry(path).getSize() > 10L * 1024 * 1024)
        return null;
      byte[] bytes = EpubParser.read(zip, path, total);
      String extension = extension(bytes);
      if (extension != null) return new Cover(bytes, extension);
      if (bytes.length > 1024 * 1024) return null;
      String name = path.toLowerCase(Locale.ROOT);
      if (!name.endsWith(".xhtml")
          && !name.endsWith(".html")
          && !name.endsWith(".htm")
          && !name.endsWith(".svg")) return null;
      org.jsoup.nodes.Document page = Jsoup.parse(new ByteArrayInputStream(bytes), null, "");
      for (Element image : page.select("img, image")) {
        String href =
            image.hasAttr("src")
                ? image.attr("src")
                : image.hasAttr("href") ? image.attr("href") : image.attr("xlink:href");
        String target = localPath(directory(path), href);
        if (target == null) continue;
        Cover cover = resource(zip, target, encrypted, total, visited, depth + 1);
        if (cover != null) return cover;
      }
    } catch (Exception ignored) {
      // A broken optional cover must not stop a readable book from being imported.
    }
    return null;
  }

  private static String extension(byte[] b) {
    if (b.length > 8
        && (b[0] & 255) == 137
        && b[1] == 80
        && b[2] == 78
        && b[3] == 71
        && b[4] == 13
        && b[5] == 10
        && b[6] == 26
        && b[7] == 10) return "png";
    if (b.length > 3 && (b[0] & 255) == 255 && (b[1] & 255) == 216 && (b[2] & 255) == 255)
      return "jpg";
    if (b.length > 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P') return "webp";
    return null;
  }

  private static String directory(String path) {
    return path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
  }

  private static String localPath(String base, String href) {
    if (href == null
        || href.trim().isEmpty()
        || href.startsWith("/")
        || href.startsWith("\\")
        || href.matches("(?s)^[A-Za-z][A-Za-z0-9+.-]*:.*")) return null;
    try {
      return EpubParser.path(base, href);
    } catch (Exception ignored) {
      return null;
    }
  }
}
