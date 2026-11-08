package com.example.bilingualreader.ui;

import android.os.Bundle;
import android.view.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.*;
import androidx.fragment.app.Fragment;
import com.example.bilingualreader.*;
import com.example.bilingualreader.contract.model.ReaderTheme;
import com.example.bilingualreader.ui.bookshelf.BookshelfFragment;
import com.example.bilingualreader.ui.reader.ReaderFragment;
import com.example.bilingualreader.ui.settings.SettingsFragment;
import com.example.bilingualreader.ui.toc.TocFragment;
import com.google.android.material.appbar.MaterialToolbar;

public final class MainActivity extends AppCompatActivity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_main);
    View root = findViewById(R.id.root);
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          androidx.core.graphics.Insets bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
    ViewCompat.requestApplyInsets(root);
    MaterialToolbar bar = findViewById(R.id.toolbar);
    setSupportActionBar(bar);
    bar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
    if (state == null)
      getSupportFragmentManager()
          .beginTransaction()
          .replace(R.id.fragment_container, new BookshelfFragment())
          .commit();
    ((ReaderApplication) getApplication())
        .getContainer()
        .settings
        .observeSettings()
        .observe(
            this,
            s -> {
              int mode =
                  s.getTheme() == ReaderTheme.NIGHT
                      ? AppCompatDelegate.MODE_NIGHT_YES
                      : AppCompatDelegate.MODE_NIGHT_NO;
              if (AppCompatDelegate.getDefaultNightMode() != mode)
                AppCompatDelegate.setDefaultNightMode(mode);
              WindowInsetsControllerCompat controller =
                  WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
              controller.setAppearanceLightStatusBars(s.getTheme() != ReaderTheme.NIGHT);
              controller.setAppearanceLightNavigationBars(s.getTheme() != ReaderTheme.NIGHT);
            });
  }

  public void page(String title, boolean back) {
    if (getSupportActionBar() != null) {
      getSupportActionBar().setTitle(title);
      getSupportActionBar().setDisplayHomeAsUpEnabled(back);
    }
  }

  private void push(Fragment fragment) {
    if (getSupportFragmentManager().isStateSaved()) return;
    Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    if (current != null && current.getClass() == fragment.getClass()) return;
    getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, fragment)
        .addToBackStack(null)
        .commit();
  }

  public void openToc(String book, String title) {
    push(TocFragment.create(book, title));
  }

  public void openReader(String book, String title, String chapter, String paragraph) {
    push(ReaderFragment.create(book, title, chapter, paragraph));
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    menu.add(0, 1, 0, "阅读设置").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
    if (((ReaderApplication) getApplication()).getContainer().translation
        instanceof com.example.bilingualreader.di.TranslationDiagnostics)
      menu.add(0, 2, 1, "模拟翻译失败（联调）");
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == 2) {
      com.example.bilingualreader.contract.translation.TranslationCoordinator translator =
          ((ReaderApplication) getApplication()).getContainer().translation;
      if (translator instanceof com.example.bilingualreader.di.TranslationDiagnostics) {
        ((com.example.bilingualreader.di.TranslationDiagnostics) translator).failNextRequest();
        android.widget.Toast.makeText(this, "下一次未完成段落将模拟失败，可验证重试", android.widget.Toast.LENGTH_LONG)
            .show();
      }
      return true;
    }
    if (item.getItemId() == 1) {
      push(new SettingsFragment());
      return true;
    }
    return super.onOptionsItemSelected(item);
  }
}
