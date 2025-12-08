package top.galqq.utils;

import android.content.Context;
import android.text.TextUtils;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import top.galqq.config.ConfigManager;

public class DictionaryManager {

    private static final List<String> sCache = new ArrayList<>();
    private static final SecureRandom sRandom = new SecureRandom();

    public static void loadDictionary(Context context) {
        sCache.clear();
        String customPath = ConfigManager.getDictPath();
        
        // Try loading from custom path
        if (!TextUtils.isEmpty(customPath)) {
            File file = new File(customPath);
            if (file.exists() && file.canRead()) {
                try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (!line.trim().isEmpty()) {
                            sCache.add(line.trim());
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        // Fallback to assets if cache is empty
        if (sCache.isEmpty()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(context.getAssets().open("gal_dict.txt")))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        sCache.add(line.trim());
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static List<String> pickRandomLines(int count) {
        List<String> result = new ArrayList<>();
        if (sCache.isEmpty()) return result;

        if (sCache.size() <= count) {
            result.addAll(sCache);
            // Fill remaining if needed by repeating
            while (result.size() < count) {
                result.add(sCache.get(sRandom.nextInt(sCache.size())));
            }
            return result;
        }

        List<Integer> indices = new ArrayList<>();
        while (result.size() < count) {
            int index = sRandom.nextInt(sCache.size());
            if (!indices.contains(index)) {
                indices.add(index);
                result.add(sCache.get(index));
            }
        }
        return result;
    }
}
