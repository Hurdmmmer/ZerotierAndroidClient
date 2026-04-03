package net.kaaass.zerotierfix.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.service.ZeroTierOneService;
import net.kaaass.zerotierfix.util.Constants;
import net.kaaass.zerotierfix.util.FileUtil;
import net.kaaass.zerotierfix.util.ThemeModeHelper;

import org.apache.commons.io.FileUtils;
import org.apache.commons.validator.routines.InetAddressValidator;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Arrays;

/**
 * 设置页面（自定义分组卡片样式）
 */
public class PrefsFragment extends Fragment implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static final int PLANET_DOWNLOAD_CONN_TIMEOUT = 5 * 1000;
    public static final int PLANET_DOWNLOAD_TIMEOUT = 10 * 1000;
    private static final String TAG = "PreferencesFragment";
    /**
     * Planet 文件固定头
     */
    private static final byte[] PLANET_FILE_HEADER = new byte[]{
            0x01,  // World type, 0x01 = planet
    };

    private SwitchCompat switchStartOnBoot;
    private SwitchCompat switchPlanetUseCustom;
    private SwitchCompat switchPlanetAutoRouteCheck;
    private SwitchCompat switchUseCellularData;
    private SwitchCompat switchDisableIpv6;

    private View rowSetPlanetFile;
    private View rowPlanetProbeIp;
    private View rowThemeMode;
    private TextView textThemeModeValue;
    private TextView textPlanetProbeIpValue;

    private Dialog planetDialog = null;
    private Dialog loadingDialog = null;
    private ActivityResultLauncher<Intent> planetFileSelectLauncher = null;
    private boolean updatingSwitchState = false;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // 初始化 Planet 文件选择结果回调
        this.planetFileSelectLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), (activityResult) -> {
            var result = activityResult.getResultCode();
            var data = activityResult.getData();
            if (result == -1 && data != null) {
                // Planet 自定义文件设置
                Uri uriData = data.getData();
                if (uriData == null) {
                    Log.e(TAG, "Invalid planet URI");
                    return;
                }
                // 复制文件
                try (InputStream in = requireContext().getContentResolver().openInputStream(uriData)) {
                    FileUtils.copyInputStreamToFile(in, FileUtil.tempFile(requireContext()));
                    boolean success = dealTempPlanetFile();
                    if (!success) {
                        Toast.makeText(getContext(), R.string.planet_wrong_format, Toast.LENGTH_LONG).show();
                        closePlanetDialog();
                        return;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Cannot copy planet file", e);
                    Toast.makeText(getContext(), R.string.cannot_open_planet, Toast.LENGTH_LONG).show();
                    closePlanetDialog();
                    return;
                } finally {
                    FileUtil.clearTempFile(requireContext());
                }
                Log.i(TAG, "Copy planet file successfully");
                Snackbar.make(requireView(), R.string.set_planet_succ, BaseTransientBottomBar.LENGTH_LONG).show();
                closePlanetDialog();
            } else {
                Toast.makeText(getContext(), R.string.cannot_open_planet, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_prefs, container, false);

        this.switchStartOnBoot = view.findViewById(R.id.switch_start_on_boot);
        this.switchPlanetUseCustom = view.findViewById(R.id.switch_planet_use_custom);
        this.switchPlanetAutoRouteCheck = view.findViewById(R.id.switch_planet_auto_route_check);
        this.switchUseCellularData = view.findViewById(R.id.switch_use_cellular_data);
        this.switchDisableIpv6 = view.findViewById(R.id.switch_disable_ipv6);

        this.rowSetPlanetFile = view.findViewById(R.id.row_set_planet_file);
        this.rowPlanetProbeIp = view.findViewById(R.id.row_planet_probe_ip);
        this.rowThemeMode = view.findViewById(R.id.row_theme_mode);
        this.textThemeModeValue = view.findViewById(R.id.text_theme_mode_value);
        this.textPlanetProbeIpValue = view.findViewById(R.id.text_planet_probe_ip_value);

        bindActions();
        refreshUIFromPreferences();
        applyWindowInsets(view);
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        PreferenceManager.getDefaultSharedPreferences(requireContext()).registerOnSharedPreferenceChangeListener(this);
        refreshUIFromPreferences();
    }

    @Override
    public void onPause() {
        super.onPause();
        PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (Constants.PREF_NETWORK_USE_CELLULAR_DATA.equals(key)) {
            if (sharedPreferences.getBoolean(Constants.PREF_NETWORK_USE_CELLULAR_DATA, false)) {
                requireActivity().startService(new Intent(getActivity(), ZeroTierOneService.class));
            }
        }
        if (Constants.PREF_UI_THEME_MODE.equals(key)) {
            ThemeModeHelper.applyThemeMode(requireContext());
        }
        refreshUIFromPreferences();
    }

    private void bindActions() {
        this.switchStartOnBoot.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingSwitchState) {
                return;
            }
            putBoolean(Constants.PREF_GENERAL_START_ZEROTIER_ON_BOOT, isChecked);
        });
        this.rowThemeMode.setOnClickListener(v -> showThemeModeDialog());

        this.switchPlanetUseCustom.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingSwitchState) {
                return;
            }
            putBoolean(Constants.PREF_PLANET_USE_CUSTOM, isChecked);
            updatePlanetSettingUI();
            if (isChecked && customPlanetFileNotExit()) {
                showPlanetDialog();
            }
        });

        this.rowSetPlanetFile.setOnClickListener(v -> showPlanetDialog());

        this.switchPlanetAutoRouteCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingSwitchState) {
                return;
            }
            putBoolean(Constants.PREF_PLANET_AUTO_ROUTE_CHECK, isChecked);
            updatePlanetRouteProbeSettingUI();
        });

        this.rowPlanetProbeIp.setOnClickListener(v -> showPlanetProbeIpDialog());

        this.switchUseCellularData.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingSwitchState) {
                return;
            }
            putBoolean(Constants.PREF_NETWORK_USE_CELLULAR_DATA, isChecked);
        });

        this.switchDisableIpv6.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingSwitchState) {
                return;
            }
            putBoolean(Constants.PREF_NETWORK_DISABLE_IPV6, isChecked);
        });
    }

    private void refreshUIFromPreferences() {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(requireContext());
        updatingSwitchState = true;
        this.switchStartOnBoot.setChecked(pref.getBoolean(Constants.PREF_GENERAL_START_ZEROTIER_ON_BOOT, true));
        this.switchPlanetUseCustom.setChecked(pref.getBoolean(Constants.PREF_PLANET_USE_CUSTOM, false));
        this.switchPlanetAutoRouteCheck.setChecked(pref.getBoolean(Constants.PREF_PLANET_AUTO_ROUTE_CHECK, false));
        this.switchUseCellularData.setChecked(pref.getBoolean(Constants.PREF_NETWORK_USE_CELLULAR_DATA, false));
        this.switchDisableIpv6.setChecked(pref.getBoolean(Constants.PREF_NETWORK_DISABLE_IPV6, false));
        updatingSwitchState = false;

        String probeIp = pref.getString(Constants.PREF_PLANET_INTRANET_PROBE_IP, "");
        if (probeIp == null || probeIp.isEmpty()) {
            this.textPlanetProbeIpValue.setText(R.string.planet_probe_ip_not_set);
        } else {
            this.textPlanetProbeIpValue.setText(probeIp);
        }
        String themeMode = pref.getString(Constants.PREF_UI_THEME_MODE, ThemeModeHelper.MODE_SYSTEM);
        this.textThemeModeValue.setText(themeModeToText(themeMode));

        updatePlanetSettingUI();
        updatePlanetRouteProbeSettingUI();
    }

    private void putBoolean(String key, boolean value) {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putBoolean(key, value)
                .apply();
    }

    private void putString(String key, String value) {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putString(key, value)
                .apply();
    }

    private String themeModeToText(String mode) {
        if (ThemeModeHelper.MODE_LIGHT.equals(mode)) {
            return getString(R.string.theme_mode_light);
        }
        if (ThemeModeHelper.MODE_DARK.equals(mode)) {
            return getString(R.string.theme_mode_dark);
        }
        return getString(R.string.theme_mode_system);
    }

    private void showThemeModeDialog() {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String currentMode = pref.getString(Constants.PREF_UI_THEME_MODE, ThemeModeHelper.MODE_SYSTEM);
        String[] values = new String[]{
                ThemeModeHelper.MODE_SYSTEM,
                ThemeModeHelper.MODE_LIGHT,
                ThemeModeHelper.MODE_DARK
        };
        String[] labels = new String[]{
                getString(R.string.theme_mode_system),
                getString(R.string.theme_mode_light),
                getString(R.string.theme_mode_dark)
        };
        int checkedIndex = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(currentMode)) {
                checkedIndex = i;
                break;
            }
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.preferences_theme_mode)
                .setSingleChoiceItems(labels, checkedIndex, (dialog, which) -> {
                    String selectedMode = values[which];
                    if (!selectedMode.equals(currentMode)) {
                        putString(Constants.PREF_UI_THEME_MODE, selectedMode);
                        ThemeModeHelper.applyThemeMode(requireContext());
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPlanetProbeIpDialog() {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String current = pref.getString(Constants.PREF_PLANET_INTRANET_PROBE_IP, "");

        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_probe_ip, null);
        EditText editText = view.findViewById(R.id.input_probe_ip);
        editText.setText(current);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.planet_intranet_probe_ip)
                .setView(view)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String newIp = editText.getText().toString().trim();
                    // 输入为空表示关闭探测地址；非空则要求合法 IP
                    if (!newIp.isEmpty() && !InetAddressValidator.getInstance().isValid(newIp)) {
                        Toast.makeText(getContext(), R.string.invalid_probe_ip, Toast.LENGTH_LONG).show();
                        return;
                    }
                    putString(Constants.PREF_PLANET_INTRANET_PROBE_IP, newIp);
                    refreshUIFromPreferences();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 显示选择 Planet 文件来源对话框
     */
    private void showPlanetDialog() {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_custom_planet_select, null);

        View viewFile = view.findViewById(R.id.from_file);
        viewFile.setOnClickListener(v -> this.showPlanetFromFileDialog());

        View viewUrl = view.findViewById(R.id.from_url);
        viewUrl.setOnClickListener(v -> this.showPlanetFromUrlDialog());

        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(requireContext())
                .setView(view)
                .setOnCancelListener(dialog -> closePlanetDialog());

        this.planetDialog = builder.create();
        this.planetDialog.show();
    }

    private void showPlanetFromFileDialog() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        this.planetFileSelectLauncher.launch(intent);
    }

    private void showPlanetFromUrlDialog() {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_planet_url, null);
        final EditText editText = view.findViewById(R.id.planet_url);

        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(requireContext())
                .setView(view)
                .setTitle(R.string.import_via_url)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String url = editText.getText().toString();
                    URL fileUrl;
                    try {
                        fileUrl = new URL(url);
                    } catch (MalformedURLException e) {
                        Toast.makeText(getContext(), R.string.wrong_url_format, Toast.LENGTH_LONG).show();
                        return;
                    }

                    if (this.planetDialog != null) {
                        this.planetDialog.dismiss();
                        this.planetDialog = null;
                    }
                    showLoadingDialog(R.string.downloading);
                    new Thread(() -> {
                        try {
                            FileUtils.copyURLToFile(fileUrl,
                                    FileUtil.tempFile(requireContext()),
                                    PLANET_DOWNLOAD_CONN_TIMEOUT, PLANET_DOWNLOAD_TIMEOUT);
                            boolean success = dealTempPlanetFile();
                            if (!success) {
                                requireActivity().runOnUiThread(() -> {
                                    Toast.makeText(getContext(), R.string.planet_wrong_format, Toast.LENGTH_LONG).show();
                                    closePlanetDialog();
                                    closeLoadingDialog();
                                });
                                return;
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "Cannot download planet file", e);
                            requireActivity().runOnUiThread(() -> {
                                int message = R.string.cannot_download_planet_file;
                                if (e instanceof SocketTimeoutException) {
                                    message = R.string.planet_download_timeout;
                                }
                                Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
                                closePlanetDialog();
                                closeLoadingDialog();
                            });
                            return;
                        } finally {
                            FileUtil.clearTempFile(requireContext());
                        }

                        requireActivity().runOnUiThread(() -> {
                            Snackbar.make(requireView(), R.string.set_planet_succ, BaseTransientBottomBar.LENGTH_LONG).show();
                            closePlanetDialog();
                            closeLoadingDialog();
                        });
                    }).start();
                });

        builder.create().show();
    }

    /**
     * 检查是否存在 Planet 文件
     */
    private boolean customPlanetFileNotExit() {
        File file = new File(requireActivity().getFilesDir(), Constants.FILE_CUSTOM_PLANET);
        return !file.exists();
    }

    /**
     * 关闭 Planet 对话框
     */
    private void closePlanetDialog() {
        if (this.planetDialog != null) {
            this.planetDialog.dismiss();
            this.planetDialog = null;
        }
        if (customPlanetFileNotExit()) {
            putBoolean(Constants.PREF_PLANET_USE_CUSTOM, false);
            refreshUIFromPreferences();
        }
    }

    /**
     * 更新 Planet 相关控件可用状态
     */
    private void updatePlanetSettingUI() {
        boolean useCustom = PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean(Constants.PREF_PLANET_USE_CUSTOM, false);
        this.rowSetPlanetFile.setEnabled(useCustom);
        this.switchPlanetAutoRouteCheck.setEnabled(useCustom);
        if (!useCustom) {
            this.rowPlanetProbeIp.setEnabled(false);
        } else {
            updatePlanetRouteProbeSettingUI();
        }
    }

    /**
     * 更新 Planet 内网探测设置项状态。
     */
    private void updatePlanetRouteProbeSettingUI() {
        boolean enabled = PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean(Constants.PREF_PLANET_AUTO_ROUTE_CHECK, false);
        this.rowPlanetProbeIp.setEnabled(enabled && this.switchPlanetAutoRouteCheck.isEnabled());
        this.textPlanetProbeIpValue.setAlpha(this.rowPlanetProbeIp.isEnabled() ? 1f : 0.5f);
    }

    /**
     * 将临时文件设置为 Planet 文件
     */
    private boolean dealTempPlanetFile() {
        File temp = FileUtil.tempFile(requireContext());
        byte[] buf = new byte[PLANET_FILE_HEADER.length];
        try (FileInputStream in = new FileInputStream(temp)) {
            if (in.read(buf) != PLANET_FILE_HEADER.length) {
                return false;
            }
            if (!Arrays.equals(buf, PLANET_FILE_HEADER)) {
                Log.i(TAG, "Planet file has a wrong header");
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        File dest = new File(requireActivity().getFilesDir(), Constants.FILE_CUSTOM_PLANET);
        return temp.renameTo(dest);
    }

    /**
     * 显示加载框
     */
    private void showLoadingDialog(int prompt) {
        closeLoadingDialog();
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_loading, null);
        TextView textPrompt = view.findViewById(R.id.prompt);
        textPrompt.setText(prompt);

        this.loadingDialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(view)
                .setCancelable(false)
                .create();
        this.loadingDialog.show();
    }

    /**
     * 关闭加载框
     */
    private void closeLoadingDialog() {
        if (this.loadingDialog != null) {
            this.loadingDialog.dismiss();
        }
    }

    /**
     * 设置页底部增加导航栏安全区。
     */
    private void applyWindowInsets(View root) {
        final int paddingBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), paddingBottom + navInsets.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
