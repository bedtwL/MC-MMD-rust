//! VMD 动画控制器

use std::collections::HashMap;
use glam::{Vec3, Quat};

use crate::skeleton::BoneManager;
use crate::morph::MorphManager;

use super::{VmdFile, BoneKeyframe, MorphKeyframe};

/// 骨骼动画控制器
struct BoneController {
    bone_index: usize,
    keyframes: Vec<BoneKeyframe>,
}

impl BoneController {
    fn new(bone_index: usize) -> Self {
        Self {
            bone_index,
            keyframes: Vec::new(),
        }
    }
    
    fn add_keyframe(&mut self, keyframe: BoneKeyframe) {
        self.keyframes.push(keyframe);
    }
    
    fn sort_keyframes(&mut self) {
        self.keyframes.sort_by_key(|k| k.frame);
    }
    
    /// 评估动画
    fn evaluate(&self, frame: f32, weight: f32) -> (Vec3, Quat) {
        if self.keyframes.is_empty() {
            return (Vec3::ZERO, Quat::IDENTITY);
        }
        
        // 查找关键帧
        let frame_u32 = frame as u32;
        let mut prev_idx = 0;
        let mut next_idx = 0;
        
        for (i, kf) in self.keyframes.iter().enumerate() {
            if kf.frame <= frame_u32 {
                prev_idx = i;
            }
            if kf.frame >= frame_u32 {
                next_idx = i;
                break;
            }
            next_idx = i;
        }
        
        let prev = &self.keyframes[prev_idx];
        let next = &self.keyframes[next_idx];
        
        if prev_idx == next_idx || prev.frame == next.frame {
            return (prev.translation * weight, Quat::IDENTITY.slerp(prev.rotation, weight));
        }
        
        // 计算插值因子
        let t = (frame - prev.frame as f32) / (next.frame - prev.frame) as f32;
        
        // 使用贝塞尔曲线插值
        let tx = prev.interp_x.evaluate(t);
        let ty = prev.interp_y.evaluate(t);
        let tz = prev.interp_z.evaluate(t);
        let tr = prev.interp_rotation.evaluate(t);
        
        let translation = Vec3::new(
            lerp(prev.translation.x, next.translation.x, tx),
            lerp(prev.translation.y, next.translation.y, ty),
            lerp(prev.translation.z, next.translation.z, tz),
        ) * weight;
        
        let rotation = Quat::IDENTITY.slerp(prev.rotation.slerp(next.rotation, tr), weight);
        
        (translation, rotation)
    }
}

/// Morph 动画控制器
struct MorphController {
    morph_index: usize,
    keyframes: Vec<MorphKeyframe>,
}

impl MorphController {
    fn new(morph_index: usize) -> Self {
        Self {
            morph_index,
            keyframes: Vec::new(),
        }
    }
    
    fn add_keyframe(&mut self, keyframe: MorphKeyframe) {
        self.keyframes.push(keyframe);
    }
    
    fn sort_keyframes(&mut self) {
        self.keyframes.sort_by_key(|k| k.frame);
    }
    
    /// 评估 Morph 权重
    fn evaluate(&self, frame: f32, weight: f32) -> f32 {
        if self.keyframes.is_empty() {
            return 0.0;
        }
        
        let frame_u32 = frame as u32;
        let mut prev_idx = 0;
        let mut next_idx = 0;
        
        for (i, kf) in self.keyframes.iter().enumerate() {
            if kf.frame <= frame_u32 {
                prev_idx = i;
            }
            if kf.frame >= frame_u32 {
                next_idx = i;
                break;
            }
            next_idx = i;
        }
        
        let prev = &self.keyframes[prev_idx];
        let next = &self.keyframes[next_idx];
        
        if prev_idx == next_idx || prev.frame == next.frame {
            return prev.weight * weight;
        }
        
        let t = (frame - prev.frame as f32) / (next.frame - prev.frame) as f32;
        lerp(prev.weight, next.weight, t) * weight
    }
}

/// VMD 动画
pub struct VmdAnimation {
    bone_controllers: Vec<BoneController>,
    morph_controllers: Vec<MorphController>,
    max_frame: u32,
}

impl VmdAnimation {
    /// 从 VMD 文件创建动画
    pub fn from_vmd(vmd: &VmdFile, bone_manager: &BoneManager, morph_manager: &MorphManager) -> Self {
        let mut bone_map: HashMap<usize, BoneController> = HashMap::new();
        let mut morph_map: HashMap<usize, MorphController> = HashMap::new();
        let mut max_frame = 0u32;
        let mut matched_count = 0usize;
        let mut unmatched_names: Vec<String> = Vec::new();
        
        // 处理骨骼关键帧
        for (bone_name, keyframe) in &vmd.bone_keyframes {
            if let Some(bone_idx) = bone_manager.find_bone_by_name(bone_name) {
                let controller = bone_map.entry(bone_idx).or_insert_with(|| BoneController::new(bone_idx));
                controller.add_keyframe(keyframe.clone());
                max_frame = max_frame.max(keyframe.frame);
                matched_count += 1;
            } else if !unmatched_names.contains(bone_name) {
                unmatched_names.push(bone_name.clone());
            }
        }
        
        // 调试日志：显示匹配情况
        log::info!("VmdAnimation: 匹配 {} 个骨骼关键帧, {} 个骨骼控制器", 
            matched_count, bone_map.len());
        if !unmatched_names.is_empty() && unmatched_names.len() <= 10 {
            log::warn!("VmdAnimation: 未匹配骨骼: {:?}", unmatched_names);
        }
        
        // 处理 Morph 关键帧
        for (morph_name, keyframe) in &vmd.morph_keyframes {
            if let Some(morph_idx) = morph_manager.find_morph_by_name(morph_name) {
                let controller = morph_map.entry(morph_idx).or_insert_with(|| MorphController::new(morph_idx));
                controller.add_keyframe(keyframe.clone());
                max_frame = max_frame.max(keyframe.frame);
            }
        }
        
        // 排序关键帧
        let mut bone_controllers: Vec<BoneController> = bone_map.into_values().collect();
        for controller in &mut bone_controllers {
            controller.sort_keyframes();
        }
        
        let mut morph_controllers: Vec<MorphController> = morph_map.into_values().collect();
        for controller in &mut morph_controllers {
            controller.sort_keyframes();
        }
        
        Self {
            bone_controllers,
            morph_controllers,
            max_frame,
        }
    }
    
    /// 获取最大帧数
    pub fn max_frame(&self) -> u32 {
        self.max_frame
    }
    
    /// 评估动画
    pub fn evaluate(&self, frame: f32, bone_manager: &mut BoneManager, morph_manager: &mut MorphManager) {
        self.evaluate_with_weight(frame, 1.0, bone_manager, morph_manager);
    }
    
    /// 带权重评估动画
    pub fn evaluate_with_weight(&self, frame: f32, weight: f32, bone_manager: &mut BoneManager, morph_manager: &mut MorphManager) {
        // 调试日志（仅首次）
        static ONCE: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
        let should_log = !ONCE.swap(true, std::sync::atomic::Ordering::Relaxed);
        
        if should_log {
            log::info!("VmdAnimation::evaluate: frame={:.1}, weight={:.3}, bone_controllers={}", 
                frame, weight, self.bone_controllers.len());
        }
        
        // 评估骨骼动画
        for controller in &self.bone_controllers {
            let (translation, rotation) = controller.evaluate(frame, weight);
            bone_manager.set_bone_translation(controller.bone_index, translation);
            bone_manager.set_bone_rotation(controller.bone_index, rotation);
        }
        
        // 评估 Morph 动画
        for controller in &self.morph_controllers {
            let morph_weight = controller.evaluate(frame, weight);
            morph_manager.set_morph_weight(controller.morph_index, morph_weight);
        }
    }
}

fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}
