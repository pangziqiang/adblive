use super::root::exec_su;
use anyhow::Result;

const SCRIPT: &str = include_str!("../../adblive-xposed/app/src/main/assets/watchdog.sh");

const GUARD_PATH: &str = "/data/adb/service.d/99_adblive_shield.sh";

pub fn deploy() -> Result<()> {
    exec_su("mkdir -p /data/adb/service.d");
    let cmd = format!("cat > {GUARD_PATH} << 'WDOGEOF'
{SCRIPT}
WDOGEOF");
    let (ok, _) = exec_su(&cmd);
    if !ok { anyhow::bail!("failed to write guard script"); }
    exec_su(&format!("chmod 755 {GUARD_PATH}"));
    Ok(())
}

pub fn start() -> Result<()> {
    let (_, pid) = exec_su("pgrep -f 99_adblive_shield");
    if !pid.trim().is_empty() { return Ok(()); }
    exec_su("nohup sh /data/adb/service.d/99_adblive_shield.sh > /dev/null 2>&1 &");
    Ok(())
}

pub fn stop() -> Result<()> {
    exec_su("pkill -f 99_adblive_shield");
    Ok(())
}

pub fn is_running() -> bool {
    let (_, pid) = exec_su("pgrep -f 99_adblive_shield");
    !pid.trim().is_empty()
}

pub fn remove() -> Result<()> {
    let _ = stop();
    exec_su(&format!("rm -f {GUARD_PATH}"));
    Ok(())
}

