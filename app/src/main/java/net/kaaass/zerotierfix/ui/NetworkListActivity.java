package net.kaaass.zerotierfix.ui;

import android.os.Bundle;
import android.content.res.Configuration;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;

/**
 * 网络列表 fragment 的容器 activity
 */
public class NetworkListActivity extends SingleFragmentActivity {
    @Override
    protected boolean useCommonTopAppBar() {
        return false;
    }

    /**
     * 首页 Fragment 内已自行处理状态栏/导航栏 Insets，禁用 Activity 级处理避免双重偏移。
     */
    @Override
    protected boolean useActivityWindowInsets() {
        return false;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 根据日夜模式动态设置状态栏图标颜色
        WindowInsetsControllerCompat insetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (insetsController != null) {
            boolean isNight = (getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            insetsController.setAppearanceLightStatusBars(!isNight);
        }
    }

    @Override
    public Fragment createFragment() {
        return new NetworkListFragment();
    }
}
