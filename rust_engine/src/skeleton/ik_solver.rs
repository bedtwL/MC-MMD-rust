//! IK 求解器 - 完全按照 C++ saba MMDIkSolver 实现

use glam::{Vec3, Quat, Mat3};
use std::f32::consts::PI;

use super::bone::{Bone, IkConfig, IkLink};

/// IK 链状态（对应 C++ IKChain）
#[derive(Clone, Debug)]
struct IkChainState {
    prev_angle: Vec3,
    plane_mode_angle: f32,
    save_ik_rot: Quat,
}

impl Default for IkChainState {
    fn default() -> Self {
        Self {
            prev_angle: Vec3::ZERO,
            plane_mode_angle: 0.0,
            save_ik_rot: Quat::IDENTITY,
        }
    }
}

/// 求解轴类型
#[derive(Clone, Copy, Debug, PartialEq)]
enum SolveAxis {
    X,
    Y,
    Z,
}

/// IK 求解器
#[derive(Clone, Debug)]
pub struct IkSolver {
    pub bone_index: usize,
    pub config: IkConfig,
    pub enabled: bool,
}

impl IkSolver {
    pub fn new(bone_index: usize, config: IkConfig) -> Self {
        Self {
            bone_index,
            config,
            enabled: true,
        }
    }
    
    /// 求解 IK（对应 C++ MMDIkSolver::Solve）
    pub fn solve(&self, bones: &mut [Bone]) {
        if !self.enabled {
            return;
        }
        
        let target_idx = self.config.target_bone as usize;
        if target_idx >= bones.len() || self.bone_index >= bones.len() {
            return;
        }
        
        // 初始化 IK 链状态
        let mut chain_states: Vec<IkChainState> = self.config.links.iter()
            .map(|_| IkChainState::default())
            .collect();
        
        // 初始化 IK 链骨骼（对应 C++ 初始化循环）
        for link in &self.config.links {
            let link_idx = link.bone_index as usize;
            if link_idx < bones.len() {
                bones[link_idx].ik_rotate = Quat::IDENTITY;
                bones[link_idx].enable_ik = true;
                bones[link_idx].update_local_transform();
                Self::update_global_transform_recursive(bones, link_idx);
            }
        }
        
        let mut max_dist = f32::MAX;
        
        // 迭代求解
        for iteration in 0..self.config.iterations {
            self.solve_core(bones, target_idx, iteration, &mut chain_states);
            
            // 检查距离是否改善
            let target_pos = bones[target_idx].global_transform.col(3).truncate();
            let ik_pos = bones[self.bone_index].global_transform.col(3).truncate();
            let dist = (target_pos - ik_pos).length();
            
            if dist < max_dist {
                max_dist = dist;
                // 保存最佳结果
                for (i, link) in self.config.links.iter().enumerate() {
                    let link_idx = link.bone_index as usize;
                    if link_idx < bones.len() {
                        chain_states[i].save_ik_rot = bones[link_idx].ik_rotate;
                    }
                }
            } else {
                // 恢复最佳结果
                for (i, link) in self.config.links.iter().enumerate() {
                    let link_idx = link.bone_index as usize;
                    if link_idx < bones.len() {
                        bones[link_idx].ik_rotate = chain_states[i].save_ik_rot;
                        bones[link_idx].update_local_transform();
                        Self::update_global_transform_recursive(bones, link_idx);
                    }
                }
                break;
            }
        }
    }
    
    /// IK 求解核心（对应 C++ MMDIkSolver::SolveCore）
    fn solve_core(&self, bones: &mut [Bone], target_idx: usize, iteration: u32, chain_states: &mut [IkChainState]) {
        let ik_pos = bones[self.bone_index].global_transform.col(3).truncate();
        
        for chain_idx in 0..self.config.links.len() {
            let link = &self.config.links[chain_idx];
            let link_idx = link.bone_index as usize;
            
            if link_idx >= bones.len() {
                continue;
            }
            
            // 跳过目标骨骼本身
            if link_idx == target_idx {
                continue;
            }
            
            // 检查是否使用单轴模式
            if link.has_limits {
                let solve_axis = Self::get_plane_solve_axis(link);
                if let Some(axis) = solve_axis {
                    self.solve_plane(bones, target_idx, iteration, chain_idx, axis, chain_states);
                    continue;
                }
            }
            
            // 通用 3 轴求解
            let target_pos = bones[target_idx].global_transform.col(3).truncate();
            let inv_chain = bones[link_idx].global_transform.inverse();
            
            let chain_ik_pos = inv_chain.transform_point3(ik_pos);
            let chain_target_pos = inv_chain.transform_point3(target_pos);
            
            let chain_ik_vec = chain_ik_pos.normalize_or_zero();
            let chain_target_vec = chain_target_pos.normalize_or_zero();
            
            if chain_ik_vec.length_squared() < 1e-6 || chain_target_vec.length_squared() < 1e-6 {
                continue;
            }
            
            let dot = chain_target_vec.dot(chain_ik_vec).clamp(-1.0, 1.0);
            let angle = dot.acos();
            let angle_deg = angle.to_degrees();
            
            if angle_deg < 1e-3 {
                continue;
            }
            
            let angle = angle.clamp(-self.config.limit_angle, self.config.limit_angle);
            let cross = chain_target_vec.cross(chain_ik_vec).normalize_or_zero();
            
            if cross.length_squared() < 1e-6 {
                continue;
            }
            
            let rot = Quat::from_axis_angle(cross, angle);
            
            // chainRot = IKRotate * AnimateRotate * rot
            let chain_rot = bones[link_idx].ik_rotate * bones[link_idx].animation_rotate * rot;
            
            let chain_rot = if link.has_limits {
                // 使用 Decompose 和增量限制
                let chain_rot_mat = Mat3::from_quat(chain_rot);
                let rot_xyz = Self::decompose(chain_rot_mat, chain_states[chain_idx].prev_angle);
                
                // 限制到角度范围
                let mut clamp_xyz = Vec3::new(
                    rot_xyz.x.clamp(link.limit_min.x, link.limit_max.x),
                    rot_xyz.y.clamp(link.limit_min.y, link.limit_max.y),
                    rot_xyz.z.clamp(link.limit_min.z, link.limit_max.z),
                );
                
                // 增量限制
                clamp_xyz = (clamp_xyz - chain_states[chain_idx].prev_angle)
                    .clamp(Vec3::splat(-self.config.limit_angle), Vec3::splat(self.config.limit_angle))
                    + chain_states[chain_idx].prev_angle;
                
                chain_states[chain_idx].prev_angle = clamp_xyz;
                
                // 重建四元数
                Quat::from_euler(glam::EulerRot::XYZ, clamp_xyz.x, clamp_xyz.y, clamp_xyz.z)
            } else {
                chain_rot
            };
            
            // ikRot = chainRot * inverse(AnimateRotate)
            let ik_rot = chain_rot * bones[link_idx].animation_rotate.inverse();
            bones[link_idx].ik_rotate = ik_rot;
            
            bones[link_idx].update_local_transform();
            Self::update_global_transform_recursive(bones, link_idx);
        }
    }
    
    /// 单轴求解（对应 C++ MMDIkSolver::SolvePlane）
    fn solve_plane(&self, bones: &mut [Bone], target_idx: usize, iteration: u32, chain_idx: usize, solve_axis: SolveAxis, chain_states: &mut [IkChainState]) {
        let (rotate_axis_index, rotate_axis) = match solve_axis {
            SolveAxis::X => (0, Vec3::X),
            SolveAxis::Y => (1, Vec3::Y),
            SolveAxis::Z => (2, Vec3::Z),
        };
        
        let link = &self.config.links[chain_idx];
        let link_idx = link.bone_index as usize;
        
        if link_idx >= bones.len() {
            return;
        }
        
        let ik_pos = bones[self.bone_index].global_transform.col(3).truncate();
        let target_pos = bones[target_idx].global_transform.col(3).truncate();
        
        let inv_chain = bones[link_idx].global_transform.inverse();
        let chain_ik_pos = inv_chain.transform_point3(ik_pos);
        let chain_target_pos = inv_chain.transform_point3(target_pos);
        
        let chain_ik_vec = chain_ik_pos.normalize_or_zero();
        let chain_target_vec = chain_target_pos.normalize_or_zero();
        
        if chain_ik_vec.length_squared() < 1e-6 || chain_target_vec.length_squared() < 1e-6 {
            return;
        }
        
        let dot = chain_target_vec.dot(chain_ik_vec).clamp(-1.0, 1.0);
        let angle = dot.acos().clamp(-self.config.limit_angle, self.config.limit_angle);
        
        // 测试两个方向
        let rot1 = Quat::from_axis_angle(rotate_axis, angle);
        let target_vec1 = rot1 * chain_target_vec;
        let dot1 = target_vec1.dot(chain_ik_vec);
        
        let rot2 = Quat::from_axis_angle(rotate_axis, -angle);
        let target_vec2 = rot2 * chain_target_vec;
        let dot2 = target_vec2.dot(chain_ik_vec);
        
        let mut new_angle = chain_states[chain_idx].plane_mode_angle;
        if dot1 > dot2 {
            new_angle += angle;
        } else {
            new_angle -= angle;
        }
        
        // 第 0 次迭代的特殊处理
        if iteration == 0 {
            let limit_min = match rotate_axis_index {
                0 => link.limit_min.x,
                1 => link.limit_min.y,
                _ => link.limit_min.z,
            };
            let limit_max = match rotate_axis_index {
                0 => link.limit_max.x,
                1 => link.limit_max.y,
                _ => link.limit_max.z,
            };
            
            if new_angle < limit_min || new_angle > limit_max {
                if -new_angle > limit_min && -new_angle < limit_max {
                    new_angle = -new_angle;
                } else {
                    let half_rad = (limit_min + limit_max) * 0.5;
                    if (half_rad - new_angle).abs() > (half_rad + new_angle).abs() {
                        new_angle = -new_angle;
                    }
                }
            }
        }
        
        // 限制角度
        let limit_min = match rotate_axis_index {
            0 => link.limit_min.x,
            1 => link.limit_min.y,
            _ => link.limit_min.z,
        };
        let limit_max = match rotate_axis_index {
            0 => link.limit_max.x,
            1 => link.limit_max.y,
            _ => link.limit_max.z,
        };
        new_angle = new_angle.clamp(limit_min, limit_max);
        chain_states[chain_idx].plane_mode_angle = new_angle;
        
        // ikRotM = rotate(newAngle, RotateAxis) * inverse(AnimateRotate)
        let ik_rot = Quat::from_axis_angle(rotate_axis, new_angle) * bones[link_idx].animation_rotate.inverse();
        bones[link_idx].ik_rotate = ik_rot;
        
        bones[link_idx].update_local_transform();
        Self::update_global_transform_recursive(bones, link_idx);
    }
    
    /// 检查是否应使用单轴模式
    fn get_plane_solve_axis(link: &IkLink) -> Option<SolveAxis> {
        let x_active = link.limit_min.x != 0.0 || link.limit_max.x != 0.0;
        let y_active = link.limit_min.y != 0.0 || link.limit_max.y != 0.0;
        let z_active = link.limit_min.z != 0.0 || link.limit_max.z != 0.0;
        let y_zero = link.limit_min.y == 0.0 && link.limit_max.y == 0.0;
        let x_zero = link.limit_min.x == 0.0 && link.limit_max.x == 0.0;
        let z_zero = link.limit_min.z == 0.0 && link.limit_max.z == 0.0;
        
        if x_active && y_zero && z_zero {
            Some(SolveAxis::X)
        } else if y_active && x_zero && z_zero {
            Some(SolveAxis::Y)
        } else if z_active && x_zero && y_zero {
            Some(SolveAxis::Z)
        } else {
            None
        }
    }
    
    /// 从旋转矩阵分解欧拉角（对应 C++ Decompose）
    /// 矩阵索引：C++ m[col][row] = Rust m.col(col)[row]
    fn decompose(m: Mat3, before: Vec3) -> Vec3 {
        let e = 1.0e-6_f32;
        // C++: float sy = -m[0][2];  // m[0][2] = 第0列第2行
        let sy = -m.col(0).z;
        
        let mut r;
        if (1.0 - sy.abs()) < e {
            // Gimbal lock 情况
            let ry = sy.asin();
            let sx = before.x.sin();
            let sz = before.z.sin();
            
            if sx.abs() < sz.abs() {
                // X 更接近 0 或 180
                let cx = before.x.cos();
                if cx > 0.0 {
                    // C++: r.z = std::asin(-m[1][0]);  // m[1][0] = 第1列第0行
                    r = Vec3::new(0.0, ry, (-m.col(1).x).asin());
                } else {
                    // C++: r.z = std::asin(m[1][0]);
                    r = Vec3::new(PI, ry, m.col(1).x.asin());
                }
            } else {
                // Z 更接近 0 或 180
                let cz = before.z.cos();
                if cz > 0.0 {
                    // C++: r.x = std::asin(-m[2][1]);  // m[2][1] = 第2列第1行
                    r = Vec3::new((-m.col(2).y).asin(), ry, 0.0);
                } else {
                    // C++: r.x = std::asin(m[2][1]);
                    r = Vec3::new(m.col(2).y.asin(), ry, PI);
                }
            }
        } else {
            // 正常情况
            // C++: r.x = std::atan2(m[1][2], m[2][2]);  // atan2(第1列第2行, 第2列第2行)
            // C++: r.y = std::asin(-m[0][2]);           // 第0列第2行
            // C++: r.z = std::atan2(m[0][1], m[0][0]);  // atan2(第0列第1行, 第0列第0行)
            r = Vec3::new(
                m.col(1).z.atan2(m.col(2).z),  // atan2(m12, m22)
                (-m.col(0).z).asin(),           // -m02
                m.col(0).y.atan2(m.col(0).x),  // atan2(m01, m00)
            );
        }
        
        // 寻找最接近 before 的解
        let tests = [
            Vec3::new(r.x + PI, PI - r.y, r.z + PI),
            Vec3::new(r.x + PI, PI - r.y, r.z - PI),
            Vec3::new(r.x + PI, -PI - r.y, r.z + PI),
            Vec3::new(r.x + PI, -PI - r.y, r.z - PI),
            Vec3::new(r.x - PI, PI - r.y, r.z + PI),
            Vec3::new(r.x - PI, PI - r.y, r.z - PI),
            Vec3::new(r.x - PI, -PI - r.y, r.z + PI),
            Vec3::new(r.x - PI, -PI - r.y, r.z - PI),
        ];
        
        let diff_angle = |a: f32, b: f32| -> f32 {
            let mut diff = Self::normalize_angle(a) - Self::normalize_angle(b);
            if diff > PI {
                diff -= 2.0 * PI;
            } else if diff < -PI {
                diff += 2.0 * PI;
            }
            diff
        };
        
        let err_x = diff_angle(r.x, before.x).abs();
        let err_y = diff_angle(r.y, before.y).abs();
        let err_z = diff_angle(r.z, before.z).abs();
        let mut min_err = err_x + err_y + err_z;
        
        for test in &tests {
            let err = diff_angle(test.x, before.x).abs()
                + diff_angle(test.y, before.y).abs()
                + diff_angle(test.z, before.z).abs();
            if err < min_err {
                min_err = err;
                r = *test;
            }
        }
        
        r
    }
    
    fn normalize_angle(angle: f32) -> f32 {
        let mut ret = angle;
        while ret >= 2.0 * PI {
            ret -= 2.0 * PI;
        }
        while ret < 0.0 {
            ret += 2.0 * PI;
        }
        ret
    }
    
    /// 递归更新骨骼全局变换（对应 C++ MMDNode::UpdateGlobalTransform）
    fn update_global_transform_recursive(bones: &mut [Bone], idx: usize) {
        if idx >= bones.len() {
            return;
        }
        
        // 更新当前骨骼
        let parent_idx = bones[idx].parent_index;
        if parent_idx >= 0 && (parent_idx as usize) < bones.len() {
            let parent_global = bones[parent_idx as usize].global_transform;
            bones[idx].global_transform = parent_global * bones[idx].local_transform;
        } else {
            bones[idx].global_transform = bones[idx].local_transform;
        }
        
        // 递归更新所有子骨骼
        let children: Vec<usize> = (0..bones.len())
            .filter(|&i| bones[i].parent_index == idx as i32)
            .collect();
        
        for child_idx in children {
            Self::update_global_transform_recursive(bones, child_idx);
        }
    }
}
