package com.example.bilingualreader.ui.settings;

import android.content.Context;
import android.widget.*;
import com.example.bilingualreader.contract.model.AppException;
import com.example.bilingualreader.translation.TranslationConfigStore;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class TranslationSettingsDialog {
  private TranslationSettingsDialog() {}
  public static void show(Context context, TranslationConfigStore config) {
    LinearLayout fields = new LinearLayout(context);
    fields.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (24 * context.getResources().getDisplayMetrics().density);
    fields.setPadding(padding, padding / 2, padding, 0);
    TextView explanation = new TextView(context);
    explanation.setText("填写团队翻译服务的完整 /translate 地址及访问令牌。百炼 API Key 由服务器保存，请勿填写在这里。切换服务后可在阅读页选择“重新翻译”替换旧译文。");
    fields.addView(explanation);
    Switch mock = new Switch(context);
    mock.setText("模拟验证（不会产生真实译文）");
    mock.setChecked(TranslationConfigStore.MOCK.equals(config.mode()));
    if (config.allowsMock()) fields.addView(mock);
    EditText endpoint = new EditText(context);
    endpoint.setSingleLine(true);
    endpoint.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    endpoint.setHint("https://团队域名/translate");
    endpoint.setText(config.endpoint());
    endpoint.setEnabled(!config.allowsMock() || !mock.isChecked());
    mock.setOnCheckedChangeListener((button, checked) -> endpoint.setEnabled(!checked));
    fields.addView(endpoint);
    EditText token=new EditText(context);
    token.setSingleLine(true);
    token.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    token.setHint("服务访问令牌（留空保留此地址已保存令牌）");
    token.setSaveEnabled(false);
    token.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
    fields.addView(token);
    CheckBox removeToken=new CheckBox(context);
    removeToken.setText("删除此地址已保存的访问令牌");fields.addView(removeToken);
    androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(context)
        .setTitle("翻译服务").setView(fields).setNegativeButton("取消", null)
        .setPositiveButton("保存", null).create();
    dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(view -> {
      try {
        config.save(config.allowsMock() && mock.isChecked() ? TranslationConfigStore.MOCK
            : TranslationConfigStore.ONLINE, endpoint.getText().toString().trim(),
            removeToken.isChecked() ? "" : token.getText().length()==0 ? null : token.getText().toString());
        Toast.makeText(context, "翻译服务已保存，下次新任务生效", Toast.LENGTH_SHORT).show();
        dialog.dismiss();
      } catch (AppException error) { endpoint.setError(error.getUserMessage()); }
    }));
    dialog.show();
  }
}
