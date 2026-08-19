package network.zamolxis.app.rns.ipc.callback;

import network.zamolxis.app.rns.api.model.TransferProgressUpdate;

oneway interface IRnsTransferProgressCallback {
    void onTransferProgress(in TransferProgressUpdate update);
}
