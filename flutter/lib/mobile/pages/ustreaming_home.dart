import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../common.dart';
import '../../models/platform_model.dart';
import '../../models/server_model.dart';
import '../../ustreaming.dart';

/// The only screen a U-Streaming customer sees: one-time setup steps, then
/// their support number in large type.
class UStreamingHome extends StatefulWidget {
  const UStreamingHome({Key? key}) : super(key: key);

  @override
  State<UStreamingHome> createState() => _UStreamingHomeState();
}

class _UStreamingHomeState extends State<UStreamingHome> {
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = periodic_immediate(const Duration(seconds: 3), () async {
      await gFFI.serverModel.fetchID();
    });
    gFFI.serverModel.checkAndroidPermission();
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider.value(
      value: gFFI.serverModel,
      child: Consumer<ServerModel>(
        builder: (context, model, _) => Scaffold(
          backgroundColor: const Color(0xFF17171B),
          body: SafeArea(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 56, vertical: 32),
              child: Row(
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(28),
                    child: Image.asset('assets/ustreaming_logo.png',
                        width: 230, height: 230),
                  ),
                  const SizedBox(width: 56),
                  Expanded(child: _content(model)),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _content(ServerModel model) {
    if (!model.inputOk) return _setup();
    final sessions =
        model.clients.where((c) => c.authorized && !c.disconnected).toList();
    if (sessions.isNotEmpty) return _connected(sessions);
    return _ready(model);
  }

  Widget _setup() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _title('One-time setup'),
        const SizedBox(height: 20),
        _text('1.  Press OK on "Open settings" below.'),
        _text('2.  Press DOWN until you reach "$kUStreamingName"'),
        _text('     at the very bottom of the list, then press OK.'),
        _text('3.  Press OK on "Enable", then OK again to confirm.'),
        const SizedBox(height: 8),
        _text('This screen comes back by itself when you are done.',
            dim: true),
        const SizedBox(height: 28),
        _button('Open settings', autofocus: true, onPressed: () {
          gFFI.invokeMethod('ustreaming_open_accessibility');
        }),
      ],
    );
  }

  Widget _ready(ServerModel model) {
    final online = model.connectStatus > 0;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _title('Your support number'),
        const SizedBox(height: 8),
        FittedBox(
          fit: BoxFit.scaleDown,
          alignment: Alignment.centerLeft,
          child: Text(
            model.serverId.text.isEmpty ? '...' : model.serverId.text,
            style: const TextStyle(
                fontSize: 96,
                fontWeight: FontWeight.w800,
                color: Colors.white,
                letterSpacing: 2),
          ),
        ),
        const SizedBox(height: 16),
        Row(children: [
          Icon(online ? Icons.check_circle : Icons.sync,
              color: online ? Colors.greenAccent : Colors.amber, size: 34),
          const SizedBox(width: 14),
          Flexible(
              child: _text(online
                  ? 'Ready. Give your Support Representative the number above to connect.'
                  : 'Connecting to U-Streaming support...')),
        ]),
      ],
    );
  }

  Widget _connected(List<Client> sessions) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _title('Support is connected'),
        const SizedBox(height: 16),
        _text('U-Streaming support is helping you right now.'),
        const SizedBox(height: 28),
        _button('End support session', onPressed: () {
          for (final c in sessions) {
            bind.cmCloseConnection(connId: c.id);
          }
        }),
      ],
    );
  }

  Widget _title(String s) => Text(s,
      style: const TextStyle(
          fontSize: 40, fontWeight: FontWeight.w700, color: Colors.white));

  Widget _text(String s, {bool dim = false}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Text(s,
            style: TextStyle(
                fontSize: 24,
                height: 1.3,
                color: dim ? Colors.white60 : Colors.white)),
      );

  Widget _button(String label,
          {required VoidCallback onPressed, bool autofocus = false}) =>
      ElevatedButton(
        autofocus: autofocus,
        style: ElevatedButton.styleFrom(
          backgroundColor: const Color(0xFFE8400A),
          foregroundColor: Colors.white,
          padding: const EdgeInsets.symmetric(horizontal: 36, vertical: 20),
          textStyle: const TextStyle(fontSize: 26, fontWeight: FontWeight.w700),
          shape:
              RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        ).copyWith(
          side: MaterialStateProperty.resolveWith((states) =>
              states.contains(MaterialState.focused)
                  ? const BorderSide(color: Colors.white, width: 4)
                  : null),
        ),
        onPressed: onPressed,
        child: Text(label),
      );
}
