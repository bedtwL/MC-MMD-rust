package com.shiroha.skinlayers3d.fabric.config;

import com.shiroha.skinlayers3d.config.ConfigManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric 配置实现
 * 使用 Properties 文件存储配置
 * 
 * 重构说明：
 * - 实现 ConfigManager.IConfigProvider 接口
 * - 统一默认值（modelPoolMaxCount: 100 -> 100）
 * - 添加异常处理和日志记录
 */
public final class SkinLayers3DConfig implements ConfigManager.IConfigProvider {
    private static final Logger logger = LogManager.getLogger();
    private static SkinLayers3DConfig instance;
    
    // 公开配置字段供 ModConfigScreen 使用
    public static boolean openGLEnableLighting = true;
    public static int modelPoolMaxCount = 100;
    public static boolean isMMDShaderEnabled = false;

    private SkinLayers3DConfig() {
        loadConfig();
    }
    
    public static void init() {
        instance = new SkinLayers3DConfig();
        ConfigManager.init(instance);
        logger.info("Fabric 配置系统初始化完成");
    }
    
    /**
     * 加载配置（带重试机制）
     */
    private void loadConfig() {
        int retryCount = 0;
        int maxRetries = 3;
        
        while (retryCount < maxRetries) {
            try {
                tryLoadConfig();
                logger.info("配置加载成功");
                return;
            } catch (Exception e) {
                retryCount++;
                logger.warn("配置加载失败 (尝试 {}/{}): {}", retryCount, maxRetries, e.getMessage());
                
                if (retryCount >= maxRetries) {
                    logger.error("配置加载失败，使用默认值", e);
                    createDefaultConfig();
                }
            }
        }
    }
    
    /**
     * 尝试加载配置
     */
    private void tryLoadConfig() throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(
                FabricLoader.getInstance().getConfigDir().resolve("3d-skin.properties"))) {
            Properties properties = new Properties();
            properties.load(reader);
            
            openGLEnableLighting = Boolean.parseBoolean(
                properties.getProperty("openGLEnableLighting", "true"));
            modelPoolMaxCount = Integer.parseInt(
                properties.getProperty("modelPoolMaxCount", "100"));
            isMMDShaderEnabled = Boolean.parseBoolean(
                properties.getProperty("isMMDShaderEnabled", "false"));
        }
    }
    
    /**
     * 创建默认配置文件
     */
    private void createDefaultConfig() {
        try (BufferedWriter writer = Files.newBufferedWriter(
                FabricLoader.getInstance().getConfigDir().resolve("3d-skin.properties"))) {
            Properties properties = new Properties();
            properties.setProperty("openGLEnableLighting", "true");
            properties.setProperty("modelPoolMaxCount", "100");
            properties.setProperty("isMMDShaderEnabled", "false");
            properties.store(writer, "3D Skin Layers Configuration");
            logger.info("默认配置文件已创建");
        } catch (IOException e) {
            logger.error("创建默认配置文件失败", e);
        }
    }
    
    @Override
    public boolean isOpenGLLightingEnabled() {
        return openGLEnableLighting;
    }
    
    @Override
    public int getModelPoolMaxCount() {
        return modelPoolMaxCount;
    }
    
    @Override
    public boolean isMMDShaderEnabled() {
        return isMMDShaderEnabled;
    }
    
    /**
     * 保存配置到文件
     */
    public static void save() {
        try (BufferedWriter writer = Files.newBufferedWriter(
                FabricLoader.getInstance().getConfigDir().resolve("3d-skin.properties"))) {
            Properties properties = new Properties();
            properties.setProperty("openGLEnableLighting", String.valueOf(openGLEnableLighting));
            properties.setProperty("modelPoolMaxCount", String.valueOf(modelPoolMaxCount));
            properties.setProperty("isMMDShaderEnabled", String.valueOf(isMMDShaderEnabled));
            properties.store(writer, "3D Skin Layers Configuration");
            logger.info("配置已保存");
        } catch (IOException e) {
            logger.error("保存配置失败", e);
        }
    }
}
