package com.learnkafka.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnkafka.entity.Book;
import com.learnkafka.entity.LibraryEvent;
import com.learnkafka.entity.LibraryEventType;
import com.learnkafka.jpa.FailureRecordRepository;
import com.learnkafka.jpa.LibraryEventsRepository;
import com.learnkafka.service.LibraryEventsService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@EmbeddedKafka(topics = {"library-events", "library-events.RETRY", "library-events.DLT"}, partitions = 3)
@TestPropertySource(properties = {"spring.kafka.producer.bootstrap-servers=${spring.embedded.kafka.brokers}"
,"spring.kafka.consumer.bootstrap-servers=${spring.embedded.kafka.brokers}"
,"retryListener.startup=false"})
public class LibraryEventsConsumerIntegrationTest {

    @Value("${topics.retry}")
    private String retryTopic;

    @Value("${topics.dlt}")
    private String deadLetterTopic;

    @Autowired
    EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    KafkaTemplate<Integer, String> kafkaTemplate;

    @Autowired
    KafkaListenerEndpointRegistry endpointRegistry;

    @SpyBean
    LibraryEventsConsumer libraryEventsConsumerSpy;

    @SpyBean
    LibraryEventsService libraryEventsServiceSpy;

    @Autowired
    LibraryEventsRepository libraryEventsRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    FailureRecordRepository failureRecordRepository;

    private Consumer<Integer, String> consumer;


    @BeforeEach
    void setUp() {
        var container = endpointRegistry.getListenerContainers()
                .stream().filter(messageListenerContainer ->
                messageListenerContainer.getGroupId().equals("library-events-listener-group"))
                .collect(Collectors.toList()).get(0);

        ContainerTestUtils.waitForAssignment(container, embeddedKafkaBroker.getPartitionsPerTopic());
        /*for (MessageListenerContainer messageListenerContainer : endpointRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(messageListenerContainer, embeddedKafkaBroker.getPartitionsPerTopic());
        }*/
    }

    @AfterEach
    void tearDown() {
        libraryEventsRepository.deleteAll();
    }

    @Test
    void publishNewLibraryEvent() throws ExecutionException, InterruptedException, JsonProcessingException {
        //given
        String json = "{\n" +
                "    \"libraryEventId\": 125,\n" +
                "    \"book\": {\n" +
                "        \"bookId\": 459,\n" +
                "        \"bookName\": \"Kafka Using Spring Boot\",\n" +
                "        \"bookAuthor\": \"Duygu\"\n" +
                "    }\n" +
                "}";
        kafkaTemplate.sendDefault(json).get();

        //when
        CountDownLatch latch = new CountDownLatch(1);
        latch.await(3, TimeUnit.SECONDS);

        //then
        verify(libraryEventsConsumerSpy, times(1)).onMessage(isA(ConsumerRecord.class));
        verify(libraryEventsServiceSpy, times(1)).processLibraryEvent(isA(ConsumerRecord.class));

        List<LibraryEvent> libraryEventList = (List<LibraryEvent>) libraryEventsRepository.findAll();
        assert libraryEventList.size() == 1;
        libraryEventList.forEach(libraryEvent -> {
            assert libraryEvent.getLibraryEventId() != null;
            assertEquals(459, libraryEvent.getBook().getBookId());
        });
    }


    @Test
    void publishUpdateLibraryEvent() throws ExecutionException, InterruptedException, JsonProcessingException {
        //given
        String json = "{\n" +
                "    \"libraryEventId\": 125,\n" +
                "    \"book\": {\n" +
                "        \"bookId\": 459,\n" +
                "        \"bookName\": \"Kafka Using Spring Boot\",\n" +
                "        \"bookAuthor\": \"Duygu\"\n" +
                "    }\n" +
                "}";
        LibraryEvent libraryEvent = objectMapper.readValue(json, LibraryEvent.class);
        libraryEvent.getBook().setLibraryEvent(libraryEvent);
        libraryEventsRepository.save(libraryEvent);

        Book updatedBook = Book.builder().
                bookId(459).bookName("afka Using Spring Boot").bookAuthor("Duygu").build();
        libraryEvent.setLibraryEventType(LibraryEventType.UPDATE);
        libraryEvent.setBook(updatedBook);
        String updatedJson = objectMapper.writeValueAsString(libraryEvent);
        kafkaTemplate.sendDefault(libraryEvent.getLibraryEventId(), updatedJson).get();

        //when
        CountDownLatch latch = new CountDownLatch(1);
        latch.await(3, TimeUnit.SECONDS);

        //then
        verify(libraryEventsConsumerSpy, times(1)).onMessage(isA(ConsumerRecord.class));
        verify(libraryEventsServiceSpy, times(1)).processLibraryEvent(isA(ConsumerRecord.class));

        LibraryEvent persistedLibraryEvent = libraryEventsRepository.findById(libraryEvent.getLibraryEventId()).get();
        assertEquals("Kafka Using Spring Boot", persistedLibraryEvent.getBook().getBookName());
    }

    @Test
    void publishUpdateLibraryEvent_null_LibraryEvent() throws ExecutionException, InterruptedException, JsonProcessingException {
        //given
        String json = "{\n" +
                "    \"libraryEventId\": null,\n" +
                "    \"book\": {\n" +
                "        \"bookId\": 459,\n" +
                "        \"bookName\": \"Kafka Using Spring Boot\",\n" +
                "        \"bookAuthor\": \"Duygu\"\n" +
                "    }\n" +
                "}";
        kafkaTemplate.sendDefault(json).get();

        //when
        CountDownLatch latch = new CountDownLatch(1);
        latch.await(3, TimeUnit.SECONDS);

        //then
        verify(libraryEventsConsumerSpy, times(1)).onMessage(isA(ConsumerRecord.class));
        verify(libraryEventsServiceSpy, times(1)).processLibraryEvent(isA(ConsumerRecord.class));

        Map<String, Object> configs = new HashMap<>(KafkaTestUtils.consumerProps("group2", "true", embeddedKafkaBroker));
        consumer = new DefaultKafkaConsumerFactory<>(configs, new IntegerDeserializer(), new StringDeserializer()).createConsumer();
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, deadLetterTopic);

        ConsumerRecord<Integer, String> consumerRecord = KafkaTestUtils.getSingleRecord(consumer, deadLetterTopic);
        System.out.println("consumerRecords is : " + consumerRecord.value());
        assertEquals(json, consumerRecord.value());
    }

    @Test
    void publishUpdateLibraryEvent_null_LibraryEvent_failurerecord() throws ExecutionException, InterruptedException, JsonProcessingException {
        //given
        String json = "{\n" +
                "    \"libraryEventId\": null,\n" +
                "    \"book\": {\n" +
                "        \"bookId\": 459,\n" +
                "        \"bookName\": \"Kafka Using Spring Boot\",\n" +
                "        \"bookAuthor\": \"Duygu\"\n" +
                "    }\n" +
                "}";
        kafkaTemplate.sendDefault(json).get();

        //when
        CountDownLatch latch = new CountDownLatch(1);
        latch.await(3, TimeUnit.SECONDS);

        //then
        verify(libraryEventsConsumerSpy, times(1)).onMessage(isA(ConsumerRecord.class));
        verify(libraryEventsServiceSpy, times(1)).processLibraryEvent(isA(ConsumerRecord.class));

        var count = failureRecordRepository.count();
        assertEquals(1, count);

        failureRecordRepository.findAll()
                .forEach(failureRecord -> {
                    System.out.println("failureRecord : " + failureRecord);
                });
    }

    @Test
    void publishUpdateLibraryEvent_999_LibraryEvent() throws ExecutionException, InterruptedException, JsonProcessingException {
        //given
        String json = "{\n" +
                "    \"libraryEventId\": 999,\n" +
                "    \"book\": {\n" +
                "        \"bookId\": 459,\n" +
                "        \"bookName\": \"Kafka Using Spring Boot\",\n" +
                "        \"bookAuthor\": \"Duygu\"\n" +
                "    }\n" +
                "}";
        kafkaTemplate.sendDefault(json).get();

        //when
        CountDownLatch latch = new CountDownLatch(1);
        latch.await(3, TimeUnit.SECONDS);

        //then
        verify(libraryEventsConsumerSpy, times(3)).onMessage(isA(ConsumerRecord.class));
        verify(libraryEventsServiceSpy, times(3)).processLibraryEvent(isA(ConsumerRecord.class));

        Map<String, Object> configs = new HashMap<>(KafkaTestUtils.consumerProps("group1", "true", embeddedKafkaBroker));
        consumer = new DefaultKafkaConsumerFactory<>(configs, new IntegerDeserializer(), new StringDeserializer()).createConsumer();
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, retryTopic);

        ConsumerRecord<Integer, String> consumerRecord = KafkaTestUtils.getSingleRecord(consumer, retryTopic);
        System.out.println("consumerRecords is : " + consumerRecord.value());
        assertEquals(json, consumerRecord.value());


    }

}
