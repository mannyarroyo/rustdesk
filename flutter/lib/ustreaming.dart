import 'package:flutter_hbb/consts.dart';
import 'package:flutter_hbb/common.dart';
import 'package:flutter_hbb/models/platform_model.dart';

/// U-Streaming Support branded Android build: built-in server, customer
/// approves each connection with OK, and no setup dialogs.
const bool kUStreaming = true;
const String kUStreamingName = 'U-Streaming Support';

const String _server = '162.243.62.158';
const String _serverKey = '9Xme1MisHaiJyVULrGWFWJA0hH5ZKnuROgJy14xvSos=';

Future<void> applyUStreamingDefaults() async {
  final options = <String, String>{
    'custom-rendezvous-server': _server,
    'relay-server': _server,
    'key': _serverKey,
    kOptionApproveMode: 'click',
    kOptionEnableAudio: 'N',
    kOptionEnableFileTransfer: 'N',
  };
  for (final e in options.entries) {
    if (await bind.mainGetOption(key: e.key) != e.value) {
      await bind.mainSetOption(key: e.key, value: e.value);
    }
  }
  await bind.mainSetLocalOption(key: 'show-scam-warning', value: 'N');
  await bind.mainSetLocalOption(key: kOptionDisableFloatingWindow, value: 'Y');
}

/// Opening the app is all the customer does: start sharing right away.
void uStreamingAutoStart() {
  Future.delayed(const Duration(seconds: 2), () {
    if (!gFFI.serverModel.isStart) {
      gFFI.serverModel.toggleService();
    }
  });
}
