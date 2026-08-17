import 'package:flutter/material.dart';

class ShieldCard extends StatelessWidget {
  final String label;
  final bool active;
  final IconData icon;
  final String detail;
  final Widget? trailing;

  const ShieldCard({
    super.key,
    required this.label,
    required this.active,
    required this.icon,
    required this.detail,
    this.trailing,
  });

  @override
  Widget build(BuildContext context) {
    final color = active ? const Color(0xFF0D9488) : Colors.grey.shade700;
    return Card(
      color: const Color(0xFF111820),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: color.withValues(alpha: 0.3), width: 1),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Icon(icon, color: color, size: 22),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(label, style: TextStyle(
                    fontFamily: 'monospace',
                    fontWeight: FontWeight.w600,
                    fontSize: 14,
                    color: Colors.grey.shade300,
                  )),
                  const SizedBox(height: 4),
                  Text(detail, style: TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 12,
                    color: color,
                  )),
                ],
              ),
            ),
            if (trailing != null) trailing!,
          ],
        ),
      ),
    );
  }
}

