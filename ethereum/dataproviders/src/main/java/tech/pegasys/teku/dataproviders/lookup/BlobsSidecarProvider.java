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

package tech.pegasys.teku.dataproviders.lookup;

import java.util.Optional;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SlotAndBlockRoot;
import tech.pegasys.teku.spec.datastructures.execution.versions.eip4844.BlobsSidecar;

@FunctionalInterface
public interface BlobsSidecarProvider {

  default SafeFuture<Optional<BlobsSidecar>> getBlobsSidecar(SignedBeaconBlock block) {
    return getBlobsSidecar(new SlotAndBlockRoot(block.getSlot(), block.getRoot()));
  }

  SafeFuture<Optional<BlobsSidecar>> getBlobsSidecar(SlotAndBlockRoot slotAndBlockRoot);
}
