package com.example.bilingualreader.translation;

import android.content.Context;
import android.content.SharedPreferences;
import com.example.bilingualreader.contract.model.*;

/** No supplier credentials are stored in the APK or these preferences. */
public final class TranslationConfigStore {
  public static final String MOCK = "mock", ONLINE = "online";
  private final SharedPreferences prefs;
  private final boolean debug;
  private volatile Runnable onChanged;
  private final SharedPreferences.OnSharedPreferenceChangeListener listener = (preferences, key) -> {
    Runnable callback = onChanged;
    if (callback != null && ("mode".equals(key) || "endpoint".equals(key))) callback.run();
  };
  public TranslationConfigStore(Context context, boolean debug) {
    prefs = context.getSharedPreferences("translation_settings", Context.MODE_PRIVATE);
    this.debug = debug;
    prefs.registerOnSharedPreferenceChangeListener(listener);
  }
  public void setOnChanged(Runnable callback) { onChanged = callback; }
  public boolean allowsMock() { return debug; }
  public String mode() { return prefs.getString("mode", debug ? MOCK : ONLINE); }
  public String endpoint() { return prefs.getString("endpoint", ""); }
  public void save(String mode, String endpoint) throws AppException {
    validate(mode, endpoint);
    prefs.edit().putString("mode", mode).putString("endpoint", endpoint.trim()).apply();
  }
  public void validate(String mode, String endpoint) throws AppException {
    if (MOCK.equals(mode) && debug) return;
    if (!ONLINE.equals(mode) || endpoint.trim().isEmpty()) throw configuration();
    try {
      java.net.URL url = new java.net.URL(endpoint.trim());
      if (!debug && !"https".equals(url.getProtocol())) throw configuration();
      new LibreTranslateService(endpoint.trim());
    } catch (java.net.MalformedURLException | IllegalArgumentException invalid) { throw configuration(); }
  }
  public boolean needsConsent() {
    return needsConsent(mode(), endpoint());
  }
  public boolean needsConsent(String mode, String endpoint) {
    return ONLINE.equals(mode) && !endpoint.equals(prefs.getString("consentedEndpoint", ""));
  }
  public void consent(String endpoint) { prefs.edit().putString("consentedEndpoint", endpoint).apply(); }
  public static AppException configuration() {
    return new AppException(ErrorCode.AUTH_FAILED, "请先在菜单“翻译服务”中配置团队服务的 /translate 地址", false);
  }
}
