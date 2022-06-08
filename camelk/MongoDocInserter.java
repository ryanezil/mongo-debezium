// camel-k: language=java
// camel-k: dependency=camel-quarkus-mongodb

package io.rafa.test;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.LoggingLevel;


import java.util.concurrent.atomic.AtomicInteger;

public class MongoDocInserter extends RouteBuilder {

    private AtomicInteger counter = new AtomicInteger(0);

    @Override
    public void configure() throws Exception {

        Processor processor = new Processor() {

            public void process(Exchange exchange) throws Exception {
                String body = exchange.getIn().getBody(String.class);
                body = body.replace("myvalue", "Demo Name #" + counter.getAndIncrement());
                exchange.getOut().setBody(body);
            }
        };

        from("timer:tick?period=10s")
          .setBody(constant("{\"username\":\"myvalue\"}"))
          .process(processor)
          .to("log:info?skipBodyLineSeparator=false")
          .to("mongodb:camelMongoClient?database=demodb&collection=mycollection&operation=insert")
          .log(LoggingLevel.INFO,"Document inserted!");

    }
}
