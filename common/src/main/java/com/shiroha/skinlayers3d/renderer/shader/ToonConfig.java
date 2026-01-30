package com.shiroha.skinlayers3d.renderer.shader;

import com.shiroha.skinlayers3d.config.ConfigManager;

/**
 * Toon 渲染配置代理
 * 
 * 直接从 ConfigManager 读取参数，不存储重复数据
 * 这样 UI 修改后立即生效，无需手动同步
 */
public class ToonConfig {
    
    // 单例实例
    private static final ToonConfig INSTANCE = new ToonConfig();
    
    private ToonConfig() {}
    
    public static ToonConfig getInstance() {
        return INSTANCE;
    }
    
    // ==================== 直接代理 ConfigManager ====================
    
    public boolean isEnabled() { 
        return ConfigManager.isToonRenderingEnabled(); 
    }
    
    public int getToonLevels() { 
        return ConfigManager.getToonLevels(); 
    }
    
    public float getRimPower() { 
        return ConfigManager.getToonRimPower(); 
    }
    
    public float getRimIntensity() { 
        return ConfigManager.getToonRimIntensity(); 
    }
    
    public float getShadowColorR() { 
        return ConfigManager.getToonShadowR(); 
    }
    
    public float getShadowColorG() { 
        return ConfigManager.getToonShadowG(); 
    }
    
    public float getShadowColorB() { 
        return ConfigManager.getToonShadowB(); 
    }
    
    public float getSpecularPower() { 
        return ConfigManager.getToonSpecularPower(); 
    }
    
    public float getSpecularIntensity() { 
        return ConfigManager.getToonSpecularIntensity(); 
    }
    
    public boolean isOutlineEnabled() { 
        return ConfigManager.isToonOutlineEnabled(); 
    }
    
    public float getOutlineWidth() { 
        return ConfigManager.getToonOutlineWidth(); 
    }
    
    public float getOutlineColorR() { 
        return ConfigManager.getToonOutlineR(); 
    }
    
    public float getOutlineColorG() { 
        return ConfigManager.getToonOutlineG(); 
    }
    
    public float getOutlineColorB() { 
        return ConfigManager.getToonOutlineB(); 
    }
}
