/*
 * Copyright 2017-2019 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.opentracing.contrib.cassandra2.nameprovider;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FullQuerySpanNameTest {

  @Test
  public void fullQuerySpanNameTest() {
    QuerySpanNameProvider fullQuerySpanName = FullQuerySpanName.newBuilder().build();
    assertEquals("SELECT * FROM test.table_name;",
        fullQuerySpanName.querySpanName("SELECT * FROM test.table_name;"));
    assertEquals("N/A", fullQuerySpanName.querySpanName(""));
    assertEquals("N/A", fullQuerySpanName.querySpanName(null));
  }
}
