package top.galqq.utils;

import android.content.Context;

import de.robv.android.xposed.XposedBridge;

/**
 * 好感度管理器 - 核心类
 * 负责管理好感度数据的获取、计算和缓存
 */
public class AffinityManager {

    private static final String TAG = "GalQQ.AffinityManager";
    
    private static AffinityManager sInstance;
    private Context mContext;
    private AffinityCache mCache;
    private CloseRankClient mClient;
    private boolean mIsRefreshing = false;

    /**
     * 刷新回调接口
     */
    public interface RefreshCallback {
        void onSuccess();
        void onFailure(Exception e);
    }

    private AffinityManager(Context context) {
        mContext = context.getApplicationContext();
        mCache = new AffinityCache(mContext);
        mClient = new CloseRankClient();
    }

    /**
     * 获取单例实例
     */
    public static synchronized AffinityManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new AffinityManager(context);
        }
        return sInstance;
    }

    /**
     * 获取指定用户的好感度
     * @param uin QQ号
     * @return 好感度值 (0-100)，如果数据不可用返回 -1
     */
    public int getAffinity(String uin) {
        // 检查功能是否启用
        if (!top.galqq.config.ConfigManager.isAffinityEnabled()) {
            return -1;
        }
        
        if (uin == null || uin.isEmpty()) {
            return -1;
        }
        
        // 从缓存获取双向数据
        java.util.Map<String, Integer> whoCaresMe = mCache.getWhoCaresMe();
        java.util.Map<String, Integer> whoICare = mCache.getWhoICare();
        
        // 如果缓存为空，尝试触发刷新
        if (whoCaresMe == null && whoICare == null) {
            // 异步刷新，不阻塞当前调用
            if (!mIsRefreshing) {
                refreshData(null);
            }
            return -1;
        }
        
        // 获取该用户的双向值
        Integer caresMeValue = whoCaresMe != null ? whoCaresMe.get(uin) : null;
        Integer iCareValue = whoICare != null ? whoICare.get(uin) : null;
        
        // 如果两个值都不存在，返回 -1
        if (caresMeValue == null && iCareValue == null) {
            return -1;
        }
        
        // 使用 0 作为默认值
        int a = caresMeValue != null ? caresMeValue : 0;
        int b = iCareValue != null ? iCareValue : 0;
        
        return calculateAffinity(a, b);
    }

    /**
     * 刷新好感度数据
     * @param callback 刷新完成回调
     */
    public void refreshData(RefreshCallback callback) {
        refreshData(false, callback);
    }

    /**
     * 刷新好感度数据
     * @param force 是否强制刷新（忽略缓存）
     * @param callback 刷新完成回调
     */
    public void refreshData(boolean force, RefreshCallback callback) {
        // 检查是否正在刷新
        if (mIsRefreshing) {
            return;
        }
        
        // 检查缓存是否有效（非强制刷新时）
        if (!force && mCache.isCacheValid()) {
            if (callback != null) {
                callback.onSuccess();
            }
            return;
        }
        
        mIsRefreshing = true;
        
        // 使用新的双向数据获取方法，一次请求获取两种数据
        mClient.fetchBothRankData(mContext, new CloseRankClient.BothRankCallback() {
            @Override
            public void onSuccess(java.util.Map<String, Integer> whoICare, java.util.Map<String, Integer> whoCaresMe) {
                mIsRefreshing = false;
                
                // 保存数据到缓存
                if (whoCaresMe != null && !whoCaresMe.isEmpty()) {
                    mCache.saveWhoCaresMe(whoCaresMe);
                }
                
                if (whoICare != null && !whoICare.isEmpty()) {
                    mCache.saveWhoICare(whoICare);
                }
                
                if (callback != null) {
                    callback.onSuccess();
                }
            }

            @Override
            public void onFailure(Exception e) {
                mIsRefreshing = false;
                XposedBridge.log(TAG + ": 刷新好感度数据失败: " + e.getMessage());
                
                if (callback != null) {
                    callback.onFailure(e);
                }
            }
        });
    }

    // 好感度计算模型常量
    public static final int MODEL_MUTUAL = 0;      // 双向奔赴模型
    public static final int MODEL_BALANCED = 1;    // 加权平衡模型
    public static final int MODEL_EGOCENTRIC = 2;  // 综合加权模型
    
    /**
     * 计算好感度（使用配置的模型）
     * 
     * @param whoCaresMe "谁在意我"的原始值 (A)
     * @param whoICare "我在意谁"的原始值 (B)
     * @return 计算后的好感度 (0-100)
     */
    public static int calculateAffinity(int whoCaresMe, int whoICare) {
        int model = top.galqq.config.ConfigManager.getAffinityModel();
        return calculateAffinityWithModel(whoCaresMe, whoICare, model);
    }
    
    /**
     * 使用指定模型计算好感度
     * 
     * @param whoCaresMe "谁在意我"的原始值 (A)
     * @param whoICare "我在意谁"的原始值 (B)
     * @param model 计算模型
     * @return 计算后的好感度 (0-100)
     */
    public static int calculateAffinityWithModel(int whoCaresMe, int whoICare, int model) {
        switch (model) {
            case MODEL_MUTUAL:
                return calculateMutualModel(whoCaresMe, whoICare);
            case MODEL_BALANCED:
                return calculateBalancedModel(whoCaresMe, whoICare);
            case MODEL_EGOCENTRIC:
            default:
                return calculateEgocentricModel(whoCaresMe, whoICare);
        }
    }
    
    /**
     * 方案一：双向奔赴模型 (Mutual Model)
     * 
     * 核心逻辑：只有当两个人互相关注时，分值才高。
     * 公式：Score = 2 * A * B / (A + B + ε)
     * 
     * 特点：
     * - 短板效应明显：只要有一个值是0，结果就是0
     * - 高分难得：只有A和B都很高时，分数才会高
     * 
     * 示例：
     * - (100, 100) → 100分
     * - (100, 10) → 18分
     * - (50, 50) → 50分
     */
    private static int calculateMutualModel(int whoCaresMe, int whoICare) {
        if (whoCaresMe <= 0 && whoICare <= 0) {
            return 0;
        }
        
        double a = Math.max(0, whoCaresMe);
        double b = Math.max(0, whoICare);
        double epsilon = 0.001; // 防止除零
        
        // 调和平均数变体
        double score = (2.0 * a * b) / (a + b + epsilon);
        
        return Math.max(0, Math.min(100, (int) Math.round(score)));
    }
    
    /**
     * 方案二：加权平衡模型 (Balanced Reality Model)
     * 
     * 核心逻辑：承认总的互动量（基础分），但对"不对等"的关系进行扣分。
     * 公式：Score = ((A + B) / 2) * (1 - |A - B| / 100)
     * 
     * 特点：
     * - 基础分 = 平均值
     * - 平衡系数 = 差值越小越接近1
     * 
     * 示例：
     * - (90, 90) → 90分
     * - (100, 20) → 12分
     * - (30, 30) → 30分
     */
    private static int calculateBalancedModel(int whoCaresMe, int whoICare) {
        if (whoCaresMe <= 0 && whoICare <= 0) {
            return 0;
        }
        
        double a = Math.max(0, whoCaresMe);
        double b = Math.max(0, whoICare);
        
        // 基础分：平均值
        double baseScore = (a + b) / 2.0;
        
        // 平衡系数：差值越小越接近1
        double balanceFactor = 1.0 - Math.abs(a - b) / 100.0;
        balanceFactor = Math.max(0, balanceFactor); // 确保不为负
        
        double score = baseScore * balanceFactor;
        
        return Math.max(0, Math.min(100, (int) Math.round(score)));
    }
    
    /**
     * 方案三：综合加权模型 (Ego-Centric Model)
     * 
     * 核心逻辑：认为"对方怎么对我"更重要，给予更高权重。
     * 公式：Score = 0.6 * A + 0.4 * B
     * 
     * 特点：
     * - "他关注我"权重60%
     * - "我关注他"权重40%
     * - 适合筛选那些"其实很在乎你，但你可能忽略了"的人
     * 
     * 示例：
     * - (90, 20) → 62分
     * - (20, 90) → 48分
     * - (50, 50) → 50分
     */
    private static int calculateEgocentricModel(int whoCaresMe, int whoICare) {
        if (whoCaresMe <= 0 && whoICare <= 0) {
            return 0;
        }
        
        double a = Math.max(0, whoCaresMe);
        double b = Math.max(0, whoICare);
        
        // 加权平均：A权重60%，B权重40%
        double score = a * 0.6 + b * 0.4;
        
        return Math.max(0, Math.min(100, (int) Math.round(score)));
    }

    /**
     * 检查是否正在刷新
     */
    public boolean isRefreshing() {
        return mIsRefreshing;
    }

    /**
     * 获取缓存实例（供内部使用）
     */
    AffinityCache getCache() {
        return mCache;
    }

    /**
     * 获取网络客户端实例（供内部使用）
     */
    CloseRankClient getClient() {
        return mClient;
    }
}
