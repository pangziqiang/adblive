import 'package:flutter/material.dart';
import '../widgets/shield_card.dart';
import '../widgets/log_panel.dart';

class Dashboard extends StatefulWidget {
  const Dashboard({super.key});
  @override
  State<Dashboard> createState() => _DashboardState();
}

class _DashboardState extends State<Dashboard> {
  bool _rootOk = false;
  bool _xposedOk = false;
  bool _guardOn = false;
  bool _adbOn = false;
  String _adbAddr = '—';
  final List<String> _logs = [];

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    // TODO: call Rust bridge
    setState(() {
      _rootOk = false;
      _xposedOk = false;
      _guardOn = false;
      _adbOn = false;
      _adbAddr = 'detecting…';
      _logs.add('[${DateTime.now().hour.toString().padLeft(2, '0')}:${DateTime.now().minute.toString().padLeft(2, '0')}] status refreshed');
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('ADBLive', style: TextStyle(fontFamily: 'monospace', fontWeight: FontWeight.w700)),
        backgroundColor: Colors.transparent,
        elevation: 0,
        actions: [
          IconButton(onPressed: _refresh, icon: const Icon(Icons.sync)),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          ShieldCard(
            label: 'Xposed Shield',
            active: _xposedOk,
            icon: Icons.shield,
            detail: _xposedOk ? 'hooks active in system_server' : 'not activated in LSPosed',
          ),
          const SizedBox(height: 12),
          ShieldCard(
            label: 'Root Access',
            active: _rootOk,
            icon: Icons.vpn_key,
            detail: _rootOk ? 'granted via KernelSU' : 'not available',
          ),
          const SizedBox(height: 12),
          ShieldCard(
            label: 'Passive Guard',
            active: _guardOn,
            icon: Icons.watch_later,
            detail: _guardOn ? 'watchdog running · 10s interval' : 'stopped',
            trailing: _rootOk
                ? FilledButton.tonal(
                    onPressed: () {},
                    child: Text(_guardOn ? 'stop' : 'start'),
                  )
                : null,
          ),
          const SizedBox(height: 12),
          ShieldCard(
            label: 'Wireless ADB',
            active: _adbOn,
            icon: Icons.wifi,
            detail: _adbOn ? _adbAddr : 'disabled',
          ),
          const SizedBox(height: 20),
          LogPanel(entries: _logs),
        ],
      ),
    );
  }
}

