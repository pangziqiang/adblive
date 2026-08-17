import 'package:flutter/material.dart';
import 'pages/dashboard.dart';

void main() => runApp(const ADBLiveApp());

class ADBLiveApp extends StatelessWidget {
  const ADBLiveApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ADBLive',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorSchemeSeed: const Color(0xFF0D9488),
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF0A0E14),
      ),
      home: const Dashboard(),
    );
  }
}

