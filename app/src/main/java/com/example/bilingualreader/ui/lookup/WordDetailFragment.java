package com.example.bilingualreader.ui.lookup;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.*;
import com.example.bilingualreader.R;
import com.example.bilingualreader.ui.BaseFragment;
import com.example.bilingualreader.ui.reader.EnglishPronunciation;
import com.example.bilingualreader.viewmodel.*;
import java.util.concurrent.Future;

/** A separate destination; the search page and its input remain on the back stack. */
public final class WordDetailFragment extends BaseFragment {
  private Future<?> pending;
  private EnglishPronunciation voice;
  private LookupViewModel vm;
  public WordDetailFragment(){super(R.layout.fragment_word_detail);}
  public static WordDetailFragment create(String word){
    WordDetailFragment page=new WordDetailFragment();Bundle args=new Bundle();args.putString("word",word);page.setArguments(args);return page;
  }
  @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state){
    super.onViewCreated(view,state);
    String word=requireArguments().getString("word","");
    ((TextView)view.findViewById(R.id.word_title)).setText(word);
    vm=models().get(LookupViewModel.class);
    voice=new EnglishPronunciation(requireContext(),status->{
      if(getView()!=view)return;
      ((TextView)view.findViewById(R.id.word_speak)).setText(status==EnglishPronunciation.State.SPEAKING?"停止":"朗读");
      if(status==EnglishPronunciation.State.UNAVAILABLE || status==EnglishPronunciation.State.ERROR)
        Toast.makeText(requireContext(),"请在手机文字转语音设置中安装英语语音",Toast.LENGTH_LONG).show();
    });
    view.findViewById(R.id.word_speak).setOnClickListener(v->voice.play(word));
    view.findViewById(R.id.word_retry).setOnClickListener(v->online(word));
    vm.result.observe(getViewLifecycleOwner(),result->{
      if(result==null)return;
      boolean loading=result.status==UiState.Status.LOADING;
      view.findViewById(R.id.word_loading).setVisibility(loading?View.VISIBLE:View.GONE);
      view.findViewById(R.id.word_retry).setVisibility(result.status==UiState.Status.ERROR?View.VISIBLE:View.GONE);
      ((TextView)view.findViewById(R.id.word_meaning)).setText(loading?"正在查询…":result.status==UiState.Status.SUCCESS?DictionaryText.format(DictionaryText.chineseOnly(result.data)):result.message);
      ((TextView)view.findViewById(R.id.word_source)).setText(vm.submitted!=null && vm.submitted.binding.demo?"模拟译文，非真实翻译":"在线词典：Free Dictionary API · Qwen 中文释义");
    });
    if(vm.result.getValue()==null)pending=LocalDictionary.find(requireContext(),word,entry->{
      if(getView()!=view)return;
      if(entry==null){online(word);return;}
      view.findViewById(R.id.word_loading).setVisibility(View.GONE);
      ((TextView)view.findViewById(R.id.word_phonetic)).setText(entry.phonetic.isEmpty()?"":"/"+entry.phonetic+"/");
      ((TextView)view.findViewById(R.id.word_tags)).setText(tags(entry.tags));
      ((TextView)view.findViewById(R.id.word_meaning)).setText(DictionaryText.format(entry.meaning));
      ((TextView)view.findViewById(R.id.word_source)).setText("词条来源：ECDICT（MIT）\nhttps://github.com/skywind3000/ECDICT\n仅展示词库收录的词性与词义。");
    },error->{if(getView()==view)online(word);});
  }
  private static String tags(String tags){
    return tags.replace("cet4","四级").replace("cet6","六级").replace("gk","高考").replace("zk","中考").replace("ky","考研").replace("ielts","雅思").replace("toefl","托福").replace("gre","GRE").replace(" ","   ");
  }
  private void online(String word){
    try{vm.input=word;vm.direction=1;vm.translate(vm.prepare());}
    catch(com.example.bilingualreader.contract.model.AppException error){vm.result.setValue(UiState.error(error.getUserMessage()));}
  }
  @Override public void onResume(){super.onResume();requireActivity().findViewById(R.id.toolbar).setVisibility(View.VISIBLE);host().page("单词详情",true);}
  @Override public void onStop(){if(voice!=null)voice.stop();if(!requireActivity().isChangingConfigurations())vm.stop();super.onStop();}
  @Override public void onDestroyView(){if(pending!=null)pending.cancel(true);if(voice!=null)voice.close();voice=null;super.onDestroyView();}
}
