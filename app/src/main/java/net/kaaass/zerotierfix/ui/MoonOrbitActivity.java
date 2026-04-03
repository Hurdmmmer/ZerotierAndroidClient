package net.kaaass.zerotierfix.ui;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import net.kaaass.zerotierfix.R;

/**
 * 入轨配置 fragment 的容器 activity
 *
 * @author kaaass
 */
public class MoonOrbitActivity extends SingleFragmentActivity {
    @Override
    public Fragment createFragment() {
        return new MoonOrbitFragment();
    }

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        // 添加返回按钮
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setHomeButtonEnabled(true);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_moon_orbit, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_item_add_moon_orbit) {
            // 顶部栏新增入口：打开 Moon 添加面板
            FragmentManager manager = getSupportFragmentManager();
            Fragment fragment = manager.findFragmentById(net.kaaass.zerotierfix.R.id.fragmentContainer);
            if (fragment instanceof MoonOrbitFragment) {
                ((MoonOrbitFragment) fragment).showAddMoonEntry();
                return true;
            }
        }
        super.onOptionsItemSelected(item);
        // 返回上一界面
        if (item.getItemId() == android.R.id.home) {
            this.finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
