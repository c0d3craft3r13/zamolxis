// Fire-once callback for AIDL methods returning an Int (or nullable Int via
// nullableIntValue with hasValue=false). Used for getHopCount, getRNodeRssi.
package network.zamolxis.app.rns.ipc.callback;

import network.zamolxis.app.rns.api.RnsError;

oneway interface IRnsIntCallback {
    void onSuccess(int value, boolean hasValue);
    void onError(in RnsError error);
}
