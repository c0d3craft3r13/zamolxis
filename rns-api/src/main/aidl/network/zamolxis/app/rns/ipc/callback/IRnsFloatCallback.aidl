// Fire-once callback for AIDL methods returning a Float.
// Used for getNomadnetDownloadProgress.
package network.zamolxis.app.rns.ipc.callback;

import network.zamolxis.app.rns.api.RnsError;

oneway interface IRnsFloatCallback {
    void onSuccess(float value);
    void onError(in RnsError error);
}
