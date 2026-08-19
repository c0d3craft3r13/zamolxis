// Fire-once callback returning the IRnsNomadnet sub-interface binder. See
// IRnsCoreCallback.aidl for the usage pattern.
package network.zamolxis.app.rns.ipc.callback;

import network.zamolxis.app.rns.ipc.IRnsNomadnet;

oneway interface IRnsNomadnetCallback {
    void onNomadnet(IRnsNomadnet service);
}
