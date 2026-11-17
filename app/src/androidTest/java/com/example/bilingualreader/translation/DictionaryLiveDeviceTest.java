package com.example.bilingualreader.translation;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.bilingualreader.contract.translation.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Opt-in, public word and self-authored context only. No credentials included in the APK. */
@RunWith(AndroidJUnit4.class)
public final class DictionaryLiveDeviceTest {
  @Test public void actualDictionaryAndContextualSelectionRemainSeparate()throws Exception {
    Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    File privateConfig=new File(context.getCacheDir(),"dictionary-test-config.json");
    Assume.assumeTrue("Live dictionary test needs explicit private runtime configuration",privateConfig.isFile());
    try{
      JsonObject config=JsonParser.parseString(new String(java.nio.file.Files.readAllBytes(privateConfig.toPath()),StandardCharsets.UTF_8)).getAsJsonObject();
      LibreTranslateService service=new LibreTranslateService(config.get("endpoint").getAsString(),config.get("token").getAsString());
      TranslationOutput dictionary=service.translate(LookupQuery.request("bank",LookupQuery.AUTO)).getItems().get(0);
      assertEquals("dictionaryapi+qwen",dictionary.getProvider());
      assertTrue(dictionary.getTranslatedText().contains("【名词 n.】"));
      assertTrue(dictionary.getTranslatedText().contains("【动词 v.】"));
      assertTrue(dictionary.getTranslatedText().contains("Free Dictionary API"));
      String passage="She sat on the river bank.";int start=passage.indexOf("bank");
      TranslationBatchRequest selected=new TranslationBatchRequest("selection:live-word","live-context","en","zh-CN",Collections.singletonList(
          new TranslationInput("selection:live-word",TranslationValidation.sha256("bank"),"bank",SelectionContext.fromParagraph(passage,start,start+4))));
      TranslationOutput meaning=service.translate(selected).getItems().get(0);
      assertEquals("qwen-mt",meaning.getProvider());assertTrue(meaning.getTranslatedText().contains("岸"));
      assertFalse(meaning.getTranslatedText().contains("【名词"));
    }finally{privateConfig.delete();}
  }
}
