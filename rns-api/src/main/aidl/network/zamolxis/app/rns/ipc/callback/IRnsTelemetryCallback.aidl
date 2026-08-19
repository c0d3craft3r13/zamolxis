// Fire-once callback returning the IRnsTelemetry sub-interface binder. See
// IRnsCoreCallback.aidl for the usage pattern.
package network.zamolxis.app.rns.ipc.callback;

import network.zamolxis.app.rns.ipc.IRnsTelemetry;

oneway interface IRnsTelemetryCallback {
    void onTelemetry(IRnsTelemetry service);
}
