package com.example.bilingualreader.ui.settings;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.*;
import androidx.appcompat.widget.SwitchCompat;
import com.example.bilingualreader.R;
import com.example.bilingualreader.contract.model.ReaderTheme;
import com.example.bilingualreader.ui.BaseFragment;
import com.example.bilingualreader.viewmodel.SettingsViewModel;
import java.util.Locale;

public final class SettingsFragment extends BaseFragment {
  public SettingsFragment() {
    super(R.layout.fragment_settings);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    super.onViewCreated(view, state);
    SettingsViewModel vm = models().get(SettingsViewModel.class);
    commands(vm);
    SeekBar font = view.findViewById(R.id.font), spacing = view.findViewById(R.id.spacing);
    SwitchCompat night = view.findViewById(R.id.night);
    Runnable preview =
        () -> {
          ((TextView) view.findViewById(R.id.font_label))
              .setText("字号 · " + (14 + font.getProgress()) + " sp");
          ((TextView) view.findViewById(R.id.spacing_label))
              .setText(
                  String.format(Locale.CHINA, "行距 · %.1f 倍", 1.2f + spacing.getProgress() / 10f));
          TextView sample = view.findViewById(R.id.preview);
          sample.setTextSize(14 + font.getProgress());
          sample.setLineSpacing(0, 1.2f + spacing.getProgress() / 10f);
        };
    SeekBar.OnSeekBarChangeListener listener =
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar s, int p, boolean user) {
            preview.run();
          }

          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {}
        };
    font.setOnSeekBarChangeListener(listener);
    spacing.setOnSeekBarChangeListener(listener);
    vm.settings.observe(
        getViewLifecycleOwner(),
        s -> {
          font.setProgress(s.getFontSizeSp() - 14);
          spacing.setProgress(Math.round((s.getLineSpacingMultiplier() - 1.2f) * 10));
          night.setChecked(s.getTheme() == ReaderTheme.NIGHT);
          preview.run();
        });
    view.findViewById(R.id.save)
        .setOnClickListener(
            v ->
                vm.save(
                    14 + font.getProgress(),
                    1.2f + spacing.getProgress() / 10f,
                    night.isChecked()));
  }

  @Override
  public void onResume() {
    super.onResume();
    host().page("阅读设置", true);
  }
}
