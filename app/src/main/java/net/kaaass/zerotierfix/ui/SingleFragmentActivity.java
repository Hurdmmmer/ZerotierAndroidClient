package net.kaaass.zerotierfix.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.IdRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.appbar.MaterialToolbar;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.util.ThemeModeHelper;

/**
 * 单片段 Activity
 */
public abstract class SingleFragmentActivity extends AppCompatActivity {
    private static final String TAG = "SingleFragmentActivity";

    public abstract Fragment createFragment();

    /**
     * 是否显示统一顶部栏。首页由 Fragment 自带顶部栏，因此禁用。
     */
    protected boolean useCommonTopAppBar() {
        return true;
    }

    /**
     * fragment 容器 ID，便于子类复用。
     */
    @IdRes
    protected int getFragmentContainerId() {
        return R.id.fragmentContainer;
    }

    /**
     * 是否启用 Activity 级系统栏 Insets 处理。
     * 对于 Fragment 内部已自行处理 Insets 的页面，应返回 false 以避免双重间距。
     */
    protected boolean useActivityWindowInsets() {
        return true;
    }

    @Override
    public void onCreate(Bundle bundle) {
        ThemeModeHelper.applyThemeMode(this);
        super.onCreate(bundle);
        setContentView(R.layout.activity_fragment);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setupCommonTopBar();
        applyWindowInsets();
        FragmentManager supportFragmentManager = getSupportFragmentManager();
        Fragment findFragmentById = supportFragmentManager.findFragmentById(getFragmentContainerId());
        if (findFragmentById == null) {
            Fragment createFragment = createFragment();
            setArgs(createFragment);
            supportFragmentManager.beginTransaction().add(getFragmentContainerId(), createFragment).commit();
            return;
        }
        setArgs(findFragmentById);
    }

    private void setupCommonTopBar() {
        View appBar = findViewById(R.id.common_app_bar);
        MaterialToolbar toolbar = findViewById(R.id.common_top_app_bar);
        if (appBar == null || toolbar == null) {
            return;
        }
        if (!useCommonTopAppBar()) {
            appBar.setVisibility(View.GONE);
            return;
        }
        appBar.setVisibility(View.VISIBLE);
        setSupportActionBar(toolbar);
        toolbar.setTitle(getTitle());
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24);
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void applyWindowInsets() {
        if (!useActivityWindowInsets()) {
            return;
        }
        View appBar = findViewById(R.id.common_app_bar);
        View fragmentContainer = findViewById(getFragmentContainerId());
        if (fragmentContainer == null) {
            return;
        }
        final int appBarPaddingTop = appBar == null ? 0 : appBar.getPaddingTop();
        final int containerPaddingBottom = fragmentContainer.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(fragmentContainer, (v, insets) -> {
            Insets statusInsets = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.displayCutout()
            );
            Insets navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            if (appBar != null && appBar.getVisibility() == View.VISIBLE) {
                appBar.setPadding(
                        appBar.getPaddingLeft(),
                        appBarPaddingTop + statusInsets.top,
                        appBar.getPaddingRight(),
                        appBar.getPaddingBottom()
                );
            }
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), containerPaddingBottom + navInsets.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(fragmentContainer);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setArgs(Fragment fragment) {
        Bundle extras;
        Intent intent = getIntent();
        if (intent != null && (extras = intent.getExtras()) != null && !fragment.isAdded()) {
            try {
                fragment.setArguments(extras);
            } catch (IllegalArgumentException unused) {
                Log.e(TAG, "Exception setting arguments on fragment");
            }
        }
    }
}
