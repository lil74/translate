package com.example.bilingualreader.viewmodel;

import android.content.Context;
import android.net.Uri;
import android.os.*;
import androidx.lifecycle.*;
import com.example.bilingualreader.contract.model.AppException;
import com.example.bilingualreader.di.AppContainer;
import com.example.bilingualreader.video.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

/** Retained session; explicit translation consent, cancellation, and cue-level checkpoints. */
public final class VideoViewModel extends ViewModel {
  public final MutableLiveData<Long> changes=new MutableLiveData<>(0L);
  private final Context context;
  private final AppContainer graph;
  private final VideoProjectStore store;
  private final Handler main=new Handler(Looper.getMainLooper());
  // Shared file queue orders the departing screen's final checkpoint before a new screen restores it.
  private static final ExecutorService io=VideoProjectStore.IO;
  private final ExecutorService network=Executors.newSingleThreadExecutor();
  private VideoProject project=VideoProject.empty();
  private volatile long generation;
  private Future<?> pending;
  private boolean cleared;
  private boolean initialized;
  private long revision;
  public boolean busy=true, translating;
  public boolean recognizingSpeech;
  public boolean fullScreen,resumePlayback;
  public float playbackSpeed=1f;
  public String message="正在恢复上次的视频项目…";
  private final Runnable saveLater=this::persist;

  public VideoViewModel(Context context, AppContainer graph) {
    this(context,graph,null);
  }
  public VideoViewModel(Context context, AppContainer graph,String requestedUri) {
    this.context=context.getApplicationContext(); this.graph=graph; store=new VideoProjectStore(this.context);
    io.execute(() -> {
      try { VideoProject restored=requestedUri==null?store.load():store.load(requestedUri); main.post(() -> {
        if (cleared || generation!=0) return;
        project=restored; initialized=true; busy=false; message="选择视频及对应的 SRT/VTT 字幕文件"; publish();
      }); } catch (IOException error) { main.post(() -> { if (!cleared && generation==0) {
        initialized=true; busy=false; message=error.getMessage(); publish();
      }}); }
    });
  }
  public VideoProject project() { return project; }
  private void publish() { changes.setValue(++revision); }
  private void changed() { publish(); main.removeCallbacks(saveLater); main.postDelayed(saveLater,500); }
  private void persist() {
    VideoProject snapshot=project;
    io.execute(() -> { try { store.save(snapshot); }
      catch (IOException error) { main.post(() -> { if(!cleared) {
        message="本地保存失败，请检查存储空间；当前内容仍在页面中"; publish();
      }}); }
    });
  }
  public void checkpoint(long position) {
    if(!initialized)return;
    project=project.position(position); main.removeCallbacks(saveLater); persist();
  }
  public void video(Uri uri, String name) {
    cancel();
    VideoProject previous=project;
    if(previous.videoUri!=null)io.execute(()->{try{store.save(previous);}catch(IOException ignored){}});
    boolean replacing=project.videoUri!=null && !project.videoUri.equals(uri.toString());
    project=new VideoProject(uri.toString(),name,replacing?null:project.subtitleName,project.direction,
        replacing?null:project.translationKey,replacing?false:project.demo,0,
        replacing?Collections.emptyList():project.cues);
    message=replacing?"已切换视频，可点击识别画面英文":"视频已选择，可点击识别画面英文"; changed();
  }
  public void importSubtitles(Uri uri, String name) {
    cancel(); busy=true; final long token=generation; message="正在解析字幕…"; publish();
    io.execute(() -> {
      try {
        byte[] raw;
        try (InputStream input=context.getContentResolver().openInputStream(uri)) {
          if (input==null) throw new IOException();
          ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int count;
          while ((count=input.read(buffer))!=-1) {
            if (out.size()+count>2*1024*1024) throw new IOException("字幕文件超过 2 MB，请分段导入");
            out.write(buffer,0,count);
          }
          raw=out.toByteArray();
        }
        Charset charset=StandardCharsets.UTF_8;
        if(raw.length>=2 && ((raw[0]==(byte)0xFF && raw[1]==(byte)0xFE) || (raw[0]==(byte)0xFE && raw[1]==(byte)0xFF))) charset=StandardCharsets.UTF_16;
        String text=charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        List<SubtitleSegment> parsed=new SubtitleCodec().acquire(text);
        main.post(() -> { if(cleared || token!=generation)return;
          project=new VideoProject(project.videoUri,project.videoName,name,project.direction,null,false,project.positionMs,parsed);
          busy=false; message="已导入 "+parsed.size()+" 条字幕，点击字幕可定位，长按可修正原文"; changed();
        });
      } catch (Exception error) { main.post(() -> { if(cleared || token!=generation)return;
        busy=false; message=error instanceof AppException ? ((AppException)error).getUserMessage()
            : error instanceof CharacterCodingException ? "编码无法识别，请将字幕转换为 UTF-8 或 UTF-16"
            : "字幕导入失败，请检查文件大小、格式及读取权限"; publish();
      }); }
    });
  }
  public void extractSoftSubtitles() {
    if(busy || project.videoUri==null)return;
    cancel();long token=generation;Uri video=Uri.parse(project.videoUri);
    busy=true;message="正在本机读取视频软字幕轨道…";publish();
    pending=network.submit(()->{
      try {
        List<SubtitleSegment> cues=SoftSubtitleExtractor.extract(context,video);
        main.post(()->{if(cleared || token!=generation)return;
          project=new VideoProject(project.videoUri,project.videoName,"视频软字幕",project.direction,null,false,project.positionMs,cues);
          busy=false;message="已提取 "+cues.size()+" 条软字幕，可预览修正后翻译";changed();
        });
      }catch(Exception error){main.post(()->{if(cleared || token!=generation)return;
        busy=false;message=error.getMessage()==null?"软字幕提取失败，请尝试导入 SRT/VTT 字幕":error.getMessage();publish();
      });}
    });
  }
  public void recognize(float top,float bottom) { recognize(top,bottom,null); }
  public Prepared prepareEnglishOcr() throws AppException {
    Prepared current=prepare();
    return new Prepared(current.binding,current.endpoint,"en","zh-CN",generation,false);
  }
  public void recognize(float top,float bottom,Prepared translation) {
    if(translation!=null && translation.generation!=generation)return;
    if(busy || project.videoUri==null)return;
    cancel();long token=generation;String uri=project.videoUri;
    busy=true;message="正在本机识别英文画面…";publish();
    pending=network.submit(()->{
      try {
        List<SubtitleSegment> cues=new VideoOcr().scan(context,Uri.parse(uri),top,bottom,percent->main.post(()->{
          if(!cleared && token==generation){message="正在识别英文画面："+percent+"%";publish();}
        }));
        main.post(()->{if(cleared || token!=generation)return;
          project=new VideoProject(project.videoUri,project.videoName,"画面英文 OCR",0,null,false,project.positionMs,cues);
          busy=false;message="识别完成，共 "+cues.size()+" 条。请预览并修正，再点击翻译。时间轴精度约 0.5 秒。";changed();
          if(translation!=null)translate(new Prepared(translation.binding,translation.endpoint,"en","zh-CN",generation,false));
        });
      }catch(Exception error){main.post(()->{if(cleared || token!=generation)return;
        busy=false;message=error instanceof IllegalArgumentException?error.getMessage():"画面识别失败，请检查视频格式或重新尝试";publish();
      });}
    });
  }
  public void direction(int direction) {
    if (project.direction==direction)return;
    cancel(); List<SubtitleSegment> cues=new ArrayList<>();
    for(SubtitleSegment cue:project.cues)cues.add(cue.clearTranslation());
    project=new VideoProject(project.videoUri,project.videoName,project.subtitleName,direction,null,false,project.positionMs,cues);
    message="方向已改变，旧译文已清除"; changed();
  }
  public static final class SpeechPrepared {
    public final Prepared translation;
    private final SpeechClient client;
    SpeechPrepared(Prepared translation,SpeechClient client){this.translation=translation;this.client=client;}
  }
  public SpeechPrepared prepareSpeech() throws Exception {
    return prepareSpeech("en");
  }
  public SpeechPrepared prepareSpeech(String language) throws Exception {
    Prepared translation=prepareEnglishOcr();
    if(translation.binding.demo || graph.translationConfig==null)
      throw new IllegalArgumentException("语音识别需要真实云端服务，请先关闭模拟模式并配置访问令牌");
    return new SpeechPrepared(translation,new SpeechClient(context,translation.endpoint,
        graph.translationConfig.tokenForEndpoint(translation.endpoint),language));
  }
  public void recognizeSpeech(SpeechPrepared prepared) {
    recognizeSpeech(prepared,true);
  }
  public void recognizeChineseSpeech(SpeechPrepared prepared) {
    recognizeSpeech(prepared,false);
  }
  private void recognizeSpeech(SpeechPrepared prepared,boolean translate) {
    if(cleared || busy || project.videoUri==null || prepared.translation.generation!=generation)return;
    cancel();long token=generation;Uri video=Uri.parse(project.videoUri);
    busy=true;recognizingSpeech=true;message="正在准备视频音频…";publish();
    pending=network.submit(()->{
      try{
        List<SubtitleSegment> cues=prepared.client.recognize(video,status->main.post(()->{
          if(!cleared && token==generation){message=status;publish();}
        }));
        main.post(()->{if(cleared || token!=generation)return;
          project=new VideoProject(project.videoUri,project.videoName,translate?"英文语音识别 ASR":"中文语音识别 ASR",0,null,false,project.positionMs,cues);
          busy=false;recognizingSpeech=false;
          message=translate?"语音识别完成，正在进入翻译…":"中文字幕识别完成，共 "+cues.size()+" 条";changed();
          if(translate){Prepared binding=prepared.translation;translate(new Prepared(binding.binding,binding.endpoint,"en","zh-CN",generation,false));}
        });
      }catch(Exception error){main.post(()->{if(cleared || token!=generation)return;
        busy=false;recognizingSpeech=false;
        message=error instanceof IllegalArgumentException || error instanceof IOException?error.getMessage():"语音识别失败，请检查音轨、网络及云端配置后继续";
        publish();
      });}
    });
  }
  public void edit(String id, String text) {
    if(text==null || text.trim().isEmpty() || text.codePointCount(0,text.length())>40000) {
      message="字幕原文应为 1–40000 字符"; publish(); return;
    }
    cancel(); List<SubtitleSegment> cues=new ArrayList<>();
    for(SubtitleSegment cue:project.cues)cues.add(cue.id.equals(id)?cue.edit(text):cue);
    project=project.withCues(cues,project.translationKey,project.demo);
    message="原文已更新，对应旧译文已失效"; changed();
  }
  public static final class Prepared {
    public final AppContainer.SelectionBinding binding;
    public final String endpoint, key, source, target;
    public final boolean restart;
    private final long generation;
    private Prepared(AppContainer.SelectionBinding binding,String endpoint,String source,String target,long generation,boolean restart) {
      this.binding=binding; this.endpoint=endpoint; this.source=source; this.target=target; this.generation=generation;
      this.restart=restart;
      key="subtitle:v1:"+(binding.demo?"mock":"online:"+endpoint)+":"+source+":"+target;
    }
  }
  public Prepared prepare() throws AppException {
    return prepare(false);
  }
  public Prepared prepare(boolean restart) throws AppException {
    if(graph.translationConfig!=null)graph.translationConfig.validate(graph.translationConfig.mode(),graph.translationConfig.endpoint());
    return new Prepared(graph.selectionBinding(),graph.translationConfig==null?"当前在线服务":graph.translationConfig.endpoint(),
        project.sourceLanguage(),project.targetLanguage(),generation,restart);
  }
  public void translate(Prepared prepared) {
    if(cleared || busy || prepared.generation!=generation || project.cues.isEmpty())return;
    cancel(); final long token=generation;
    if(prepared.restart || !prepared.key.equals(project.translationKey)) {
      List<SubtitleSegment> fresh=new ArrayList<>(); for(SubtitleSegment cue:project.cues)fresh.add(cue.clearTranslation());
      project=project.withCues(fresh,prepared.key,prepared.binding.demo);
    }
    List<SubtitleSegment> snapshot=project.cues;
    busy=true; translating=true; message="正在翻译字幕…"; changed();
    pending=network.submit(() -> {
      try {
        new SubtitleTranslator().translate(snapshot,prepared.binding.service,prepared.source,prepared.target,cue -> {
          main.post(() -> { if(cleared || token!=generation)return;
            List<SubtitleSegment> next=new ArrayList<>(project.cues);
            for(int i=0;i<next.size();i++)if(next.get(i).id.equals(cue.id) && next.get(i).sourceHash().equals(cue.sourceHash()))next.set(i,cue);
            project=project.withCues(next,prepared.key,prepared.binding.demo);
            message="已翻译 "+project.translatedCount()+" / "+next.size()+" 条"; changed();
          });
        });
        main.post(() -> { if(cleared || token!=generation)return;
          busy=false; translating=false; message="字幕翻译完成，可播放双语字幕或导出"; changed();
        });
      } catch(Exception error) { main.post(() -> { if(cleared || token!=generation)return;
        busy=false; translating=false;
        message=error instanceof AppException?((AppException)error).getUserMessage():"字幕翻译失败，已完成内容保留，可点击继续翻译"; changed();
      }); }
    });
  }
  public void cancel() {
    if(!initialized)return;
    generation++; if(pending!=null){pending.cancel(true);pending=null;}
    if(busy){busy=false;translating=false;message=recognizingSpeech
        ?"已暂停等待，云端语音任务可能仍在运行；点击语音识别可继续获取结果":"处理已取消，已完成字幕保留";recognizingSpeech=false;publish();}
  }
  public void export(Uri destination, boolean vtt, boolean bilingual) {
    final String text;
    try { text=SubtitleCodec.export(project.cues,vtt,bilingual); }
    catch(AppException error){message=error.getUserMessage();publish();return;}
    final boolean demo=project.demo;
    io.execute(() -> {
      String next;
      try(OutputStream output=context.getContentResolver().openOutputStream(destination,"wt")) {
        if(output==null)throw new IOException();
        // Export snapshots are immutable even if the user edits the project while writing.
        output.write((demo?markDemo(text,vtt):text).getBytes(StandardCharsets.UTF_8));
        next="字幕已导出"+(demo?"（含模拟译文标记）":"");
      } catch(IOException|SecurityException error){next="导出失败，请检查目标文件权限和存储空间";}
      String delivered=next; main.post(() -> {if(!cleared){message=delivered;publish();}});
    });
  }
  private static String markDemo(String text, boolean vtt) {
    return text.replaceAll("(?m)(^\\d{2,}:\\d{2}:\\d{2}[,.]\\d{3} --> [^\\n]+\\n)","$1【模拟译文，非真实翻译】\n");
  }
  @Override protected void onCleared() {
    cleared=true; generation++; if(pending!=null)pending.cancel(true);
    main.removeCallbacks(saveLater); if(initialized)persist(); network.shutdownNow();
  }
}
