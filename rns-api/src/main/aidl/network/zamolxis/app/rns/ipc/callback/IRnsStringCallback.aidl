// Fire-once callback for AIDL methods returning a String (nullable).
// Used for getNomadnetRequestStatus, getOutboundPropagationNode (Result<String?>),
// getNextHopInterfaceName, getBleConnectionDetails.
package network.zamolxis.app.rns.ipc.callback;

import network.zamolxis.app.rns.api.RnsError;

oneway interface IRnsStringCallback {
    void onSuccess(@nullable String value);
    void onError(in RnsError error);
}
