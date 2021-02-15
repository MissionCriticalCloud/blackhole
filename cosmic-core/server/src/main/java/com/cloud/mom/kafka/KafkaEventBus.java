package com.cloud.mom.kafka;

import com.cloud.framework.events.Event;
import com.cloud.framework.events.EventBus;
import com.cloud.framework.events.EventBusException;
import com.cloud.framework.events.EventSubscriber;
import com.cloud.framework.events.EventTopic;
import com.cloud.utils.PropertiesUtil;
import com.cloud.utils.component.ManagerBase;
import com.cloud.vm.dao.VMInstanceDao;

import javax.ejb.Local;
import javax.inject.Inject;
import javax.naming.ConfigurationException;
import java.io.FileInputStream;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Local(value = EventBus.class)
public class KafkaEventBus extends ManagerBase implements EventBus {

    @Inject
    public VMInstanceDao _vmDao;

    private static final String DEFAULT_TOPIC = "cosmic";
    private String _topic = DEFAULT_TOPIC;
    private Producer<String,String> _producer;
    private static final Logger s_logger = LoggerFactory.getLogger(KafkaEventBus.class);
    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {

        final Properties props = new Properties();

        try {
            final FileInputStream is = new FileInputStream(PropertiesUtil.findConfigFile("kafka.producer.properties"));
            props.load(is);

            _topic = (String) props.remove("topic");
            if (_topic == null) {
                _topic = DEFAULT_TOPIC;
            }

            is.close();
        } catch (Exception e) {
            // Fallback to default properties
            props.setProperty("bootstrap.servers", "192.168.22.1:9092");
            props.setProperty("acks", "all");
            props.setProperty("retries", "1");
            props.setProperty("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            props.setProperty("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        }

        _producer = new KafkaProducer<String,String>(props);
        _name = name;

        return true;
    }

    @Override
    public void setName(String name) {
        _name = name;
    }

    @Override
    public UUID subscribe(EventTopic topic, EventSubscriber subscriber) throws EventBusException {
        /* NOOP */
        return UUID.randomUUID();
    }

    @Override
    public void unsubscribe(UUID subscriberId, EventSubscriber subscriber) throws EventBusException {
        /* NOOP */
    }

    @Override
    public void publish(Event event) throws EventBusException {
        ProducerRecord<String, String> record = new ProducerRecord<String,String>(_topic, event.getResourceUUID(), event.getDescription());
        _producer.send(record);
    }

    @Override
    public String getName() {
        return _name;
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }
}