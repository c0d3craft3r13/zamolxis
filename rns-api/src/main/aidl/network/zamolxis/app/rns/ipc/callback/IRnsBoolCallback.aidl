// Fire-once callback for AIDL methods returning a Boolean.
// Used for hasPath, isTransportEnabled, isDiscoveryEnabled, isSharedInstanceAvailable,
// identifyNomadnetLink, closeConversationLink (Result<Boolean>).
package network.zamolxis.app.rns.ipc.callback;

import network.zamolxis.app.rns.api.RnsError;

oneway interface IRnsBoolCallback {
    void onSuccess(boolean value);
    void onError(in RnsError error);
}
