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
import com.example.bilingualreader.ui.toc.TocFragment;
import com.google.android.material.appbar.MaterialToolbar;

public final class MainActivity extends AppCompatActivity {
  @Override
  public void onCreate(Bundle state) {
    setTheme(com.example.bilingualreader.ui.settings.AppAppearance.theme(this));
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
    androidx.activity.OnBackPressedCallback rootBack =
        new androidx.activity.OnBackPressedCallback(false) {
          @Override
          public void handleOnBackPressed() {
            selectRoot(false);
          }
        };
    getOnBackPressedDispatcher().addCallback(this, rootBack);
    findViewById(R.id.nav_home).setOnClickListener(v -> selectRoot(false));
    findViewById(R.id.nav_profile).setOnClickListener(v -> selectRoot(true));
    getSupportFragmentManager()
        .registerFragmentLifecycleCallbacks(
            new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
              @Override
              public void onFragmentResumed(
                  @androidx.annotation.NonNull androidx.fragment.app.FragmentManager fm,
                  @androidx.annotation.NonNull Fragment fragment) {
                boolean home = fragment instanceof com.example.bilingualreader.ui.home.HomeFragment;
                boolean profile =
                    fragment instanceof com.example.bilingualreader.ui.profile.ProfileFragment;
                findViewById(R.id.root_navigation)
                    .setVisibility(home || profile ? View.VISIBLE : View.GONE);
                findViewById(R.id.nav_home).setSelected(home);
                findViewById(R.id.nav_profile).setSelected(profile);
                rootBack.setEnabled(profile);
                if (home || profile) findViewById(R.id.toolbar).setVisibility(View.VISIBLE);
              }
            },
            false);
    if (state == null)
      getSupportFragmentManager()
          .beginTransaction()
          .replace(R.id.fragment_container, new com.example.bilingualreader.ui.home.HomeFragment())
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

  private void selectRoot(boolean profile) {
    if (getSupportFragmentManager().isStateSaved()) return;
    Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    if (profile && current instanceof com.example.bilingualreader.ui.profile.ProfileFragment
        || !profile && current instanceof com.example.bilingualreader.ui.home.HomeFragment) return;
    getSupportFragmentManager()
        .popBackStackImmediate(
            null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
    getSupportFragmentManager()
        .beginTransaction()
        .replace(
            R.id.fragment_container,
            profile
                ? new com.example.bilingualreader.ui.profile.ProfileFragment()
                : new com.example.bilingualreader.ui.home.HomeFragment())
        .commit();
  }

  public void openAppSettings() {
    push(new com.example.bilingualreader.ui.settings.AppSettingsFragment());
  }

  public void openBookshelf(boolean favorites) {
    BookshelfFragment fragment = new BookshelfFragment();
    Bundle args = new Bundle();
    args.putBoolean("favorites", favorites);
    fragment.setArguments(args);
    push(fragment);
  }

  public void openBookmark(
      String book,
      String title,
      com.example.bilingualreader.ui.reader.ReaderLocalStore.Bookmark mark) {
    ReaderFragment fragment = ReaderFragment.create(book, title, mark.chapter, mark.paragraph);
    fragment.requireArguments().putInt("bookmarkOffset", mark.offset);
    push(fragment);
  }

  public void openLookup() {
    push(new com.example.bilingualreader.ui.lookup.LookupFragment());
  }

  public void openWord(String word) {
    push(com.example.bilingualreader.ui.lookup.WordDetailFragment.create(word));
  }

  public void openVideo() {
    push(new com.example.bilingualreader.ui.video.VideoFragment());
  }

  public void openToc(String book, String title, String readerResultKey) {
    TocFragment fragment = TocFragment.create(book, title);
    fragment.requireArguments().putString("readerResultKey", readerResultKey);
    push(fragment);
  }

  public void openToc(String book, String title) {
    push(TocFragment.create(book, title));
  }

  public void openReader(String book, String title, String chapter, String paragraph) {
    push(ReaderFragment.create(book, title, chapter, paragraph));
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    menu.add(0, 1, 0, "应用设置").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
    com.example.bilingualreader.di.AppContainer graph =
        ((ReaderApplication) getApplication()).getContainer();
    if (graph.translationConfig != null)
      menu.add(0, 2, 1, "翻译服务").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
    if (graph.translation instanceof com.example.bilingualreader.di.TranslationDiagnostics)
      menu.add(0, 3, 2, "模拟翻译失败（联调）");
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == 3) {
      com.example.bilingualreader.contract.translation.TranslationCoordinator translator =
          ((ReaderApplication) getApplication()).getContainer().translation;
      if (translator instanceof com.example.bilingualreader.di.TranslationDiagnostics) {
        ((com.example.bilingualreader.di.TranslationDiagnostics) translator).failNextRequest();
        android.widget.Toast.makeText(this, "下一次未完成段落将模拟失败，可验证重试", android.widget.Toast.LENGTH_LONG)
            .show();
      }
      return true;
    }
    if (item.getItemId() == 2) {
      if (((ReaderApplication) getApplication()).getContainer().translationConfig == null)
        return true;
      com.example.bilingualreader.ui.settings.TranslationSettingsDialog.show(
          this, ((ReaderApplication) getApplication()).getContainer().translationConfig);
      return true;
    }
    if (item.getItemId() == 1) {
      openAppSettings();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }
}
