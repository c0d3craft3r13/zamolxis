// Observer callback for nullable-String-emitting StateFlow surfaces:
//   - RnsTelephony.remoteIdentity
package network.zamolxis.app.rns.ipc.callback;

oneway interface IRnsNullableStringEventCallback {
    void onString(@nullable String value);
}
