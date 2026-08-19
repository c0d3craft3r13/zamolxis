// Fire-once callback returning the IRnsTransportAdmin sub-interface binder.
// See IRnsCoreCallback.aidl for the usage pattern.
package network.zamolxis.app.rns.ipc.callback;

import network.zamolxis.app.rns.ipc.IRnsTransportAdmin;

oneway interface IRnsTransportAdminCallback {
    void onTransportAdmin(IRnsTransportAdmin service);
}
