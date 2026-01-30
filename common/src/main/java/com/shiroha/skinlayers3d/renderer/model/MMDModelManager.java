package com.shiroha.skinlayers3d.renderer.model;

import com.shiroha.skinlayers3d.SkinLayers3DClient;
import com.shiroha.skinlayers3d.config.ConfigManager;
import com.shiroha.skinlayers3d.renderer.animation.MMDAnimManager;
import com.shiroha.skinlayers3d.renderer.core.IMMDModel;
import com.shiroha.skinlayers3d.NativeFunc;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.client.Minecraft;

/**
 * MMD 模型管理器
 * 负责模型的加载、缓存和生命周期管理
 * 
 * 线程安全：使用 ConcurrentHashMap 保证多线程访问安全
 * 性能优化：实现 LRU 缓存清理机制，防止内存泄漏
 */
public class MMDModelManager {
    static final Logger logger = LogManager.getLogger();
    static Map<String, Model> models;
    static String gameDirectory = Minecraft.getInstance().gameDirectory.getAbsolutePath();
    
    private static final AtomicInteger cacheSize = new AtomicInteger(0);
    private static long lastModelSwitchTime = 0;
    private static boolean pendingCleanup = false;
    private static final long MODEL_SWITCH_CLEANUP_DELAY = 60000; // 切换模型后 1 分钟无操作则清理

    public static void Init() {
        models = new ConcurrentHashMap<>();
        
        // 从配置读取 GPU 蒙皮设置
        useGpuSkinning = ConfigManager.isGpuSkinningEnabled();
        logger.info("MMDModelManager 初始化完成 (GPU蒙皮: {})", useGpuSkinning ? "启用" : "禁用");
    }

    // GPU 蒙皮模式开关（可通过配置切换）
    private static boolean useGpuSkinning = false;
    // Iris 兼容模式（使用 Minecraft 原生渲染系统）
    private static boolean useNativeRender = false;
    
    /**
     * 设置是否使用 GPU 蒙皮
     * GPU 蒙皮可大幅提升大面数模型性能，但需要 OpenGL 4.3+ 支持
     */
    public static void setUseGpuSkinning(boolean enabled) {
        useGpuSkinning = enabled;
        logger.info("GPU 蒙皮模式: {}", enabled ? "启用" : "禁用");
    }
    
    public static boolean isUseGpuSkinning() {
        return useGpuSkinning;
    }
    
    /**
     * 设置是否使用原生渲染模式（Iris 兼容）
     * 原生渲染使用 Minecraft 的 ShaderInstance 系统，Iris 可正确拦截
     */
    public static void setUseNativeRender(boolean enabled) {
        useNativeRender = enabled;
        logger.info("原生渲染模式 (Iris兼容): {}", enabled ? "启用" : "禁用");
    }
    
    public static boolean isUseNativeRender() {
        return useNativeRender;
    }
    
    /**
     * 加载模型
     * 支持任意名称的 PMX/PMD 文件
     * 渲染模式优先级：原生渲染(Iris兼容) > GPU蒙皮 > CPU蒙皮
     */
    public static IMMDModel LoadModel(String modelName, long layerCount) {
        // 使用 ModelInfo 扫描模型文件
        ModelInfo modelInfo = ModelInfo.findByFolderName(modelName);
        
        if (modelInfo == null) {
            logger.warn("模型未找到: " + modelName);
            return null;
        }
        
        String modelFilenameStr = modelInfo.getModelFilePath();
        String modelDirStr = modelInfo.getFolderPath();
        boolean isPMD = modelInfo.isPMD();
        
        String renderMode = useNativeRender ? "原生渲染(Iris兼容)" : (useGpuSkinning ? "GPU蒙皮" : "CPU蒙皮");
        logger.info("加载 {} 模型: {} -> {} ({})", 
            modelInfo.getFormatDescription(), modelName, modelInfo.getModelFileName(), renderMode);
        
        try {
            // 优先使用原生渲染模式（Iris 兼容）
            if (useNativeRender && !isPMD) {
                IMMDModel nativeModel = MMDModelNativeRender.LoadModel(modelFilenameStr, modelDirStr, layerCount);
                if (nativeModel != null) {
                    return nativeModel;
                }
                logger.warn("原生渲染创建失败，回退到其他模式");
            }
            
            // 其次使用 GPU 蒙皮
            if (useGpuSkinning) {
                IMMDModel gpuModel = MMDModelGpuSkinning.Create(modelFilenameStr, modelDirStr, isPMD, layerCount);
                if (gpuModel != null) {
                    return gpuModel;
                }
                logger.warn("GPU 蒙皮创建失败，回退到 CPU 蒙皮");
            }
            
            // 最后使用 CPU 蒙皮
            return MMDModelOpenGL.Create(modelFilenameStr, modelDirStr, isPMD, layerCount);
        } catch (Exception e) {
            logger.error("加载模型失败: " + modelName, e);
            return null;
        }
    }

    /**
     * 获取模型（带缓存和自动清理）
     */
    public static Model GetModel(String modelName, String cacheKey) {
        String fullCacheKey = modelName + "_" + cacheKey;
        
        Model model = models.get(fullCacheKey);
        if (model != null) {
            model.updateAccessTime();
            return model;
        }
        
        checkAndCleanCache();
        
        IMMDModel m = LoadModel(modelName, 3);
        if (m == null) {
            return null;
        }
        
        try {
            MMDAnimManager.AddModel(m);
            AddModel(fullCacheKey, m, modelName);
            model = models.get(fullCacheKey);
            cacheSize.incrementAndGet();
            logger.info("模型已加载: {} (缓存: {})", fullCacheKey, cacheSize.get());
            return model;
        } catch (Exception e) {
            logger.error("添加模型失败: " + fullCacheKey, e);
            return null;
        }
    }

    public static Model GetModel(String modelName){
        return GetModel(modelName, "default");
    }
    
    /**
     * 记录模型切换事件，触发延迟清理
     */
    public static void onModelSwitch() {
        lastModelSwitchTime = System.currentTimeMillis();
        pendingCleanup = true;
    }
    
    /**
     * 检查是否需要清理缓存
     * 在渲染循环中定期调用
     */
    public static void tick() {
        if (!pendingCleanup) return;
        
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastModelSwitchTime >= MODEL_SWITCH_CLEANUP_DELAY) {
            logger.info("模型切换后 1 分钟无操作，清理缓存");
            cleanupAllCache();
            pendingCleanup = false;
        }
    }
    
    private static void checkAndCleanCache() {
        int maxCacheSize = ConfigManager.getModelPoolMaxCount();
        if (cacheSize.get() >= maxCacheSize) {
            cleanupCache(maxCacheSize);
        }
    }
    
    private static synchronized void cleanupCache(int maxSize) {
        if (models.size() <= maxSize * 0.8) {
            return;
        }
        
        logger.info("清理模型缓存 (当前: {}, 最大: {})", models.size(), maxSize);
        
        models.entrySet().stream()
            .sorted((e1, e2) -> Long.compare(e1.getValue().lastAccessTime, e2.getValue().lastAccessTime))
            .limit(models.size() - (int)(maxSize * 0.7))
            .forEach(entry -> {
                try {
                    DeleteModel(entry.getValue());
                    models.remove(entry.getKey());
                    cacheSize.decrementAndGet();
                } catch (Exception e) {
                    logger.error("清理模型失败: " + entry.getKey(), e);
                }
            });
        
        logger.info("缓存清理完成 (剩余: {})", models.size());
    }
    
    /**
     * 清理所有缓存（保留当前使用的模型）
     */
    private static synchronized void cleanupAllCache() {
        if (models.isEmpty()) return;
        
        long currentTime = System.currentTimeMillis();
        int cleanedCount = 0;
        
        // 清理超过 1 分钟未访问的模型
        var iterator = models.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (currentTime - entry.getValue().lastAccessTime > MODEL_SWITCH_CLEANUP_DELAY) {
                try {
                    DeleteModel(entry.getValue());
                    iterator.remove();
                    cacheSize.decrementAndGet();
                    cleanedCount++;
                } catch (Exception e) {
                    logger.error("清理模型失败: " + entry.getKey(), e);
                }
            }
        }
        
        if (cleanedCount > 0) {
            logger.info("已清理 {} 个未使用的模型缓存", cleanedCount);
        }
    }

    public static void AddModel(String Name, IMMDModel model, String modelName) {
        NativeFunc nf = NativeFunc.GetInst();
        EntityData ed = new EntityData();
        ed.stateLayers = new EntityData.EntityState[3];
        ed.playCustomAnim = false;
        ed.rightHandMat = nf.CreateMat();
        ed.leftHandMat = nf.CreateMat();
        ed.matBuffer = ByteBuffer.allocateDirect(64);

        ModelWithEntityData m = new ModelWithEntityData();
        m.entityName = Name;
        m.model = model;
        m.modelName = modelName;
        m.entityData = ed;
        m.lastAccessTime = System.currentTimeMillis();
        
        model.ResetPhysics();
        model.ChangeAnim(MMDAnimManager.GetAnimModel(model, "idle"), 0);
        models.put(Name, m);
    }

    public static void ReloadModel() {
        for (Model i : models.values()) {
            DeleteModel(i);
        }
        models = new ConcurrentHashMap<>();
        cacheSize.set(0);
        logger.info("模型已重载");
    }

    static void DeleteModel(Model model) {
        try {
            // 根据模型类型调用对应的删除方法
            if (model.model instanceof MMDModelNativeRender) {
                MMDModelNativeRender.Delete((MMDModelNativeRender) model.model);
            } else if (model.model instanceof MMDModelGpuSkinning) {
                MMDModelGpuSkinning.Delete((MMDModelGpuSkinning) model.model);
            } else if (model.model instanceof MMDModelOpenGL) {
                MMDModelOpenGL.Delete((MMDModelOpenGL) model.model);
            }
            MMDAnimManager.DeleteModel(model.model);
            
            // 释放 EntityData 资源
            if (model instanceof ModelWithEntityData med) {
                NativeFunc nf = NativeFunc.GetInst();
                if (med.entityData != null) {
                    if (med.entityData.rightHandMat != 0) {
                        nf.DeleteMat(med.entityData.rightHandMat);
                    }
                    if (med.entityData.leftHandMat != 0) {
                        nf.DeleteMat(med.entityData.leftHandMat);
                    }
                    // matBuffer 由 GC 回收（allocateDirect）
                }
            }
        } catch (Exception e) {
            logger.error("删除模型失败", e);
        }
    }

    public static class Model {
        public IMMDModel model;
        String entityName;
        String modelName;
        public Properties properties = new Properties();
        boolean isPropertiesLoaded = false;
        long lastAccessTime;

        public void updateAccessTime() {
            this.lastAccessTime = System.currentTimeMillis();
        }

        public void loadModelProperties(boolean forceReload){
            if (isPropertiesLoaded && !forceReload) {
                return;
            }
            
            // 使用 ModelInfo 获取模型文件夹路径
            ModelInfo info = ModelInfo.findByFolderName(modelName);
            if (info == null) {
                logger.warn("模型属性加载失败，模型未找到: {}", modelName);
                isPropertiesLoaded = true;
                return;
            }
            
            String path2Properties = info.getFolderPath() + "/model.properties";
            try (InputStream istream = new FileInputStream(path2Properties)) {
                properties.load(istream);
                logger.debug("模型属性加载成功: {}", modelName);
            } catch (IOException e) {
                logger.debug("模型属性文件未找到: {}", modelName);
            }
            isPropertiesLoaded = true;
            SkinLayers3DClient.reloadProperties = false;
        } 
    }

    public static class ModelWithEntityData extends Model {
        public EntityData entityData;
    }

    public static class EntityData {
        public static HashMap<EntityState, String> stateProperty = new HashMap<>() {{
            put(EntityState.Idle, "idle");
            put(EntityState.Walk, "walk");
            put(EntityState.Sprint, "sprint");
            put(EntityState.Air, "air");
            put(EntityState.OnClimbable, "onClimbable");
            put(EntityState.OnClimbableUp, "onClimbableUp");
            put(EntityState.OnClimbableDown, "onClimbableDown");
            put(EntityState.Swim, "swim");
            put(EntityState.Ride, "ride");
            put(EntityState.Ridden, "ridden");
            put(EntityState.Driven, "driven");
            put(EntityState.Sleep, "sleep");
            put(EntityState.ElytraFly, "elytraFly");
            put(EntityState.Die, "die");
            put(EntityState.SwingRight, "swingRight");
            put(EntityState.SwingLeft, "swingLeft");
            put(EntityState.Sneak, "sneak");
            put(EntityState.OnHorse, "onHorse");
            put(EntityState.Crawl, "crawl");
            put(EntityState.LieDown, "lieDown");
        }};
        public boolean playCustomAnim;
        public long rightHandMat, leftHandMat;
        public EntityState[] stateLayers;
        ByteBuffer matBuffer;

        public enum EntityState {Idle, Walk, Sprint, Air, OnClimbable, OnClimbableUp, OnClimbableDown, Swim, Ride, Ridden, Driven, Sleep, ElytraFly, Die, SwingRight, SwingLeft, ItemRight, ItemLeft, Sneak, OnHorse, Crawl, LieDown}
    }
}
