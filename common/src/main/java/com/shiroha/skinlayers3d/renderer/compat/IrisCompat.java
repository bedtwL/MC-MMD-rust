package com.shiroha.skinlayers3d.renderer.compat;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL46C;

/**
 * Iris 光影模组兼容层
 * 处理自定义渲染前后的 OpenGL 状态保存/恢复
 * 
 * 关键点：
 * 1. Iris 通过 Mixin 拦截 GlStateManager._glUseProgram，必须使用它来恢复着色器
 * 2. GPU 蒙皮使用的 SSBO 绑定点可能与 Iris 冲突，需要解绑
 * 3. 自定义着色器渲染后必须完全清理状态
 */
public class IrisCompat {
    
    // 保存的状态
    private static int savedVao = 0;
    private static int savedArrayBuffer = 0;
    private static int savedElementBuffer = 0;
    private static int savedProgram = 0;
    private static int savedActiveTexture = 0;
    
    // GPU 蒙皮使用的 SSBO 绑定点
    private static final int BONE_MATRIX_SSBO_BINDING = 0;
    
    /**
     * 开始自定义渲染前调用
     * 保存当前 OpenGL 状态，防止与 Iris 冲突
     */
    public static void beginCustomRendering() {
        // 保存当前绑定的 VAO
        savedVao = GL46C.glGetInteger(GL46C.GL_VERTEX_ARRAY_BINDING);
        // 保存当前绑定的缓冲区
        savedArrayBuffer = GL46C.glGetInteger(GL46C.GL_ARRAY_BUFFER_BINDING);
        savedElementBuffer = GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        // 保存当前着色器程序
        savedProgram = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        // 保存当前活动纹理单元
        savedActiveTexture = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
    }
    
    /**
     * 自定义渲染结束后调用
     * 恢复 OpenGL 状态，确保 Iris 能正常工作
     */
    public static void endCustomRendering() {
        // === 关键：使用 GlStateManager 解绑着色器，让 Iris 能正确拦截 ===
        GlStateManager._glUseProgram(0);
        
        // 解绑 SSBO（GPU 蒙皮使用的骨骼矩阵缓冲区）
        GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BONE_MATRIX_SSBO_BINDING, 0);
        
        // 解绑 VAO
        GlStateManager._glBindVertexArray(0);
        
        // 解绑缓冲区
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, 0);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, 0);
        
        // 恢复活动纹理单元
        RenderSystem.activeTexture(GL46C.GL_TEXTURE0);
        
        // 重置 Minecraft 的缓冲区上传器状态
        BufferUploader.reset();
        
        // 清除当前着色器引用
        if (RenderSystem.getShader() != null) {
            RenderSystem.getShader().clear();
        }
    }
    
    /**
     * GPU 蒙皮专用：完全恢复状态
     * 在使用自定义着色器后调用
     */
    public static void endGpuSkinningRendering() {
        // 使用 GlStateManager 解绑着色器（Iris 会拦截这个调用）
        GlStateManager._glUseProgram(0);
        
        // 解绑 SSBO 绑定点 0-3（防止与 Iris 的 SSBO 冲突）
        for (int i = 0; i < 4; i++) {
            GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, i, 0);
        }
        
        // 解绑 VAO
        GlStateManager._glBindVertexArray(0);
        
        // 解绑缓冲区
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, 0);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, 0);
        
        // 恢复纹理单元
        RenderSystem.activeTexture(GL46C.GL_TEXTURE0);
        RenderSystem.bindTexture(0);
        
        // 重置缓冲区上传器
        BufferUploader.reset();
        
        // 清除着色器引用
        if (RenderSystem.getShader() != null) {
            RenderSystem.getShader().clear();
        }
    }
    
    /**
     * 完全恢复到之前保存的状态
     */
    public static void restoreFullState() {
        // 恢复 VAO
        GlStateManager._glBindVertexArray(savedVao);
        // 恢复缓冲区
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, savedArrayBuffer);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, savedElementBuffer);
        // 使用 GlStateManager 恢复着色器程序
        GlStateManager._glUseProgram(savedProgram);
        // 恢复活动纹理单元
        GL46C.glActiveTexture(savedActiveTexture);
    }
}
