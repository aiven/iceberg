/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.flink;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Iterator;
import org.apache.flink.table.data.RowData;
import org.apache.iceberg.RecordWrapperTestBase;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.RandomGenericData;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.flink.data.RandomRowData;
import org.apache.iceberg.util.StructLikeWrapper;

public class TestRowDataWrapper extends RecordWrapperTestBase {

  /**
   * Flink's time type has been truncated to millis seconds, so we need a customized assert method
   * to check the values.
   */
  @Override
  public void testTime() {
    generateAndValidate(
        new Schema(TIME.fields()),
        (message, expectedWrapper, actualWrapper) -> {
          for (int pos = 0; pos < TIME.fields().size(); pos++) {
            Object expected = expectedWrapper.get().get(pos, Object.class);
            Object actual = actualWrapper.get().get(pos, Object.class);
            if (expected == actual) {
              return;
            }

            assertThat(actual).isNotNull();
            assertThat(expected).isNotNull();

            int expectedMilliseconds = (int) ((long) expected / 1000_000);
            int actualMilliseconds = (int) ((long) actual / 1000_000);
            assertThat(actualMilliseconds).as(message).isEqualTo(expectedMilliseconds);
          }
        });
  }

  @Override
  protected void generateAndValidate(
      Schema schema, RecordWrapperTestBase.AssertMethod assertMethod) {
    int numRecords = 100;
    Iterable<Record> recordList = RandomGenericData.generate(schema, numRecords, 101L);
    Iterable<RowData> rowDataList = RandomRowData.generate(schema, numRecords, 101L);

    InternalRecordWrapper recordWrapper = new InternalRecordWrapper(schema.asStruct());
    RowDataWrapper rowDataWrapper =
        new RowDataWrapper(FlinkSchemaUtil.convert(schema), schema.asStruct());

    Iterator<Record> actual = recordList.iterator();
    Iterator<RowData> expected = rowDataList.iterator();

    StructLikeWrapper actualWrapper = StructLikeWrapper.forType(schema.asStruct());
    StructLikeWrapper expectedWrapper = StructLikeWrapper.forType(schema.asStruct());
    for (int i = 0; i < numRecords; i++) {
      assertThat(actual).hasNext();
      assertThat(expected).hasNext();

      StructLike recordStructLike = recordWrapper.wrap(actual.next());
      StructLike rowDataStructLike = rowDataWrapper.wrap(expected.next());

      assertMethod.assertEquals(
          "Should have expected StructLike values",
          expectedWrapper.set(rowDataStructLike),
          actualWrapper.set(recordStructLike));
    }

    assertThat(actual).isExhausted();
    assertThat(expected).isExhausted();
  }
}
