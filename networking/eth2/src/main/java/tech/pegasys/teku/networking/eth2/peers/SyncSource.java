/*
 * Copyright ConsenSys Software Inc., 2022
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.networking.eth2.peers;

import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason;
import tech.pegasys.teku.networking.p2p.reputation.ReputationAdjustment;
import tech.pegasys.teku.networking.p2p.rpc.RpcResponseListener;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.execution.versions.eip4844.BlobsSidecar;

/**
 * Represents an external source of blocks (and blobs sidecars post EIP-4844) to sync. Typically, a
 * peer, but this provides the minimal interface required by the sync system.
 */
public interface SyncSource {
  SafeFuture<Void> requestBlocksByRange(
      UInt64 startSlot, UInt64 count, RpcResponseListener<SignedBeaconBlock> listener);

  SafeFuture<Void> requestBlobsSidecarsByRange(
      UInt64 startSlot, UInt64 count, RpcResponseListener<BlobsSidecar> listener);

  void adjustReputation(final ReputationAdjustment adjustment);

  SafeFuture<Void> disconnectCleanly(DisconnectReason reason);
}
