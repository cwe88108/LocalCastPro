package com.localcast.pro.ui;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.localcast.pro.R;

/**
 * 设置界面
 * 
 * 视频设置：分辨率、帧率、码率
 * 音频设置：音频开关、音频质量
 * 网络设置：端口配置
 * 高级设置：强制硬件编解码、禁用B帧、I帧间隔、触控回传
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, new SettingsFragment())
                .commit();
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            // 视频设置
            ListPreference resolutionPref = findPreference("resolution");
            if (resolutionPref != null) {
                resolutionPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    String value = (String) newValue;
                    preference.setSummary(getResolutionSummary(value));
                    return true;
                });
                resolutionPref.setSummary(
                        getResolutionSummary(resolutionPref.getValue()));
            }

            ListPreference fpsPref = findPreference("frame_rate");
            if (fpsPref != null) {
                fpsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    preference.setSummary(getFpsSummary((String) newValue));
                    return true;
                });
                fpsPref.setSummary(getFpsSummary(fpsPref.getValue()));
            }

            ListPreference bitratePref = findPreference("bitrate");
            if (bitratePref != null) {
                bitratePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    preference.setSummary(getBitrateSummary((String) newValue));
                    return true;
                });
                bitratePref.setSummary(getBitrateSummary(bitratePref.getValue()));
            }

            // 音频设置
            SwitchPreferenceCompat audioPref = findPreference("audio_enabled");
            if (audioPref != null) {
                audioPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean enabled = (boolean) newValue;
                    Toast.makeText(getContext(),
                            "音频" + (enabled ? "已开启" : "已关闭"),
                            Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
            
            // 音频模式设置
            ListPreference audioModePref = findPreference("audio_mode");
            if (audioModePref != null) {
                audioModePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    String mode = (String) newValue;
                    String summary;
                    if ("microphone".equals(mode)) {
                        summary = "仅采集环境声音，不投送设备播放声";
                    } else {
                        summary = "投送设备播放声（Android 10+；受播放应用权限限制）";
                    }
                    preference.setSummary(summary);
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .edit().putBoolean("audio_mode_user_selected", true).apply();
                    
                    Toast.makeText(getContext(),
                            "音频模式: " + summary,
                            Toast.LENGTH_LONG).show();
                    return true;
                });
                
                // 设置初始摘要
                String currentMode = audioModePref.getValue();
                if ("microphone".equals(currentMode)) {
                    audioModePref.setSummary("仅采集环境声音，不投送设备播放声");
                } else {
                    audioModePref.setSummary("投送设备播放声（Android 10+；受播放应用权限限制）");
                }
            }

            // 高级设置
            SwitchPreferenceCompat hwCodecPref = findPreference("force_hardware_codec");
            if (hwCodecPref != null) {
                hwCodecPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean enabled = (boolean) newValue;
                    if (!enabled) {
                        Toast.makeText(getContext(),
                                "关闭硬件编解码会显著增加延迟",
                                Toast.LENGTH_LONG).show();
                    }
                    return true;
                });
            }
        }

        private String getResolutionSummary(String value) {
            switch (value) {
                case "auto": return "自动（推荐）";
                case "360p": return "360p - 640x360";
                case "720p": return "720p - 1280x720";
                case "1080p": return "1080p - 1920x1080";
                case "1440p": return "1440p - 2560x1440";
                case "4k": return "4K - 3840x2160";
                default: return value;
            }
        }

        private String getBitrateSummary(String value) {
            switch (value) {
                case "auto": return "自动（推荐）";
                case "low": return "低 - 约1-2 Mbps";
                case "medium": return "中 - 约3-5 Mbps";
                case "high": return "高 - 约8-12 Mbps";
                case "extreme": return "极高 - 约15-30 Mbps";
                default: return value;
            }
        }

        private String getFpsSummary(String value) {
            switch (value) {
                case "30": return "30 fps（稳定，推荐）";
                case "60": return "60 fps（需要较好的 Wi-Fi 与硬件编码）";
                case "120": return "120 fps（实验性；系统采集通常会自动降至 60）";
                default: return value + " fps";
            }
        }
    }
}
