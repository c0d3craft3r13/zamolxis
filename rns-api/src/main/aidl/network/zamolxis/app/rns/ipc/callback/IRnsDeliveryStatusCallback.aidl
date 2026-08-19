// Observer callback for Flow<DeliveryStatusUpdate> (RnsLxmf.observeDeliveryStatus).
package network.zamolxis.app.rns.ipc.callback;

import network.zamolxis.app.rns.api.model.DeliveryStatusUpdate;

oneway interface IRnsDeliveryStatusCallback {
    void onDeliveryStatus(in DeliveryStatusUpdate update);
}
