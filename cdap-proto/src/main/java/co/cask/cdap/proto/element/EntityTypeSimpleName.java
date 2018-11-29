/*
 * Copyright © 2015-2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package co.cask.cdap.proto.element;

import co.cask.cdap.api.annotation.Beta;
import co.cask.cdap.api.metadata.MetadataEntity;

/**
 * Simple names for various CDAP entities which can be used during entity serialization for persistence.
 */
@Beta
public enum EntityTypeSimpleName {
  // the custom values are required because these value match the entity-type stored as
  // a part of MDS key.
  ALL("all"),
  NAMESPACE(MetadataEntity.NAMESPACE),
  ARTIFACT(MetadataEntity.ARTIFACT),
  APP(MetadataEntity.APPLICATION),
  PROGRAM(MetadataEntity.PROGRAM),
  DATASET(MetadataEntity.DATASET),
  // TODO (CDAP-14584) remove stream and view
  STREAM("stream"),
  VIEW("stream_view"),
  SCHEDULE(MetadataEntity.SCHEDULE);

  private final String serializedForm;

  EntityTypeSimpleName(String serializedForm) {
    this.serializedForm = serializedForm;
  }

  /**
   * @return {@link EntityTypeSimpleName} of the given value.
   */
  public static EntityTypeSimpleName valueOfSerializedForm(String value) {
    for (EntityTypeSimpleName entityTypeSimpleName : values()) {
      if (entityTypeSimpleName.serializedForm.equalsIgnoreCase(value)) {
        return entityTypeSimpleName;
      }
    }
    throw new IllegalArgumentException(String.format("No enum constant for serialized form: %s", value));
  }

  public String getSerializedForm() {
    return serializedForm;
  }
}
