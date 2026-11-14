package com.example.bilingualreader.translation;

import com.example.bilingualreader.contract.translation.*;
import com.example.bilingualreader.contract.model.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SelectionContextTest {
  private TranslationBatchRequest request(String selected,TranslationInput.Context context) {
    return new TranslationBatchRequest("selection:test","request","en","zh-CN",Collections.singletonList(
        new TranslationInput("selection:test",TranslationValidation.sha256(selected),selected,context)));
  }
  @Test public void repeatedWordKeepsTheSelectedOccurrence()throws Exception {
    String paragraph="The bank approved the loan. He sat on the river bank.";
    int start=paragraph.lastIndexOf("bank");
    TranslationInput.Context context=SelectionContext.fromParagraph(paragraph,start,start+4);
    assertEquals(paragraph,context.getText());assertEquals(start,context.getStart());
    TranslationValidation.validateRequest(request("bank",context));
  }
  @Test public void emojiContextUsesCodePointsInsteadOfUtf16Offsets()throws Exception {
    String paragraph="😀 He waited at the bank.";int start=paragraph.indexOf("bank");
    TranslationInput.Context context=SelectionContext.fromParagraph(paragraph,start,start+4);
    assertEquals(start-1,context.getStart());
    TranslationValidation.validateRequest(request("bank",context));
  }
  @Test public void veryLongParagraphIsBoundedAroundSelectionWithoutLosingIt()throws Exception {
    String emoji=String.join("",Collections.nCopies(6000,"😀"));
    String paragraph=emoji+" bank "+emoji;int start=paragraph.indexOf("bank");
    TranslationInput.Context context=SelectionContext.fromParagraph(paragraph,start,start+4);
    assertEquals(4000,context.getText().codePointCount(0,context.getText().length()));
    assertTrue(context.getText().startsWith("😀"));
    TranslationValidation.validateRequest(request("bank",context));
  }
  @Test public void originalPunctuationAndWhitespaceRemainUnchanged()throws Exception {
    String paragraph="He said, \"  give me a hand!  \"";int start=paragraph.indexOf("  give");
    String selected="  give me a hand!  ";
    TranslationInput.Context context=SelectionContext.fromParagraph(paragraph,start,start+selected.length());
    TranslationValidation.validateRequest(request(selected,context));assertEquals(paragraph,context.getText());
  }
  @Test public void mismatchedContextIsRejectedBeforeNetwork()throws Exception {
    try {TranslationValidation.validateRequest(request("bank",new TranslationInput.Context("river bank",0,5)));fail();}
    catch(AppException expected){assertEquals(ErrorCode.INVALID_RESPONSE,expected.getCode());}
  }
  @Test public void cannotSplitSurrogatePair() {
    try {SelectionContext.fromParagraph("😀 bank",1,3);fail();}
    catch(IllegalArgumentException expected){assertNotNull(expected);}
  }
}
