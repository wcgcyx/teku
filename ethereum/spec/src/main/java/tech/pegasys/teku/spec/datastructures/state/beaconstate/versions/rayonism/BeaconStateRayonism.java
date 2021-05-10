/*
 * Copyright 2021 ConsenSys AG.
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

package tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.rayonism;

import java.util.Optional;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadHeader;
import tech.pegasys.teku.spec.datastructures.state.PendingAttestation;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.common.BeaconStateFields;
import tech.pegasys.teku.ssz.SszList;

public interface BeaconStateRayonism extends BeaconState {

  @Override
  default BeaconStateSchemaRayonism getBeaconStateSchema() {
    return (BeaconStateSchemaRayonism) getSchema();
  }

  static BeaconStateRayonism required(final BeaconState state) {
    return state
        .toVersionRayonism()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Expected a merge state but got: " + state.getClass().getSimpleName()));
  }

  // Attestations
  default SszList<PendingAttestation> getPrevious_epoch_attestations() {
    final int fieldIndex =
        getSchema().getFieldIndex(BeaconStateFields.PREVIOUS_EPOCH_ATTESTATIONS.name());
    return getAny(fieldIndex);
  }

  default SszList<PendingAttestation> getCurrent_epoch_attestations() {
    final int fieldIndex =
        getSchema().getFieldIndex(BeaconStateFields.CURRENT_EPOCH_ATTESTATIONS.name());
    return getAny(fieldIndex);
  }

  // Execution
  default ExecutionPayloadHeader getLatest_execution_payload_header() {
    final int fieldIndex =
        getSchema().getFieldIndex(BeaconStateFields.LATEST_EXECUTION_PAYLOAD_HEADER.name());
    return getAny(fieldIndex);
  }

  @Override
  default Optional<BeaconStateRayonism> toVersionRayonism() {
    return Optional.of(this);
  }

  <E1 extends Exception, E2 extends Exception, E3 extends Exception> BeaconStateRayonism updatedMerge(
      Mutator<MutableBeaconStateRayonism, E1, E2, E3> mutator) throws E1, E2, E3;
}
