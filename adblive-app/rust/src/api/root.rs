use std::process::Command;

/// 检测 root 权限
pub fn check_root() -> bool {
    Command::new("su")
        .arg("-c")
        .arg("id")
        .output()
        .map(|o| o.status.success() && String::from_utf8_lossy(&o.stdout).contains("uid=0"))
        .unwrap_or(false)
}

/// 通过 root 执行命令，返回 (成功, 输出)
pub fn exec_su(cmd: &str) -> (bool, String) {
    match Command::new("su").arg("-c").arg(cmd).output() {
        Ok(o) => {
            let out = String::from_utf8_lossy(&o.stdout).to_string();
            (o.status.success(), out)
        }
        Err(e) => (false, format!("exec error: {e}")),
    }
}

