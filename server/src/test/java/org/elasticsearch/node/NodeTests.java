/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.node;

import org.apache.lucene.util.Constants;
import org.apache.lucene.util.LuceneTestCase;
import org.elasticsearch.bootstrap.BootstrapCheck;
import org.elasticsearch.bootstrap.BootstrapContext;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.engine.Engine.Searcher;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.InternalTestCluster;
import org.elasticsearch.test.MockHttpTransport;
import org.elasticsearch.threadpool.ThreadPool;
import org.hamcrest.Matchers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_NUMBER_OF_REPLICAS;
import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_NUMBER_OF_SHARDS;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;

@LuceneTestCase.SuppressFileSystems(value = "ExtrasFS")
public class NodeTests extends ESTestCase {

    public static class CheckPlugin extends Plugin {
        public static final BootstrapCheck CHECK = context -> BootstrapCheck.BootstrapCheckResult.success();

        @Override
        public List<BootstrapCheck> getBootstrapChecks() {
            return Collections.singletonList(CHECK);
        }
    }

    private List<Class<? extends Plugin>> basePlugins() {
        List<Class<? extends Plugin>> plugins = new ArrayList<>();
        plugins.add(getTestTransportPlugin());
        plugins.add(MockHttpTransport.TestPlugin.class);
        return plugins;
    }

    public void testLoadPluginBootstrapChecks() throws IOException {
        final String name = randomBoolean() ? randomAlphaOfLength(10) : null;
        Settings.Builder settings = baseSettings();
        if (name != null) {
            settings.put(Node.NODE_NAME_SETTING.getKey(), name);
        }
        AtomicBoolean executed = new AtomicBoolean(false);
        List<Class<? extends Plugin>> plugins = basePlugins();
        plugins.add(CheckPlugin.class);
        try (Node node = new MockNode(settings.build(), plugins) {
            @Override
            protected void validateNodeBeforeAcceptingRequests(BootstrapContext context, BoundTransportAddress boundTransportAddress,
                                                               List<BootstrapCheck> bootstrapChecks) throws NodeValidationException {
                assertEquals(1, bootstrapChecks.size());
                assertSame(CheckPlugin.CHECK, bootstrapChecks.get(0));
                executed.set(true);
                throw new NodeValidationException("boom");
            }
        }) {
            expectThrows(NodeValidationException.class, () -> node.start());
            assertTrue(executed.get());
        }
    }

    public void testNodeAttributes() throws IOException {
        String attr = randomAlphaOfLength(5);
        Settings.Builder settings = baseSettings().put(Node.NODE_ATTRIBUTES.getKey() + "test_attr", attr);
        try (Node node = new MockNode(settings.build(), basePlugins())) {
            final Settings nodeSettings = randomBoolean() ? node.settings() : node.getEnvironment().settings();
            assertEquals(attr, Node.NODE_ATTRIBUTES.getAsMap(nodeSettings).get("test_attr"));
        }

        // leading whitespace not allowed
        attr = " leading";
        settings = baseSettings().put(Node.NODE_ATTRIBUTES.getKey() + "test_attr", attr);
        try (Node node = new MockNode(settings.build(), basePlugins())) {
            fail("should not allow a node attribute with leading whitespace");
        } catch (IllegalArgumentException e) {
            assertEquals("node.attr.test_attr cannot have leading or trailing whitespace [ leading]", e.getMessage());
        }

        // trailing whitespace not allowed
        attr = "trailing ";
        settings = baseSettings().put(Node.NODE_ATTRIBUTES.getKey() + "test_attr", attr);
        try (Node node = new MockNode(settings.build(), basePlugins())) {
            fail("should not allow a node attribute with trailing whitespace");
        } catch (IllegalArgumentException e) {
            assertEquals("node.attr.test_attr cannot have leading or trailing whitespace [trailing ]", e.getMessage());
        }
    }

    public void testServerNameNodeAttribute() throws IOException {
        String attr = "valid-hostname";
        Settings.Builder settings = baseSettings().put(Node.NODE_ATTRIBUTES.getKey() + "server_name", attr);
        int i = 0;
        try (Node node = new MockNode(settings.build(), basePlugins())) {
            final Settings nodeSettings = randomBoolean() ? node.settings() : node.getEnvironment().settings();
            assertEquals(attr, Node.NODE_ATTRIBUTES.getAsMap(nodeSettings).get("server_name"));
        }

        // non-LDH hostname not allowed
        attr = "invalid_hostname";
        settings = baseSettings().put(Node.NODE_ATTRIBUTES.getKey() + "server_name", attr);
        try (Node node = new MockNode(settings.build(), basePlugins())) {
            fail("should not allow a server_name attribute with an underscore");
        } catch (IllegalArgumentException e) {
            assertEquals("invalid node.attr.server_name [invalid_hostname]", e.getMessage());
        }
    }

    private static Settings.Builder baseSettings() {
        final Path tempDir = createTempDir();
        return Settings.builder()
                .put(ClusterName.CLUSTER_NAME_SETTING.getKey(), InternalTestCluster.clusterName("single-node-cluster", randomLong()))
                .put(Environment.PATH_HOME_SETTING.getKey(), tempDir)
                .put(NetworkModule.TRANSPORT_TYPE_KEY, getTestTransportType())
                .put(Node.NODE_DATA_SETTING.getKey(), true);
    }

    public void testCloseOnOutstandingTask() throws Exception {
        assumeFalse("https://github.com/elastic/elasticsearch/issues/44256", Constants.WINDOWS);
        Node node = new MockNode(baseSettings().build(), basePlugins());
        node.start();
        ThreadPool threadpool = node.injector().getInstance(ThreadPool.class);
        AtomicBoolean shouldRun = new AtomicBoolean(true);
        final CountDownLatch threadRunning = new CountDownLatch(1);
        threadpool.executor(ThreadPool.Names.SEARCH).execute(() -> {
            threadRunning.countDown();
            while (shouldRun.get());
        });
        threadRunning.await();
        node.close();
        shouldRun.set(false);
        assertTrue(node.awaitClose(1, TimeUnit.DAYS));
    }

    public void testCloseRaceWithTaskExecution() throws Exception {
        Node node = new MockNode(baseSettings().build(), basePlugins());
        node.start();
        ThreadPool threadpool = node.injector().getInstance(ThreadPool.class);
        AtomicBoolean shouldRun = new AtomicBoolean(true);
        final CountDownLatch running = new CountDownLatch(3);
        Thread submitThread = new Thread(() -> {
            running.countDown();
            try {
                running.await();
            } catch (InterruptedException e) {
                throw new AssertionError("interrupted while waiting", e);
            }
            threadpool.executor(ThreadPool.Names.SEARCH).execute(() -> {
                while (shouldRun.get());
            });
        });
        Thread closeThread = new Thread(() -> {
            running.countDown();
            try {
                running.await();
            } catch (InterruptedException e) {
                throw new AssertionError("interrupted while waiting", e);
            }
            try {
                node.close();
            } catch (IOException e) {
                throw new AssertionError("node close failed", e);
            }
        });
        submitThread.start();
        closeThread.start();
        running.countDown();
        running.await();

        submitThread.join();
        closeThread.join();

        shouldRun.set(false);
        assertTrue(node.awaitClose(1, TimeUnit.DAYS));
    }

    public void testAwaitCloseTimeoutsOnNonInterruptibleTask() throws Exception {
        Node node = new MockNode(baseSettings().build(), basePlugins());
        node.start();
        ThreadPool threadpool = node.injector().getInstance(ThreadPool.class);
        AtomicBoolean shouldRun = new AtomicBoolean(true);
        final CountDownLatch threadRunning = new CountDownLatch(1);
        threadpool.executor(ThreadPool.Names.SEARCH).execute(() -> {
            threadRunning.countDown();
            while (shouldRun.get());
        });
        threadRunning.await();
        node.close();
        assertFalse(node.awaitClose(0, TimeUnit.MILLISECONDS));
        shouldRun.set(false);
        assertTrue(node.awaitClose(1, TimeUnit.DAYS));
    }

    public void testCloseOnInterruptibleTask() throws Exception {
        Node node = new MockNode(baseSettings().build(), basePlugins());
        node.start();
        ThreadPool threadpool = node.injector().getInstance(ThreadPool.class);
        final CountDownLatch threadRunning = new CountDownLatch(1);
        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch finishLatch = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean(false);
        threadpool.executor(ThreadPool.Names.SEARCH).execute(() -> {
            threadRunning.countDown();
            try {
                latch.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                finishLatch.countDown();
            }
        });
        threadRunning.await();
        node.close();
        // close should not interrput ongoing tasks
        assertFalse(interrupted.get());
        // but awaitClose should
        node.awaitClose(0, TimeUnit.SECONDS);
        finishLatch.await();
        assertTrue(interrupted.get());
    }

    public void testCloseOnLeakedIndexReaderReference() throws Exception {
        Node node = new MockNode(baseSettings().build(), basePlugins());
        node.start();
        IndicesService indicesService = node.injector().getInstance(IndicesService.class);
        assertAcked(node.client().admin().indices().prepareCreate("test")
                .setSettings(Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 1).put(SETTING_NUMBER_OF_REPLICAS, 0)));
        IndexService indexService = indicesService.iterator().next();
        IndexShard shard = indexService.getShard(0);
        Searcher searcher = shard.acquireSearcher("test");
        node.close();

        IllegalStateException e = expectThrows(IllegalStateException.class, () -> node.awaitClose(1, TimeUnit.DAYS));
        searcher.close();
        assertThat(e.getMessage(), Matchers.containsString("Something is leaking index readers or store references"));
    }

    public void testCloseOnLeakedStoreReference() throws Exception {
        Node node = new MockNode(baseSettings().build(), basePlugins());
        node.start();
        IndicesService indicesService = node.injector().getInstance(IndicesService.class);
        assertAcked(node.client().admin().indices().prepareCreate("test")
                .setSettings(Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 1).put(SETTING_NUMBER_OF_REPLICAS, 0)));
        IndexService indexService = indicesService.iterator().next();
        IndexShard shard = indexService.getShard(0);
        shard.store().incRef();
        node.close();

        IllegalStateException e = expectThrows(IllegalStateException.class, () -> node.awaitClose(1, TimeUnit.DAYS));
        shard.store().decRef();
        assertThat(e.getMessage(), Matchers.containsString("Something is leaking index readers or store references"));
    }
}
