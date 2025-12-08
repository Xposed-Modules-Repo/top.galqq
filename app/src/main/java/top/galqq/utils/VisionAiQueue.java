package top.galqq.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XposedBridge;
import top.galqq.config.ConfigManager;

/**
 * 外挂AI图片识别队列管理器
 * 
 * 功能：
 * 1. 使用与主AI相同的QPS限制
 * 2. 支持缓存，避免重复识别
 * 3. 优先级队列（当前消息优先于上下文消息）
 * 4. 批量处理同一消息的多张图片
 */
public class VisionAiQueue {
    
    private static final String TAG = "GalQQ.VisionQueue";
    
    // 单例
    private static volatile VisionAiQueue instance;
    
    // 优先级队列
    private final PriorityBlockingQueue<ImageRecognitionTask> taskQueue;
    
    // 动态限流器（与主AI共享QPS配置）
    private final DynamicRateLimiter rateLimiter;
    
    // 线程池
    private final ExecutorService executorService;
    
    // 工作线程
    private Thread workerThread;
    
    // UI Handler
    private final Handler mainHandler;
    
    private VisionAiQueue() {
        this.taskQueue = new PriorityBlockingQueue<>(50);
        // 使用外挂AI专用的QPS配置，而不是主AI的QPS
        float initialQps = ConfigManager.getVisionAiQps();
        this.rateLimiter = new DynamicRateLimiter(initialQps, 0.3); // 最小0.3 QPS
        this.executorService = Executors.newFixedThreadPool(2); // 最多2个并发识别
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        startWorker();
        debugLog("VisionAiQueue 初始化完成，外挂AI QPS=" + initialQps);
    }
    
    public static VisionAiQueue getInstance() {
        if (instance == null) {
            synchronized (VisionAiQueue.class) {
                if (instance == null) {
                    instance = new VisionAiQueue();
                }
            }
        }
        return instance;
    }
    
    /**
     * 图片识别回调
     */
    public interface VisionCallback {
        void onSuccess(List<String> descriptions);
        void onFailure(Exception e);
    }
    
    /**
     * 提交图片识别任务
     * 
     * @param context Android上下文
     * @param conversationId 会话ID
     * @param msgId 消息ID
     * @param imageElements 图片元素列表
     * @param priority 优先级（true=高优先级，当前消息；false=低优先级，上下文消息）
     * @param callback 回调
     */
    public void submitTask(Context context, String conversationId, String msgId,
                          List<ImageExtractor.ImageElement> imageElements,
                          boolean priority, VisionCallback callback) {
        if (imageElements == null || imageElements.isEmpty()) {
            callback.onSuccess(new ArrayList<>());
            return;
        }
        
        // 检查缓存
        if (ImageDescriptionCache.hasAll(conversationId, msgId, imageElements.size())) {
            debugLog("所有图片已缓存，直接返回: " + msgId);
            List<String> cached = ImageDescriptionCache.getAll(conversationId, msgId, imageElements.size());
            callback.onSuccess(cached);
            return;
        }
        
        ImageRecognitionTask task = new ImageRecognitionTask(
            context, conversationId, msgId, imageElements, priority, callback, System.currentTimeMillis()
        );
        
        boolean added = taskQueue.offer(task);
        if (added) {
            debugLog("图片识别任务入队: msgId=" + msgId + ", 图片数=" + imageElements.size() + ", 优先级=" + (priority ? "高" : "低"));
        } else {
            debugLog("队列已满，丢弃任务: " + msgId);
            callback.onFailure(new Exception("图片识别队列已满"));
        }
    }
    
    /**
     * 同步识别图片（阻塞调用，用于当前消息）
     * 
     * @param context Android上下文
     * @param conversationId 会话ID
     * @param msgId 消息ID
     * @param imageElements 图片元素列表
     * @return 图片描述列表
     */
    public List<String> recognizeSync(Context context, String conversationId, String msgId,
                                      List<ImageExtractor.ImageElement> imageElements) {
        if (imageElements == null || imageElements.isEmpty()) {
            return new ArrayList<>();
        }
        
        // 检查缓存
        if (ImageDescriptionCache.hasAll(conversationId, msgId, imageElements.size())) {
            debugLog("所有图片已缓存，直接返回: " + msgId);
            return ImageDescriptionCache.getAll(conversationId, msgId, imageElements.size());
        }
        
        List<String> descriptions = new ArrayList<>();
        
        for (int i = 0; i < imageElements.size(); i++) {
            // 检查单张图片缓存
            String cached = ImageDescriptionCache.get(conversationId, msgId, i);
            if (cached != null) {
                descriptions.add(cached);
                debugLog("图片 " + (i + 1) + " 命中缓存");
                continue;
            }
            
            // 限流
            rateLimiter.acquire();
            
            ImageExtractor.ImageElement img = imageElements.get(i);
            String base64 = ImageBase64Helper.fromImageElement(img);
            
            if (base64 != null) {
                debugLog("正在识别图片 " + (i + 1) + "/" + imageElements.size());
                String description = VisionAiClient.analyzeImageSync(base64);
                
                if (description != null && !description.isEmpty()) {
                    descriptions.add(description);
                    // 缓存结果
                    ImageDescriptionCache.put(conversationId, msgId, i, description);
                    rateLimiter.onSuccess();
                    debugLog("图片 " + (i + 1) + " 识别成功: " + truncate(description, 50));
                } else {
                    String placeholder = "[图片识别失败]";
                    descriptions.add(placeholder);
                    debugLog("图片 " + (i + 1) + " 识别失败");
                }
            } else {
                String placeholder = "[无法读取图片]";
                descriptions.add(placeholder);
                debugLog("图片 " + (i + 1) + " 无法读取");
            }
        }
        
        return descriptions;
    }
    
    /**
     * 启动工作线程
     */
    private void startWorker() {
        workerThread = new Thread(() -> {
            debugLog("VisionAiQueue 工作线程启动");
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    ImageRecognitionTask task = taskQueue.take();
                    
                    // 异步执行
                    executorService.submit(() -> processTask(task));
                    
                } catch (InterruptedException e) {
                    debugLog("工作线程被中断");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": 工作线程异常: " + t.getMessage());
                }
            }
        }, "VisionQueueWorker");
        workerThread.start();
    }
    
    /**
     * 处理单个任务
     */
    private void processTask(ImageRecognitionTask task) {
        try {
            List<String> descriptions = new ArrayList<>();
            
            for (int i = 0; i < task.imageElements.size(); i++) {
                // 检查单张图片缓存
                String cached = ImageDescriptionCache.get(task.conversationId, task.msgId, i);
                if (cached != null) {
                    descriptions.add(cached);
                    continue;
                }
                
                // 限流
                rateLimiter.acquire();
                
                ImageExtractor.ImageElement img = task.imageElements.get(i);
                String base64 = ImageBase64Helper.fromImageElement(img);
                
                if (base64 != null) {
                    String description = VisionAiClient.analyzeImageSync(base64);
                    
                    if (description != null && !description.isEmpty()) {
                        descriptions.add(description);
                        ImageDescriptionCache.put(task.conversationId, task.msgId, i, description);
                        rateLimiter.onSuccess();
                    } else {
                        descriptions.add("[图片识别失败]");
                    }
                } else {
                    descriptions.add("[无法读取图片]");
                }
            }
            
            // 回调成功
            mainHandler.post(() -> task.callback.onSuccess(descriptions));
            
        } catch (Exception e) {
            debugLog("任务处理失败: " + e.getMessage());
            mainHandler.post(() -> task.callback.onFailure(e));
        }
    }
    
    /**
     * 获取队列大小
     */
    public int getQueueSize() {
        return taskQueue.size();
    }
    
    /**
     * 获取当前QPS
     */
    public double getCurrentQPS() {
        return rateLimiter.getCurrentQPS();
    }
    
    // ========== 内部类 ==========
    
    /**
     * 图片识别任务
     */
    private static class ImageRecognitionTask implements Comparable<ImageRecognitionTask> {
        final Context context;
        final String conversationId;
        final String msgId;
        final List<ImageExtractor.ImageElement> imageElements;
        final boolean highPriority;
        final VisionCallback callback;
        final long timestamp;
        
        ImageRecognitionTask(Context context, String conversationId, String msgId,
                            List<ImageExtractor.ImageElement> imageElements,
                            boolean highPriority, VisionCallback callback, long timestamp) {
            this.context = context;
            this.conversationId = conversationId;
            this.msgId = msgId;
            this.imageElements = imageElements;
            this.highPriority = highPriority;
            this.callback = callback;
            this.timestamp = timestamp;
        }
        
        @Override
        public int compareTo(ImageRecognitionTask other) {
            // 高优先级在前
            if (this.highPriority != other.highPriority) {
                return this.highPriority ? -1 : 1;
            }
            // 同优先级按时间排序
            return Long.compare(this.timestamp, other.timestamp);
        }
    }
    
    /**
     * 动态限流器
     */
    private static class DynamicRateLimiter {
        private volatile double currentQPS;
        private volatile double targetQPS;
        private final double minQPS;
        private final AtomicInteger successCount = new AtomicInteger(0);
        private volatile long lastAdjustTime = System.currentTimeMillis();
        private volatile long lastTokenTime = System.currentTimeMillis();
        
        DynamicRateLimiter(double initialQPS, double minQPS) {
            this.targetQPS = initialQPS;
            this.minQPS = minQPS;
            this.currentQPS = initialQPS;
        }
        
        synchronized void acquire() {
            // 更新目标QPS（使用外挂AI专用的QPS配置）
            float configQps = ConfigManager.getVisionAiQps();
            if (Math.abs(this.targetQPS - configQps) > 0.1) {
                this.targetQPS = configQps;
                if (this.currentQPS > configQps) {
                    this.currentQPS = configQps;
                }
            }
            
            long intervalMs = (long) (1000.0 / currentQPS);
            long now = System.currentTimeMillis();
            long waitTime = lastTokenTime + intervalMs - now;
            
            if (waitTime > 0) {
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            lastTokenTime = System.currentTimeMillis();
        }
        
        void onSuccess() {
            int count = successCount.incrementAndGet();
            long now = System.currentTimeMillis();
            
            if (now - lastAdjustTime > 30000 && count >= 10 && currentQPS < targetQPS) {
                synchronized (this) {
                    currentQPS = Math.min(targetQPS, currentQPS * 1.2);
                    successCount.set(0);
                    lastAdjustTime = now;
                }
            }
        }
        
        double getCurrentQPS() {
            return currentQPS;
        }
    }
    
    private static void debugLog(String message) {
        try {
            if (ConfigManager.isDebugHookLogEnabled()) {
                XposedBridge.log(TAG + ": " + message);
            }
        } catch (Throwable ignored) {}
    }
    
    private static String truncate(String text, int maxLength) {
        if (text == null) return "null";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
