package com.example.bilingualreader.ui.settings;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.*;
import com.example.bilingualreader.*;
import com.example.bilingualreader.ui.BaseFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class AppSettingsFragment extends BaseFragment {
  public AppSettingsFragment() {
    super(R.layout.fragment_app_settings);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    super.onViewCreated(view, state);
    String palette = AppAppearance.palette(requireContext());
    RadioGroup choices = view.findViewById(R.id.palette_choices);
    choices.check(
        "blue".equals(palette)
            ? R.id.palette_blue
            : "brown".equals(palette) ? R.id.palette_brown : R.id.palette_green);
    ((TextView) view.findViewById(R.id.palette_preview_name))
        .setText(AppAppearance.label(requireContext()));
    choices.setOnCheckedChangeListener(
        (group, id) -> {
          String next =
              id == R.id.palette_blue ? "blue" : id == R.id.palette_brown ? "brown" : "green";
          if (!next.equals(AppAppearance.palette(requireContext()))) {
            AppAppearance.save(requireContext(), next);
            requireActivity().recreate();
          }
        });
    view.findViewById(R.id.app_translation_service)
        .setVisibility(graph().translationConfig == null ? View.GONE : View.VISIBLE);
    view.findViewById(R.id.app_translation_service)
        .setOnClickListener(
            v -> TranslationSettingsDialog.show(requireContext(), graph().translationConfig));
    view.findViewById(R.id.app_about)
        .setOnClickListener(
            v ->
                new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("轻阅")
                    .setMessage("版本 " + BuildConfig.VERSION_NAME + "\n小说与视频中的外语学习")
                    .setPositiveButton("确定", null)
                    .show());
  }

  @Override
  public void onResume() {
    super.onResume();
    host().page("应用设置", true);
  }
}
