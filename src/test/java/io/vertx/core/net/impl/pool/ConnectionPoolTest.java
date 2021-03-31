/*
 * Copyright (c) 2011-2021 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.vertx.core.net.impl.pool;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.ConnectionPoolTooBusyException;
import io.vertx.core.impl.EventLoopContext;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.net.impl.clientconnection.ConnectResult;
import io.vertx.core.net.impl.clientconnection.Lease;
import io.vertx.test.core.VertxTestBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class ConnectionPoolTest extends VertxTestBase {

  VertxInternal vertx;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    this.vertx = (VertxInternal) super.vertx;
  }

  @Test
  public void testConnect() {
    EventLoopContext context = vertx.createEventLoopContext();
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 10, 10);
    Connection expected = new Connection();
    pool.acquire(context, 1, onSuccess(lease -> {
      assertSame(expected, lease.get());
      assertSame(context, Vertx.currentContext());
      testComplete();
    }));
    ConnectionRequest request = mgr.assertRequest();
    assertSame(context, request.context);
    request.connect(expected, 1);
    await();
  }

  @Test
  public void testAcquireRecycledConnection() throws Exception {
    EventLoopContext context = vertx.createEventLoopContext();
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 10, 10);
    Connection expected = new Connection();
    CountDownLatch latch = new CountDownLatch(1);
    pool.acquire(context, 1, onSuccess(lease -> {
      lease.recycle();
      latch.countDown();
    }));
    ConnectionRequest request = mgr.assertRequest();
    assertSame(context, request.context);
    request.connect(expected, 1);
    awaitLatch(latch);
    pool.acquire(context, 1, onSuccess(lease -> {
      assertSame(expected, lease.get());
      assertSame(context, Vertx.currentContext());
      testComplete();
    }));
    await();
  }

  @Test
  public void testRecycleRemovedConnection() throws Exception {
    EventLoopContext context = vertx.createEventLoopContext();
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 10, 10);
    Connection expected1 = new Connection();
    Promise<Lease<Connection>> promise = Promise.promise();
    pool.acquire(context, 1, promise);
    ConnectionRequest request1 = mgr.assertRequest();
    request1.connect(expected1, 1);
    CountDownLatch latch = new CountDownLatch(1);
    promise.future().onComplete(onSuccess(lease -> {
      request1.listener.onRemove();
      lease.recycle();
      latch.countDown();
    }));
    awaitLatch(latch);
    Connection expected2 = new Connection();
    pool.acquire(context, 1, onSuccess(lease -> {
      assertSame(expected2, lease.get());
      assertSame(context, Vertx.currentContext());
      testComplete();
    }));
    ConnectionRequest request2 = mgr.assertRequest();
    request2.connect(expected2, 1);
    await();
  }

  @Test
  public void testCapacity() throws Exception {
    EventLoopContext context = vertx.createEventLoopContext();
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 10, 10);
    int capacity = 2;
    Connection expected = new Connection(capacity);
    CountDownLatch latch = new CountDownLatch(1);
    pool.acquire(context, 1, onSuccess(conn -> {
      latch.countDown();
    }));
    ConnectionRequest request = mgr.assertRequest();
    request.connect(expected, 1);
    awaitLatch(latch);
    pool.acquire(context, 1, onSuccess(lease -> {
      assertSame(lease.get(), expected);
      testComplete();
    }));
    await();
  }

  @Test
  public void testSatisfyPendingWaitersWithExtraCapacity() throws Exception {
    EventLoopContext context = vertx.createEventLoopContext();
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 1, 1);
    int capacity = 2;
    Connection expected = new Connection(capacity);
    AtomicInteger seq = new AtomicInteger();
    pool.acquire(context, 1, onSuccess(lease -> {
      assertSame(lease.get(), expected);
      assertEquals(1, seq.incrementAndGet());
    }));
    pool.acquire(context, 1, onSuccess(lease -> {
      assertSame(lease.get(), expected);
      assertEquals(2, seq.incrementAndGet());
      testComplete();
    }));
    ConnectionRequest request = mgr.assertRequest();
    request.connect(expected, 1);
    await();
  }

  @Test
  public void testWaiter() throws Exception {
    EventLoopContext ctx1 = vertx.createEventLoopContext();
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 1, 1);
    Connection expected = new Connection();
    CompletableFuture<Lease<Connection>> latch = new CompletableFuture<>();
    pool.acquire(ctx1, 1, onSuccess(latch::complete));
    ConnectionRequest request = mgr.assertRequest();
    request.connect(expected, 1);
    Lease<Connection> lease1 = latch.get(10, TimeUnit.SECONDS);
    AtomicBoolean recycled = new AtomicBoolean();
    EventLoopContext ctx2 = vertx.createEventLoopContext();
    pool.acquire(ctx2, 1, onSuccess(lease2 -> {
      assertSame(ctx1, Vertx.currentContext());
      assertTrue(recycled.get());
      testComplete();
    }));
    assertEquals(1, pool.waiters());
    recycled.set(true);
    lease1.recycle();
    await();
  }

  @Test
  public void testRemoveSingleConnection() throws Exception {
    EventLoopContext ctx1 = vertx.createEventLoopContext();
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 1, 1);
    Connection conn = new Connection();
    CompletableFuture<Lease<Connection>> latch = new CompletableFuture<>();
    pool.acquire(ctx1, 1, onSuccess(latch::complete));
    ConnectionRequest request = mgr.assertRequest();
    request.connect(conn, 1);
    latch.get(10, TimeUnit.SECONDS);
    request.listener.onRemove();
    assertEquals(0, pool.size());
    assertEquals(0, pool.weight());
  }

  @Test
  public void testRemoveFirstConnection() throws Exception {
    EventLoopContext ctx = vertx.createEventLoopContext();
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 2, 2);
    Connection conn1 = new Connection();
    CompletableFuture<Lease<Connection>> latch1 = new CompletableFuture<>();
    pool.acquire(ctx, 1, onSuccess(latch1::complete));
    Connection conn2 = new Connection();
    CompletableFuture<Lease<Connection>> latch2 = new CompletableFuture<>();
    pool.acquire(ctx, 1, onSuccess(latch2::complete));
    ConnectionRequest request1 = mgr.assertRequest();
    request1.connect(conn1, 1);
    ConnectionRequest request2 = mgr.assertRequest();
    request2.connect(conn2, 1);
    latch1.get(10, TimeUnit.SECONDS);
    request1.listener.onRemove();
    assertEquals(1, pool.size());
    assertEquals(1, pool.weight());
  }

  @Test
  public void testRemoveSingleConnectionWithWaiter() throws Exception {
    EventLoopContext ctx1 = vertx.createEventLoopContext();
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 1, 1);
    Connection connection1 = new Connection();
    CompletableFuture<Lease<Connection>> latch = new CompletableFuture<>();
    pool.acquire(ctx1, 1, onSuccess(latch::complete));
    ConnectionRequest request1 = mgr.assertRequest();
    request1.connect(connection1, 1);
    Lease<Connection> lease1 = latch.get(10, TimeUnit.SECONDS);
    assertSame(connection1, lease1.get());
    AtomicBoolean evicted = new AtomicBoolean();
    Connection conn2 = new Connection();
    EventLoopContext ctx2 = vertx.createEventLoopContext();
    pool.acquire(ctx2, 1, onSuccess(lease2 -> {
      assertSame(ctx2, Vertx.currentContext());
      assertTrue(evicted.get());
      assertSame(conn2, lease2.get());
      testComplete();
    }));
    assertEquals(1, pool.waiters());
    evicted.set(true);
    request1.listener.onRemove();
    ConnectionRequest request2 = mgr.assertRequest();
    request2.connect(conn2, 1);
    await();
  }

  @Test
  public void testConnectFailureWithPendingWaiter() throws Exception {
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 2, 2);
    Throwable failure = new Throwable();
    Connection expected = new Connection();
    CountDownLatch latch = new CountDownLatch(1);
    EventLoopContext ctx1 = vertx.createEventLoopContext();
    pool.acquire(ctx1, 2, onFailure(cause -> {
      assertSame(failure, cause);
      latch.countDown();
    }));
    EventLoopContext ctx2 = vertx.createEventLoopContext();
    pool.acquire(ctx2, 1, onSuccess(lease -> {
      assertSame(expected, lease.get());
      testComplete();
    }));
    ConnectionRequest request1 = mgr.assertRequest();
    assertEquals(2, pool.weight());
    request1.fail(failure);
    awaitLatch(latch);
    assertEquals(1, pool.weight());
    ConnectionRequest request2 = mgr.assertRequest();
    request2.connect(expected, 1);
    await();
  }

  @Test
  public void testExpireFirst() throws Exception {
    assertEquals(Arrays.asList(0), testExpire(1, 10, 0));
    assertEquals(Arrays.asList(0), testExpire(2, 10, 0));
    assertEquals(Arrays.asList(0), testExpire(3, 10, 0));
  }

  @Test
  public void testExpireLast() throws Exception {
    assertEquals(Arrays.asList(0), testExpire(1, 10, 0));
    assertEquals(Arrays.asList(1), testExpire(2, 10, 1));
    assertEquals(Arrays.asList(2), testExpire(3, 10, 2));
  }

  @Test
  public void testExpireMiddle() throws Exception {
    assertEquals(Arrays.asList(1), testExpire(3, 10, 1));
  }

  @Test
  public void testExpireSome() throws Exception {
    assertEquals(Arrays.asList(2, 1), testExpire(3, 10, 1, 2));
    assertEquals(Arrays.asList(2, 1, 0), testExpire(3, 10, 0, 1, 2));
    assertEquals(Arrays.asList(1, 0), testExpire(3, 10, 0, 1));
  }

  private List<Integer> testExpire(int num, int max, int... recycled) throws Exception {
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, max, max);
    CountDownLatch latch = new CountDownLatch(num);
    List<Lease<Connection>> leases = new ArrayList<>();
    EventLoopContext ctx = vertx.createEventLoopContext();
    for (int i = 0;i < num;i++) {
      Connection expected = new Connection();
      pool.acquire(ctx, 1, onSuccess(lease -> {
        assertSame(expected, lease.get());
        leases.add(lease);
        latch.countDown();
      }));
      mgr.assertRequest().connect(expected, 1);
    }
    awaitLatch(latch);
    for (int i = 0;i < recycled.length;i++) {
      leases.get(recycled[i]).recycle();
    }
    CompletableFuture<List<Integer>> cf = new CompletableFuture<>();
    pool.evict(c -> true, ar -> {
      if (ar.succeeded()) {
        assertEquals(num - recycled.length, pool.weight());
        List<Integer> res = new ArrayList<>();
        List<Connection> all = leases.stream().map(Lease::get).collect(Collectors.toList());
        ar.result().forEach(c -> res.add(all.indexOf(c)));
        cf.complete(res);
      } else {
        cf.completeExceptionally(ar.cause());
      }
    });
    return cf.get();
  }

  @Test
  public void testConnectionInProgressShouldNotBeEvicted() {
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 1, 1, 5);
    EventLoopContext ctx = vertx.createEventLoopContext();
    pool.acquire(ctx, 1, ar -> {
    });
    mgr.assertRequest();
    pool.evict(c -> {
      fail();
      return false;
    }, onSuccess(v -> {
      testComplete();
    }));
    await();
  }

  @Test
  public void testRecycleRemoveConnection() throws Exception {
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 1, 1);
    Connection expected = new Connection();
    CompletableFuture<Lease<Connection>> latch = new CompletableFuture<>();
    EventLoopContext ctx1 = vertx.createEventLoopContext();
    pool.acquire(ctx1, 1, onSuccess(latch::complete));
    ConnectionRequest request = mgr.assertRequest();
    request.connect(expected, 1);
    Lease<Connection> lease = latch.get();
    request.listener.onRemove();
    assertEquals(0, pool.size());
    lease.recycle();
    assertEquals(0, pool.size());
  }

  @Test
  public void testRecycleMultiple() throws Exception {
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 1, 1);
    Connection expected = new Connection();
    CompletableFuture<Lease<Connection>> latch = new CompletableFuture<>();
    EventLoopContext ctx1 = vertx.createEventLoopContext();
    pool.acquire(ctx1, 1, onSuccess(latch::complete));
    ConnectionRequest request = mgr.assertRequest();
    request.connect(expected, 1);
    Lease<Connection> lease = latch.get();
    lease.recycle();
    try {
      lease.recycle();
      fail();
    } catch (IllegalStateException ignore) {
    }
  }

  @Test
  public void testMaxWaiters() {
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 1, 1, 5);
    EventLoopContext ctx = vertx.createEventLoopContext();
    for (int i = 0;i < (1 + 5);i++) {
      pool.acquire(ctx, 1, ar -> fail());
    }
    pool.acquire(ctx, 1, onFailure(err -> {
      assertTrue(err instanceof ConnectionPoolTooBusyException);
      testComplete();
    }));
    await();
  }

  @Test
  public void testWeight() throws Exception {
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 5, 2 * 5);
    EventLoopContext ctx = vertx.createEventLoopContext();
    CountDownLatch latch = new CountDownLatch(5);
    for (int i = 0;i < 5;i++) {
      pool.acquire(ctx, 5, onSuccess(lease -> {
        latch.countDown();
      }));
      Connection conn = new Connection();
      mgr.assertRequest().connect(conn, 2);
    }
    awaitLatch(latch);
    assertEquals(10, pool.weight());
    pool.acquire(ctx, 5, onSuccess(lease -> {

    }));
    assertEquals(1, pool.waiters());
  }

  @Test
  public void testClose() throws Exception {
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 2, 2);
    EventLoopContext ctx = vertx.createEventLoopContext();
    Connection conn1 = new Connection();
    pool.acquire(ctx, 1, onSuccess(lease -> {

    }));
    waitFor(3);
    pool.acquire(ctx, 1, onFailure(err -> {
      complete();
    }));
    pool.acquire(ctx, 1, onFailure(err -> {
      complete();
    }));
    mgr.assertRequest().connect(conn1, 1);
    mgr.assertRequest();
    pool.close(onSuccess(lst -> {
      assertEquals(2, lst.size());

      // does this assertion make sense?
      // actually the pool.size() == 2 in this case.
      assertEquals(0, pool.size());
      complete();
    }));
    await();
  }

  @Test
  public void testCloseTwice() throws Exception {
    AtomicBoolean isReentrant = new AtomicBoolean();
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 2, 2);
    CountDownLatch latch = new CountDownLatch(1);
    pool.close(onSuccess(lst -> {
      AtomicBoolean inCallback = new AtomicBoolean();
      pool.close(onFailure(err -> {
        isReentrant.set(inCallback.get());
        latch.countDown();
      }));
    }));
    awaitLatch(latch);
    assertFalse(isReentrant.get());
  }

  @Test
  public void testUseAfterClose() throws Exception {
    waitFor(3);
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 1, 1);
    EventLoopContext ctx = vertx.createEventLoopContext();
    CompletableFuture<PoolWaiter<Connection>> waiterFut = new CompletableFuture<>();
    pool.acquire(ctx, new PoolWaiter.Listener<Connection>() {
      @Override
      public void onConnect(PoolWaiter<Connection> waiter) {
        waiterFut.complete(waiter);
      }
    }, 1, ar -> {
      // Failed
    });
    PoolWaiter<Connection> waiter = waiterFut.get(20, TimeUnit.SECONDS);
    ConnectionRequest request = mgr.assertRequest();
    CountDownLatch latch = new CountDownLatch(1);
    pool.close(onSuccess(lst -> {
      latch.countDown();
    }));
    awaitLatch(latch);
    pool.evict(c -> true, onFailure(err -> {
      complete();
    }));
    pool.acquire(ctx, 1, onFailure(err -> {
      complete();
    }));
    pool.cancel(waiter, onFailure(err -> {
      complete();
    }));
    request.connect(new Connection(), 1);
    await();
  }

  @Test
  public void testCancelQueuedWaiters() throws Exception {
    waitFor(1);
    EventLoopContext context = vertx.createEventLoopContext();
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 0, 0);
    CompletableFuture<PoolWaiter<Connection>> w = new CompletableFuture<>();
    pool.acquire(context, new PoolWaiter.Listener<Connection>() {
      @Override
      public void onEnqueue(PoolWaiter<Connection> waiter) {
        w.complete(waiter);
      }
    }, 1, ar -> fail());
    w.get(10, TimeUnit.SECONDS);
    pool.cancel(w.get(10, TimeUnit.SECONDS), onSuccess(removed -> {
      assertTrue(removed);
      testComplete();
    }));
    await();
  }

  @Test
  public void testCancelWaiterBeforeConnectionSuccess() throws Exception {
    testCancelWaiterBeforeConnection(true);
  }

  @Test
  public void testCancelWaiterBeforeConnectionFailure() throws Exception {
    testCancelWaiterBeforeConnection(false);
  }

  public void testCancelWaiterBeforeConnection(boolean success) throws Exception {
    waitFor(1);
    EventLoopContext context = vertx.createEventLoopContext();
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 1, 1);
    CompletableFuture<PoolWaiter<Connection>> w = new CompletableFuture<>();
    pool.acquire(context, new PoolWaiter.Listener<Connection>() {
      @Override
      public void onConnect(PoolWaiter<Connection> waiter) {
        w.complete(waiter);
      }
    }, 1, ar -> fail());
    w.get(10, TimeUnit.SECONDS);
    ConnectionRequest request = mgr.assertRequest();
    CountDownLatch latch = new CountDownLatch(1);
    pool.cancel(w.get(10, TimeUnit.SECONDS), onSuccess(removed -> {
      assertTrue(removed);
      latch.countDown();
    }));
    awaitLatch(latch);
    if (success) {
      request.connect(new Connection(), 1);
    } else {
      request.fail(new Throwable());
    }
  }

  @Test
  public void testCancelWaiterAfterConnectionSuccess() throws Exception {
    testCancelWaiterAfterConnectionSuccess(true);
  }

  @Test
  public void testCancelWaiterAfterConnectionFailure() throws Exception {
    testCancelWaiterAfterConnectionSuccess(false);
  }

  public void testCancelWaiterAfterConnectionSuccess(boolean success) throws Exception {
    waitFor(1);
    EventLoopContext context = vertx.createEventLoopContext();
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 1, 1);
    CompletableFuture<PoolWaiter<Connection>> w = new CompletableFuture<>();
    CountDownLatch latch = new CountDownLatch(1);
    pool.acquire(context, new PoolWaiter.Listener<Connection>() {
      @Override
      public void onConnect(PoolWaiter<Connection> waiter) {
        w.complete(waiter);
      }
    }, 1, ar -> {
      latch.countDown();
    });
    w.get(10, TimeUnit.SECONDS);
    ConnectionRequest request = mgr.assertRequest();
    if (success) {
      request.connect(new Connection(), 1);
    } else {
      request.fail(new Throwable());
    }
    awaitLatch(latch);
    pool.cancel(w.get(10, TimeUnit.SECONDS), onSuccess(removed -> {
      assertFalse(removed);
      testComplete();
    }));
    await();
  }

  @Test
  public void testConnectionSelector() throws Exception {
    waitFor(1);
    EventLoopContext context = vertx.createEventLoopContext();
    ConnectionManager mgr = new ConnectionManager();
    ConnectionPool<Connection> pool = ConnectionPool.pool(mgr, 2, 2);
    CountDownLatch latch1 = new CountDownLatch(1);
    pool.acquire(context, 1, onSuccess(lease -> {
      lease.recycle();
      latch1.countDown();
    }));
    Connection conn1 = new Connection();
    mgr.assertRequest().connect(conn1, 1);
    awaitLatch(latch1);
    pool.connectionSelector((waiter, list) -> {
      assertEquals(1, list.size());
      PoolConnection<Connection> pooled = list.get(0);
      assertEquals(1, pooled.capacity());
      assertEquals(1, pooled.maxCapacity());
      assertSame(conn1, pooled.get());
      assertSame(context, pooled.context());
      assertSame(context, waiter.context());
      return pooled;
    });
    pool.acquire(context, 1, onSuccess(lease -> {
      testComplete();
    }));
    await();
  }

  static class Connection {
    final int capacity;
    public Connection() {
      this(1);
    }
    public Connection(int capacity) {
      this.capacity = capacity;
    }
  }

  static class ConnectionRequest {
    final EventLoopContext context;
    final PoolConnector.Listener listener;
    final Handler<AsyncResult<ConnectResult<Connection>>> handler;
    ConnectionRequest(EventLoopContext context, PoolConnector.Listener listener, Handler<AsyncResult<ConnectResult<Connection>>> handler) {
      this.context = context;
      this.listener = listener;
      this.handler = handler;
    }
    void connect(Connection connection, int weight) {
      handler.handle(Future.succeededFuture(new ConnectResult<>(connection, connection.capacity, weight)));
    }

    public void fail(Throwable cause) {
      handler.handle(Future.failedFuture(cause));
    }
  }

  class ConnectionManager implements PoolConnector<Connection> {

    private final Queue<ConnectionRequest> requests = new ArrayBlockingQueue<>(100);

    @Override
    public void connect(EventLoopContext context, Listener listener, Handler<AsyncResult<ConnectResult<Connection>>> handler) {
      requests.add(new ConnectionRequest(context, listener, handler));
    }

    @Override
    public boolean isValid(Connection connection) {
      return true;
    }

    ConnectionRequest assertRequest() {
      ConnectionRequest request = requests.poll();
      assertNotNull(request);
      return request;
    }
  }
}