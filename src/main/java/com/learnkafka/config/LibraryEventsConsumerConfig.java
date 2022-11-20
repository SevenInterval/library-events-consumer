package com.learnkafka.config;

import com.learnkafka.service.FailureService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.TopicPartition;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.FixedBackOff;

import java.util.List;


@Configuration
@EnableKafka
@Slf4j
public class LibraryEventsConsumerConfig {

    public static final String RETRY = "RETRY";
    public static final String DEAD = "DEAD";
    public static final String SUCCESS = "SUCCESS";

    @Autowired
    KafkaTemplate kafkaTemplate;

    @Autowired
    FailureService failureService;

    @Value("${topics.retry}")
    private String retryTopic;

    @Value("${topics.dlt}")
    private String deadLetterTopic;


    public DeadLetterPublishingRecoverer publishingRecoverer() {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (r, e) -> {
                    if (e.getCause() instanceof RecoverableDataAccessException) {
                        return new TopicPartition(retryTopic, r.partition());
                    }
                    else {
                        return new TopicPartition(deadLetterTopic, r.partition());
                    }
                });
        CommonErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 2L));
        return recoverer;
    }

    ConsumerRecordRecoverer consumerRecordRecoverer = ((consumerRecord, e) -> {
        log.error("Exception in consumerRecordRecoverer: {}", e.getMessage(), e);
        var record = (ConsumerRecord<Integer,String>)consumerRecord;
        if (e.getCause() instanceof RecoverableDataAccessException) {
            //recovery logic
            log.info("Inside Recovery");
            failureService.saveFailedRecord(record, e, RETRY);
        }
        else {
            //non - recovery logic
            log.info("Inside non recovery ");
            failureService.saveFailedRecord(record, e, DEAD);

        }
    });

    public DefaultErrorHandler errorHandler() {

        var exceptionsToIgnoreList = List.of(
                IllegalArgumentException.class
        );

        var exceptionsToRetryList = List.of(
                RecoverableDataAccessException.class
        );

        var fixedBackOff = new FixedBackOff(1000L, 2);

        var excepBackOff = new ExponentialBackOffWithMaxRetries(2);
        excepBackOff.setInitialInterval(1_000L);
        excepBackOff.setMultiplier(2.0);
        excepBackOff.setMaxInterval(2_000L);

        var errorHandler = new DefaultErrorHandler(
                //publishingRecoverer(),
                consumerRecordRecoverer,
                //fixedBackOff
                excepBackOff
        );

        errorHandler.addNotRetryableExceptions();
        exceptionsToIgnoreList.forEach(errorHandler::addNotRetryableExceptions);
        //exceptionsToRetryList.forEach(errorHandler::addRetryableExceptions);

        errorHandler
                .setRetryListeners(((record, ex, deliveryAttempt) -> {
                    log.info("Failed Record in Retry Listener, Exception : {}, deliveryAttempt: {}", ex.getMessage(), deliveryAttempt);
                }));
        return errorHandler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<?, ?> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ObjectProvider<ConsumerFactory<Object, Object>> kafkaConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, (ConsumerFactory<Object, Object>) kafkaConsumerFactory);
        factory.setConcurrency(3);
        factory.setCommonErrorHandler(errorHandler());
        return factory;
    }
}
