package com.example.bilingualreader.ui.lookup;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.RelativeSizeSpan;
import java.util.regex.*;

/** Keep selectable/copyable text, while making actual source POS sections easy to scan. */
final class DictionaryText {
  private DictionaryText(){}
  static CharSequence format(String text){
    SpannableString result=new SpannableString(text);
    Matcher headings=Pattern.compile("(?m)^【[^\\n]+】$|^(?:n|v|vt|vi|adj|adv|prep|pron|conj|interj|num|art|aux)\\.").matcher(text);
    while(headings.find()){
      result.setSpan(new StyleSpan(Typeface.BOLD),headings.start(),headings.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      result.setSpan(new RelativeSizeSpan(1.1f),headings.start(),headings.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    return result;
  }
  static String chineseOnly(String text){
    // Older servers return English definition lines; keep Chinese meanings and examples.
    return text.replaceAll("(?m)^英文释义[：:].*(?:\\r?\\n|$)", "");
  }
}
