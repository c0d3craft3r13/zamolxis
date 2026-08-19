// Fire-once callback returning the IRnsTelephony sub-interface binder. See
// IRnsCoreCallback.aidl for the usage pattern.
package network.zamolxis.app.rns.ipc.callback;

import network.zamolxis.app.rns.ipc.IRnsTelephony;

oneway interface IRnsTelephonyCallback {
    void onTelephony(IRnsTelephony service);
}
