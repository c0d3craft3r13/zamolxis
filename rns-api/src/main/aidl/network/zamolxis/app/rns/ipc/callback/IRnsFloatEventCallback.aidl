// Observer callback for StateFlow<Float> (RnsNomadnet.nomadnetDownloadProgressFlow).
package network.zamolxis.app.rns.ipc.callback;

oneway interface IRnsFloatEventCallback {
    void onFloat(float value);
}
