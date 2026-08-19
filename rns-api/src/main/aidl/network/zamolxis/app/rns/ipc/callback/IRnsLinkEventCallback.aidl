// Observer callback for Flow<LinkEvent> (RnsCore.observeLinks).
package network.zamolxis.app.rns.ipc.callback;

import network.zamolxis.app.rns.api.model.LinkEvent;

oneway interface IRnsLinkEventCallback {
    void onLinkEvent(in LinkEvent event);
}
