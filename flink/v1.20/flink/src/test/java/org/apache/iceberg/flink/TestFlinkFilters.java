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

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Expressions;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.expressions.ApiExpressionUtils;
import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.expressions.UnresolvedCallExpression;
import org.apache.flink.table.expressions.UnresolvedReferenceExpression;
import org.apache.flink.table.expressions.ValueLiteralExpression;
import org.apache.flink.table.expressions.utils.ApiExpressionDefaultVisitor;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;
import org.apache.iceberg.expressions.And;
import org.apache.iceberg.expressions.BoundLiteralPredicate;
import org.apache.iceberg.expressions.Not;
import org.apache.iceberg.expressions.Or;
import org.apache.iceberg.expressions.UnboundPredicate;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.util.DateTimeUtil;
import org.apache.iceberg.util.Pair;
import org.junit.jupiter.api.Test;

public class TestFlinkFilters {

  private static final ResolvedSchema RESOLVED_SCHEMA =
      ResolvedSchema.of(
          Column.physical("field1", DataTypes.INT()),
          Column.physical("field2", DataTypes.BIGINT()),
          Column.physical("field3", DataTypes.FLOAT()),
          Column.physical("field4", DataTypes.DOUBLE()),
          Column.physical("field5", DataTypes.STRING()),
          Column.physical("field6", DataTypes.BOOLEAN()),
          Column.physical("field7", DataTypes.BINARY(2)),
          Column.physical("field8", DataTypes.DECIMAL(10, 2)),
          Column.physical("field9", DataTypes.DATE()),
          Column.physical("field10", DataTypes.TIME()),
          Column.physical("field11", DataTypes.TIMESTAMP()),
          Column.physical("field12", DataTypes.TIMESTAMP_WITH_LOCAL_TIME_ZONE()));

  // A map list of fields and values used to verify the conversion of flink expression to iceberg
  // expression
  private static final List<Pair<String, Object>> FIELD_VALUE_LIST =
      ImmutableList.of(
          Pair.of("field1", 1),
          Pair.of("field2", 2L),
          Pair.of("field3", 3F),
          Pair.of("field4", 4D),
          Pair.of("field5", "iceberg"),
          Pair.of("field6", true),
          Pair.of("field7", new byte[] {'a', 'b'}),
          Pair.of("field8", BigDecimal.valueOf(10.12)),
          Pair.of("field9", DateTimeUtil.daysFromDate(LocalDate.now())),
          Pair.of("field10", DateTimeUtil.microsFromTime(LocalTime.now())),
          Pair.of("field11", DateTimeUtil.microsFromTimestamp(LocalDateTime.now())),
          Pair.of("field12", DateTimeUtil.microsFromInstant(Instant.now())));

  @Test
  public void testFlinkDataTypeEqual() {
    matchLiteral("field1", 1, 1);
    matchLiteral("field2", 10L, 10L);
    matchLiteral("field3", 1.2F, 1.2F);
    matchLiteral("field4", 3.4D, 3.4D);
    matchLiteral("field5", "abcd", "abcd");
    matchLiteral("field6", true, true);
    matchLiteral("field7", new byte[] {'a', 'b'}, ByteBuffer.wrap(new byte[] {'a', 'b'}));
    matchLiteral("field8", BigDecimal.valueOf(10.12), BigDecimal.valueOf(10.12));

    LocalDate date = LocalDate.parse("2020-12-23");
    matchLiteral("field9", date, DateTimeUtil.daysFromDate(date));

    LocalTime time = LocalTime.parse("12:13:14");
    matchLiteral("field10", time, DateTimeUtil.microsFromTime(time));

    LocalDateTime dateTime = LocalDateTime.parse("2020-12-23T12:13:14");
    matchLiteral("field11", dateTime, DateTimeUtil.microsFromTimestamp(dateTime));

    Instant instant = Instant.parse("2020-12-23T12:13:14.00Z");
    matchLiteral("field12", instant, DateTimeUtil.microsFromInstant(instant));
  }

  @Test
  public void testEquals() {
    for (Pair<String, Object> pair : FIELD_VALUE_LIST) {
      UnboundPredicate<?> expected =
          org.apache.iceberg.expressions.Expressions.equal(pair.first(), pair.second());

      Optional<org.apache.iceberg.expressions.Expression> actual =
          FlinkFilters.convert(
              resolve(Expressions.$(pair.first()).isEqual(Expressions.lit(pair.second()))));
      assertThat(actual).isPresent();
      assertPredicatesMatch(expected, actual.get());

      Optional<org.apache.iceberg.expressions.Expression> actual1 =
          FlinkFilters.convert(
              resolve(Expressions.lit(pair.second()).isEqual(Expressions.$(pair.first()))));
      assertThat(actual1).isPresent();
      assertPredicatesMatch(expected, actual1.get());
    }
  }

  @Test
  public void testEqualsNaN() {
    UnboundPredicate<Float> expected = org.apache.iceberg.expressions.Expressions.isNaN("field3");

    Optional<org.apache.iceberg.expressions.Expression> actual =
        FlinkFilters.convert(resolve(Expressions.$("field3").isEqual(Expressions.lit(Float.NaN))));
    assertThat(actual).isPresent();
    assertPredicatesMatch(expected, actual.get());

    Optional<org.apache.iceberg.expressions.Expression> actual1 =
        FlinkFilters.convert(resolve(Expressions.lit(Float.NaN).isEqual(Expressions.$("field3"))));
    assertThat(actual1).isPresent();
    assertPredicatesMatch(expected, actual1.get());
  }

  @Test
  public void testNotEquals() {
    for (Pair<String, Object> pair : FIELD_VALUE_LIST) {
      UnboundPredicate<?> expected =
          org.apache.iceberg.expressions.Expressions.notEqual(pair.first(), pair.second());

      Optional<org.apache.iceberg.expressions.Expression> actual =
          FlinkFilters.convert(
              resolve(Expressions.$(pair.first()).isNotEqual(Expressions.lit(pair.second()))));
      assertThat(actual).isPresent();
      assertPredicatesMatch(expected, actual.get());

      Optional<org.apache.iceberg.expressions.Expression> actual1 =
          FlinkFilters.convert(
              resolve(Expressions.lit(pair.second()).isNotEqual(Expressions.$(pair.first()))));
      assertThat(actual1).isPresent();
      assertPredicatesMatch(expected, actual1.get());
    }
  }

  @Test
  public void testNotEqualsNaN() {
    UnboundPredicate<Float> expected = org.apache.iceberg.expressions.Expressions.notNaN("field3");

    Optional<org.apache.iceberg.expressions.Expression> actual =
        FlinkFilters.convert(
            resolve(Expressions.$("field3").isNotEqual(Expressions.lit(Float.NaN))));
    assertThat(actual).isPresent();
    assertPredicatesMatch(expected, actual.get());

    Optional<org.apache.iceberg.expressions.Expression> actual1 =
        FlinkFilters.convert(
            resolve(Expressions.lit(Float.NaN).isNotEqual(Expressions.$("field3"))));
    assertThat(actual1).isPresent();
    assertPredicatesMatch(expected, actual1.get());
  }

  @Test
  public void testGreaterThan() {
    UnboundPredicate<Integer> expected =
        org.apache.iceberg.expressions.Expressions.greaterThan("field1", 1);

    Optional<org.apache.iceberg.expressions.Expression> actual =
        FlinkFilters.convert(resolve(Expressions.$("field1").isGreater(Expressions.lit(1))));
    assertThat(actual).isPresent();
    assertPredicatesMatch(expected, actual.get());

    Optional<org.apache.iceberg.expressions.Expression> actual1 =
        FlinkFilters.convert(resolve(Expressions.lit(1).isLess(Expressions.$("field1"))));
    assertThat(actual1).isPresent();
    assertPredicatesMatch(expected, actual1.get());
  }

  @Test
  public void testGreaterThanEquals() {
    UnboundPredicate<Integer> expected =
        org.apache.iceberg.expressions.Expressions.greaterThanOrEqual("field1", 1);

    Optional<org.apache.iceberg.expressions.Expression> actual =
        FlinkFilters.convert(resolve(Expressions.$("field1").isGreaterOrEqual(Expressions.lit(1))));
    assertThat(actual).isPresent();
    assertPredicatesMatch(expected, actual.get());

    Optional<org.apache.iceberg.expressions.Expression> actual1 =
        FlinkFilters.convert(resolve(Expressions.lit(1).isLessOrEqual(Expressions.$("field1"))));
    assertThat(actual1).isPresent();
    assertPredicatesMatch(expected, actual1.get());
  }

  @Test
  public void testLessThan() {
    UnboundPredicate<Integer> expected =
        org.apache.iceberg.expressions.Expressions.lessThan("field1", 1);

    Optional<org.apache.iceberg.expressions.Expression> actual =
        FlinkFilters.convert(resolve(Expressions.$("field1").isLess(Expressions.lit(1))));
    assertThat(actual).isPresent();
    assertPredicatesMatch(expected, actual.get());

    Optional<org.apache.iceberg.expressions.Expression> actual1 =
        FlinkFilters.convert(resolve(Expressions.lit(1).isGreater(Expressions.$("field1"))));
    assertThat(actual1).isPresent();
    assertPredicatesMatch(expected, actual1.get());
  }

  @Test
  public void testLessThanEquals() {
    UnboundPredicate<Integer> expected =
        org.apache.iceberg.expressions.Expressions.lessThanOrEqual("field1", 1);

    Optional<org.apache.iceberg.expressions.Expression> actual =
        FlinkFilters.convert(resolve(Expressions.$("field1").isLessOrEqual(Expressions.lit(1))));
    assertThat(actual).isPresent();
    assertPredicatesMatch(expected, actual.get());

    Optional<org.apache.iceberg.expressions.Expression> actual1 =
        FlinkFilters.convert(resolve(Expressions.lit(1).isGreaterOrEqual(Expressions.$("field1"))));
    assertThat(actual1).isPresent();
    assertPredicatesMatch(expected, actual1.get());
  }

  @Test
  public void testIsNull() {
    Expression expr = resolve(Expressions.$("field1").isNull());
    Optional<org.apache.iceberg.expressions.Expression> actual = FlinkFilters.convert(expr);
    assertThat(actual).isPresent();
    UnboundPredicate<Object> expected = org.apache.iceberg.expressions.Expressions.isNull("field1");
    assertPredicatesMatch(expected, actual.get());
  }

  @Test
  public void testIsNotNull() {
    Expression expr = resolve(Expressions.$("field1").isNotNull());
    Optional<org.apache.iceberg.expressions.Expression> actual = FlinkFilters.convert(expr);
    assertThat(actual).isPresent();
    UnboundPredicate<Object> expected =
        org.apache.iceberg.expressions.Expressions.notNull("field1");
    assertPredicatesMatch(expected, actual.get());
  }

  @Test
  public void testAnd() {
    Expression expr =
        resolve(
            Expressions.$("field1")
                .isEqual(Expressions.lit(1))
                .and(Expressions.$("field2").isEqual(Expressions.lit(2L))));
    Optional<org.apache.iceberg.expressions.Expression> actual = FlinkFilters.convert(expr);
    assertThat(actual).isPresent();
    And and = (And) actual.get();
    And expected =
        (And)
            org.apache.iceberg.expressions.Expressions.and(
                org.apache.iceberg.expressions.Expressions.equal("field1", 1),
                org.apache.iceberg.expressions.Expressions.equal("field2", 2L));

    assertPredicatesMatch(expected.left(), and.left());
    assertPredicatesMatch(expected.right(), and.right());
  }

  @Test
  public void testOr() {
    Expression expr =
        resolve(
            Expressions.$("field1")
                .isEqual(Expressions.lit(1))
                .or(Expressions.$("field2").isEqual(Expressions.lit(2L))));
    Optional<org.apache.iceberg.expressions.Expression> actual = FlinkFilters.convert(expr);
    assertThat(actual).isPresent();
    Or or = (Or) actual.get();
    Or expected =
        (Or)
            org.apache.iceberg.expressions.Expressions.or(
                org.apache.iceberg.expressions.Expressions.equal("field1", 1),
                org.apache.iceberg.expressions.Expressions.equal("field2", 2L));

    assertPredicatesMatch(expected.left(), or.left());
    assertPredicatesMatch(expected.right(), or.right());
  }

  @Test
  public void testNot() {
    Expression expr =
        resolve(
            ApiExpressionUtils.unresolvedCall(
                BuiltInFunctionDefinitions.NOT,
                Expressions.$("field1").isEqual(Expressions.lit(1))));
    Optional<org.apache.iceberg.expressions.Expression> actual = FlinkFilters.convert(expr);
    assertThat(actual).isPresent();
    Not not = (Not) actual.get();
    Not expected =
        (Not)
            org.apache.iceberg.expressions.Expressions.not(
                org.apache.iceberg.expressions.Expressions.equal("field1", 1));

    assertThat(not.op()).as("Predicate operation should match").isEqualTo(expected.op());
    assertPredicatesMatch(expected.child(), not.child());
  }

  @Test
  public void testLike() {
    UnboundPredicate<?> expected =
        org.apache.iceberg.expressions.Expressions.startsWith("field5", "abc");
    Expression expr =
        resolve(
            ApiExpressionUtils.unresolvedCall(
                BuiltInFunctionDefinitions.LIKE, Expressions.$("field5"), Expressions.lit("abc%")));
    Optional<org.apache.iceberg.expressions.Expression> actual = FlinkFilters.convert(expr);
    assertThat(actual).isPresent();
    assertPredicatesMatch(expected, actual.get());

    expr =
        resolve(
            ApiExpressionUtils.unresolvedCall(
                BuiltInFunctionDefinitions.LIKE, Expressions.$("field5"), Expressions.lit("%abc")));
    actual = FlinkFilters.convert(expr);
    assertThat(actual).isNotPresent();

    expr =
        resolve(
            ApiExpressionUtils.unresolvedCall(
                BuiltInFunctionDefinitions.LIKE,
                Expressions.$("field5"),
                Expressions.lit("%abc%")));
    actual = FlinkFilters.convert(expr);
    assertThat(actual).isNotPresent();

    expr =
        resolve(
            ApiExpressionUtils.unresolvedCall(
                BuiltInFunctionDefinitions.LIKE,
                Expressions.$("field5"),
                Expressions.lit("abc%d")));
    actual = FlinkFilters.convert(expr);
    assertThat(actual).isNotPresent();

    expr =
        resolve(
            ApiExpressionUtils.unresolvedCall(
                BuiltInFunctionDefinitions.LIKE, Expressions.$("field5"), Expressions.lit("%")));
    actual = FlinkFilters.convert(expr);
    assertThat(actual).isNotPresent();

    expr =
        resolve(
            ApiExpressionUtils.unresolvedCall(
                BuiltInFunctionDefinitions.LIKE, Expressions.$("field5"), Expressions.lit("a_")));
    actual = FlinkFilters.convert(expr);
    assertThat(actual).isNotPresent();

    expr =
        resolve(
            ApiExpressionUtils.unresolvedCall(
                BuiltInFunctionDefinitions.LIKE, Expressions.$("field5"), Expressions.lit("a%b")));
    actual = FlinkFilters.convert(expr);
    assertThat(actual).isNotPresent();
  }

  @SuppressWarnings("unchecked")
  private <T> void matchLiteral(String fieldName, Object flinkLiteral, T icebergLiteral) {
    Expression expr = resolve(Expressions.$(fieldName).isEqual(Expressions.lit(flinkLiteral)));
    Optional<org.apache.iceberg.expressions.Expression> actual = FlinkFilters.convert(expr);
    assertThat(actual).isPresent();
    org.apache.iceberg.expressions.Expression expression = actual.get();
    assertThat(expression)
        .as("The expression should be a UnboundPredicate")
        .isInstanceOf(UnboundPredicate.class);
    UnboundPredicate<T> unboundPredicate = (UnboundPredicate<T>) expression;

    org.apache.iceberg.expressions.Expression expression1 =
        unboundPredicate.bind(FlinkSchemaUtil.convert(RESOLVED_SCHEMA).asStruct(), false);
    assertThat(expression1)
        .as("The expression should be a BoundLiteralPredicate")
        .isInstanceOf(BoundLiteralPredicate.class);

    BoundLiteralPredicate<T> predicate = (BoundLiteralPredicate<T>) expression1;
    assertThat(predicate.test(icebergLiteral)).isTrue();
  }

  private static Expression resolve(Expression originalExpression) {
    return originalExpression.accept(
        new ApiExpressionDefaultVisitor<>() {
          @Override
          public Expression visit(UnresolvedReferenceExpression unresolvedReference) {
            String name = unresolvedReference.getName();
            return RESOLVED_SCHEMA
                .getColumn(name)
                .map(
                    column -> {
                      int columnIndex = RESOLVED_SCHEMA.getColumns().indexOf(column);
                      return new FieldReferenceExpression(
                          name, column.getDataType(), 0, columnIndex);
                    })
                .orElse(null);
          }

          @Override
          public Expression visit(UnresolvedCallExpression unresolvedCall) {
            List<ResolvedExpression> children =
                unresolvedCall.getChildren().stream()
                    .map(e -> (ResolvedExpression) e.accept(this))
                    .collect(Collectors.toList());
            // TODO mxm false?
            return new CallExpression(
                unresolvedCall.getFunctionDefinition(), children, DataTypes.STRING());
          }

          @Override
          public Expression visit(ValueLiteralExpression valueLiteral) {
            return valueLiteral;
          }

          @Override
          protected Expression defaultMethod(Expression expression) {
            throw new UnsupportedOperationException(
                String.format("unsupported expression: %s", expression));
          }
        });
  }

  private void assertPredicatesMatch(
      org.apache.iceberg.expressions.Expression expected,
      org.apache.iceberg.expressions.Expression actual) {
    assertThat(expected)
        .as("The expected expression should be a UnboundPredicate")
        .isInstanceOf(UnboundPredicate.class);
    assertThat(actual)
        .as("The actual expression should be a UnboundPredicate")
        .isInstanceOf(UnboundPredicate.class);
    UnboundPredicate<?> predicateExpected = (UnboundPredicate<?>) expected;
    UnboundPredicate<?> predicateActual = (UnboundPredicate<?>) actual;
    assertThat(predicateActual.op()).isEqualTo(predicateExpected.op());
    assertThat(predicateActual.literal()).isEqualTo(predicateExpected.literal());
    assertThat(predicateActual.ref().name()).isEqualTo(predicateExpected.ref().name());
  }
}
