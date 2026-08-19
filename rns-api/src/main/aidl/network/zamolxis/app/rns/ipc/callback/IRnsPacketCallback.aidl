// Observer callback for Flow<ReceivedPacket> (RnsCore.observePackets).
package network.zamolxis.app.rns.ipc.callback;

import network.zamolxis.app.rns.api.model.ReceivedPacket;

oneway interface IRnsPacketCallback {
    void onPacket(in ReceivedPacket packet);
}
