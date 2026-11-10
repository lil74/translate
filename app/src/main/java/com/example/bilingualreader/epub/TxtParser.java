package com.example.bilingualreader.epub;

import com.example.bilingualreader.contract.model.*;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.*;

/** Plain-text book importer. A nonempty line is one readable block. */
public final class TxtParser {
  public static final String DEFAULT_CHAPTER_REGEX =
      "(?iu)^(?:第[\\d一二三四五六七八九十百千万零〇两]+[章节卷回部篇].*"
      + "|(?:chapter|part|book)\\s+(?:\\d+|[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten)(?:\\b.*)?"
      + "|prologue|epilogue)\\s*$";

  private static final class Section {
    final String title;
    final List<String> text=new ArrayList<>();
    final boolean hasHeading;
    Section(String title,boolean hasHeading){this.title=title;this.hasHeading=hasHeading;}
  }

  public EpubParser.Result parse(File file,String bookId,String fallbackTitle,String chapterRegex)
      throws AppException {
    Pattern headings;
    try{
      String expression=chapterRegex==null||chapterRegex.trim().isEmpty()
          ?DEFAULT_CHAPTER_REGEX:chapterRegex.trim();
      if(expression.length()>300)throw new PatternSyntaxException("表达式超过 300 字符",expression,300);
      headings=Pattern.compile(expression);
    }catch(PatternSyntaxException e){
      throw new AppException(ErrorCode.UNSUPPORTED_CONTENT,"目录正则表达式无效："+e.getDescription(),false,e);
    }
    String content;
    try{content=decode(Files.readAllBytes(file.toPath()));}
    catch(Exception e){throw new AppException(ErrorCode.IMPORT_IO_ERROR,"TXT 文件读取失败",true,e);}
    if(content.indexOf('\0')>=0)throw new AppException(ErrorCode.UNSUPPORTED_CONTENT,"TXT 文件包含二进制内容",false);
    List<Section> sections=new ArrayList<>();Section current=null;
    for(String raw:content.split("\\r\\n|\\n|\\r",-1)){
      String line=raw.replace('\u00a0',' ').replaceAll("[\\t ]+"," ").trim();
      if(line.isEmpty())continue;
      Matcher match=line.length()<=160?headings.matcher(line):null;
      if(match!=null&&match.find()){
        String title=line;
        if(match.groupCount()>0&&match.group(1)!=null&&!match.group(1).trim().isEmpty())
          title=match.group(1).trim();
        current=new Section(title,true);sections.add(current);
        current.text.add(line);
      }else{
        if(current==null){current=new Section("前言",false);sections.add(current);}
        current.text.add(line);
      }
    }
    if(sections.isEmpty())throw new AppException(ErrorCode.UNSUPPORTED_CONTENT,"TXT 文件没有可阅读文字",false);
    if(sections.size()==1&&!sections.get(0).hasHeading&&"前言".equals(sections.get(0).title)){
      Section only=sections.get(0);Section whole=new Section("全文",false);
      whole.text.addAll(only.text);sections.set(0,whole);
    }
    List<Chapter> chapters=new ArrayList<>();List<Paragraph> paragraphs=new ArrayList<>();
    List<TocEntry> toc=new ArrayList<>();List<String> warnings=new ArrayList<>();
    for(int i=0;i<sections.size();i++){
      Section section=sections.get(i);String resource="txt/"+i;
      String chapterId=EpubParser.sha(bookId+"|"+i+"|"+resource);
      int order=0;
      for(String line:section.text){
        BlockType type=section.hasHeading&&order==0?BlockType.HEADING:BlockType.TEXT;
        paragraphs.add(new Paragraph(EpubParser.sha(chapterId+"|"+order),chapterId,order,type,line,EpubParser.sha(line)));
        order++;
      }
      chapters.add(new Chapter(chapterId,bookId,section.title,i,resource,order));
      toc.add(new TocEntry(EpubParser.sha(bookId+"|toc|"+i),bookId,null,i,section.title,chapterId,null));
    }
    if(chapters.size()==1&&"全文".equals(chapters.get(0).getTitle()))warnings.add("未识别到章节标题，已按全文导入");
    return new EpubParser.Result(fallbackTitle,"未知作者","und",null,null,chapters,paragraphs,toc,warnings);
  }

  private static String decode(byte[] bytes)throws CharacterCodingException {
    if(bytes.length>=3&&(bytes[0]&255)==0xef&&(bytes[1]&255)==0xbb&&(bytes[2]&255)==0xbf)
      return new String(bytes,3,bytes.length-3,StandardCharsets.UTF_8);
    if(bytes.length>=2&&(bytes[0]&255)==0xff&&(bytes[1]&255)==0xfe)
      return new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16LE);
    if(bytes.length>=2&&(bytes[0]&255)==0xfe&&(bytes[1]&255)==0xff)
      return new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16BE);
    try{
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }catch(CharacterCodingException invalidUtf8){
      return Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
  }
}
