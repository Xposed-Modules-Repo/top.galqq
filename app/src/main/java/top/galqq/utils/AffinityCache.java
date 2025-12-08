package top.galqq.utils;

import android.content.Context;

import de.robv.android.xposed.XposedBridge;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import top.galqq.config.ConfigManager;

/**
 * 好感度数据缓存
 * 使用 MMKV 存储好感度数据，支持缓存有效期检查
 */
public class AffinityCache {

    private static final String TAG = "GalQQ.AffinityCache";
    
    private static void debugLog(String message) {
        try {
            if (ConfigManager.isVerboseLogEnabled()) {
                XposedBridge.log(message);
            }
        } catch (Throwable ignored) {}
    }
    
    // 缓存键
    private static final String KEY_WHO_CARES_ME = "affinity_who_cares_me";
    private static final String KEY_WHO_I_CARE = "affinity_who_i_care";
    private static final String KEY_TIMESTAMP = "affinity_timestamp";
    
    // 缓存有效期：1小时（毫秒）
    public static final long CACHE_DURATION_MS = 60 * 60 * 1000L;

    private Context mContext;

    public AffinityCache(Context context) {
        mContext = context.getApplicationContext();
        // 确保 ConfigManager 已初始化
        ConfigManager.init(mContext);
    }

    /**
     * 保存"谁在意我"数据
     * @param data UIN -> 分数 映射
     */
    public void saveWhoCaresMe(Map<String, Integer> data) {
        saveData(KEY_WHO_CARES_ME, data);
        updateTimestamp();
    }

    /**
     * 保存"我在意谁"数据
     * @param data UIN -> 分数 映射
     */
    public void saveWhoICare(Map<String, Integer> data) {
        saveData(KEY_WHO_I_CARE, data);
        updateTimestamp();
    }

    /**
     * 获取"谁在意我"数据
     * @return UIN -> 分数 映射，如果没有缓存返回 null
     */
    public Map<String, Integer> getWhoCaresMe() {
        return loadData(KEY_WHO_CARES_ME);
    }

    /**
     * 获取"我在意谁"数据
     * @return UIN -> 分数 映射，如果没有缓存返回 null
     */
    public Map<String, Integer> getWhoICare() {
        return loadData(KEY_WHO_I_CARE);
    }

    /**
     * 检查缓存是否有效
     * @return true 如果缓存存在且未过期
     */
    public boolean isCacheValid() {
        long timestamp = getTimestamp();
        if (timestamp <= 0) {
            return false;
        }
        
        long currentTime = System.currentTimeMillis();
        long age = currentTime - timestamp;
        
        boolean valid = age < CACHE_DURATION_MS;
        debugLog(TAG + ": 缓存有效性检查: age=" + (age / 1000) + "s, valid=" + valid);
        
        return valid;
    }

    /**
     * 获取缓存时间戳
     * @return 时间戳（毫秒），如果没有缓存返回 0
     */
    public long getTimestamp() {
        return ConfigManager.getLong(KEY_TIMESTAMP, 0);
    }

    /**
     * 清除所有缓存
     */
    public void clearCache() {
        ConfigManager.remove(KEY_WHO_CARES_ME);
        ConfigManager.remove(KEY_WHO_I_CARE);
        ConfigManager.remove(KEY_TIMESTAMP);
        debugLog(TAG + ": 缓存已清除");
    }

    /**
     * 保存数据到 MMKV
     */
    private void saveData(String key, Map<String, Integer> data) {
        if (data == null) {
            ConfigManager.remove(key);
            return;
        }
        
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, Integer> entry : data.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }
            ConfigManager.putString(key, json.toString());
            debugLog(TAG + ": 保存数据: " + key + ", 共 " + data.size() + " 条");
        } catch (Exception e) {
            debugLog(TAG + ": 保存数据失败: " + e.getMessage());
        }
    }

    /**
     * 从 MMKV 加载数据
     */
    private Map<String, Integer> loadData(String key) {
        String jsonStr = ConfigManager.getString(key, null);
        if (jsonStr == null || jsonStr.isEmpty()) {
            return null;
        }
        
        try {
            Map<String, Integer> result = new HashMap<>();
            JSONObject json = new JSONObject(jsonStr);
            Iterator<String> keys = json.keys();
            
            while (keys.hasNext()) {
                String uin = keys.next();
                int score = json.getInt(uin);
                result.put(uin, score);
            }
            
            //XposedBridge.log(TAG + ": 加载数据: " + key + ", 共 " + result.size() + " 条");
            return result;
            
        } catch (Exception e) {
            debugLog(TAG + ": 加载数据失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 更新时间戳
     */
    private void updateTimestamp() {
        long timestamp = System.currentTimeMillis();
        ConfigManager.putLong(KEY_TIMESTAMP, timestamp);
        debugLog(TAG + ": 更新时间戳: " + timestamp);
    }
}
