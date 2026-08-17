use super::root::{check_root, exec_su};

pub struct AdbStatus {
    pub wifi_enabled: bool,
    pub adbd_alive: bool,
    pub port_open: bool,
    pub device_ip: Option<String>,
    pub port: u16,
}

pub fn get_status() -> AdbStatus {
    let has_root = check_root();
    AdbStatus {
        wifi_enabled: has_root && query_wifi_enabled(),
        adbd_alive: has_root && query_adbd_alive(),
        port_open: has_root && query_port(5555),
        device_ip: has_root && query_wifi_enabled().then(get_ip).flatten(),
        port: 5555,
    }
}

fn query_wifi_enabled() -> bool {
    let (ok, val) = exec_su("settings get global adb_wifi_enabled");
    ok && val.trim() == "1"
}

fn query_adbd_alive() -> bool {
    exec_su("pidof adbd").1.trim().parse::<i32>().is_ok()
}

fn query_port(port: u16) -> bool {
    let (_, out) = exec_su(&format!("ss -tln sport = :{port}"));
    out.contains(&port.to_string())
}

fn get_ip() -> Option<String> {
    let (_, out) = exec_su("ip route get 8.8.8.8 | head -1 | awk '{print $7}'");
    let ip = out.trim().to_string();
    (!ip.is_empty() && !ip.contains("error")).then_some(ip)
}

pub fn enable() -> anyhow::Result<()> {
    let (ok, _) = exec_su("settings put global adb_wifi_enabled 1");
    if !ok { anyhow::bail!("failed to enable adb_wifi_enabled"); }
    Ok(())
}

