// Observer callback for the StateFlow<CallState> on IRnsTelephony.
// Used as both snapshot (getCurrentCallState) and continuous observer.
package network.zamolxis.app.rns.ipc.callback;

import network.zamolxis.app.rns.api.model.CallState;

oneway interface IRnsCallStateCallback {
    void onState(in CallState state);
}
