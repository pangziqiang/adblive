import 'package:flutter/material.dart';

class LogPanel extends StatelessWidget {
  final List<String> entries;
  const LogPanel({super.key, required this.entries});

  @override
  Widget build(BuildContext context) {
    final visible = entries.length > 8 ? entries.sublist(entries.length - 8) : entries;
    return Card(
      color: const Color(0xFF111820),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('LOG', style: TextStyle(
              fontFamily: 'monospace',
              fontWeight: FontWeight.w700,
              fontSize: 12,
              color: Colors.grey.shade500,
              letterSpacing: 2,
            )),
            const SizedBox(height: 8),
            ...visible.map((e) => Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Text(e, style: const TextStyle(
                fontFamily: 'monospace',
                fontSize: 11,
                color: Color(0xFF0D9488),
              )),
            )),
          ],
        ),
      ),
    );
  }
}

