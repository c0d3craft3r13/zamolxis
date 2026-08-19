// Observer callback for Boolean-emitting StateFlow surfaces:
//   - RnsTelephony.isMuted
//   - RnsTelephony.isSpeakerOn
//   - RnsTelephony.isPttMode
//   - RnsTelephony.isPttActive
package network.zamolxis.app.rns.ipc.callback;

oneway interface IRnsBoolEventCallback {
    void onBool(boolean value);
}
