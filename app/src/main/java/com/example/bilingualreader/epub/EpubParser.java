package com.example.bilingualreader.epub;

import com.example.bilingualreader.contract.model.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
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
    public final byte[] cover;
    public final String coverExtension;
    public final List<Chapter> chapters;
    public final List<Paragraph> paragraphs;
    public final List<TocEntry> toc;
    public final List<String> warnings;
    Result(String title, String author, String language, byte[] cover, String coverExtension,
        List<Chapter> chapters, List<Paragraph> paragraphs, List<TocEntry> toc, List<String> warnings) {
      this.title=title; this.author=author; this.language=language;
      this.cover=cover; this.coverExtension=coverExtension;
      this.chapters=chapters; this.paragraphs=paragraphs; this.toc=toc; this.warnings=warnings;
    }
  }

  public Result parse(File file, String bookId, String fallbackTitle) throws AppException {
    if (file.length() > 50L*1024*1024) throw error(ErrorCode.EPUB_TOO_LARGE, "EPUB 文件超过 50 MiB");
    try (ZipFile zip = new ZipFile(file)) {
      if (zip.size() > 10000) throw error(ErrorCode.EPUB_TOO_LARGE, "EPUB 条目过多");
      long[] total = {0};
      Set<String> encrypted=encryptedResources(zip,total);
      Document container = xml(read(zip, "META-INF/container.xml", total));
      NodeList roots = container.getElementsByTagNameNS("*", "rootfile");
      if (roots.getLength()==0) throw error(ErrorCode.INVALID_EPUB, "缺少 OPF 路径");
      String opfPath = path("", ((org.w3c.dom.Element)roots.item(0)).getAttribute("full-path"));
      Document opf = xml(read(zip, opfPath, total));
      String base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/')+1) : "";
      String title = first(opf,"title",fallbackTitle), author=authors(opf);
      String language=first(opf,"language","und");
      Map<String,String> manifest=new HashMap<>();
      Map<String,String> mediaTypes=new HashMap<>();
      String navPath=null,coverId=null,ncxId=null;
      NodeList items=opf.getElementsByTagNameNS("*","item");
      for(int i=0;i<items.getLength();i++) {
        org.w3c.dom.Element item=(org.w3c.dom.Element)items.item(i);
        manifest.put(item.getAttribute("id"),path(base,item.getAttribute("href")));
        mediaTypes.put(item.getAttribute("id"),item.getAttribute("media-type"));
        Set<String> properties=new HashSet<>(Arrays.asList(item.getAttribute("properties").split("\\s+")));
        if(properties.contains("nav"))navPath=manifest.get(item.getAttribute("id"));
        if(properties.contains("cover-image"))coverId=item.getAttribute("id");
      }
      NodeList metas=opf.getElementsByTagNameNS("*","meta");
      if(coverId==null)for(int i=0;i<metas.getLength();i++){
        org.w3c.dom.Element meta=(org.w3c.dom.Element)metas.item(i);
        if("cover".equalsIgnoreCase(meta.getAttribute("name"))){coverId=meta.getAttribute("content");break;}
      }
      NodeList spines=opf.getElementsByTagNameNS("*","spine");
      if(spines.getLength()>0)ncxId=((org.w3c.dom.Element)spines.item(0)).getAttribute("toc");
      byte[] cover=null;String coverExtension=null;
      if(coverId!=null&&manifest.containsKey(coverId)){
        String type=mediaTypes.get(coverId);
        if("image/jpeg".equals(type)||"image/png".equals(type)||"image/webp".equals(type)){
          cover=read(zip,manifest.get(coverId),total);
          coverExtension="image/jpeg".equals(type)?"jpg":"image/png".equals(type)?"png":"webp";
        }
      }
      List<Chapter> chapters=new ArrayList<>(); List<Paragraph> paragraphs=new ArrayList<>();
      List<TocEntry> toc=new ArrayList<>(); List<String> warnings=new ArrayList<>();
      Map<String,String> anchors=new HashMap<>();
      NodeList spine=opf.getElementsByTagNameNS("*","itemref");
      for(int i=0;i<spine.getLength();i++) {
        org.w3c.dom.Element ref=(org.w3c.dom.Element)spine.item(i);
        if("no".equalsIgnoreCase(ref.getAttribute("linear"))){warnings.add("已跳过非线性 spine 资源");continue;}
        String resource=manifest.get(ref.getAttribute("idref"));
        if(resource==null) {warnings.add("spine 资源缺失: "+ref.getAttribute("idref"));continue;}
        if(encrypted.contains(resource))throw error(ErrorCode.DRM_UNSUPPORTED,"正文受到加密保护，暂不支持导入");
        org.jsoup.nodes.Document html=Jsoup.parse(new ByteArrayInputStream(read(zip,resource,total)),null,"");
        Element body=html.body(); if(body==null) continue;
        body.select("script,style,nav,svg,form").remove();
        String chapterId=sha(bookId+"|"+i+"|"+resource);
        int count=0;
        for(Element block:body.select("h1,h2,h3,h4,h5,h6,p,li,blockquote")) {
          if(block.parents().stream().anyMatch(p -> p.tagName().equals("nav") || p.tagName().equals("script") || p.tagName().equals("style"))) continue;
          if(block.children().stream().anyMatch(c -> c.is("h1,h2,h3,h4,h5,h6,p,li,blockquote")
              || !c.select("h1,h2,h3,h4,h5,h6,p,li,blockquote").isEmpty())) continue;
          String value=block.wholeText().replace('\u00a0',' ').replaceAll("[\\t ]+"," ").trim();
          if(value.isEmpty()) continue;
          String pid=sha(chapterId+"|"+count);
          if(!block.id().isEmpty()) anchors.put(resource+"#"+block.id(),pid);
          for(Element parent:block.parents()) if(!parent.id().isEmpty()) anchors.putIfAbsent(resource+"#"+parent.id(),pid);
          for(Element descendant:block.select("[id]"))if(!descendant.id().isEmpty())anchors.putIfAbsent(resource+"#"+descendant.id(),pid);
          paragraphs.add(new Paragraph(pid,chapterId,count,block.tagName().matches("h[1-6]")?BlockType.HEADING:BlockType.TEXT,value,sha(value)));
          count++;
        }
        if(count==0){
          for(Element block:body.select("div,section,article")){
            if(block.children().stream().anyMatch(c -> c.is("div,section,article,h1,h2,h3,h4,h5,h6,p,li,blockquote")
                || !c.select("div,section,article,h1,h2,h3,h4,h5,h6,p,li,blockquote").isEmpty()))continue;
            String value=block.wholeText().replace('\u00a0',' ').replaceAll("[\\t ]+"," ").trim();
            if(value.isEmpty())continue;
            String pid=sha(chapterId+"|"+count);
            paragraphs.add(new Paragraph(pid,chapterId,count,BlockType.TEXT,value,sha(value)));
            if(!block.id().isEmpty())anchors.put(resource+"#"+block.id(),pid);
            count++;
          }
          if(count==0){
            String value=body.wholeText().replace('\u00a0',' ').replaceAll("[\\t ]+"," ").trim();
            if(!value.isEmpty()){
              String pid=sha(chapterId+"|0");
              paragraphs.add(new Paragraph(pid,chapterId,0,BlockType.TEXT,value,sha(value)));count=1;
            }
          }
        }
        if(count==0) {warnings.add("无正文资源: "+resource);continue;}
        for(Element anchor:body.select("[id]")){
          String key=resource+"#"+anchor.id();if(anchors.containsKey(key))continue;
          Element following=anchor.nextElementSibling();
          while(following!=null&&!following.is("h1,h2,h3,h4,h5,h6,p,li,blockquote"))following=following.nextElementSibling();
          if(following!=null&&anchors.containsKey(resource+"#"+following.id()))anchors.put(key,anchors.get(resource+"#"+following.id()));
        }
        String heading=paragraphs.get(paragraphs.size()-count).getBlockType()==BlockType.HEADING ? paragraphs.get(paragraphs.size()-count).getOriginalText() : "第 "+(chapters.size()+1)+" 节";
        chapters.add(new Chapter(chapterId,bookId,heading,chapters.size(),resource,count));
      }
      if(chapters.isEmpty()) throw error(ErrorCode.UNSUPPORTED_CONTENT,"EPUB 没有可阅读正文");
      Map<String,Chapter> byPath=new HashMap<>();for(Chapter c:chapters)byPath.put(c.getResourcePath(),c);
      if(navPath!=null){
        org.jsoup.nodes.Document nav=Jsoup.parse(new ByteArrayInputStream(read(zip,navPath,total)),null,"");
        String navBase=navPath.contains("/")?navPath.substring(0,navPath.lastIndexOf('/')+1):"";
        for(Element list:nav.select("nav ol"))if(list.parent().tagName().equals("nav")){appendNav(list,null,bookId,navBase,byPath,anchors,toc,warnings);break;}
      }
      if(toc.isEmpty()&&ncxId!=null&&manifest.containsKey(ncxId)){
        String ncxPath=manifest.get(ncxId);
        if(zip.getEntry(ncxPath)==null)warnings.add("目录资源缺失，已按章节生成目录");
        else{
          Document ncx=xml(read(zip,ncxPath,total));
          String ncxBase=ncxPath.contains("/")?ncxPath.substring(0,ncxPath.lastIndexOf('/')+1):"";
          NodeList maps=ncx.getElementsByTagNameNS("*","navMap");
          if(maps.getLength()>0)appendNcx(maps.item(0),null,bookId,ncxBase,byPath,anchors,toc,warnings);
        }
      }
      if(toc.isEmpty())for(Chapter chapter:chapters) toc.add(new TocEntry(sha(bookId+"|toc|"+chapter.getOrderIndex()),bookId,null,chapter.getOrderIndex(),chapter.getTitle(),chapter.getChapterId(),null));
      Map<String,String> tocTitles=new HashMap<>();
      for(TocEntry entry:toc)tocTitles.putIfAbsent(entry.getChapterId(),entry.getTitle());
      for(int i=0;i<chapters.size();i++){
        Chapter chapter=chapters.get(i);String preferred=tocTitles.get(chapter.getChapterId());
        if(preferred!=null&&!preferred.trim().isEmpty())chapters.set(i,new Chapter(chapter.getChapterId(),bookId,
            preferred,chapter.getOrderIndex(),chapter.getResourcePath(),chapter.getParagraphCount()));
      }
      return new Result(title,author,language,cover,coverExtension,chapters,paragraphs,toc,warnings);
    } catch(AppException e) {throw e;} catch(Exception e) {throw new AppException(ErrorCode.INVALID_EPUB,"EPUB 解析失败",false,e);}
  }
  private static void appendNav(Element list,String parentId,String bookId,String base,Map<String,Chapter> chapters,Map<String,String> anchors,List<TocEntry> toc,List<String> warnings)throws AppException{
    for(Element li:list.children())if(li.tagName().equals("li")){
      Element a=directChild(li,"a");String currentParent=parentId;
      if(a!=null){String href=a.attr("href");String resource=path(base,href);Chapter chapter=chapters.get(resource);
        if(chapter!=null){String id=sha(bookId+"|toc|"+toc.size());String fragment=fragment(href);
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
  private static Set<String> encryptedResources(ZipFile zip,long[] total)throws Exception{
    Set<String> paths=new HashSet<>();
    if(zip.getEntry("META-INF/encryption.xml")==null)return paths;
    Document encryption=xml(read(zip,"META-INF/encryption.xml",total));
    NodeList data=encryption.getElementsByTagNameNS("*","EncryptedData");
    for(int i=0;i<data.getLength();i++){
      org.w3c.dom.Element encrypted=(org.w3c.dom.Element)data.item(i);
      NodeList references=encrypted.getElementsByTagNameNS("*","CipherReference");
      for(int j=0;j<references.getLength();j++){
        String uri=((org.w3c.dom.Element)references.item(j)).getAttribute("URI");
        if(!uri.isEmpty())paths.add(path("",uri));
      }
    }
    return paths;
  }
  private static void appendNcx(Node parent,String parentId,String bookId,String base,
      Map<String,Chapter> chapters,Map<String,String> anchors,List<TocEntry> toc,
      List<String> warnings)throws AppException{
    for(Node point=parent.getFirstChild();point!=null;point=point.getNextSibling()){
      if(!"navPoint".equals(point.getLocalName()))continue;
      String title="";String href="";
      for(Node child=point.getFirstChild();child!=null;child=child.getNextSibling()){
        if("navLabel".equals(child.getLocalName()))title=child.getTextContent().trim();
        if("content".equals(child.getLocalName()))href=((org.w3c.dom.Element)child).getAttribute("src");
      }
      String nextParent=parentId;
      if(!href.isEmpty()){
        String resource=path(base,href);Chapter chapter=chapters.get(resource);
        if(chapter!=null){
          String id=sha(bookId+"|toc|"+toc.size());
          String fragment=fragment(href);
          toc.add(new TocEntry(id,bookId,parentId,toc.size(),title.isEmpty()?chapter.getTitle():title,
              chapter.getChapterId(),fragment==null?null:anchors.get(resource+"#"+fragment)));
          nextParent=id;
        }else warnings.add("目录资源未导入: "+href);
      }
      appendNcx(point,nextParent,bookId,base,chapters,anchors,toc,warnings);
    }
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
    if (header.contains("<!ENTITY"))
      throw error(ErrorCode.INVALID_EPUB, "EPUB XML 不允许实体声明");
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
  private static String authors(Document doc){
    NodeList creators=doc.getElementsByTagNameNS("*","creator");List<String> names=new ArrayList<>();
    for(int i=0;i<creators.getLength();i++){
      String name=creators.item(i).getTextContent().trim();if(!name.isEmpty())names.add(name);
    }
    return names.isEmpty()?"未知作者":String.join("、",names);
  }
  private static String path(String base,String relative) throws AppException {
    String decoded=decode(relative.split("[#?]",2)[0]);
    Deque<String> parts=new ArrayDeque<>();for(String part:(base+decoded).replace('\\','/').split("/")) {if(part.isEmpty()||part.equals("."))continue;if(part.equals("..")){if(parts.isEmpty())throw error(ErrorCode.INVALID_EPUB,"EPUB 路径越界");parts.removeLast();}else parts.addLast(part);}return String.join("/",parts);
  }
  private static String fragment(String href)throws AppException{
    int index=href.indexOf('#');return index<0?null:decode(href.substring(index+1));
  }
  private static String decode(String value)throws AppException{
    try{return URLDecoder.decode(value.replace("+","%2B"),"UTF-8");}
    catch(IllegalArgumentException|java.io.UnsupportedEncodingException e){throw new AppException(ErrorCode.INVALID_EPUB,"EPUB 路径编码无效",false,e);}
  }
  private static AppException error(ErrorCode code,String message){return new AppException(code,message,false);}
}
