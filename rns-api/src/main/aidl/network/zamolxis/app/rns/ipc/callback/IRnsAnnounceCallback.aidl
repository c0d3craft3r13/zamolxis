// Observer callback for Flow<AnnounceEvent> (RnsCore.observeAnnounces).
package network.zamolxis.app.rns.ipc.callback;

import network.zamolxis.app.rns.api.model.AnnounceEvent;

oneway interface IRnsAnnounceCallback {
    void onAnnounce(in AnnounceEvent event);
}
