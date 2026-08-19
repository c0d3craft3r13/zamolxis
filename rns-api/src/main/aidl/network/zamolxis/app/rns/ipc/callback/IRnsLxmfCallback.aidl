// Fire-once callback returning the IRnsLxmf sub-interface binder. See
// IRnsCoreCallback.aidl for the usage pattern.
package network.zamolxis.app.rns.ipc.callback;

import network.zamolxis.app.rns.ipc.IRnsLxmf;

oneway interface IRnsLxmfCallback {
    void onLxmf(IRnsLxmf service);
}
