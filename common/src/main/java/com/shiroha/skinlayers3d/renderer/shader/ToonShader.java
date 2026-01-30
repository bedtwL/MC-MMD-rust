package com.shiroha.skinlayers3d.renderer.shader;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL46C;

import java.nio.FloatBuffer;

/**
 * 3渲2（卡通渲染/Toon Shading）着色器
 * 
 * 实现原理：
 * - 色阶化光照：将连续的漫反射光照离散化为 2-3 个色阶
 * - 描边效果：使用背面扩张法绘制轮廓线
 * - 边缘光（Rim Light）：视角边缘高亮增强立体感
 * - 高光色阶化：可选的卡通式高光
 * 
 * 渲染流程：
 * 1. 第一遍：正面剔除，绘制放大的纯色模型作为描边
 * 2. 第二遍：背面剔除，绘制卡通着色的模型
 */
public class ToonShader {
    private static final Logger logger = LogManager.getLogger();
    
    // 主着色器程序（卡通着色）
    private int mainProgram = 0;
    // 描边着色器程序
    private int outlineProgram = 0;
    private int boneMatrixSSBO = 0;
    private boolean initialized = false;
    
    // 主着色器 Uniform locations
    private int projMatLocation = -1;
    private int modelViewMatLocation = -1;
    private int sampler0Location = -1;
    private int lightIntensityLocation = -1;
    private int toonLevelsLocation = -1;
    private int rimPowerLocation = -1;
    private int rimIntensityLocation = -1;
    private int shadowColorLocation = -1;
    private int specularPowerLocation = -1;
    private int specularIntensityLocation = -1;
    
    // 描边着色器 Uniform locations
    private int outlineProjMatLocation = -1;
    private int outlineModelViewMatLocation = -1;
    private int outlineWidthLocation = -1;
    private int outlineColorLocation = -1;
    
    // Attribute locations（两个着色器共用）
    private int positionLocation = -1;
    private int normalLocation = -1;
    private int uv0Location = -1;
    private int boneIndicesLocation = -1;
    private int boneWeightsLocation = -1;
    
    // 描边着色器的 attribute locations
    private int outlinePositionLocation = -1;
    private int outlineNormalLocation = -1;
    private int outlineBoneIndicesLocation = -1;
    private int outlineBoneWeightsLocation = -1;
    
    public static int MAX_BONES = 2048;
    
    // ==================== 主着色器（卡通着色） ====================
    private static final String MAIN_VERTEX_SHADER = """
        #version 460 core
        
        layout(location = 0) in vec3 Position;
        layout(location = 1) in vec3 Normal;
        layout(location = 2) in vec2 UV0;
        layout(location = 3) in ivec4 BoneIndices;
        layout(location = 4) in vec4 BoneWeights;
        
        layout(std430, binding = 0) readonly buffer BoneMatrices {
            mat4 boneMatrices[2048];
        };
        
        uniform mat4 ProjMat;
        uniform mat4 ModelViewMat;
        
        out vec2 texCoord0;
        out vec3 viewNormal;
        out vec3 viewPos;
        
        void main() {
            // 计算蒙皮矩阵
            float totalWeight = 0.0;
            for (int i = 0; i < 4; i++) {
                int boneIdx = BoneIndices[i];
                if (boneIdx >= 0 && boneIdx < 2048) {
                    totalWeight += BoneWeights[i];
                }
            }
            
            mat4 skinMatrix = mat4(0.0);
            if (totalWeight > 0.001) {
                float invWeight = 1.0 / totalWeight;
                for (int i = 0; i < 4; i++) {
                    int boneIdx = BoneIndices[i];
                    float weight = BoneWeights[i];
                    if (boneIdx >= 0 && boneIdx < 2048 && weight > 0.0) {
                        skinMatrix += boneMatrices[boneIdx] * (weight * invWeight);
                    }
                }
            } else {
                skinMatrix = mat4(1.0);
            }
            
            vec4 skinnedPos = skinMatrix * vec4(Position, 1.0);
            vec4 viewPosition = ModelViewMat * skinnedPos;
            
            mat3 normalMatrix = mat3(ModelViewMat) * mat3(skinMatrix);
            vec3 skinnedNormal = normalMatrix * Normal;
            
            gl_Position = ProjMat * viewPosition;
            texCoord0 = UV0;
            viewNormal = normalize(skinnedNormal);
            viewPos = viewPosition.xyz;
        }
        """;
    
    private static final String MAIN_FRAGMENT_SHADER = """
        #version 460 core
        
        in vec2 texCoord0;
        in vec3 viewNormal;
        in vec3 viewPos;
        
        uniform sampler2D Sampler0;
        uniform float LightIntensity;
        uniform int ToonLevels;          // 色阶数量（2-5）
        uniform float RimPower;          // 边缘光锐度
        uniform float RimIntensity;      // 边缘光强度
        uniform vec3 ShadowColor;        // 阴影色调
        uniform float SpecularPower;     // 高光锐度
        uniform float SpecularIntensity; // 高光强度
        
        out vec4 fragColor;
        
        // 色阶化函数
        float toonify(float value, int levels) {
            return floor(value * float(levels) + 0.5) / float(levels);
        }
        
        void main() {
            vec4 texColor = texture(Sampler0, texCoord0);
            vec3 normal = normalize(viewNormal);
            
            // 主光源方向（视图空间）
            vec3 lightDir = normalize(vec3(0.2, 1.0, -0.7));
            vec3 viewDir = normalize(-viewPos);
            
            // === 漫反射（色阶化） ===
            float NdotL = dot(normal, lightDir);
            float diffuse = max(NdotL, 0.0);
            float toonDiffuse = toonify(diffuse, ToonLevels);
            
            // === 高光（色阶化） ===
            vec3 halfDir = normalize(lightDir + viewDir);
            float NdotH = max(dot(normal, halfDir), 0.0);
            float specular = pow(NdotH, SpecularPower);
            // 卡通高光：硬边界
            float toonSpecular = step(0.5, specular) * SpecularIntensity;
            
            // === 边缘光（Rim Light） ===
            float rim = 1.0 - max(dot(viewDir, normal), 0.0);
            rim = pow(rim, RimPower) * RimIntensity;
            
            // === 阴影混合 ===
            // 亮部使用原色，暗部混合阴影色
            vec3 litColor = texColor.rgb;
            vec3 shadowedColor = texColor.rgb * ShadowColor;
            vec3 baseColor = mix(shadowedColor, litColor, toonDiffuse);
            
            // === 最终合成 ===
            vec3 finalColor = baseColor * LightIntensity;
            finalColor += toonSpecular * vec3(1.0);  // 高光（白色）
            finalColor += rim * texColor.rgb;         // 边缘光
            
            // 环境光保底
            float ambient = 0.15;
            finalColor = max(finalColor, texColor.rgb * ambient);
            
            fragColor = vec4(finalColor, texColor.a);
            
            if (fragColor.a < 0.004) discard;
        }
        """;
    
    // ==================== 描边着色器 ====================
    private static final String OUTLINE_VERTEX_SHADER = """
        #version 460 core
        
        layout(location = 0) in vec3 Position;
        layout(location = 1) in vec3 Normal;
        layout(location = 3) in ivec4 BoneIndices;
        layout(location = 4) in vec4 BoneWeights;
        
        layout(std430, binding = 0) readonly buffer BoneMatrices {
            mat4 boneMatrices[2048];
        };
        
        uniform mat4 ProjMat;
        uniform mat4 ModelViewMat;
        uniform float OutlineWidth;
        
        out vec3 viewNormal;
        out vec3 viewPos;
        
        void main() {
            // 计算蒙皮矩阵
            float totalWeight = 0.0;
            for (int i = 0; i < 4; i++) {
                int boneIdx = BoneIndices[i];
                if (boneIdx >= 0 && boneIdx < 2048) {
                    totalWeight += BoneWeights[i];
                }
            }
            
            mat4 skinMatrix = mat4(0.0);
            if (totalWeight > 0.001) {
                float invWeight = 1.0 / totalWeight;
                for (int i = 0; i < 4; i++) {
                    int boneIdx = BoneIndices[i];
                    float weight = BoneWeights[i];
                    if (boneIdx >= 0 && boneIdx < 2048 && weight > 0.0) {
                        skinMatrix += boneMatrices[boneIdx] * (weight * invWeight);
                    }
                }
            } else {
                skinMatrix = mat4(1.0);
            }
            
            vec4 skinnedPos = skinMatrix * vec4(Position, 1.0);
            mat3 normalMatrix = mat3(ModelViewMat) * mat3(skinMatrix);
            vec3 skinnedNormal = normalize(normalMatrix * Normal);
            
            // 沿法线方向扩张顶点（背面扩张法）
            vec4 vPos = ModelViewMat * skinnedPos;
            vPos.xyz += skinnedNormal * OutlineWidth;
            
            gl_Position = ProjMat * vPos;
            viewNormal = skinnedNormal;
            viewPos = vPos.xyz;
        }
        """;
    
    private static final String OUTLINE_FRAGMENT_SHADER = """
        #version 460 core
        
        in vec3 viewNormal;
        in vec3 viewPos;
        
        uniform vec3 OutlineColor;
        
        out vec4 fragColor;
        
        void main() {
            // 计算视线方向（从片段指向相机）
            vec3 viewDir = normalize(-viewPos);
            vec3 normal = normalize(viewNormal);
            
            // 法线朝向相机的区域不应该有描边（凹陷区域）
            // 当法线与视线夹角小于90度时，说明是正面，不应画描边
            float NdotV = dot(normal, viewDir);
            if (NdotV > 0.1) {
                discard;
            }
            
            fragColor = vec4(OutlineColor, 1.0);
        }
        """;
    
    public boolean init() {
        if (initialized) return true;
        
        try {
            // 编译主着色器
            mainProgram = compileProgram(MAIN_VERTEX_SHADER, MAIN_FRAGMENT_SHADER, "Toon主着色器");
            if (mainProgram == 0) return false;
            
            // 编译描边着色器
            outlineProgram = compileProgram(OUTLINE_VERTEX_SHADER, OUTLINE_FRAGMENT_SHADER, "Toon描边着色器");
            if (outlineProgram == 0) {
                GL46C.glDeleteProgram(mainProgram);
                mainProgram = 0;
                return false;
            }
            
            // 获取主着色器 uniform 位置
            projMatLocation = GL46C.glGetUniformLocation(mainProgram, "ProjMat");
            modelViewMatLocation = GL46C.glGetUniformLocation(mainProgram, "ModelViewMat");
            sampler0Location = GL46C.glGetUniformLocation(mainProgram, "Sampler0");
            lightIntensityLocation = GL46C.glGetUniformLocation(mainProgram, "LightIntensity");
            toonLevelsLocation = GL46C.glGetUniformLocation(mainProgram, "ToonLevels");
            rimPowerLocation = GL46C.glGetUniformLocation(mainProgram, "RimPower");
            rimIntensityLocation = GL46C.glGetUniformLocation(mainProgram, "RimIntensity");
            shadowColorLocation = GL46C.glGetUniformLocation(mainProgram, "ShadowColor");
            specularPowerLocation = GL46C.glGetUniformLocation(mainProgram, "SpecularPower");
            specularIntensityLocation = GL46C.glGetUniformLocation(mainProgram, "SpecularIntensity");
            
            // 获取主着色器 attribute 位置
            positionLocation = GL46C.glGetAttribLocation(mainProgram, "Position");
            normalLocation = GL46C.glGetAttribLocation(mainProgram, "Normal");
            uv0Location = GL46C.glGetAttribLocation(mainProgram, "UV0");
            boneIndicesLocation = GL46C.glGetAttribLocation(mainProgram, "BoneIndices");
            boneWeightsLocation = GL46C.glGetAttribLocation(mainProgram, "BoneWeights");
            
            // 获取描边着色器 uniform 位置
            outlineProjMatLocation = GL46C.glGetUniformLocation(outlineProgram, "ProjMat");
            outlineModelViewMatLocation = GL46C.glGetUniformLocation(outlineProgram, "ModelViewMat");
            outlineWidthLocation = GL46C.glGetUniformLocation(outlineProgram, "OutlineWidth");
            outlineColorLocation = GL46C.glGetUniformLocation(outlineProgram, "OutlineColor");
            
            // 获取描边着色器 attribute 位置
            outlinePositionLocation = GL46C.glGetAttribLocation(outlineProgram, "Position");
            outlineNormalLocation = GL46C.glGetAttribLocation(outlineProgram, "Normal");
            outlineBoneIndicesLocation = GL46C.glGetAttribLocation(outlineProgram, "BoneIndices");
            outlineBoneWeightsLocation = GL46C.glGetAttribLocation(outlineProgram, "BoneWeights");
            
            // 创建骨骼矩阵 SSBO
            boneMatrixSSBO = GL46C.glGenBuffers();
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, boneMatrixSSBO);
            GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, MAX_BONES * 64, GL46C.GL_DYNAMIC_DRAW);
            GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, 0, boneMatrixSSBO);
            
            initialized = true;
            logger.info("Toon着色器初始化成功");
            return true;
            
        } catch (Exception e) {
            logger.error("Toon着色器初始化异常", e);
            return false;
        }
    }
    
    private int compileProgram(String vertexSource, String fragmentSource, String name) {
        int vertexShader = GL46C.glCreateShader(GL46C.GL_VERTEX_SHADER);
        GL46C.glShaderSource(vertexShader, vertexSource);
        GL46C.glCompileShader(vertexShader);
        
        if (GL46C.glGetShaderi(vertexShader, GL46C.GL_COMPILE_STATUS) == GL46C.GL_FALSE) {
            String log = GL46C.glGetShaderInfoLog(vertexShader, 8192).trim();
            logger.error("{}顶点着色器编译失败: {}", name, log);
            GL46C.glDeleteShader(vertexShader);
            return 0;
        }
        
        int fragShader = GL46C.glCreateShader(GL46C.GL_FRAGMENT_SHADER);
        GL46C.glShaderSource(fragShader, fragmentSource);
        GL46C.glCompileShader(fragShader);
        
        if (GL46C.glGetShaderi(fragShader, GL46C.GL_COMPILE_STATUS) == GL46C.GL_FALSE) {
            String log = GL46C.glGetShaderInfoLog(fragShader, 8192).trim();
            logger.error("{}片段着色器编译失败: {}", name, log);
            GL46C.glDeleteShader(vertexShader);
            GL46C.glDeleteShader(fragShader);
            return 0;
        }
        
        int program = GL46C.glCreateProgram();
        GL46C.glAttachShader(program, vertexShader);
        GL46C.glAttachShader(program, fragShader);
        GL46C.glLinkProgram(program);
        
        if (GL46C.glGetProgrami(program, GL46C.GL_LINK_STATUS) == GL46C.GL_FALSE) {
            String log = GL46C.glGetProgramInfoLog(program, 8192);
            logger.error("{}链接失败: {}", name, log);
            GL46C.glDeleteProgram(program);
            GL46C.glDeleteShader(vertexShader);
            GL46C.glDeleteShader(fragShader);
            return 0;
        }
        
        GL46C.glDeleteShader(vertexShader);
        GL46C.glDeleteShader(fragShader);
        return program;
    }
    
    public void uploadBoneMatrices(FloatBuffer matrices, int boneCount) {
        if (!initialized || boneMatrixSSBO == 0) return;
        
        GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, 0, boneMatrixSSBO);
        GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, boneMatrixSSBO);
        
        int actualBones = Math.min(boneCount, MAX_BONES);
        matrices.limit(actualBones * 16);
        matrices.position(0);
        GL46C.glBufferSubData(GL46C.GL_SHADER_STORAGE_BUFFER, 0, matrices);
    }
    
    // ==================== 主着色器方法 ====================
    
    public void useMain() {
        if (mainProgram > 0) {
            GL46C.glUseProgram(mainProgram);
        }
    }
    
    public void setProjectionMatrix(FloatBuffer matrix) {
        if (projMatLocation >= 0) {
            matrix.position(0);
            GL46C.glUniformMatrix4fv(projMatLocation, false, matrix);
        }
    }
    
    public void setModelViewMatrix(FloatBuffer matrix) {
        if (modelViewMatLocation >= 0) {
            matrix.position(0);
            GL46C.glUniformMatrix4fv(modelViewMatLocation, false, matrix);
        }
    }
    
    public void setSampler0(int textureUnit) {
        if (sampler0Location >= 0) {
            GL46C.glUniform1i(sampler0Location, textureUnit);
        }
    }
    
    public void setLightIntensity(float intensity) {
        if (lightIntensityLocation >= 0) {
            GL46C.glUniform1f(lightIntensityLocation, intensity);
        }
    }
    
    public void setToonLevels(int levels) {
        if (toonLevelsLocation >= 0) {
            GL46C.glUniform1i(toonLevelsLocation, Math.max(2, Math.min(5, levels)));
        }
    }
    
    public void setRimLight(float power, float intensity) {
        if (rimPowerLocation >= 0) {
            GL46C.glUniform1f(rimPowerLocation, power);
        }
        if (rimIntensityLocation >= 0) {
            GL46C.glUniform1f(rimIntensityLocation, intensity);
        }
    }
    
    public void setShadowColor(float r, float g, float b) {
        if (shadowColorLocation >= 0) {
            GL46C.glUniform3f(shadowColorLocation, r, g, b);
        }
    }
    
    public void setSpecular(float power, float intensity) {
        if (specularPowerLocation >= 0) {
            GL46C.glUniform1f(specularPowerLocation, power);
        }
        if (specularIntensityLocation >= 0) {
            GL46C.glUniform1f(specularIntensityLocation, intensity);
        }
    }
    
    // ==================== 描边着色器方法 ====================
    
    public void useOutline() {
        if (outlineProgram > 0) {
            GL46C.glUseProgram(outlineProgram);
        }
    }
    
    public void setOutlineProjectionMatrix(FloatBuffer matrix) {
        if (outlineProjMatLocation >= 0) {
            matrix.position(0);
            GL46C.glUniformMatrix4fv(outlineProjMatLocation, false, matrix);
        }
    }
    
    public void setOutlineModelViewMatrix(FloatBuffer matrix) {
        if (outlineModelViewMatLocation >= 0) {
            matrix.position(0);
            GL46C.glUniformMatrix4fv(outlineModelViewMatLocation, false, matrix);
        }
    }
    
    public void setOutlineWidth(float width) {
        if (outlineWidthLocation >= 0) {
            GL46C.glUniform1f(outlineWidthLocation, width);
        }
    }
    
    public void setOutlineColor(float r, float g, float b) {
        if (outlineColorLocation >= 0) {
            GL46C.glUniform3f(outlineColorLocation, r, g, b);
        }
    }
    
    // ==================== Getters ====================
    
    public int getMainProgram() { return mainProgram; }
    public int getOutlineProgram() { return outlineProgram; }
    
    public int getPositionLocation() { return positionLocation; }
    public int getNormalLocation() { return normalLocation; }
    public int getUv0Location() { return uv0Location; }
    public int getBoneIndicesLocation() { return boneIndicesLocation; }
    public int getBoneWeightsLocation() { return boneWeightsLocation; }
    
    public int getOutlinePositionLocation() { return outlinePositionLocation; }
    public int getOutlineNormalLocation() { return outlineNormalLocation; }
    public int getOutlineBoneIndicesLocation() { return outlineBoneIndicesLocation; }
    public int getOutlineBoneWeightsLocation() { return outlineBoneWeightsLocation; }
    
    public boolean isInitialized() { return initialized; }
    
    public void cleanup() {
        if (mainProgram > 0) {
            GL46C.glDeleteProgram(mainProgram);
            mainProgram = 0;
        }
        if (outlineProgram > 0) {
            GL46C.glDeleteProgram(outlineProgram);
            outlineProgram = 0;
        }
        if (boneMatrixSSBO > 0) {
            GL46C.glDeleteBuffers(boneMatrixSSBO);
            boneMatrixSSBO = 0;
        }
        initialized = false;
    }
}
