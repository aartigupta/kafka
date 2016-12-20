/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.streams.processor.internals;

import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.utils.SystemTime;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.streams.StreamsMetrics;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ProcessorNode<K, V> {

    private final List<ProcessorNode<?, ?>> children;

    private final String name;
    private final Processor<K, V> processor;
    protected NodeMetrics nodeMetrics;
    private Time time;

    private K key;
    private V value;
    private Runnable processDelegate = new Runnable() {
        @Override
        public void run() {
            processor.process(key, value);
        }
    };
    private ProcessorContext context;
    private Runnable initDelegate = new Runnable() {
        @Override
        public void run() {
            if (processor != null) {
                processor.init(context);
            }
        }
    };
    private Runnable closeDelegate = new Runnable() {
        @Override
        public void run() {
            if (processor != null) {
                processor.close();
            }
        }
    };

    private long timestamp;
    private Runnable punctuateDelegate = new Runnable() {
        @Override
        public void run() {
            processor().punctuate(timestamp);
        }
    };

    public final Set<String> stateStores;

    public ProcessorNode(String name) {
        this(name, null, null);
    }


    public ProcessorNode(String name, Processor<K, V> processor, Set<String> stateStores) {
        this.name = name;
        this.processor = processor;
        this.children = new ArrayList<>();
        this.stateStores = stateStores;
        this.time = new SystemTime();
    }


    public final String name() {
        return name;
    }

    public final Processor<K, V> processor() {
        return processor;
    }

    public final List<ProcessorNode<?, ?>> children() {
        return children;
    }

    public void addChild(ProcessorNode<?, ?> child) {
        children.add(child);
    }


    public void init(ProcessorContext context) {
        this.context = context;
        try {
            nodeMetrics = new NodeMetrics(context.metrics(), name,  "task." + context.taskId());
            nodeMetrics.metrics.measureLatencyNs(time, initDelegate, nodeMetrics.nodeCreationSensor);
        } catch (Exception e) {
            throw new StreamsException(String.format("failed to initialize processor %s", name), e);
        }
    }

    public void close() {
        try {
            nodeMetrics.metrics.measureLatencyNs(time, closeDelegate, nodeMetrics.nodeDestructionSensor);
            nodeMetrics.removeAllSensors();
        } catch (Exception e) {
            throw new StreamsException(String.format("failed to close processor %s", name), e);
        }
    }


    public void process(final K key, final V value) {
        this.key = key;
        this.value = value;

        this.nodeMetrics.metrics.measureLatencyNs(time, processDelegate, nodeMetrics.nodeProcessTimeSensor);

        // record throughput
        nodeMetrics.nodeThroughputSensor.record();
    }

    public void punctuate(long timestamp) {
        this.timestamp = timestamp;
        this.nodeMetrics.metrics.measureLatencyNs(time, punctuateDelegate, nodeMetrics.nodePunctuateTimeSensor);
    }

    /**
     * @return a string representation of this node, useful for debugging.
     */
    public String toString() {
        StringBuilder sb = new StringBuilder("");
        sb.append(name + ": ");
        if (stateStores != null && !stateStores.isEmpty()) {
            sb.append("stateStores [");
            for (String store : (Set<String>) stateStores) {
                sb.append(store + ",");
            }
            sb.setLength(sb.length() - 1);
            sb.append("] ");
        }
        return sb.toString();
    }

    protected class NodeMetrics  {
        final StreamsMetrics metrics;
        final String metricGrpName;
        final Map<String, String> metricTags;

        final Sensor nodeProcessTimeSensor;
        final Sensor nodePunctuateTimeSensor;
        final Sensor nodeThroughputSensor;
        final Sensor nodeCreationSensor;
        final Sensor nodeDestructionSensor;


        public NodeMetrics(StreamsMetrics metrics, String name, String sensorNamePrefix) {
            final String scope = "processor-node";
            final String tagKey = "processor-node-id";
            final String tagValue = name;
            this.metrics = metrics;
            this.metricGrpName = "stream-processor-node-metrics";
            this.metricTags = new LinkedHashMap<>();
            this.metricTags.put(tagKey, tagValue);

            // these are all latency metrics
            this.nodeProcessTimeSensor = metrics.addLatencySensor(scope, sensorNamePrefix + "." + name, "process", Sensor.RecordLevel.DEBUG, tagKey, tagValue);
            this.nodePunctuateTimeSensor = metrics.addLatencySensor(scope, sensorNamePrefix + "." + name, "punctuate", Sensor.RecordLevel.DEBUG, tagKey, tagValue);
            this.nodeCreationSensor = metrics.addLatencySensor(scope, sensorNamePrefix + "." + name, "create", Sensor.RecordLevel.DEBUG, tagKey, tagValue);
            this.nodeDestructionSensor = metrics.addLatencySensor(scope, sensorNamePrefix + "." + name, "destroy", Sensor.RecordLevel.DEBUG, tagKey, tagValue);
            this.nodeThroughputSensor = metrics.addThroughputSensor(scope, sensorNamePrefix + "." + name, "process-throughput", Sensor.RecordLevel.DEBUG, tagKey, tagValue);

        }

        public void removeAllSensors() {
            metrics.removeSensor(nodeProcessTimeSensor.name());
            metrics.removeSensor(nodePunctuateTimeSensor.name());
            metrics.removeSensor(nodeThroughputSensor.name());
            metrics.removeSensor(nodeCreationSensor.name());
            metrics.removeSensor(nodeDestructionSensor.name());
        }
    }
}
