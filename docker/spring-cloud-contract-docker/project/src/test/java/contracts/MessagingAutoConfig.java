/*
 * Copyright 2013-2020 the original author or authors.
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

package contracts;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.camel.ConsumerTemplate;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.yaml.snakeyaml.Yaml;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.contract.verifier.converter.YamlContract;
import org.springframework.cloud.contract.verifier.messaging.MessageVerifier;
import org.springframework.cloud.contract.verifier.messaging.amqp.AmqpMetadata;
import org.springframework.cloud.contract.verifier.messaging.internal.ContractVerifierMessage;
import org.springframework.cloud.contract.verifier.messaging.internal.ContractVerifierMessaging;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * @author Marcin Grzejszczak
 */
@Configuration
@ConditionalOnProperty("MESSAGING_TYPE")
public class MessagingAutoConfig {

	@Bean
	public ContractVerifierMessaging<Message> contractVerifierMessaging(
			MessageVerifier<Message> exchange) {
		return new ContractVerifierCamelHelper(exchange);
	}

	@Bean
	MessageVerifier<Message> manualMessageVerifier(@Value("${MESSAGING_TYPE:}") String messagingType, Environment environment, ConsumerTemplate consumerTemplate) {
		return new MessageVerifier<>() {

			@Override
			public Message receive(String destination, long timeout, TimeUnit timeUnit, YamlContract yamlContract) {
				String uri = messagingType() + "://" + destination + additionalOptions(yamlContract);
				Exchange exchange = consumerTemplate.receive(uri, timeUnit.toMillis(timeout));
				if (exchange == null) {
					return null;
				}
				return exchange.getMessage();
			}

			private String messagingType() {
				if (messagingType.equalsIgnoreCase("kafka")) {
					return "kafka";
				}
				return "rabbitmq";
			}

			private String additionalOptions(YamlContract contract) {
				if (contract == null) {
					return "";
				}
				if (messagingType.equalsIgnoreCase("kafka")) {
					return setKafkaOpts(contract);
				}
				return setRabbitOpts(contract);
			}

			private String setKafkaOpts(YamlContract contract) {
				int consumerGroup = sameConsumerGroupForSameContract(contract);
				return "?brokers=" + environment.getRequiredProperty("SPRING_KAFKA_BOOTSTRAP_SERVERS") + "&autoOffsetReset=latest&groupId=" + consumerGroup;
			}

			private int sameConsumerGroupForSameContract(YamlContract contract) {
				return contract.input.hashCode() + contract.outputMessage.hashCode();
			}

			private String setRabbitOpts(YamlContract contract) {
				String opts = "?addresses=" + environment.getRequiredProperty("SPRING_RABBITMQ_ADDRESSES");
				AmqpMetadata amqpMetadata = AmqpMetadata.fromMetadata(contract.metadata);
				if (StringUtils.hasText(amqpMetadata.getOutputMessage().getDeclareQueueWithName())) {
					opts = opts + "&queue=" + amqpMetadata.getOutputMessage().getDeclareQueueWithName();
					// routing key
				}
				if (StringUtils.hasText(amqpMetadata.getOutputMessage().getMessageProperties().getReceivedRoutingKey())) {
					opts = opts + "&routingKey=" + amqpMetadata.getOutputMessage().getMessageProperties().getReceivedRoutingKey();
				}
				return opts;
			}

			@Override
			public Message receive(String destination, YamlContract yamlContract) {
				return receive(destination, 5, TimeUnit.SECONDS, yamlContract);
			}

			@Override
			public void send(Message message, String destination, YamlContract yamlContract) {
				throw new UnsupportedOperationException("Currently supports only receiving");
			}

			@Override
			public void send(Object payload, Map headers, String destination, YamlContract yamlContract) {
				throw new UnsupportedOperationException("Currently supports only receiving");
			}
		};
	}

}

class ContractVerifierCamelHelper extends ContractVerifierMessaging<Message> {

	ContractVerifierCamelHelper(MessageVerifier<Message> exchange) {
		super(exchange);
	}

	@Override
	protected ContractVerifierMessage convert(Message receive) {
		return new ContractVerifierMessage(receive.getBody(), receive.getHeaders());
	}

}