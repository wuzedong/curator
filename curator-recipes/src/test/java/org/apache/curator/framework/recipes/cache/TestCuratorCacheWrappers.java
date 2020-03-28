/**
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

package org.apache.curator.framework.recipes.cache;

import com.google.common.collect.ImmutableSet;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.compatibility.CuratorTestBase;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.AbstractMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.curator.framework.recipes.cache.CuratorCache.Options.SINGLE_NODE_CACHE;
import static org.apache.curator.framework.recipes.cache.CuratorCacheAccessor.parentPathFilter;
import static org.apache.curator.framework.recipes.cache.CuratorCacheListener.builder;

@Test(groups = CuratorTestBase.zk36Group)
public class TestCuratorCacheWrappers extends CuratorTestBase
{
    @Test
    public void testPathChildrenCache() throws Exception    // copied from TestPathChildrenCache#testBasics()
    {
        try (CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1)))
        {
            client.start();
            client.create().forPath("/test");

            final BlockingQueue<PathChildrenCacheEvent.Type> events = new LinkedBlockingQueue<>();
            try (CuratorCache cache = CuratorCache.build(client, "/test"))
            {
                PathChildrenCacheListener listener = (__, event) -> {
                    if ( event.getData().getPath().equals("/test/one") )
                    {
                        events.offer(event.getType());
                    }
                };
                cache.listenable().addListener(builder().forPathChildrenCache(client, listener).build());
                cache.start();

                client.create().forPath("/test/one", "hey there".getBytes());
                Assert.assertEquals(events.poll(timing.forWaiting().seconds(), TimeUnit.SECONDS), PathChildrenCacheEvent.Type.CHILD_ADDED);

                client.setData().forPath("/test/one", "sup!".getBytes());
                Assert.assertEquals(events.poll(timing.forWaiting().seconds(), TimeUnit.SECONDS), PathChildrenCacheEvent.Type.CHILD_UPDATED);
                Assert.assertEquals(new String(cache.get("/test/one").orElseThrow(AssertionError::new).getData()), "sup!");

                client.delete().forPath("/test/one");
                Assert.assertEquals(events.poll(timing.forWaiting().seconds(), TimeUnit.SECONDS), PathChildrenCacheEvent.Type.CHILD_REMOVED);
            }
        }
    }

    @Test
    public void testTreeCache() throws Exception    // copied from TestTreeCache#testBasics()
    {
        BaseTestTreeCache treeCacheBase = new BaseTestTreeCache();
        try (CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1)))
        {
            client.start();
            client.create().forPath("/test");

            try (CuratorCache cache = CuratorCache.build(client, "/test"))
            {
                cache.listenable().addListener(builder().forTreeCache(client, treeCacheBase.eventListener).build());
                cache.start();

                treeCacheBase.assertEvent(TreeCacheEvent.Type.NODE_ADDED, "/test");
                treeCacheBase.assertEvent(TreeCacheEvent.Type.INITIALIZED);
                Assert.assertEquals(toMap(cache.stream().filter(parentPathFilter("/test"))).keySet(), ImmutableSet.of());
                Assert.assertEquals(cache.stream().filter(parentPathFilter("/t")).count(), 0);
                Assert.assertEquals(cache.stream().filter(parentPathFilter("/testing")).count(), 0);

                client.create().forPath("/test/one", "hey there".getBytes());
                treeCacheBase.assertEvent(TreeCacheEvent.Type.NODE_ADDED, "/test/one");
                Assert.assertEquals(toMap(cache.stream().filter(parentPathFilter("/test"))).keySet(), ImmutableSet.of("/test/one"));
                Assert.assertEquals(new String(cache.get("/test/one").orElseThrow(AssertionError::new).getData()), "hey there");
                Assert.assertEquals(toMap(cache.stream().filter(parentPathFilter("/test/one"))).keySet(), ImmutableSet.of());
                Assert.assertEquals(cache.stream().filter(parentPathFilter("/test/o")).count(), 0);
                Assert.assertEquals(cache.stream().filter(parentPathFilter("/test/onely")).count(), 0);

                client.setData().forPath("/test/one", "sup!".getBytes());
                treeCacheBase.assertEvent(TreeCacheEvent.Type.NODE_UPDATED, "/test/one");
                Assert.assertEquals(toMap(cache.stream().filter(parentPathFilter("/test"))).keySet(), ImmutableSet.of("/test/one"));
                Assert.assertEquals(new String(cache.get("/test/one").orElseThrow(AssertionError::new).getData()), "sup!");

                client.delete().forPath("/test/one");
                treeCacheBase.assertEvent(TreeCacheEvent.Type.NODE_REMOVED, "/test/one", "sup!".getBytes());
                Assert.assertEquals(toMap(cache.stream().filter(parentPathFilter("/test"))).keySet(), ImmutableSet.of());
            }
        }
    }

    @Test
    public void testNodeCache() throws Exception    // copied from TestNodeCache#testBasics()
    {
        try ( CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1)) )
        {
            client.start();
            client.create().forPath("/test");

            try (CuratorCache cache = CuratorCache.build(client, "/test/node", SINGLE_NODE_CACHE))
            {
                Supplier<ChildData> getRootData = () -> cache.get("/test/node").orElseThrow(() -> new AssertionError("is not present"));
                cache.start();

                final Semaphore semaphore = new Semaphore(0);
                cache.listenable().addListener(builder().forNodeCache(semaphore::release).build());
                try
                {
                    getRootData.get();
                    Assert.fail("Should have thrown");
                }
                catch ( AssertionError expected )
                {
                    // expected
                }

                client.create().forPath("/test/node", "a".getBytes());
                Assert.assertTrue(timing.acquireSemaphore(semaphore));
                Assert.assertEquals(getRootData.get().getData(), "a".getBytes());

                client.setData().forPath("/test/node", "b".getBytes());
                Assert.assertTrue(timing.acquireSemaphore(semaphore));
                Assert.assertEquals(getRootData.get().getData(), "b".getBytes());

                client.delete().forPath("/test/node");
                Assert.assertTrue(timing.acquireSemaphore(semaphore));
                try
                {
                    getRootData.get();
                    Assert.fail("Should have thrown");
                }
                catch ( AssertionError expected )
                {
                    // expected
                }
            }
        }
    }

    private static Map<String, ChildData> toMap(Stream<ChildData> stream)
    {
        return stream.map(data -> new AbstractMap.SimpleEntry<>(data.getPath(), data))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
