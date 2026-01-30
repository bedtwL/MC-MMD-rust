package com.shiroha.skinlayers3d.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 统一配置管理器
 * 提供跨平台的配置访问接口，解耦配置实现
 * 
 * 设计原则：
 * - 依赖倒置：依赖抽象接口而非具体实现
 * - 单一职责：只负责配置的读取和管理
 */
public class ConfigManager {
    private static final Logger logger = LogManager.getLogger();
    private static IConfigProvider provider;
    
    /**
     * 初始化配置管理器
     * @param configProvider 平台特定的配置提供者
     */
    public static void init(IConfigProvider configProvider) {
        provider = configProvider;
        logger.info("配置管理器初始化完成，使用提供者: " + configProvider.getClass().getSimpleName());
    }
    
    /**
     * 获取 OpenGL 光照启用状态
     */
    public static boolean isOpenGLLightingEnabled() {
        return provider != null ? provider.isOpenGLLightingEnabled() : true;
    }
    
    /**
     * 获取模型池最大数量
     */
    public static int getModelPoolMaxCount() {
        return provider != null ? provider.getModelPoolMaxCount() : 100;
    }
    
    /**
     * 获取 MMD Shader 启用状态
     */
    public static boolean isMMDShaderEnabled() {
        return provider != null ? provider.isMMDShaderEnabled() : false;
    }
    
    /**
     * 获取 GPU 蒙皮启用状态
     * GPU 蒙皮可大幅提升大面数模型性能，需要 OpenGL 4.3+
     */
    public static boolean isGpuSkinningEnabled() {
        return provider != null ? provider.isGpuSkinningEnabled() : false;
    }
    
    /**
     * 获取 GPU Morph 启用状态
     * GPU Morph 使用 Compute Shader 计算顶点变形，需要 OpenGL 4.3+
     */
    public static boolean isGpuMorphEnabled() {
        return provider != null ? provider.isGpuMorphEnabled() : false;
    }
    
    // ==================== Toon 渲染配置 ====================
    
    /**
     * 获取 Toon 渲染启用状态
     */
    public static boolean isToonRenderingEnabled() {
        return provider != null ? provider.isToonRenderingEnabled() : true;
    }
    
    /**
     * 获取 Toon 色阶数量（2-5）
     */
    public static int getToonLevels() {
        return provider != null ? provider.getToonLevels() : 3;
    }
    
    /**
     * 获取 Toon 描边启用状态
     */
    public static boolean isToonOutlineEnabled() {
        return provider != null ? provider.isToonOutlineEnabled() : false;
    }
    
    /**
     * 获取 Toon 描边宽度
     */
    public static float getToonOutlineWidth() {
        return provider != null ? provider.getToonOutlineWidth() : 0.003f;
    }
    
    // 边缘光参数
    public static float getToonRimPower() {
        return provider != null ? provider.getToonRimPower() : 5.0f;
    }
    
    public static float getToonRimIntensity() {
        return provider != null ? provider.getToonRimIntensity() : 0.1f;
    }
    
    // 阴影色参数
    public static float getToonShadowR() {
        return provider != null ? provider.getToonShadowR() : 0.8f;
    }
    
    public static float getToonShadowG() {
        return provider != null ? provider.getToonShadowG() : 0.8f;
    }
    
    public static float getToonShadowB() {
        return provider != null ? provider.getToonShadowB() : 0.8f;
    }
    
    // 高光参数
    public static float getToonSpecularPower() {
        return provider != null ? provider.getToonSpecularPower() : 30.0f;
    }
    
    public static float getToonSpecularIntensity() {
        return provider != null ? provider.getToonSpecularIntensity() : 0.08f;
    }
    
    // 描边颜色参数
    public static float getToonOutlineR() {
        return provider != null ? provider.getToonOutlineR() : 0.0f;
    }
    
    public static float getToonOutlineG() {
        return provider != null ? provider.getToonOutlineG() : 0.0f;
    }
    
    public static float getToonOutlineB() {
        return provider != null ? provider.getToonOutlineB() : 0.0f;
    }
    
    // GPU 蒙皮最大骨骼数量
    public static int getMaxBones() {
        return provider != null ? provider.getMaxBones() : 2048;
    }
    
    /**
     * 配置提供者接口
     * 各平台实现此接口以提供配置值
     */
    public interface IConfigProvider {
        boolean isOpenGLLightingEnabled();
        int getModelPoolMaxCount();
        boolean isMMDShaderEnabled();
        
        /** GPU 蒙皮启用状态（默认关闭） */
        default boolean isGpuSkinningEnabled() { return false; }
        
        /** GPU Morph 启用状态（默认关闭） */
        default boolean isGpuMorphEnabled() { return false; }
        
        /** Toon 渲染启用状态（默认开启） */
        default boolean isToonRenderingEnabled() { return true; }
        
        /** Toon 色阶数量（默认3） */
        default int getToonLevels() { return 3; }
        
        /** Toon 描边启用状态（默认关闭） */
        default boolean isToonOutlineEnabled() { return false; }
        
        /** Toon 描边宽度（默认0.003） */
        default float getToonOutlineWidth() { return 0.003f; }
        
        // 边缘光参数
        default float getToonRimPower() { return 5.0f; }
        default float getToonRimIntensity() { return 0.1f; }
        
        // 阴影色参数
        default float getToonShadowR() { return 0.8f; }
        default float getToonShadowG() { return 0.8f; }
        default float getToonShadowB() { return 0.8f; }
        
        // 高光参数
        default float getToonSpecularPower() { return 30.0f; }
        default float getToonSpecularIntensity() { return 0.08f; }
        
        // 描边颜色参数
        default float getToonOutlineR() { return 0.0f; }
        default float getToonOutlineG() { return 0.0f; }
        default float getToonOutlineB() { return 0.0f; }
        
        /** GPU 蒙皮最大骨骼数量（默认2048） */
        default int getMaxBones() { return 2048; }
    }
}
