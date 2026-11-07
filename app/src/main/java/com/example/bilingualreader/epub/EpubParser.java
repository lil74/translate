package com.example.bilingualreader.epub;

import com.example.bilingualreader.contract.model.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import org.xml.sax.InputSource;
import org.w3c.dom.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** B's bounded EPUB parsing core. The caller owns the source file. */
public final class EpubParser {
  public static final class Result {
    public final String title, author, language;
    public final List<Chapter> chapters;
    public final List<Paragraph> paragraphs;
    public final List<TocEntry> toc;
    public final List<String> warnings;
    Result(String title, String author, String language, List<Chapter> chapters,
        List<Paragraph> paragraphs, List<TocEntry> toc, List<String> warnings) {
      this.title=title; this.author=author; this.language=language;
      this.chapters=chapters; this.paragraphs=paragraphs; this.toc=toc; this.warnings=warnings;
    }
  }

  public Result parse(File file, String bookId, String fallbackTitle) throws AppException {
    if (file.length() > 50L*1024*1024) throw error(ErrorCode.EPUB_TOO_LARGE, "EPUB 文件超过 50 MiB");
    try (ZipFile zip = new ZipFile(file)) {
      if (zip.size() > 10000) throw error(ErrorCode.EPUB_TOO_LARGE, "EPUB 条目过多");
      long[] total = {0};
      Document container = xml(read(zip, "META-INF/container.xml", total));
      NodeList roots = container.getElementsByTagNameNS("*", "rootfile");
      if (roots.getLength()==0) throw error(ErrorCode.INVALID_EPUB, "缺少 OPF 路径");
      String opfPath = path("", ((org.w3c.dom.Element)roots.item(0)).getAttribute("full-path"));
      Document opf = xml(read(zip, opfPath, total));
      String base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/')+1) : "";
      String title = first(opf,"title",fallbackTitle), author=first(opf,"creator","未知作者");
      String language=first(opf,"language","und");
      Map<String,String> manifest=new HashMap<>();
      NodeList items=opf.getElementsByTagNameNS("*","item");
      for(int i=0;i<items.getLength();i++) {
        org.w3c.dom.Element item=(org.w3c.dom.Element)items.item(i);
        manifest.put(item.getAttribute("id"),path(base,item.getAttribute("href")));
      }
      List<Chapter> chapters=new ArrayList<>(); List<Paragraph> paragraphs=new ArrayList<>();
      List<TocEntry> toc=new ArrayList<>(); List<String> warnings=new ArrayList<>();
      Map<String,String> anchors=new HashMap<>();
      NodeList spine=opf.getElementsByTagNameNS("*","itemref");
      for(int i=0;i<spine.getLength();i++) {
        org.w3c.dom.Element ref=(org.w3c.dom.Element)spine.item(i);
        if("no".equalsIgnoreCase(ref.getAttribute("linear"))) continue;
        String resource=manifest.get(ref.getAttribute("idref"));
        if(resource==null) {warnings.add("spine 资源缺失: "+ref.getAttribute("idref"));continue;}
        org.jsoup.nodes.Document html=Jsoup.parse(new String(read(zip,resource,total),StandardCharsets.UTF_8));
        Element body=html.body(); if(body==null) continue;
        String chapterId=sha(bookId+"|"+i+"|"+resource);
        int count=0;
        for(Element block:body.select("h1,h2,h3,h4,h5,h6,p,li,blockquote")) {
          if(block.parents().stream().anyMatch(p -> p.tagName().equals("nav") || p.tagName().equals("script") || p.tagName().equals("style"))) continue;
          if(block.children().stream().anyMatch(c -> c.is("h1,h2,h3,h4,h5,h6,p,li,blockquote"))) continue;
          String value=block.wholeText().replace('\u00a0',' ').replaceAll("[\\t ]+"," ").trim();
          if(value.isEmpty()) continue;
          String pid=sha(chapterId+"|"+count);
          if(!block.id().isEmpty()) anchors.put(resource+"#"+block.id(),pid);
          for(Element parent:block.parents()) if(!parent.id().isEmpty()) anchors.putIfAbsent(resource+"#"+parent.id(),pid);
          paragraphs.add(new Paragraph(pid,chapterId,count,block.tagName().matches("h[1-6]")?BlockType.HEADING:BlockType.TEXT,value,sha(value)));
          count++;
        }
        if(count==0) {warnings.add("无正文资源: "+resource);continue;}
        String heading=paragraphs.get(paragraphs.size()-count).getBlockType()==BlockType.HEADING ? paragraphs.get(paragraphs.size()-count).getOriginalText() : "第 "+(chapters.size()+1)+" 节";
        chapters.add(new Chapter(chapterId,bookId,heading,chapters.size(),resource,count));
      }
      if(chapters.isEmpty()) throw error(ErrorCode.UNSUPPORTED_CONTENT,"EPUB 没有可阅读正文");
      Map<String,Chapter> byPath=new HashMap<>();for(Chapter c:chapters)byPath.put(c.getResourcePath(),c);
      String navPath=null;for(int i=0;i<items.getLength();i++){org.w3c.dom.Element item=(org.w3c.dom.Element)items.item(i);if(Arrays.asList(item.getAttribute("properties").split(" ")).contains("nav")){navPath=manifest.get(item.getAttribute("id"));break;}}
      if(navPath!=null){
        org.jsoup.nodes.Document nav=Jsoup.parse(new String(read(zip,navPath,total),StandardCharsets.UTF_8));
        String navBase=navPath.contains("/")?navPath.substring(0,navPath.lastIndexOf('/')+1):"";
        for(Element list:nav.select("nav ol"))if(list.parent().tagName().equals("nav")){appendNav(list,null,bookId,navBase,byPath,anchors,toc,warnings);break;}
      }
      if(toc.isEmpty())for(Chapter chapter:chapters) toc.add(new TocEntry(sha(bookId+"|toc|"+chapter.getOrderIndex()),bookId,null,chapter.getOrderIndex(),chapter.getTitle(),chapter.getChapterId(),null));
      return new Result(title,author,language,chapters,paragraphs,toc,warnings);
    } catch(AppException e) {throw e;} catch(Exception e) {throw new AppException(ErrorCode.INVALID_EPUB,"EPUB 解析失败",false,e);}
  }
  private static void appendNav(Element list,String parentId,String bookId,String base,Map<String,Chapter> chapters,Map<String,String> anchors,List<TocEntry> toc,List<String> warnings)throws AppException{
    for(Element li:list.children())if(li.tagName().equals("li")){
      Element a=directChild(li,"a");String currentParent=parentId;
      if(a!=null){String href=a.attr("href");String resource=path(base,href);Chapter chapter=chapters.get(resource);
        if(chapter!=null){String id=sha(bookId+"|toc|"+toc.size());String fragment=href.contains("#")?href.substring(href.indexOf('#')+1):null;
          toc.add(new TocEntry(id,bookId,parentId,toc.size(),a.text(),chapter.getChapterId(),fragment==null?null:anchors.get(resource+"#"+fragment)));currentParent=id;
        }else warnings.add("目录资源未导入: "+href);
      }
      Element nested=directChild(li,"ol");if(nested!=null)appendNav(nested,currentParent,bookId,base,chapters,anchors,toc,warnings);
    }
  }
  private static Element directChild(Element parent,String tag){
    for(Element child:parent.children())if(child.tagName().equals(tag))return child;
    return null;
  }

  public static String sha(String text) {
    try {byte[] digest=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); StringBuilder b=new StringBuilder();for(byte x:digest)b.append(String.format(Locale.ROOT,"%02x",x&255));return b.toString();}
    catch(Exception e){throw new IllegalStateException(e);}
  }
  private static byte[] read(ZipFile zip,String name,long[] total) throws Exception {
    ZipEntry entry=zip.getEntry(name); if(entry==null) throw error(ErrorCode.INVALID_EPUB,"缺少 EPUB 资源: "+name);
    try(InputStream in=zip.getInputStream(entry);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
      byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1) {total[0]+=n;if(total[0]>200L*1024*1024||out.size()+n>10*1024*1024)throw error(ErrorCode.EPUB_TOO_LARGE,"EPUB 解压内容超过限制");out.write(buf,0,n);}return out.toByteArray();
    }
  }
  private static Document xml(byte[] bytes) throws Exception {
    // Android's Harmony parser does not support disallow-doctype-decl. Reject DTDs
    // before parsing, then disable external entities wherever the parser supports it.
    String header = new String(bytes, StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
    if (header.contains("<!DOCTYPE") || header.contains("<!ENTITY"))
      throw error(ErrorCode.INVALID_EPUB, "EPUB XML 不允许 DTD 或实体声明");
    DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
    try { f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true); } catch (javax.xml.parsers.ParserConfigurationException ignored) {}
    try { f.setFeature("http://xml.org/sax/features/external-general-entities",false); } catch (javax.xml.parsers.ParserConfigurationException ignored) {}
    try { f.setFeature("http://xml.org/sax/features/external-parameter-entities",false); } catch (javax.xml.parsers.ParserConfigurationException ignored) {}
    try { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false); } catch (javax.xml.parsers.ParserConfigurationException ignored) {}
    try { f.setXIncludeAware(false); } catch (UnsupportedOperationException ignored) {}
    try { f.setExpandEntityReferences(false); } catch (UnsupportedOperationException ignored) {}
    javax.xml.parsers.DocumentBuilder builder=f.newDocumentBuilder();
    builder.setEntityResolver((publicId,systemId)->new InputSource(new StringReader("")));
    return builder.parse(new ByteArrayInputStream(bytes));
  }
  private static String first(Document doc,String local,String fallback) {NodeList n=doc.getElementsByTagNameNS("*",local);return n.getLength()>0&&!n.item(0).getTextContent().trim().isEmpty()?n.item(0).getTextContent().trim():fallback;}
  private static String path(String base,String relative) throws AppException {
    Deque<String> parts=new ArrayDeque<>();for(String part:(base+relative.split("[#?]",2)[0]).replace('\\','/').split("/")) {if(part.isEmpty()||part.equals("."))continue;if(part.equals("..")){if(parts.isEmpty())throw error(ErrorCode.INVALID_EPUB,"EPUB 路径越界");parts.removeLast();}else parts.addLast(part);}return String.join("/",parts);
  }
  private static AppException error(ErrorCode code,String message){return new AppException(code,message,false);}
}
