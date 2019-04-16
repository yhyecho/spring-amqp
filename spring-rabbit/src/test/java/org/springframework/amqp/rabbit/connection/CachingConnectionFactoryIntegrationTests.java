/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.amqp.rabbit.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.instanceOf;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.logging.log4j.Level;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import org.springframework.amqp.AmqpApplicationContextClosedException;
import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.AmqpResourceNotAvailableException;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory.CacheMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.junit.BrokerRunning;
import org.springframework.amqp.rabbit.junit.BrokerTestUtils;
import org.springframework.amqp.rabbit.test.LogLevelAdjuster;
import org.springframework.amqp.utils.test.TestUtils;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;

/**
 * @author Dave Syer
 * @author Gunnar Hillert
 * @author Gary Russell
 * @author Artem Bilan
 *
 * @since 1.0
 *
 */
public class CachingConnectionFactoryIntegrationTests {

	private static final String CF_INTEGRATION_TEST_QUEUE = "cfIntegrationTest";

	private static final String CF_INTEGRATION_CONNECTION_NAME = "cfIntegrationTestConnectionName";

	private static Log logger = LogFactory.getLog(CachingConnectionFactoryIntegrationTests.class);

	private CachingConnectionFactory connectionFactory;

	@Rule
	public BrokerRunning brokerIsRunning = BrokerRunning.isRunningWithEmptyQueues(CF_INTEGRATION_TEST_QUEUE);

	@Rule
	public ExpectedException exception = ExpectedException.none();

	@Rule
	public LogLevelAdjuster adjuster = new LogLevelAdjuster(Level.DEBUG,
			CachingConnectionFactoryIntegrationTests.class, CachingConnectionFactory.class)
		.categories("com.rabbitmq");

	@Before
	public void open() {
		connectionFactory = new CachingConnectionFactory("localhost");
		connectionFactory.setPort(BrokerTestUtils.getPort());
		connectionFactory.getRabbitConnectionFactory().getClientProperties().put("foo", "bar");
		connectionFactory.setConnectionNameStrategy(cf -> CF_INTEGRATION_CONNECTION_NAME);
	}

	@After
	public void close() {
		if (!this.connectionFactory.getVirtualHost().equals("non-existent")) {
			this.brokerIsRunning.removeTestQueues();
		}
		assertThat(connectionFactory.getRabbitConnectionFactory().getClientProperties().get("foo")).isEqualTo("bar");
		connectionFactory.destroy();
	}

	@Test
	public void testCachedConnections() {
		connectionFactory.setCacheMode(CacheMode.CONNECTION);
		connectionFactory.setConnectionCacheSize(5);
		connectionFactory.setExecutor(Executors.newCachedThreadPool());
		List<Connection> connections = new ArrayList<>();
		connections.add(connectionFactory.createConnection());
		connections.add(connectionFactory.createConnection());
		assertThat(connections.get(1)).isNotSameAs(connections.get(0));
		connections.add(connectionFactory.createConnection());
		connections.add(connectionFactory.createConnection());
		connections.add(connectionFactory.createConnection());
		connections.add(connectionFactory.createConnection());
		Set<?> allocatedConnections = TestUtils.getPropertyValue(connectionFactory, "allocatedConnections", Set.class);
		assertThat(allocatedConnections.size()).isEqualTo(6);
		connections.forEach(Connection::close);
		assertThat(allocatedConnections.size()).isEqualTo(6);
		assertThat(connectionFactory.getCacheProperties().get("openConnections")).isEqualTo("5");
		BlockingQueue<?> idleConnections = TestUtils.getPropertyValue(connectionFactory, "idleConnections",
				BlockingQueue.class);
		assertThat(idleConnections.size()).isEqualTo(6);
		connections.clear();
		connections.add(connectionFactory.createConnection());
		connections.add(connectionFactory.createConnection());
		assertThat(allocatedConnections.size()).isEqualTo(6);
		assertThat(idleConnections.size()).isEqualTo(4);
		connections.forEach(Connection::close);
	}

	@Test
	public void testCachedConnectionsChannelLimit() throws Exception {
		connectionFactory.setCacheMode(CacheMode.CONNECTION);
		connectionFactory.setConnectionCacheSize(2);
		connectionFactory.setChannelCacheSize(1);
		connectionFactory.setChannelCheckoutTimeout(10);
		connectionFactory.setExecutor(Executors.newCachedThreadPool());
		List<Connection> connections = new ArrayList<Connection>();
		connections.add(connectionFactory.createConnection());
		connections.add(connectionFactory.createConnection());
		List<Channel> channels = new ArrayList<Channel>();
		channels.add(connections.get(0).createChannel(false));
		try {
			channels.add(connections.get(0).createChannel(false));
			fail("Exception expected");
		}
		catch (AmqpTimeoutException e) { }
		channels.add(connections.get(1).createChannel(false));
		try {
			channels.add(connections.get(1).createChannel(false));
			fail("Exception expected");
		}
		catch (AmqpTimeoutException e) { }
		channels.get(0).close();
		channels.get(1).close();
		channels.add(connections.get(0).createChannel(false));
		channels.add(connections.get(1).createChannel(false));
		assertThat(channels.get(2)).isSameAs(channels.get(0));
		assertThat(channels.get(3)).isSameAs(channels.get(1));
		channels.get(2).close();
		channels.get(3).close();
		connections.forEach(Connection::close);
	}

	@Test
	public void testCachedConnectionsAndChannels() throws Exception {
		connectionFactory.setCacheMode(CacheMode.CONNECTION);
		connectionFactory.setConnectionCacheSize(1);
		connectionFactory.setChannelCacheSize(3);
		// the following is needed because we close the underlying connection below.
		connectionFactory.getRabbitConnectionFactory().setAutomaticRecoveryEnabled(false);
		List<Connection> connections = new ArrayList<Connection>();
		connections.add(connectionFactory.createConnection());
		connections.add(connectionFactory.createConnection());
		Set<?> allocatedConnections = TestUtils.getPropertyValue(connectionFactory, "allocatedConnections", Set.class);
		assertThat(allocatedConnections.size()).isEqualTo(2);
		assertThat(connections.get(1)).isNotSameAs(connections.get(0));
		List<Channel> channels = new ArrayList<Channel>();
		for (int i = 0; i < 5; i++) {
			channels.add(connections.get(0).createChannel(false));
			channels.add(connections.get(1).createChannel(false));
			channels.add(connections.get(0).createChannel(true));
			channels.add(connections.get(1).createChannel(true));
		}
		@SuppressWarnings("unchecked")
		Map<?, List<?>> cachedChannels = TestUtils.getPropertyValue(connectionFactory,
				"allocatedConnectionNonTransactionalChannels", Map.class);
		assertThat(cachedChannels.get(connections.get(0)).size()).isEqualTo(0);
		assertThat(cachedChannels.get(connections.get(1)).size()).isEqualTo(0);
		@SuppressWarnings("unchecked")
		Map<?, List<?>> cachedTxChannels = TestUtils.getPropertyValue(connectionFactory,
				"allocatedConnectionTransactionalChannels", Map.class);
		assertThat(cachedTxChannels.get(connections.get(0)).size()).isEqualTo(0);
		assertThat(cachedTxChannels.get(connections.get(1)).size()).isEqualTo(0);
		for (Channel channel : channels) {
			channel.close();
		}
		assertThat(cachedChannels.get(connections.get(0)).size()).isEqualTo(3);
		assertThat(cachedChannels.get(connections.get(1)).size()).isEqualTo(3);
		assertThat(cachedTxChannels.get(connections.get(0)).size()).isEqualTo(3);
		assertThat(cachedTxChannels.get(connections.get(1)).size()).isEqualTo(3);
		for (int i = 0; i < 3; i++) {
			assertThat(connections.get(0).createChannel(false)).isEqualTo(channels.get(i * 4));
			assertThat(connections.get(1).createChannel(false)).isEqualTo(channels.get(i * 4 + 1));
			assertThat(connections.get(0).createChannel(true)).isEqualTo(channels.get(i * 4 + 2));
			assertThat(connections.get(1).createChannel(true)).isEqualTo(channels.get(i * 4 + 3));
		}
		assertThat(cachedChannels.get(connections.get(0)).size()).isEqualTo(0);
		assertThat(cachedChannels.get(connections.get(1)).size()).isEqualTo(0);
		assertThat(cachedTxChannels.get(connections.get(0)).size()).isEqualTo(0);
		assertThat(cachedTxChannels.get(connections.get(1)).size()).isEqualTo(0);
		for (Channel channel : channels) {
			channel.close();
		}
		for (Connection connection : connections) {
			connection.close();
		}
		assertThat(cachedChannels.get(connections.get(0)).size()).isEqualTo(3);
		assertThat(cachedChannels.get(connections.get(1)).size()).isEqualTo(0);
		assertThat(cachedTxChannels.get(connections.get(0)).size()).isEqualTo(3);
		assertThat(cachedTxChannels.get(connections.get(1)).size()).isEqualTo(0);

		assertThat(allocatedConnections.size()).isEqualTo(2);
		assertThat(connectionFactory.getCacheProperties().get("openConnections")).isEqualTo("1");

		Connection connection = connectionFactory.createConnection();
		Connection rabbitConnection = TestUtils.getPropertyValue(connection, "target", Connection.class);
		rabbitConnection.close();
		Channel channel = connection.createChannel(false);
		assertThat(allocatedConnections.size()).isEqualTo(2);
		assertThat(connectionFactory.getCacheProperties().get("openConnections")).isEqualTo("1");
		channel.close();
		connection.close();
		assertThat(allocatedConnections.size()).isEqualTo(2);
		assertThat(connectionFactory.getCacheProperties().get("openConnections")).isEqualTo("1");
	}

	@Test
	public void testSendAndReceiveFromVolatileQueue() throws Exception {

		RabbitTemplate template = new RabbitTemplate(connectionFactory);

		RabbitAdmin admin = new RabbitAdmin(connectionFactory);
		Queue queue = admin.declareQueue();
		template.convertAndSend(queue.getName(), "message");
		String result = (String) template.receiveAndConvert(queue.getName());
		assertThat(result).isEqualTo("message");
		template.stop();

	}

	@Test
	public void testReceiveFromNonExistentVirtualHost() throws Exception {
		connectionFactory.setVirtualHost("non-existent");
		RabbitTemplate template = new RabbitTemplate(connectionFactory);

		// Wrong vhost is very unfriendly to client - the exception has no clue (just an EOF)
		exception.expect(anyOf(instanceOf(AmqpIOException.class),
				instanceOf(AmqpAuthenticationException.class),
				/*
				 * If localhost also resolves to an IPv6 address, the client will try that
				 * after a failure due to an invalid vHost and, if Rabbit is not listening there,
				 * we'll get an...
				 */
				instanceOf(AmqpConnectException.class)));
		template.receiveAndConvert("foo");
	}

	@Test
	public void testSendAndReceiveFromVolatileQueueAfterImplicitRemoval() throws Exception {

		RabbitTemplate template = new RabbitTemplate(connectionFactory);

		RabbitAdmin admin = new RabbitAdmin(connectionFactory);
		Queue queue = admin.declareQueue();
		template.convertAndSend(queue.getName(), "message");

		// Force a physical close of the channel
		connectionFactory.destroy();

		// The queue was removed when the channel was closed
		exception.expect(AmqpIOException.class);

		String result = (String) template.receiveAndConvert(queue.getName());
		assertThat(result).isEqualTo("message");
		template.stop();
	}

	@Test
	public void testMixTransactionalAndNonTransactional() throws Exception {

		RabbitTemplate template1 = new RabbitTemplate(connectionFactory);
		RabbitTemplate template2 = new RabbitTemplate(connectionFactory);
		template1.setChannelTransacted(true);

		RabbitAdmin admin = new RabbitAdmin(connectionFactory);
		Queue queue = admin.declareQueue();

		template1.convertAndSend(queue.getName(), "message");
		String result = (String) template2.receiveAndConvert(queue.getName());
		assertThat(result).isEqualTo("message");

		// The channel is not transactional
		exception.expect(AmqpIOException.class);

		template2.execute(channel -> {
			// Should be an exception because the channel is not transactional
			channel.txRollback();
			return null;
		});

	}

	@Test
	public void testHardErrorAndReconnectNoAuto() throws Exception {
		this.connectionFactory.getRabbitConnectionFactory().setAutomaticRecoveryEnabled(false);
		RabbitTemplate template = new RabbitTemplate(connectionFactory);
		RabbitAdmin admin = new RabbitAdmin(connectionFactory);
		Queue queue = new Queue(CF_INTEGRATION_TEST_QUEUE);
		admin.declareQueue(queue);
		final String route = queue.getName();

		final CountDownLatch latch = new CountDownLatch(1);
		try {
			template.execute(channel -> {
				channel.getConnection().addShutdownListener(cause -> {
					logger.info("Error", cause);
					latch.countDown();
					// This will be thrown on the Connection thread just before it dies, so basically ignored
					throw new RuntimeException(cause);
				});
				String tag = channel.basicConsume(route, new DefaultConsumer(channel));
				// Consume twice with the same tag is a hard error (connection will be reset)
				String result = channel.basicConsume(route, false, tag, new DefaultConsumer(channel));
				fail("Expected IOException, got: " + result);
				return null;
			});
			fail("Expected AmqpIOException");
		}
		catch (AmqpIOException e) {
			// expected
		}
		template.convertAndSend(route, "message");
		assertThat(latch.await(1000, TimeUnit.MILLISECONDS)).isTrue();
		String result = (String) template.receiveAndConvert(route);
		assertThat(result).isEqualTo("message");
		result = (String) template.receiveAndConvert(route);
		assertThat(result).isEqualTo(null);
	}

	@Test
	public void testConnectionCloseLog() {
		Log logger = spy(TestUtils.getPropertyValue(this.connectionFactory, "logger", Log.class));
		new DirectFieldAccessor(this.connectionFactory).setPropertyValue("logger", logger);
		Connection conn = this.connectionFactory.createConnection();
		conn.createChannel(false);
		this.connectionFactory.destroy();
		verify(logger, never()).error(anyString());
	}

	@Test
	public void testConnectionName() {
		Connection connection = this.connectionFactory.createConnection();
		com.rabbitmq.client.Connection rabbitConnection = TestUtils.getPropertyValue(connection, "target.delegate",
				com.rabbitmq.client.Connection.class);
		assertThat(rabbitConnection.getClientProperties().get("connection_name")).isEqualTo(CF_INTEGRATION_CONNECTION_NAME);
		this.connectionFactory.destroy();
	}

	@Test
	public void testDestroy() {
		Connection connection1 = this.connectionFactory.createConnection();
		this.connectionFactory.destroy();
		Connection connection2 = this.connectionFactory.createConnection();
		assertThat(connection2).isSameAs(connection1);
		ApplicationContext context = mock(ApplicationContext.class);
		this.connectionFactory.setApplicationContext(context);
		this.connectionFactory.onApplicationEvent(new ContextClosedEvent(context));
		this.connectionFactory.destroy();
		try {
			connection2 = this.connectionFactory.createConnection();
			fail("Expected exception");
		}
		catch (AmqpApplicationContextClosedException e) {
			assertThat(e.getMessage()).contains("is closed");
		}
	}

	@Test
	@Ignore // Don't run this on the CI build server
	public void hangOnClose() throws Exception {
		final Socket proxy = SocketFactory.getDefault().createSocket("localhost", 5672);
		final ServerSocket server = ServerSocketFactory.getDefault().createServerSocket(2765);
		final AtomicBoolean hangOnClose = new AtomicBoolean();
		// create a simple proxy so we can drop the close response
		Executors.newSingleThreadExecutor().execute(() -> {
			try {
				final Socket socket = server.accept();
				Executors.newSingleThreadExecutor().execute(() -> {
					while (!socket.isClosed()) {
						try {
							int c = socket.getInputStream().read();
							if (c >= 0) {
								proxy.getOutputStream().write(c);
							}
						}
						catch (Exception e) {
							try {
								socket.close();
								proxy.close();
							}
							catch (Exception ee) { }
						}
					}
				});
				while (!proxy.isClosed()) {
					try {
						int c = proxy.getInputStream().read();
						if (c >= 0 && !hangOnClose.get()) {
							socket.getOutputStream().write(c);
						}
					}
					catch (Exception e) {
						try {
							socket.close();
							proxy.close();
						}
						catch (Exception ee) { }
					}
				}
				socket.close();
			}
			catch (Exception e) {
				e.printStackTrace();
			}
		});
		CachingConnectionFactory factory = new CachingConnectionFactory(2765);
		factory.createConnection();
		hangOnClose.set(true);
		factory.destroy();
	}

	@Test(expected = AmqpResourceNotAvailableException.class)
	public void testChannelMax() {
		this.connectionFactory.getRabbitConnectionFactory().setRequestedChannelMax(1);
		Connection connection = this.connectionFactory.createConnection();
		connection.createChannel(true);
		connection.createChannel(false);
	}

}
