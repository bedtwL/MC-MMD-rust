package com.shiroha.skinlayers3d.fabric.config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Fabric 模组设置界面
 * 使用 Cloth Config API 构建
 */
public class ModConfigScreen {
    
    /**
     * 创建模组设置界面
     */
    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.translatable("gui.skinlayers3d.mod_settings.title"));
        
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        
        // 渲染设置分类
        ConfigCategory renderCategory = builder.getOrCreateCategory(
            Component.translatable("gui.skinlayers3d.mod_settings.category.render"));
        
        renderCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.skinlayers3d.mod_settings.opengl_lighting"),
                SkinLayers3DConfig.openGLEnableLighting)
            .setDefaultValue(true)
            .setTooltip(Component.translatable("gui.skinlayers3d.mod_settings.opengl_lighting.tooltip"))
            .setSaveConsumer(value -> SkinLayers3DConfig.openGLEnableLighting = value)
            .build());
        
        renderCategory.addEntry(entryBuilder
            .startBooleanToggle(
                Component.translatable("gui.skinlayers3d.mod_settings.mmd_shader"),
                SkinLayers3DConfig.isMMDShaderEnabled)
            .setDefaultValue(false)
            .setTooltip(Component.translatable("gui.skinlayers3d.mod_settings.mmd_shader.tooltip"))
            .setSaveConsumer(value -> SkinLayers3DConfig.isMMDShaderEnabled = value)
            .build());
        
        // 性能设置分类
        ConfigCategory performanceCategory = builder.getOrCreateCategory(
            Component.translatable("gui.skinlayers3d.mod_settings.category.performance"));
        
        performanceCategory.addEntry(entryBuilder
            .startIntSlider(
                Component.translatable("gui.skinlayers3d.mod_settings.model_pool_max"),
                SkinLayers3DConfig.modelPoolMaxCount, 10, 500)
            .setDefaultValue(100)
            .setTooltip(Component.translatable("gui.skinlayers3d.mod_settings.model_pool_max.tooltip"))
            .setSaveConsumer(value -> SkinLayers3DConfig.modelPoolMaxCount = value)
            .build());
        
        builder.setSavingRunnable(() -> {
            SkinLayers3DConfig.save();
        });
        
        return builder.build();
    }
}
