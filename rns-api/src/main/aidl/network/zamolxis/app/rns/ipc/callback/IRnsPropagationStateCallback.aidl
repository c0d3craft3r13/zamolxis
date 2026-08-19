// Observer callback for SharedFlow<PropagationState> (RnsLxmf.propagationStateFlow).
package network.zamolxis.app.rns.ipc.callback;

import network.zamolxis.app.rns.api.model.PropagationState;

oneway interface IRnsPropagationStateCallback {
    void onPropagationState(in PropagationState state);
}
