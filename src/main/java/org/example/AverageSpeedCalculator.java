package org.example;

import com.espertech.esper.common.client.EventBean;
import com.espertech.esper.common.client.configuration.Configuration;
import com.espertech.esper.common.client.EPCompiled;
import com.espertech.esper.compiler.client.CompilerArguments;
import com.espertech.esper.compiler.client.EPCompileException;
import com.espertech.esper.compiler.client.EPCompiler;
import com.espertech.esper.compiler.client.EPCompilerProvider;
import com.espertech.esper.runtime.client.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.List;
import java.util.ArrayList;
import java.util.Properties;

public class AverageSpeedCalculator {
    private EPRuntime runtime;
    private KafkaProducer<String, String> producer;

    public AverageSpeedCalculator(EPRuntime runtime) {
        this.runtime = runtime;
        setupKafkaProducer();
    }

    private void setupKafkaProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "10.63.64.48:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        producer = new KafkaProducer<>(props);
    }

    public void setupAverageSpeedCalculation() {
        String epl = "select id, window(*) as points from TrajectoryDataType.win:time_batch(15 sec) group by id";
        EPCompiled compiledQuery;
        try {
            EPCompiler compiler = EPCompilerProvider.getCompiler();
            CompilerArguments arguments = new CompilerArguments(runtime.getConfigurationDeepCopy());
            compiledQuery = compiler.compile(epl, arguments);
            EPStatement statement = runtime.getDeploymentService().deploy(compiledQuery).getStatements()[0];

            // Add listener to the statement
            statement.addListener((newData, oldData, stat, rt) -> calculateAndPublishAverageSpeed(newData));

        } catch (EPCompileException | EPDeployException e) {
            System.err.println("Error in compiling or deploying EPL: " + e.getMessage());
        }
    }

    private void calculateAndPublishAverageSpeed(EventBean[] newData) {
        if (newData != null && newData.length > 0) {
            for (EventBean eventBean : newData) {
                String id = (String) eventBean.get("id");
                Object[] pointsArray = (Object[]) eventBean.get("points");
                List<TrajectoryDataType> points = new ArrayList<>();
                for (Object point : pointsArray) {
                    points.add((TrajectoryDataType) point);
                }

                if (!points.isEmpty()) {
                    double averageWeightedSpeed = GeoSpeed.calculateWeightedAverageSpeed(points);
                    String message = id + "-" + averageWeightedSpeed + " m/s";
                    System.out.println("Average Weighted Speed for Robot " + id + " over last 15 seconds: " + averageWeightedSpeed + " m/s");
                    producer.send(new ProducerRecord<>("r2k_pos2", id, message));
                }
            }
        }
    }
}
