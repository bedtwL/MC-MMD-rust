//! 动画层系统 - 支持多轨并行动画
//!
//! 实现 MMD 标准的多层动画混合：
//! - 每层有独立的时间轴、权重和播放状态
//! - 支持淡入淡出过渡
//! - 层间动画通过权重混合


use std::sync::Arc;
use std::time::Instant;

use crate::skeleton::BoneManager;
use crate::morph::MorphManager;

use super::VmdAnimation;

/// 动画层状态
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AnimationLayerState {
    /// 停止状态
    Stopped,
    /// 播放中
    Playing,
    /// 暂停
    Paused,
    /// 淡入中
    FadingIn,
    /// 淡出中
    FadingOut,
}

/// 动画层配置
#[derive(Clone, Debug)]
pub struct AnimationLayerConfig {
    /// 层权重（0.0 - 1.0）
    pub weight: f32,
    /// 播放速度倍率
    pub speed: f32,
    /// 是否循环播放
    pub loop_playback: bool,
    /// 淡入时间（秒）
    pub fade_in_time: f32,
    /// 淡出时间（秒）
    pub fade_out_time: f32,
}

impl Default for AnimationLayerConfig {
    fn default() -> Self {
        Self {
            weight: 1.0,
            speed: 1.0,
            loop_playback: true,
            fade_in_time: 0.0,
            fade_out_time: 0.0,
        }
    }
}

/// 单个动画层
pub struct AnimationLayer {
    /// 层ID
    pub id: usize,
    /// 层名称
    pub name: String,
    /// 当前动画
    animation: Option<Arc<VmdAnimation>>,
    /// 当前播放帧
    current_frame: f32,
    /// 当前状态
    state: AnimationLayerState,
    /// 配置
    config: AnimationLayerConfig,
    /// 实际权重（考虑淡入淡出）
    effective_weight: f32,
    /// 淡入淡出进度（0.0 - 1.0）
    fade_progress: f32,
    /// 最后更新时间
    last_update_time: Option<Instant>,
    /// 是否启用
    enabled: bool,
}

impl AnimationLayer {
    /// 创建新层
    pub fn new(id: usize, name: impl Into<String>) -> Self {
        Self {
            id,
            name: name.into(),
            animation: None,
            current_frame: 0.0,
            state: AnimationLayerState::Stopped,
            config: AnimationLayerConfig::default(),
            effective_weight: 0.0,
            fade_progress: 0.0,
            last_update_time: None,
            enabled: true,
        }
    }

    /// 设置动画
    pub fn set_animation(&mut self, animation: Option<Arc<VmdAnimation>>) {
        self.animation = animation;
        self.current_frame = 0.0;
        self.state = AnimationLayerState::Stopped;
        self.effective_weight = 0.0;
        self.fade_progress = 0.0;
    }

    /// 播放动画
    pub fn play(&mut self) {
        if self.animation.is_some() {
            if self.config.fade_in_time > 0.0 {
                self.state = AnimationLayerState::FadingIn;
                self.fade_progress = 0.0;
            } else {
                self.state = AnimationLayerState::Playing;
                self.effective_weight = self.config.weight;
            }
            self.last_update_time = Some(Instant::now());
        }
    }

    /// 暂停动画
    pub fn pause(&mut self) {
        if self.state == AnimationLayerState::Playing || self.state == AnimationLayerState::FadingIn {
            self.state = AnimationLayerState::Paused;
        }
    }

    /// 恢复播放
    pub fn resume(&mut self) {
        if self.state == AnimationLayerState::Paused {
            self.state = AnimationLayerState::Playing;
            self.last_update_time = Some(Instant::now());
        }
    }

    /// 停止动画
    pub fn stop(&mut self) {
        if self.config.fade_out_time > 0.0 && self.effective_weight > 0.0 {
            self.state = AnimationLayerState::FadingOut;
            self.fade_progress = 1.0;
        } else {
            self.state = AnimationLayerState::Stopped;
            self.effective_weight = 0.0;
            self.current_frame = 0.0;
        }
    }

    /// 立即重置
    pub fn reset(&mut self) {
        self.current_frame = 0.0;
        self.state = AnimationLayerState::Stopped;
        self.effective_weight = 0.0;
        self.fade_progress = 0.0;
    }

    /// 跳转到指定帧
    pub fn seek_to(&mut self, frame: f32) {
        self.current_frame = frame.max(0.0);
    }

    /// 设置权重
    pub fn set_weight(&mut self, weight: f32) {
        self.config.weight = weight.clamp(0.0, 1.0);
        // 如果不是在淡入淡出过程中，直接更新有效权重
        if self.state != AnimationLayerState::FadingIn && self.state != AnimationLayerState::FadingOut {
            self.effective_weight = self.config.weight;
        }
    }

    /// 设置播放速度
    pub fn set_speed(&mut self, speed: f32) {
        self.config.speed = speed.max(0.0);
    }

    /// 更新层状态
    pub fn update(&mut self, delta_time: f32) -> bool {
        if !self.enabled || self.animation.is_none() {
            return false;
        }

        let dt = delta_time * self.config.speed;

        match self.state {
            AnimationLayerState::Playing => {
                self.update_frame(dt);
                true
            }
            AnimationLayerState::FadingIn => {
                self.update_fade_in(dt);
                self.update_frame(dt);
                true
            }
            AnimationLayerState::FadingOut => {
                self.update_fade_out(dt);
                self.update_frame(dt);
                true
            }
            AnimationLayerState::Paused => {
                // 暂停时只更新时间戳，不推进帧
                self.last_update_time = Some(Instant::now());
                true
            }
            AnimationLayerState::Stopped => false,
        }
    }

    /// 更新帧位置
    fn update_frame(&mut self, dt: f32) {
        if let Some(ref anim) = self.animation {
            let max_frame = anim.max_frame() as f32;
            
            if max_frame > 0.0 {
                self.current_frame += dt * 30.0; // 假设30fps
                
                if self.current_frame > max_frame {
                    if self.config.loop_playback {
                        self.current_frame = self.current_frame % max_frame;
                    } else {
                        self.current_frame = max_frame;
                        self.state = AnimationLayerState::Stopped;
                    }
                }
            }
        }
    }

    /// 更新淡入
    fn update_fade_in(&mut self, dt: f32) {
        if self.config.fade_in_time > 0.0 {
            self.fade_progress += dt / self.config.fade_in_time;
            if self.fade_progress >= 1.0 {
                self.fade_progress = 1.0;
                self.state = AnimationLayerState::Playing;
            }
        } else {
            self.fade_progress = 1.0;
            self.state = AnimationLayerState::Playing;
        }
        self.effective_weight = self.config.weight * self.fade_progress;
    }

    /// 更新淡出
    fn update_fade_out(&mut self, dt: f32) {
        if self.config.fade_out_time > 0.0 {
            self.fade_progress -= dt / self.config.fade_out_time;
            if self.fade_progress <= 0.0 {
                self.fade_progress = 0.0;
                self.state = AnimationLayerState::Stopped;
                self.current_frame = 0.0;
            }
        } else {
            self.fade_progress = 0.0;
            self.state = AnimationLayerState::Stopped;
            self.current_frame = 0.0;
        }
        self.effective_weight = self.config.weight * self.fade_progress;
    }

    /// 评估动画并应用到骨骼管理器
    pub fn evaluate(&self, bone_manager: &mut BoneManager, morph_manager: &mut MorphManager) {
        if let Some(ref animation) = self.animation {
            if self.effective_weight > 0.001 {
                animation.evaluate_with_weight(
                    self.current_frame,
                    self.effective_weight,
                    bone_manager,
                    morph_manager,
                );
            }
        }
    }

    /// 获取当前状态
    pub fn state(&self) -> AnimationLayerState {
        self.state.clone()
    }

    /// 获取当前帧
    pub fn current_frame(&self) -> f32 {
        self.current_frame
    }

    /// 获取有效权重
    pub fn effective_weight(&self) -> f32 {
        self.effective_weight
    }

    /// 获取配置权重
    pub fn weight(&self) -> f32 {
        self.config.weight
    }

    /// 是否启用
    pub fn is_enabled(&self) -> bool {
        self.enabled
    }

    /// 设置启用状态
    pub fn set_enabled(&mut self, enabled: bool) {
        self.enabled = enabled;
    }

    /// 获取动画最大帧数
    pub fn max_frame(&self) -> u32 {
        self.animation.as_ref().map(|a| a.max_frame()).unwrap_or(0)
    }

    /// 是否正在播放
    pub fn is_playing(&self) -> bool {
        matches!(self.state, AnimationLayerState::Playing | AnimationLayerState::FadingIn)
    }
}

/// 动画层管理器
pub struct AnimationLayerManager {
    /// 所有层
    layers: Vec<AnimationLayer>,
}

impl AnimationLayerManager {
    /// 创建层管理器
    pub fn new(max_layers: usize) -> Self {
        let mut layers = Vec::with_capacity(max_layers);
        for i in 0..max_layers {
            layers.push(AnimationLayer::new(i, format!("Layer_{}", i)));
        }
        
        Self {
            layers,
        }
    }

    /// 获取层（可变）
    pub fn get_layer_mut(&mut self, layer_id: usize) -> Option<&mut AnimationLayer> {
        self.layers.get_mut(layer_id)
    }

    /// 获取层（只读）
    pub fn get_layer(&self, layer_id: usize) -> Option<&AnimationLayer> {
        self.layers.get(layer_id)
    }

    /// 设置层的动画
    pub fn set_layer_animation(&mut self, layer_id: usize, animation: Option<Arc<VmdAnimation>>) {
        if let Some(layer) = self.layers.get_mut(layer_id) {
            layer.set_animation(animation);
        }
    }

    /// 播放指定层
    pub fn play_layer(&mut self, layer_id: usize) {
        if let Some(layer) = self.layers.get_mut(layer_id) {
            layer.play();
        }
    }

    /// 停止指定层
    pub fn stop_layer(&mut self, layer_id: usize) {
        if let Some(layer) = self.layers.get_mut(layer_id) {
            layer.stop();
        }
    }

    /// 暂停指定层
    pub fn pause_layer(&mut self, layer_id: usize) {
        if let Some(layer) = self.layers.get_mut(layer_id) {
            layer.pause();
        }
    }

    /// 恢复指定层
    pub fn resume_layer(&mut self, layer_id: usize) {
        if let Some(layer) = self.layers.get_mut(layer_id) {
            layer.resume();
        }
    }

    /// 设置层权重
    pub fn set_layer_weight(&mut self, layer_id: usize, weight: f32) {
        if let Some(layer) = self.layers.get_mut(layer_id) {
            layer.set_weight(weight);
        }
    }

    /// 设置层播放速度
    pub fn set_layer_speed(&mut self, layer_id: usize, speed: f32) {
        if let Some(layer) = self.layers.get_mut(layer_id) {
            layer.set_speed(speed);
        }
    }

    /// 跳转到指定帧
    pub fn seek_layer(&mut self, layer_id: usize, frame: f32) {
        if let Some(layer) = self.layers.get_mut(layer_id) {
            layer.seek_to(frame);
        }
    }

    /// 设置层淡入淡出时间
    pub fn set_layer_fade_times(&mut self, layer_id: usize, fade_in: f32, fade_out: f32) {
        if let Some(layer) = self.layers.get_mut(layer_id) {
            layer.config.fade_in_time = fade_in.max(0.0);
            layer.config.fade_out_time = fade_out.max(0.0);
        }
    }

    /// 更新所有层
    pub fn update(&mut self, delta_time: f32) {
        for layer in &mut self.layers {
            layer.update(delta_time);
        }
    }

    /// 评估所有层并混合结果
    /// 
    /// 混合策略：
    /// 1. 层 0 作为基础层（通常权重为 1.0）
    /// 2. 其他层按权重叠加
    /// 3. 如果所有层权重之和不为 1.0，自动归一化
    #[allow(unused_variables)]
    pub fn evaluate(&self, bone_manager: &mut BoneManager, morph_manager: &mut MorphManager) {
        // 计算总权重用于归一化
        let total_weight: f32 = self.layers
            .iter()
            .filter(|l| l.is_enabled() && l.effective_weight() > 0.001)
            .map(|l| l.effective_weight())
            .sum();

        if total_weight < 0.001 {
            return;
        }

        // 归一化因子
        let _normalize_factor = if total_weight > 1.0 {
            1.0 / total_weight
        } else {
            1.0
        };

        // 注意：使用 evaluate_normalized 方法进行正确的权重归一化
        // 此方法保留用于未来扩展
    }

    /// 评估所有层（改进版，支持权重归一化）
    pub fn evaluate_normalized(&self, bone_manager: &mut BoneManager, morph_manager: &mut MorphManager) {
        // 收集所有活跃层及其权重
        let active_layers: Vec<(usize, f32)> = self.layers
            .iter()
            .enumerate()
            .filter(|(_, l)| l.is_enabled() && l.effective_weight() > 0.001 && l.animation.is_some())
            .map(|(i, l)| (i, l.effective_weight()))
            .collect();

        if active_layers.is_empty() {
            // 调试：检查为什么没有活跃层
            static ONCE: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
            if !ONCE.swap(true, std::sync::atomic::Ordering::Relaxed) {
                for (i, layer) in self.layers.iter().enumerate() {
                    log::warn!("Layer {}: enabled={}, weight={:.3}, has_anim={}, state={:?}",
                        i, layer.is_enabled(), layer.effective_weight(), 
                        layer.animation.is_some(), layer.state);
                }
            }
            return;
        }

        // 计算总权重
        let total_weight: f32 = active_layers.iter().map(|(_, w)| w).sum();
        
        // 对每个活跃层进行评估，使用归一化权重
        for (layer_idx, original_weight) in active_layers {
            let normalized_weight = if total_weight > 1.0 {
                original_weight / total_weight
            } else {
                original_weight
            };

            if let Some(layer) = self.layers.get(layer_idx) {
                if let Some(ref animation) = layer.animation {
                    animation.evaluate_with_weight(
                        layer.current_frame,
                        normalized_weight,
                        bone_manager,
                        morph_manager,
                    );
                }
            }
        }
    }

    /// 获取层数量
    pub fn layer_count(&self) -> usize {
        self.layers.len()
    }

    /// 停止所有层
    pub fn stop_all(&mut self) {
        for layer in &mut self.layers {
            layer.stop();
        }
    }

    /// 重置所有层
    pub fn reset_all(&mut self) {
        for layer in &mut self.layers {
            layer.reset();
        }
    }

    /// 获取活跃层数量
    pub fn active_layer_count(&self) -> usize {
        self.layers
            .iter()
            .filter(|l| l.is_enabled() && l.is_playing())
            .count()
    }
}

impl Default for AnimationLayerManager {
    fn default() -> Self {
        Self::new(4) // 默认4层
    }
}
