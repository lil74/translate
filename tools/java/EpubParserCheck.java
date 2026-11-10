import com.example.bilingualreader.contract.model.Chapter;
import com.example.bilingualreader.contract.model.AppException;
import com.example.bilingualreader.contract.model.ErrorCode;
import com.example.bilingualreader.contract.model.Paragraph;
import com.example.bilingualreader.contract.model.TocEntry;
import com.example.bilingualreader.epub.EpubParser;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

/** Local, read-only parser smoke check. Prints counts, never book text. */
public final class EpubParserCheck {
  public static void main(String[] args) throws Exception {
    for (String name : args) {
      File file = new File(name);
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
      StringBuilder id = new StringBuilder();
      for (byte b : hash) id.append(String.format("%02x", b & 255));
      EpubParser.Result result;
      try { result = new EpubParser().parse(file, id.toString(), file.getName()); }
      catch (AppException e) {
        if (file.getName().equals("synthetic-no-text.epub") && e.getCode() == ErrorCode.UNSUPPORTED_CONTENT
            || file.getName().equals("broken.epub") && e.getCode() == ErrorCode.INVALID_EPUB
            || file.getName().equals("synthetic-drm.epub") && e.getCode() == ErrorCode.DRM_UNSUPPORTED) {
          System.out.println(file.getName() + ": expected " + e.getCode());
          continue;
        }
        throw e;
      }
      System.out.println(file.getName() + ": chapters=" + result.chapters.size()
          + " paragraphs=" + result.paragraphs.size() + " toc=" + result.toc.size()
          + " cover=" + (result.cover != null) + " warnings=" + result.warnings.size());
      for (String warning : result.warnings) System.out.println("  warning: " + warning);
      for (Chapter chapter : result.chapters) {
        int count = 0;
        for (Paragraph paragraph : result.paragraphs)
          if (paragraph.getChapterId().equals(chapter.getChapterId())) {
            if (!paragraph.getParagraphId().equals(EpubParser.sha(chapter.getChapterId()+"|"+paragraph.getOrderIndex()))
                || !paragraph.getSourceHash().equals(EpubParser.sha(paragraph.getOriginalText())))
              throw new AssertionError("Unstable paragraph ID or source hash");
            count++;
          }
        if (count != chapter.getParagraphCount()) throw new AssertionError("Paragraph count mismatch");
      }
      for (TocEntry entry : result.toc) {
        boolean found = false;
        for (Chapter chapter : result.chapters)
          if (chapter.getChapterId().equals(entry.getChapterId())) found = true;
        if (!found) throw new AssertionError("TOC points outside reading spine");
      }
      if (file.getName().equals("two-chapters.epub")) {
        if (result.chapters.size()!=2 || result.paragraphs.size()!=8 || result.toc.size()!=3
            || !result.chapters.get(0).getResourcePath().endsWith("z-first.xhtml")
            || !result.chapters.get(0).getChapterId().equals(EpubParser.sha(id+"|0|OEBPS/z-first.xhtml"))
            || !result.toc.get(1).getParagraphId().equals(result.paragraphs.get(2).getParagraphId()))
          throw new AssertionError("EPUB 3 spine or anchor mismatch");
      }
      if (file.getName().equals("synthetic-epub2.epub")) {
        if (result.chapters.size()!=2 || result.paragraphs.size()!=5 || result.toc.size()!=3
            || result.cover==null || !result.author.equals("First Author、Second Author")
            || !result.toc.get(1).getParagraphId().equals(result.paragraphs.get(2).getParagraphId()))
          throw new AssertionError("EPUB 2 NCX, cover, metadata or anchor mismatch");
      }
      if (file.getName().equals("synthetic-no-toc.epub") && result.toc.size()!=2)
        throw new AssertionError("Missing TOC did not fall back to chapters");
      if (file.getName().equals("synthetic-div-only.epub") && result.paragraphs.size()!=4)
        throw new AssertionError("Visible div fallback lost blocks");
      if (file.getName().equals("synthetic-font-obfuscation.epub") && result.chapters.size()!=2)
        throw new AssertionError("Obfuscated font was mistaken for book DRM");
    }
  }
}
