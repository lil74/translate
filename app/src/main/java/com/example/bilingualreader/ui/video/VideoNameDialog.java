package com.example.bilingualreader.ui.video;

import android.app.Dialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Optional display name only; the original document and URI remain unchanged. */
public final class VideoNameDialog extends DialogFragment {
  public static final String RESULT = "video-import-name";
  private EditText name;

  public static void show(Fragment owner, String uri, String filename) {
    if (owner.getChildFragmentManager().isStateSaved()) return;
    VideoNameDialog dialog = new VideoNameDialog();
    Bundle args = new Bundle();
    args.putString("uri", uri);
    args.putString("filename", filename);
    dialog.setArguments(args);
    dialog.show(owner.getChildFragmentManager(), "video-name");
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(Bundle state) {
    LinearLayout fields = new LinearLayout(requireContext());
    fields.setOrientation(LinearLayout.VERTICAL);
    int padding = Math.round(24 * getResources().getDisplayMetrics().density);
    fields.setPadding(padding, 0, padding, 0);
    name = new EditText(requireContext());
    name.setSingleLine(true);
    name.setHint("留空使用文件名");
    name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(80)});
    if (state != null) name.setText(state.getString("draft", ""));
    fields.addView(name);
    return new MaterialAlertDialogBuilder(requireContext())
        .setTitle("为视频命名（可选）")
        .setMessage(requireArguments().getString("filename"))
        .setView(fields)
        .setNegativeButton("使用文件名", (d, w) -> finish(""))
        .setPositiveButton("导入", (d, w) -> finish(name.getText().toString().trim()))
        .create();
  }

  private void finish(String value) {
    Bundle result = new Bundle(requireArguments());
    result.putString("customName", value);
    getParentFragmentManager().setFragmentResult(RESULT, result);
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle state) {
    super.onSaveInstanceState(state);
    if (name != null) state.putString("draft", name.getText().toString());
  }
}
