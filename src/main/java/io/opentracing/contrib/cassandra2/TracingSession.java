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
package io.opentracing.contrib.cassandra2;

import com.datastax.driver.core.*;
import com.google.common.base.Function;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.contrib.cassandra2.nameprovider.CustomStringSpanName;
import io.opentracing.contrib.cassandra2.nameprovider.QuerySpanNameProvider;
import io.opentracing.tag.Tags;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decorator for {@link Session} Instantiated by TracingCluster
 */
public class TracingSession implements AsyncInitSession {

  static final String COMPONENT_NAME = "java-cassandra";
  private static final boolean queryParamsExtracted = retrieveQueryParamsExtractedConfig();

  private final ExecutorService executorService;
  private final Session session;
  private final Tracer tracer;
  private final QuerySpanNameProvider querySpanNameProvider;

  private final static boolean retrieveQueryParamsExtractedConfig() {
    // Perhaps we should be able to configure that?
    return true;
  }

  public TracingSession(Session session, Tracer tracer) {
    this.session = session;
    this.tracer = tracer;
    this.querySpanNameProvider = CustomStringSpanName.newBuilder().build("execute");
    this.executorService = Executors.newCachedThreadPool();
  }

  public TracingSession(Session session, Tracer tracer,
      QuerySpanNameProvider querySpanNameProvider) {
    this.session = session;
    this.tracer = tracer;
    this.querySpanNameProvider = querySpanNameProvider;
    this.executorService = Executors.newCachedThreadPool();
  }

  public TracingSession(Session session, Tracer tracer, QuerySpanNameProvider querySpanNameProvider,
      ExecutorService executorService) {
    this.session = session;
    this.tracer = tracer;
    this.querySpanNameProvider = querySpanNameProvider;
    this.executorService = executorService;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getLoggedKeyspace() {
    return session.getLoggedKeyspace();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public AsyncInitSession init() {
    return new TracingSession(session.init(), tracer, querySpanNameProvider, executorService);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ListenableFuture<Session> initAsync() {
    return Futures.transform(((AsyncInitSession) session).initAsync(), new Function<Session, Session>() {
          @Override
          public Session apply(Session session) {
            return new TracingSession((AsyncInitSession) session, tracer);
          }
        });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet execute(String query) {
    Span span = buildSpan(query);
    ResultSet resultSet;
    try {
      resultSet = session.execute(query);
      finishSpan(span, resultSet);
      return resultSet;
    } catch (Exception e) {
      finishSpan(span, e);
      throw e;
    }

  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet execute(String query, Object... values) {
    Span span = buildSpan(query);
    ResultSet resultSet;
    try {
      addQueryParams(span, values);
      resultSet = session.execute(query, values);
      finishSpan(span, resultSet);
      return resultSet;
    } catch (Exception e) {
      finishSpan(span, e);
      throw e;
    }
  }


  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet execute(Statement statement) {
    String query = getQuery(statement);
    Span span = buildSpan(query);
    addQueryParams(span, statement);

    ResultSet resultSet = null;
    try {
      resultSet = session.execute(statement);
      finishSpan(span, resultSet);
      return resultSet;
    } catch (Exception e) {
      finishSpan(span, e);
      throw e;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSetFuture executeAsync(String query) {
    final Span span = buildSpan(query);
    ResultSetFuture future = session.executeAsync(query);
    future.addListener(createListener(span, future), executorService);

    return future;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSetFuture executeAsync(String query, Object... values) {
    final Span span = buildSpan(query);
    addQueryParams(span, values);

    ResultSetFuture future = session.executeAsync(query, values);
    future.addListener(createListener(span, future), executorService);

    return future;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSetFuture executeAsync(Statement statement) {
    String query = getQuery(statement);
    Span span = buildSpan(query);
    addQueryParams(span, statement);

    try {
      ResultSetFuture future = session.executeAsync(statement);
      future.addListener(createListener(span, future), executorService);
      return future;
    } catch (Exception e) {
      finishSpan(span, e);
      throw e;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public PreparedStatement prepare(String query) {
    return session.prepare(query);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public PreparedStatement prepare(RegularStatement statement) {
    return session.prepare(statement);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ListenableFuture<PreparedStatement> prepareAsync(String query) {
    return session.prepareAsync(query);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ListenableFuture<PreparedStatement> prepareAsync(RegularStatement statement) {
    return session.prepareAsync(statement);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CloseFuture closeAsync() {
    return session.closeAsync();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() {
    session.close();
  }

  @Override
  public boolean isClosed() {
    return session.isClosed();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Cluster getCluster() {
    return session.getCluster();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public State getState() {
    return session.getState();
  }

  private static String getQuery(Statement statement) {
    String query = null;
    if (statement instanceof BoundStatement) {
      query = ((BoundStatement) statement).preparedStatement().getQueryString();
    } else if (statement instanceof RegularStatement) {
      query = ((RegularStatement) statement).getQueryString();
    }

    return query == null ? "" : query;
  }

  private static final String buildValueName(String valueName) {
    return String.format("%s.%s", Tags.DB_STATEMENT.getKey(), normalizeName(valueName));
  }

  private static final String buildValueIndex(int valueIndex) {
    return String.format("%s.value_%d", Tags.DB_STATEMENT.getKey(), valueIndex);
  }

  private static final Pattern mapValueMatch = Pattern.compile("value\\((.*)\\)");

  private static final String normalizeName(String paramName) {
    Matcher m = mapValueMatch.matcher(paramName);
    if (m.matches()) {
      return m.group(1);
    } else {
      return paramName;
    }
  }

  private static void addQueryParams(Span span, Statement statement) {
    if (!queryParamsExtracted)
      return;

    if (statement instanceof BoundStatement) {
      Multiset<String> allParamNames = HashMultiset.create();

      List<ColumnDefinitions.Definition> vars = ((BoundStatement) statement).preparedStatement().getVariables().asList();
      for (ColumnDefinitions.Definition def : vars) {
        allParamNames.add(def.getName());
      }

      int index = 0;
      for (ColumnDefinitions.Definition def : vars) {
        try {
          if (allParamNames.count(def.getName()) > 1) {
            span.setTag(buildValueIndex(index), String.valueOf(((BoundStatement) statement).getObject(index)));
          } else {
            span.setTag(buildValueName(def.getName()), String.valueOf(((BoundStatement) statement).getObject(def.getName())));
          }
        } catch (Throwable ignored) { /* An ill-described query will fail here */ }

        index++;
      }
    }
  }

  private static void addQueryParams(Span span, Object... params) {
    if (!queryParamsExtracted)
      return;

    for (int i = 0; i < params.length; i++) {
      span.setTag(buildValueIndex(i), String.valueOf(params[i]));
    }
  }

  private static Runnable createListener(final Span span, final ResultSetFuture future) {
    return new Runnable() {
      @Override
      public void run() {
        try {
          finishSpan(span, future.get());
        } catch (InterruptedException | ExecutionException e) {
          finishSpan(span, e);
        }
      }
    };
  }

  private Span buildSpan(String query) {
    String querySpanName = querySpanNameProvider.querySpanName(query);
    Tracer.SpanBuilder spanBuilder = tracer.buildSpan(querySpanName)
        .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT);

    Span span = spanBuilder.start();

    Tags.COMPONENT.set(span, COMPONENT_NAME);
    Tags.DB_STATEMENT.set(span, query);
    Tags.DB_TYPE.set(span, "cassandra");

    String keyspace = getLoggedKeyspace();
    if (keyspace != null) {
      Tags.DB_INSTANCE.set(span, keyspace);
    }

    return span;
  }

  private static void finishSpan(Span span, ResultSet resultSet) {
    if (resultSet != null) {
      Host host = resultSet.getExecutionInfo().getQueriedHost();
      Tags.PEER_PORT.set(span, host.getSocketAddress().getPort());

      Tags.PEER_HOSTNAME.set(span, host.getAddress().getHostName());
      InetAddress inetAddress = host.getSocketAddress().getAddress();

      if (inetAddress instanceof Inet4Address) {
        byte[] address = inetAddress.getAddress();
        Tags.PEER_HOST_IPV4.set(span, ByteBuffer.wrap(address).getInt());
      } else {
        Tags.PEER_HOST_IPV6.set(span, inetAddress.getHostAddress());
      }

    }
    span.finish();
  }

  private static void finishSpan(Span span, Exception e) {
    Tags.ERROR.set(span, Boolean.TRUE);
    span.log(errorLogs(e));
    span.finish();
  }

  private static Map<String, Object> errorLogs(Throwable throwable) {
    Map<String, Object> errorLogs = new HashMap<>(4);
    errorLogs.put("event", Tags.ERROR.getKey());
    errorLogs.put("error.kind", throwable.getClass().getName());
    errorLogs.put("error.object", throwable);

    errorLogs.put("message", throwable.getMessage());

    StringWriter sw = new StringWriter();
    throwable.printStackTrace(new PrintWriter(sw));
    errorLogs.put("stack", sw.toString());

    return errorLogs;
  }
}
